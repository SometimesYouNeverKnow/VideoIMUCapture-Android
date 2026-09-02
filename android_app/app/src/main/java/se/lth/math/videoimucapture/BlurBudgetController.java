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
 * WHAT IT CANNOT DO, and why #38 stays partly open: there is no auto-AE knob for an ISO ceiling,
 * so the "let ISO rise, but not past N" half needs manual exposure and is not implemented here.
 * And when even the tightest available FPS range cannot meet the budget — the operator is moving
 * too fast for a sharp frame at this focal length, whatever the light — it raises the "hold still"
 * cue rather than pretending exposure can fix it. UNTESTED on a moving device as written.
 */
public class BlurBudgetController {
    private static final String TAG = "VIMUC-BlurBudget";
    private static final long PERIOD_MS = 200;      // 5 Hz; smoothed, so the FPS range does not thrash
    private static final float GYRO_EMA = 0.3f;     // smoothing on |omega|
    private static final int CAPTURE_FPS = VideoEncoderCore.FRAME_RATE;
    // Below this exposure a tighter cap buys nothing and only starves the sensor.
    private static final float MIN_USEFUL_EXPOSURE_S = 0.001f;

    public interface Listener {
        /** holdStill true => moving too fast for the budget at this focal length. */
        void onBlurBudgetState(boolean holdStill, float smearPx, long capExposureNs);
    }

    private final IMUManager mImu;
    private final Camera2Proxy mProxy;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private float mBudgetPx = 3f;
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
        mBudgetPx = budgetPx > 0 ? budgetPx : 3f;
        mGyroEma = mImu != null ? mImu.getLatestGyroMagnitude() : 0f;
        mRunning = true;
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
        Log.i(TAG, "blur budget on, " + mBudgetPx + " px");
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
        boolean holdStill = (best == null);   // no range tight enough — motion, not light
        if (best != null && !best.equals(mApplied)) {
            mProxy.setAeTargetFpsRange(best);
            mApplied = best;
        }
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
