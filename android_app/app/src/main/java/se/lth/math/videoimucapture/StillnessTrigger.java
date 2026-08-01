package se.lth.math.videoimucapture;

import android.util.Log;

/**
 * Fires the shutter at the quiet moments of handheld motion.
 *
 * A radiance-field capture wants sharp frames across a wide baseline, not a high frame
 * rate. At 1.4 m/s, 4K30 puts 4.7 cm between frames — close to the degenerate baseline
 * that makes rotation and translation indistinguishable — while two captures a second
 * put 70 cm between them, and adaptive keyframe selection was discarding ~90% of the
 * video frames anyway. The frame rate was never the deliverable.
 *
 * WHAT IS WATCHED, AND WHY IT IS NOT THE GAIT. The damage a moving camera does is
 * angular: rotating at omega during an exposure t smears the image by
 *
 *     blur_px = f * omega * t
 *
 * regardless of what caused the rotation. Watching that directly self-adapts to
 * walking, a gimbal, a vehicle or a operator standing still, and it degrades gracefully
 * when motion is not periodic — where a gait model would simply fail to lock. On this
 * hardware f = 2755 px, so at 1/120 s one degree per second costs 0.4 px of smear.
 *
 * LATENCY IS HANDLED BY WHEN IT FIRES, NOT BY PREDICTING. There are tens of
 * milliseconds between requesting a capture and the shutter opening. Firing at the
 * detected BOTTOM of a quiet window would therefore expose after the window had begun
 * to close. Firing instead on the DOWNWARD CROSSING — the moment motion first falls
 * under budget — spends that latency travelling further into the quiet window rather
 * than out of it. A walking gait's quiet window is on the order of 150-200 ms against a
 * shutter latency of 30-80 ms, so the exposure lands inside it without any prediction,
 * period estimation or phase model.
 *
 * The estimate is recorded per shot and can be graded afterwards: the IMU stream is
 * written alongside, so the angular rate at each shot's SENSOR_TIMESTAMP gives the blur
 * that was actually achieved against the blur that was predicted here.
 */
public class StillnessTrigger {
    private static final String TAG = "StillnessTrigger";

    /** Callback on the sensor thread. Implementations must not block. */
    public interface Listener {
        /**
         * @param forced true only when the interval ceiling expired while motion was
         *               still OVER budget — a rough stretch that never settled. A phone
         *               held perfectly still also fires on the ceiling, but it is under
         *               budget and is not forced; conflating the two would mark the
         *               steadiest frames of a run as its worst.
         */
        void onQuietMoment(long timestampNs, float predictedBlurPx, float omegaRadPerS,
                           boolean forced);
    }

    private final Listener mListener;

    // Tuning. Deliberately three numbers: a quality budget and two rate guards.
    private float mMaxBlurPx = 1.5f;
    private long mMinIntervalNs = 400_000_000L;    // 2.5 Hz ceiling
    private long mMaxIntervalNs = 3_000_000_000L;  // give up waiting for quiet after 3 s

    // Assumed until the camera reports otherwise, so the trigger is usable before the
    // first capture result arrives.
    private volatile float mFocalPx = 2755f;
    private volatile float mExposureS = 1f / 120f;

    private volatile boolean mRunning = false;
    private long mLastFireNs = 0;
    private boolean mWasOverBudget = true;  // require a crossing, not a cold start
    private long mStartNs = 0;

    // Counters, reported at the end of a run so the operator learns what the walk cost.
    private int mFired = 0;
    private int mForced = 0;
    private double mBlurSum = 0;

    public StillnessTrigger(Listener listener) {
        mListener = listener;
    }

    public void setMaxBlurPx(float px) {
        mMaxBlurPx = Math.max(0.1f, px);
    }

    public void setIntervalBounds(long minMs, long maxMs) {
        mMinIntervalNs = minMs * 1_000_000L;
        mMaxIntervalNs = maxMs * 1_000_000L;
    }

    /**
     * Keep the blur model honest by feeding it what the camera is actually doing.
     * Exposure in particular moves by orders of magnitude between sun and shade, and a
     * budget computed against a stale exposure is the wrong budget.
     */
    public void updateOptics(float focalPx, long exposureNs) {
        if (focalPx > 0) {
            mFocalPx = focalPx;
        }
        if (exposureNs > 0) {
            mExposureS = exposureNs / 1e9f;
        }
    }

    public void start(long nowNs) {
        mRunning = true;
        mStartNs = nowNs;
        mLastFireNs = nowNs;
        mWasOverBudget = true;
        mFired = 0;
        mForced = 0;
        mBlurSum = 0;
        Log.i(TAG, String.format("WALK started: budget %.2f px at f=%.0f px, t=%.1f ms",
                mMaxBlurPx, mFocalPx, mExposureS * 1000f));
    }

    public void stop() {
        mRunning = false;
        Log.i(TAG, String.format("WALK stopped: %d shots (%d forced), mean predicted blur %.2f px",
                mFired, mForced, mFired > 0 ? mBlurSum / mFired : 0.0));
    }

    public boolean isRunning() {
        return mRunning;
    }

    public int shotsFired() {
        return mFired;
    }

    /**
     * Feed one gyro sample. Called on the sensor thread at ~189 Hz.
     *
     * @param gyro raw angular rate, rad/s, at least three elements.
     */
    public void onGyro(long timestampNs, float[] gyro) {
        if (!mRunning || gyro.length < 3) {
            return;
        }
        float omega = (float) Math.sqrt(
                gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]);
        float blur = mFocalPx * omega * mExposureS;

        long sinceFire = timestampNs - mLastFireNs;
        if (sinceFire < mMinIntervalNs) {
            // Still inside the rate floor; track the budget state so the next crossing
            // is a real crossing rather than an artefact of the guard expiring.
            mWasOverBudget = blur > mMaxBlurPx;
            return;
        }

        boolean underBudget = blur <= mMaxBlurPx;
        boolean crossedDown = underBudget && mWasOverBudget;
        boolean timedOut = sinceFire >= mMaxIntervalNs;

        if (crossedDown || timedOut) {
            // Forced means "over budget and out of patience". A stationary phone never
            // crosses downward — it was already quiet — and reaches the ceiling too, but
            // that frame is the best kind, not the worst.
            boolean forced = timedOut && !underBudget;
            mLastFireNs = timestampNs;
            mFired++;
            mBlurSum += blur;
            if (forced) {
                mForced++;
                // A rough walk that never settles still needs coverage; take the frame
                // and let the recorded blur estimate mark it as the weaker observation.
            }
            mListener.onQuietMoment(timestampNs, blur, omega, forced);
        }
        mWasOverBudget = !underBudget;
    }
}
