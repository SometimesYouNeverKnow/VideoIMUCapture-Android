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
        mBudgetPx = budgetPx > 0 ? budgetPx : 3f;
        mHoldStillPx = holdStillPx > 0 ? holdStillPx : 40f;
        mGyroEma = mImu != null ? mImu.getLatestGyroMagnitude() : 0f;
        mRunning = true;
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
        Log.i(TAG, "blur budget on, " + mBudgetPx + " px; hold-still alarm at "
                + mHoldStillPx + " px");
    }

    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mTick);
        // Release the cap: hand the AE its full range back.
        if (mApplied != null && mProxy != null) {
            mProxy.setAeTargetFpsRange(null);
            mApplied = null;
        }
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
