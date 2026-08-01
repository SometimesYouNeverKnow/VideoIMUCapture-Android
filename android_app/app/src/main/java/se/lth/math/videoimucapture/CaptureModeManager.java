package se.lth.math.videoimucapture;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;

/**
 * One button, three behaviours. The operating principle is the operator's: almost dumb.
 *
 * The camera button never asks a question. What it does depends on a single mode
 * setting, and each mode is a complete opinion about how to capture that kind of
 * subject rather than a pile of knobs:
 *
 *   WALK   — press to start, press to stop. Stills fire at the quiet moments of the
 *            operator's gait, exposure and white balance locked at the first frame so
 *            the whole run is radiometrically consistent. JPEG, because RAW at this
 *            rate does not fit (25 MB per DNG against ~7 MB per JPEG: a ten-minute
 *            walk is 8.4 GB one way and 38 GB the other), with one RAW at each end as
 *            a linearity reference for the JPEGs in between.
 *
 *   OBJECT — stationary. One press fires the composite: a focus stack across the
 *            subject's depth, then an exposure bracket, then a full-quality RAW. Focus
 *            stacking is only meaningful here — it combines focal planes of the SAME
 *            view, so it needs a viewpoint that does not move, which is exactly why it
 *            must not run in WALK.
 *
 *   PANO   — gimbal or tripod. Stillness-triggered like WALK, but the viewpoint really
 *            is fixed, so an exposure bracket per position is correct and merges
 *            cleanly.
 *
 * WHY WALK DOES NOT BRACKET. A five-shot burst spans 134 ms on this hardware, which at
 * walking pace is 19 cm of travel — far too much for an HDR merge, which assumes a
 * fixed viewpoint. But a radiance field does not need the merge: every frame is a valid
 * observation carrying its own recorded exposure, so range is recovered ACROSS views
 * rather than within a pixel. One frame per quiet moment beats five ghosted ones.
 */
public class CaptureModeManager implements StillnessTrigger.Listener {
    private static final String TAG = "CaptureMode";

    public enum Mode {WALK, OBJECT, PANO}

    /** Fired on the main thread when a run starts or stops, for UI state. */
    public interface StateListener {
        void onRunStateChanged(boolean running, String summary);
    }

    private final CameraCaptureActivity mActivity;
    private final StillnessTrigger mTrigger;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private Mode mMode = Mode.WALK;
    private StateListener mStateListener;

    private boolean mRunning = false;
    private File mRunDir;
    private RecordingWriter mWriter;
    private boolean mOwnsWriter = false;
    private int mShots = 0;
    private boolean mEndRawPending = false;

    public CaptureModeManager(CameraCaptureActivity activity) {
        mActivity = activity;
        mTrigger = new StillnessTrigger(this);
    }

    public void setStateListener(StateListener l) {
        mStateListener = l;
    }

    public void setMode(Mode mode) {
        if (mRunning) {
            Log.w(TAG, "mode change ignored while a run is active");
            return;
        }
        mMode = mode;
    }

    public Mode getMode() {
        return mMode;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public StillnessTrigger getTrigger() {
        return mTrigger;
    }

    // ------------------------------------------------------------------- video session
    //
    // TWO BUTTONS, TWO INSTRUMENTS, ONE DISCIPLINE. The camera button takes stills; the
    // record button takes video. What the MODE decides is not which button does what — it
    // is the discipline applied to whichever one is pressed: locked radiometry, an IMU and
    // GNSS stream on the same clock, and an output directory named for what it contains.
    //
    // This exists because plain video recording had none of that. The 2026-08-01 walk came
    // back with one exposure value across 948 frames and it looked like the lock working;
    // it was not locked at all. The scene was uniformly bright and AE happened to sit on
    // the sensor's ISO floor for half a minute. Step into shade and the same recording
    // would have drifted, and a radiance field would have explained the drift as content.
    //
    // The two also COMPOSE. Press record then capture and the stills land in the video's
    // own directory, sharing its writer and therefore its clock — dense frames for
    // structure plus full-resolution stills at the quiet moments, which is exactly the
    // combination the 09:39 walk should have produced and did not.

    private boolean mVideoOwnsSession = false;

    /**
     * Claim (or join) a capture session for a video recording.
     *
     * @return the directory the video and its metadata belong in, or null on failure.
     */
    public File beginVideoSession() {
        if (mRunning && mRunDir != null) {
            // A stills run is already up: join it rather than opening a second writer over
            // the top of the first. One session, one clock, one directory.
            mVideoOwnsSession = false;
            notifyState(true, mMode + ": video + stills");
            Log.i(TAG, "video joining the active " + mMode + " run in " + mRunDir);
            return mRunDir;
        }
        File dir = mActivity.newCaptureDir(mMode.name().toLowerCase(java.util.Locale.US) + "_vid");
        if (dir == null) {
            return null;
        }
        mVideoOwnsSession = true;
        mRunDir = dir;
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null) {
            proxy.lockAutoAlgorithms(true);
        }
        notifyState(true, mMode == Mode.OBJECT
                ? "OBJECT: video adds little to a fixed viewpoint"
                : mMode + ": video recording");
        Log.i(TAG, "video session started in " + dir + " (mode " + mMode + ")");
        return dir;
    }

    /** Release whatever beginVideoSession took, and nothing that it did not. */
    public void endVideoSession() {
        if (!mVideoOwnsSession) {
            // The stills run owns the session; it will unlock and close on its own stop.
            notifyState(mRunning, mRunning ? mMode + " running" : "");
            return;
        }
        mVideoOwnsSession = false;
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null) {
            proxy.lockAutoAlgorithms(false);
        }
        File dir = mRunDir;
        mRunDir = null;
        notifyState(false, "video saved");
        Log.i(TAG, "video session ended: " + dir);
    }

    /** True when the video recording, not a stills run, is holding the session open. */
    public boolean videoOwnsSession() {
        return mVideoOwnsSession;
    }

    /** The camera button. Exactly one entry point, whatever the mode. */
    public void onCaptureButton() {
        if (mMode == Mode.OBJECT) {
            fireObjectComposite();
            return;
        }
        if (mRunning) {
            stopRun();
        } else {
            startRun();
        }
    }

    // ------------------------------------------------------------------ continuous run

    private void startRun() {
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null) {
            Log.w(TAG, "no camera");
            return;
        }
        mRunDir = mActivity.newCaptureDir(mMode.name().toLowerCase(java.util.Locale.US));
        if (mRunDir == null) {
            return;
        }
        mWriter = mActivity.getsRecordingWriter();
        mOwnsWriter = false;
        if (!mWriter.isRecording()) {
            try {
                mWriter.startRecording(new File(mRunDir, "video_meta.pb3").getAbsolutePath());
                mOwnsWriter = true;
            } catch (java.io.IOException e) {
                Log.e(TAG, "could not open metadata file: " + e);
                return;
            }
        }
        // The IMU stream must be recorded alongside: it is what lets the blur PREDICTED
        // at trigger time be graded against the blur actually achieved at each shutter.
        mActivity.getmImuManager().startRecording(mWriter);
        mActivity.getmGnssLogger().startRecording(mWriter);

        // Lock the auto algorithms for the whole run so every frame shares one
        // radiometry; a drifting AE would make the splat explain brightness as content.
        proxy.lockAutoAlgorithms(true);

        // Quality is set by mode rather than by the operator, because the right answer
        // differs and neither is a preference. Measured on identical pixels (re-encoding
        // one frame, so noise cannot confound it): q90 is 44% of the size of the device
        // default for a gradient-field error of ~0.87 luma units per pixel step, against
        // typical texture gradients of tens of units. A WALK run is hundreds of frames
        // and storage-bound, so it takes the halving; OBJECT and PANO are a handful of
        // frames where the storage is irrelevant and the detail is the point.
        StillCaptureManager scm = proxy.getStillCaptureManager();
        if (scm != null) {
            scm.setJpegQuality(mMode == Mode.WALK ? 90 : 0);
        }

        mShots = 0;
        mRunning = true;
        mEndRawPending = false;
        mActivity.getmImuManager().setStillnessTrigger(mTrigger);
        mTrigger.start(android.os.SystemClock.elapsedRealtimeNanos());

        // A RAW at the start, one at the end: the JPEGs between them are 8-bit with a
        // tone curve, and these two give the run a linear reference to check against.
        captureNow(StillCaptureManager.Mode.SINGLE, 1, true, 0f, 0f, false);
        notifyState(true, mMode + " running");
        Log.i(TAG, "run started in " + mRunDir);
    }

    private void stopRun() {
        mRunning = false;
        mTrigger.stop();
        mActivity.getmImuManager().setStillnessTrigger(null);

        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy != null) {
            mEndRawPending = true;
            captureNow(StillCaptureManager.Mode.SINGLE, 1, true, 0f, 0f, false);
            proxy.lockAutoAlgorithms(false);
        }
        final int shots = mShots;
        final RecordingWriter writer = mWriter;
        final boolean owns = mOwnsWriter;
        // Let the closing RAW drain before the metadata file is sealed.
        mMain.postDelayed(() -> {
            mActivity.getmImuManager().stopRecording();
            mActivity.getmGnssLogger().stopRecording();
            if (owns && writer != null) {
                writer.stopRecording();
            }
            notifyState(false, shots + " shots");
        }, 2500L);
        Log.i(TAG, "run stopped after " + shots + " shots");
    }

    @Override
    public void onQuietMoment(long timestampNs, float predictedBlurPx, float omega,
                              boolean forced) {
        // Sensor thread. Hop to main: the camera session is driven from there.
        mMain.post(() -> {
            if (!mRunning) {
                return;
            }
            if (mMode == Mode.PANO) {
                // Fixed viewpoint, so a bracket per position is correct and mergeable.
                captureNow(StillCaptureManager.Mode.EXPOSURE_BRACKET, 3, false,
                        predictedBlurPx, omega, forced);
            } else {
                captureNow(StillCaptureManager.Mode.SINGLE, 1, false,
                        predictedBlurPx, omega, forced);
            }
        });
    }

    private void captureNow(StillCaptureManager.Mode burstMode, int shots, boolean raw,
                            float predictedBlurPx, float omega, boolean forced) {
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null || proxy.getStillCaptureManager() == null) {
            return;
        }
        StillCaptureManager.CaptureMode cm =
                mMode == Mode.PANO ? StillCaptureManager.CaptureMode.PANO
                        : StillCaptureManager.CaptureMode.WALK;
        proxy.getStillCaptureManager().setTriggerContext(cm, predictedBlurPx, omega, forced);
        // Feed the trigger the optics it should be modelling: exposure moves by orders
        // of magnitude between sun and shade, and a stale value is the wrong budget.
        proxy.refreshTriggerOptics(mTrigger);
        proxy.captureStills(burstMode, shots, 2.0f, raw, mRunDir, mWriter);
        mShots += shots;
    }

    // ------------------------------------------------------------------- object mode

    private void fireObjectComposite() {
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null) {
            return;
        }
        File dir = mActivity.newCaptureDir("object");
        if (dir == null) {
            return;
        }
        RecordingWriter writer = mActivity.getsRecordingWriter();
        boolean owns = false;
        if (!writer.isRecording()) {
            try {
                writer.startRecording(new File(dir, "video_meta.pb3").getAbsolutePath());
                owns = true;
            } catch (java.io.IOException e) {
                Log.e(TAG, "could not open metadata file: " + e);
                return;
            }
        }
        proxy.lockAutoAlgorithms(true);
        StillCaptureManager scm = proxy.getStillCaptureManager();
        if (scm != null) {
            scm.setTriggerContext(StillCaptureManager.CaptureMode.OBJECT, 0f, 0f, false);
        }

        // Sequenced rather than concurrent: each stage reconfigures the request, and a
        // burst must finish draining before the next changes focus or exposure under it.
        //
        // The focus stack is FIRST and gets the longest slot. It is the only stage that
        // waits on hardware: each of its five slices parks the voice coil and waits for the
        // lens to report it has arrived, up to 400 ms per step plus 120 ms of spacing. Five
        // slices is therefore 2.6 s worst case against the 167 ms the burst version took —
        // which is the whole reason that version came back with five identical pictures.
        notifyState(true, "OBJECT: focus stack");
        proxy.captureFocusStack(5, false, dir, writer);

        mMain.postDelayed(() -> {
            notifyState(true, "OBJECT: exposure bracket");
            proxy.captureStills(StillCaptureManager.Mode.EXPOSURE_BRACKET, 5, 2.0f,
                    false, dir, writer);
        }, 4000L);

        mMain.postDelayed(() -> {
            notifyState(true, "OBJECT: full-quality RAW");
            proxy.captureStills(StillCaptureManager.Mode.SINGLE, 1, 0f, true, dir, writer);
        }, 7000L);

        // The stereo pair. Last, because it is the one stage whose value does not
        // degrade if the operator has already drifted — both frames are simultaneous,
        // so the 18.02 mm baseline between them holds regardless of what the hand did
        // before it. Everything else in this composite is monocular and therefore
        // scale-free; this is the stage that makes the capture metric.
        final boolean ownsWriter = owns;
        final boolean hasStereo = scm != null && scm.stereoSupported();
        if (hasStereo) {
            mMain.postDelayed(() -> {
                notifyState(true, "OBJECT: stereo pair (metric scale)");
                proxy.captureStereoPair(dir, writer);
            }, 10000L);
        }

        mMain.postDelayed(() -> {
            proxy.lockAutoAlgorithms(false);
            if (ownsWriter) {
                writer.stopRecording();
            }
            notifyState(false, hasStereo ? "OBJECT complete + stereo" : "OBJECT complete");
            Log.i(TAG, "object composite complete: " + dir);
        }, hasStereo ? 15500L : 10500L);   // stereo adds a warm-up before its capture
    }

    private void notifyState(boolean running, String summary) {
        if (mStateListener != null) {
            mMain.post(() -> mStateListener.onRunStateChanged(running, summary));
        }
    }
}
