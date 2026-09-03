package se.lth.math.videoimucapture;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;

/**
 * A blur budget instead of a light budget (ReconStab #38).
 *
 * Motion blur is rotation rate x exposure time x focal length. The metering picks exposure from
 * LIGHT alone, so when the sun drops it opens the shutter and a walking camera smears — measured
 * on the two jetty walks, hands equally steady, walk 2 was 2.7x blurrier only because the shutter
 * went from 6.4 to 16.7 ms. This controller caps exposure from the gyro instead: the shortest
 * exposure that keeps the smear under `budgetPx`, expressed as an AE target-FPS-range floor
 * (exposure <= 1 / lowerFps), which is the portable way to bound exposure while leaving auto
 * exposure to raise ISO in compensation.
 *
 * It is entirely opt-in and default-off. When off, nothing here runs and the capture is byte-for-
 * byte what it was. When on, it only ever tightens the AE FPS floor within the device's own
 * available ranges; it never sets a manual exposure, so it cannot produce a black frame.
 *
 * WHAT IT CANNOT DO, measured on a real walk 2026-09-02 rather than assumed:
 *
 * The AE-target-FPS-range mechanism CANNOT DELIVER THIS BUDGET while walking, and the gap is
 * not marginal. At 30 fps the shortest exposure the range can force is 1/30 s. That walk ran a
 * median 0.263 rad/s of rotation at 2777 px focal, which is 24 px of smear at 1/30 s against a
 * 3 px budget; reaching 3 px would have needed 1/243 s. The only range on the device tight
 * enough is [60,60], and using it desynchronises the camera from the 30 fps encoder and kills
 * the recording outright. So this controller can shorten the shutter a little in bright light
 * and cannot save a walking frame in dim light. Doing that needs SENSOR_EXPOSURE_TIME with AE
 * off and an ISO ceiling — the manual-exposure half, still not implemented, and now known to
 * be the ONLY half that can meet the stated goal rather than a refinement of it.
 *
 * Because of that, the hold-still cue is no longer tied to the budget. Defined as "no range
 * meets the budget" it was true 98.4% of a 51.6 s walk, including while standing still, which
 * is a constant rather than a signal. It is now an alarm on the predicted smear itself, at its
 * own threshold, alongside a live smear readout.
 */
public class BlurBudgetController {
    private static final String TAG = "VIMUC-BlurBudget";
    private static final long PERIOD_MS = 200;      // 5 Hz; smoothed, so the FPS range does not thrash
    private static final float GYRO_EMA = 0.3f;     // smoothing on |omega|
    private static final int CAPTURE_FPS = VideoEncoderCore.FRAME_RATE;
    // Below this exposure a tighter cap buys nothing and only starves the sensor.
    private static final float MIN_USEFUL_EXPOSURE_S = 0.001f;
    private static final int AE_STATE_CONVERGED = 2;    // CaptureResult.CONTROL_AE_STATE_CONVERGED

    public interface Listener {
        /**
         * smearPx is the predicted motion smear of the CURRENT frame, at the exposure
         * the camera actually used -- the honest number, worth showing continuously.
         * holdStill is true when that number crosses the alarm threshold.
         */
        void onBlurBudgetState(boolean holdStill, float smearPx, long capExposureNs);
    }

    private final IMUManager mImu;
    private final Camera2Proxy mProxy;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private float mBudgetPx = 3f;
    // The alarm threshold, in pixels of predicted smear -- deliberately NOT the same number as
    // the exposure budget. The budget is a target for the AE cap; this is the point at which
    // telling the operator to slow down is useful. Defaulted from a measured S24U walk whose
    // smear ran median 24 px, p95 63 px: 40 px sits around that walk's p80, so it stays quiet
    // through normal walking and speaks up on a genuine swing.
    private float mHoldStillPx = 40f;
    private volatile boolean mRunning = false;
    private float mGyroEma = 0f;
    private Range<Integer> mApplied = null;
    // The manual-shutter half (#38): opt-in on top of the budget itself, because it takes AE
    // off and a mistake here is a black clip rather than a blurred one.
    private boolean mManualEnabled = false;
    private long mHeldNs = 0;
    private int mManualIso = 0;
    // The AE's OWN answer for this scene, as ISO x nanoseconds -- the quantity a manual shutter
    // has to preserve. Measured 2026-09-03: taking this from whatever the metering happened to
    // say at the instant of engagement underexposed a walk by 2.1 stops, because the AE was
    // still climbing out of a stale convergence from before the phone was raised. So the target
    // is only accepted once the metering has been CONVERGED AND STEADY for a full second, and
    // it is re-taken periodically -- a walk that starts in shade and ends in sun has more than
    // one right answer, and freezing the first is how you get a black clip at the end of it.
    private double mAeTargetLight = 0;          // ISO * ns
    private final java.util.ArrayDeque<Double> mConverged = new java.util.ArrayDeque<>();
    // These four are set by two measurements and one report from the field, all 2026-09-03.
    //
    // From the operator, watching the first version run: "it was way too laggy, not too fast. it
    // was reacting, then dumping after a second, then overcorrecting." That is this controller's
    // own re-metering cycle, seen from outside -- hold, hand back, AE lurches, grab again, every
    // six seconds.
    //
    // From the file, which agrees with him: inside those windows the metering ran 3,250 -> 10,884
    // ISO*ms and was STILL CLIMBING when the window closed. Handing the AE 1.4 s and taking the
    // answer back is asking a question and interrupting the reply. And at the start of a clip the
    // AE reports CONVERGED for a full second while carrying the metering of whatever the phone was
    // pointed at before it was raised -- 2,950 against a true 12,100 -- so a one-second steadiness
    // test passes on a stale value.
    //
    // So: converge ONCE, properly, before doing anything, and then leave the AE alone. Rare and
    // long beats frequent and short on both counts -- it converges, and it does not pump.
    private static final long WARMUP_MS = 3000;          // pure AE at the start; engage after
    private static final int STEADY_TICKS = 10;          // 2 s at 5 Hz
    private static final double STEADY_TOLERANCE = 0.10;
    private static final long REMETER_PERIOD_MS = 30000; // a walk changes light over minutes
    private static final long REMETER_WINDOW_MS = 3000;  // long enough for the AE to finish
    private long mHeldSinceMs = 0;
    private long mStartedMs = 0;
    private long mRemeterUntilMs = 0;
    private boolean mWarnedNoTarget = false;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            step();
            mHandler.postDelayed(this, PERIOD_MS);
        }
    };

    public BlurBudgetController(IMUManager imu, Camera2Proxy proxy, Listener listener) {
        mImu = imu;
        mProxy = proxy;
        mListener = listener;
    }

    public void start(float budgetPx) {
        start(budgetPx, mHoldStillPx);
    }

    public void start(float budgetPx, float holdStillPx) {
        start(budgetPx, holdStillPx, false);
    }

    public void start(float budgetPx, float holdStillPx, boolean manualShutter) {
        mBudgetPx = budgetPx > 0 ? budgetPx : 3f;
        mHoldStillPx = holdStillPx > 0 ? holdStillPx : 40f;
        mManualEnabled = manualShutter;
        mGyroEma = mImu != null ? mImu.getLatestGyroMagnitude() : 0f;
        mRunning = true;
        mStartedMs = android.os.SystemClock.elapsedRealtime();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
        Log.i(TAG, "blur budget on, " + mBudgetPx + " px; hold-still alarm at "
                + mHoldStillPx + " px; manual shutter " + (mManualEnabled ? "ON" : "off"));
    }

    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mTick);
        // Release the cap: hand the AE its full range back, and the shutter with it. Both, and
        // in this order -- a manual hold left behind outlives the recording that justified it.
        if (mProxy != null) {
            if (mApplied != null) {
                mProxy.setAeTargetFpsRange(null);
                mApplied = null;
            }
            releaseToAe();
        }
        // The AE target belongs to a scene, and the next recording is a different one.
        mAeTargetLight = 0;
        mRemeterUntilMs = 0;
        mWarnedNoTarget = false;
        Log.i(TAG, "blur budget off");
    }

    private void step() {
        if (mProxy == null || mImu == null) {
            return;
        }
        float omega = mImu.getLatestGyroMagnitude();
        mGyroEma += GYRO_EMA * (omega - mGyroEma);
        float focalPx = mProxy.getFocalPixels();
        if (focalPx <= 0f) {
            return;   // intrinsics not known yet; leave AE alone
        }

        // The longest exposure that keeps the smear under budget, seconds.
        float maxExposureS = mGyroEma > 1e-4f ? mBudgetPx / (mGyroEma * focalPx) : 1f;
        // What the smear WOULD be at the metered exposure, for the readout.
        long metered = mProxy.getLastExposureNs();
        float smearPx = mGyroEma * focalPx * (metered / 1e9f);

        Range<Integer> best = chooseRange(maxExposureS);
        if (best != null && !best.equals(mApplied)) {
            mProxy.setAeTargetFpsRange(best);
            mApplied = best;
        }

        applyManualCap(maxExposureS, metered);

        // The cue used to be `best == null` -- "no available FPS range meets the budget".
        // That is a statement about the DEVICE, not the operator, and on this hardware it is
        // almost always true: at 30 fps the shortest exposure the AE range can force is
        // 1/30 s, and a 3 px budget at 2777 px focal needs the operator under 1.9 deg/s.
        // Measured on a real S24U walk, it was on for 98.4% of 51.6 s -- including while
        // standing still, because standing still is not 1.9 deg/s. A cue that is always on
        // carries no information and trains the operator to ignore it.
        //
        // So it now says what it can honestly say: how blurred this frame actually is, at the
        // exposure the camera actually used, and an alarm only when that number is high
        // enough to be worth acting on. smearPx was already being computed and thrown away.
        boolean holdStill = smearPx > mHoldStillPx;
        long capExposureNs = best != null ? (long) (1e9 / best.getLower()) : 0L;
        if (mListener != null) {
            mListener.onBlurBudgetState(holdStill, smearPx, capExposureNs);
        }
    }

    /**
     * The manual half of #38: hold SENSOR_EXPOSURE_TIME below the budget and spend ISO for it.
     *
     * The AE-range path above cannot go shorter than 1/CAPTURE_FPS, and the measured walk needed
     * 1/243 s. So when — and only when — the budget asks for something shorter than the range
     * path can deliver, this takes AE off and sets the shutter directly, raising ISO by the same
     * factor so the frame keeps its exposure value. The trade is explicit: blur is unrecoverable,
     * noise is a known quantity with a per-frame noise_profile in the file to model it.
     *
     * It releases the moment the motion no longer needs it, so a walk that stops for a photograph
     * gets its auto exposure back rather than staying pinned at ISO 3200. Off by default.
     */
    /**
     * Track what the auto-exposure decides for this scene, and only believe it once it has
     * stopped moving.
     *
     * CONVERGED on its own is not enough: the first frames of a recording can report CONVERGED
     * while carrying the metering of whatever the camera was looking at before it was raised.
     * Measured on testB, 2026-09-03 — seven frames of "converged" at ISO 159, then a climb to
     * 184 and still climbing when the cap engaged; the AE's real answer for that room, from the
     * control clip recorded 47 seconds earlier, was ISO 675 at the same shutter. Steadiness is
     * the property that separates those, so a target must hold within a tolerance for a second
     * before it is allowed to freeze 30 seconds of capture.
     */
    private void trackAeTarget(long meteredNs) {
        int iso = mProxy.getLastIso();
        if (mProxy.isManualExposureHeld() || iso <= 0 || meteredNs <= 0
                || mProxy.getLastAeState() != AE_STATE_CONVERGED) {
            if (!mProxy.isManualExposureHeld()) {
                mConverged.clear();   // a search restarts the steadiness clock
            }
            return;
        }
        double light = (double) iso * (double) meteredNs;
        mConverged.addLast(light);
        while (mConverged.size() > STEADY_TICKS) {
            mConverged.removeFirst();
        }
        if (mConverged.size() < STEADY_TICKS) {
            return;
        }
        double lo = Double.MAX_VALUE, hi = 0;
        for (double v : mConverged) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (lo > 0 && (hi - lo) / lo <= STEADY_TOLERANCE) {
            mAeTargetLight = light;
        }
    }

    private void applyManualCap(float maxExposureS, long meteredNs) {
        if (!mManualEnabled) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        trackAeTarget(meteredNs);

        // Let the auto exposure meet the scene before taking the shutter off it. Three seconds of
        // a walk is worth more than thirty seconds held at the wrong exposure value.
        if (now - mStartedMs < WARMUP_MS) {
            return;
        }

        long rangeFloorNs = 1000000000L / CAPTURE_FPS;   // the shortest the AE-range path can force
        long wantNs = (long) (maxExposureS * 1e9f);
        boolean held = mProxy.isManualExposureHeld();

        if (wantNs >= rangeFloorNs) {
            // The range path can do this on its own; a manual hold would buy nothing and would
            // cost the AE its judgement about everything else in the scene.
            if (held) {
                Log.i(TAG, "manual shutter released; budget reachable by AE again");
                releaseToAe();
            }
            return;
        }

        // Periodically hand the shutter back so the AE can answer again. A walk goes from shade
        // into sun, and an exposure value taken once at the start is only right at the start.
        // The frames spent re-metering are longer and blurrier -- and identifiable in the file
        // by ae_mode == ON, so a solve can weight them accordingly rather than being surprised.
        if (held && now - mHeldSinceMs > REMETER_PERIOD_MS) {
            releaseToAe();
            mRemeterUntilMs = now + REMETER_WINDOW_MS;
            return;
        }
        if (!held && now < mRemeterUntilMs) {
            return;   // let the AE settle; trackAeTarget is watching it
        }

        if (mAeTargetLight <= 0) {
            // No steady metering yet. Do NOT engage on a guess: an underexposed clip is a worse
            // failure than a blurred one, because the noise floor cannot be undone and the blur
            // at least leaves the geometry.
            if (!mWarnedNoTarget) {
                Log.i(TAG, "manual shutter waiting: auto exposure has not settled yet");
                mWarnedNoTarget = true;
            }
            return;
        }
        // THE ISO CEILING, which is what makes this safe rather than merely short (#38's other
        // unfinished half). The budget asks for an exposure; the sensor can only pay for it up to
        // its maximum sensitivity. Beyond that point a shorter shutter does not buy a sharper
        // frame -- it buys a DARKER one, and darkness is the failure that cannot be undone later.
        // So the shortest exposure allowed is the one the ISO ceiling can still hold the AE's own
        // exposure value at, and the budget degrades to "as sharp as this sensor can afford"
        // instead of quietly underexposing. Correcting the focal length on 2026-09-03 made this
        // load-bearing: at the true 4629 px the same room asks for 1/300 s, past what ISO can pay.
        Range<Integer> isoRange = mProxy.getSensitivityRange();
        if (isoRange != null && isoRange.getUpper() > 0) {
            long affordableNs = (long) (mAeTargetLight / isoRange.getUpper());
            if (wantNs < affordableNs) {
                wantNs = affordableNs;
                if (wantNs >= rangeFloorNs) {
                    // The ceiling puts the answer back where auto exposure already had it.
                    if (held) {
                        releaseToAe();
                    }
                    return;
                }
            }
        }
        int iso = (int) Math.round(mAeTargetLight / (double) wantNs);
        // Do not re-issue a repeating request for a change the sensor cannot even express.
        if (held && Math.abs(wantNs - mHeldNs) < mHeldNs / 8) {
            return;
        }
        mProxy.setManualExposure(wantNs, iso);
        if (!held) {
            mHeldSinceMs = now;
            Log.i(TAG, String.format(java.util.Locale.US,
                    "manual shutter engaged: %.2f ms at ISO %d, holding the AE's %.0f ISO*ms",
                    wantNs / 1e6, iso, mAeTargetLight / 1e6));
        }
        mHeldNs = wantNs;
        mManualIso = iso;
    }

    private void releaseToAe() {
        mProxy.clearManualExposure();
        mManualIso = 0;
        mHeldNs = 0;
        mHeldSinceMs = 0;
        mConverged.clear();
    }

    /**
     * The loosest available AE FPS range whose lower bound caps exposure at or under maxExposure,
     * preferring one whose upper bound is the capture rate so the frame rate is undisturbed.
     * Null when the operator is moving too fast for any available range to help.
     */
    private Range<Integer> chooseRange(float maxExposureS) {
        maxExposureS = Math.max(maxExposureS, MIN_USEFUL_EXPOSURE_S);
        int requiredFloor = (int) Math.ceil(1.0 / maxExposureS);
        Range<Integer>[] avail = mProxy.getAvailableFpsRanges();
        Range<Integer> chosen = null;
        Range<Integer> tightest = null;   // fallback: the highest floor available
        for (Range<Integer> r : avail) {
            // A range whose UPPER bound exceeds the encoder's rate does not merely cap
            // exposure — it speeds the camera up. The capture stream then delivers frame
            // metadata faster than the encoder delivers frames, the recorder's two
            // frame-merge queues drift apart, and the one that runs ahead grows without
            // bound. Measured on an S24U 2026-09-02: the fallback below picked [60,60]
            // against a 30 fps encoder and the recording died 17 s in with "Queue full".
            // Whatever the budget asks for, the cadence stays the encoder's; a range that
            // would change it is not a candidate, not even as a last resort.
            if (r.getUpper() > CAPTURE_FPS) {
                continue;
            }
            if (tightest == null || r.getLower() > tightest.getLower()) {
                tightest = r;
            }
            if (r.getLower() >= requiredFloor) {
                // meets the budget; prefer the loosest (smallest lower), upper == capture fps
                boolean better = chosen == null
                        || r.getLower() < chosen.getLower()
                        || (r.getLower() == chosen.getLower()
                            && Math.abs(r.getUpper() - CAPTURE_FPS) < Math.abs(chosen.getUpper() - CAPTURE_FPS));
                if (better) {
                    chosen = r;
                }
            }
        }
        if (chosen != null) {
            return chosen;
        }
        // Nothing meets the budget. If the required floor is only a touch above what exists, the
        // tightest range is still the best exposure we can offer AND the hold-still cue is right.
        if (tightest != null && requiredFloor > CAPTURE_FPS) {
            // apply the tightest so exposure is at least as short as possible
            if (!tightest.equals(mApplied)) {
                mProxy.setAeTargetFpsRange(tightest);
                mApplied = tightest;
            }
        }
        return null;
    }
}
