package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OisSample;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import androidx.annotation.NonNull;

import androidx.preference.PreferenceManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.Math.abs;

public class Camera2Proxy {

    private static final String TAG = "Camera2Proxy";

    private Activity mActivity;

    private String mCameraIdStr = "";
    private Size mPreviewSize;
    private CameraManager mCameraManager;
    private CameraSettingsManager mCameraSettingsManager;
    private CameraCharacteristics mCameraCharacteristics;
    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private StillCaptureManager mStillCaptureManager;
    // Most recent metered result, used as the base exposure a bracket steps away from.
    private volatile TotalCaptureResult mLastResult;
    // True while the shutter is held off auto exposure by the blur budget (#38).
    private volatile boolean mManualExposureHeld = false;
    // Live exposure compensation in device units, adjustable mid-recording (#28).
    private volatile int mExposureCompensation = 0;
    private volatile boolean mTorchOn = false;
    private CaptureRequest.Builder mPreviewRequestBuilder;
    private Rect sensorArraySize;

    private CaptureRequest mPreviewRequest;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private Surface mPreviewSurface;
    private SurfaceTexture mPreviewSurfaceTexture = null;

    private RecordingWriter mRecordingWriter = null;

    // https://stackoverflow.com/questions/3786825/volatile-boolean-vs-atomicboolean
    private volatile boolean mRecordingMetadata = false;
    private boolean mSwappedDimensions;
    private int mSensorOrientation;
    private boolean mExposureTriggered = false;
    private boolean mFocusTriggered = false;

    private FocalLengthHelper mFocalLengthHelper = new FocalLengthHelper();

    public boolean getSwappedDimensions() {return mSwappedDimensions;}

    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "onOpened");
            mCameraDevice = camera;
            initPreviewRequest();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.d(TAG, "onDisconnected");
            releaseCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera Open failed, error: " + error);
            releaseCamera();
        }
    };

    /**
     * Fire a still burst into the given directory. Safe to call whether or not a video
     * recording is in progress; the stills share the preview session.
     */
    public void captureStills(StillCaptureManager.Mode mode, int shots, float stops,
                              boolean writeRaw, File outputDir, RecordingWriter writer) {
        if (mStillCaptureManager == null) {
            Log.w(TAG, "captureStills before the session exists");
            return;
        }
        mStillCaptureManager.capture(mCameraDevice, mCaptureSession, mPreviewRequestBuilder,
                mLastResult, mode, shots, stops, writeRaw, outputDir, writer);
    }

    public StillCaptureManager getStillCaptureManager() {
        return mStillCaptureManager;
    }

    // ---------------------------------------------------------------- focus stack
    //
    // A focus bracket cannot be a burst. The first build proved it: five requests spanning
    // 0.658 dioptres came back as five frames all reporting 0.100 D, landing 33.3 ms apart
    // — one sensor period — with a global sharpness spread of 1.0048x. captureBurst exists
    // to minimise the gap between frames, which is exactly the wrong property when the
    // parameter being bracketed has to physically move.
    //
    // So each step is now: park the lens with a REPEATING request, wait until the lens
    // reports it has arrived, then open the shutter. The wait is bounded, and how long it
    // took is recorded per shot, so a lens that never arrives is visible in the data.

    /** Dioptre tolerance for "the lens got there". Well under one depth-of-field step. */
    private static final float FOCUS_TOLERANCE_D = 0.02f;
    /** Give up on a step after this long and shoot anyway, flagged as unsettled. */
    private static final long FOCUS_SETTLE_TIMEOUT_MS = 400L;
    /** Breathing room after the shutter before the lens is driven somewhere else. */
    private static final long FOCUS_SHOT_SPACING_MS = 120L;

    // THREADING. Every one of these is written by runFocusStep and read by onFocusResult,
    // which runs on the camera callback thread. The whole sequence is therefore posted to
    // mBackgroundHandler — the same thread the session callbacks are delivered on — so the
    // steps and the results they are waiting for are serialised by construction rather than
    // by hoping. volatile covers the initial hand-off from whichever thread pressed the
    // button.
    private volatile float mFocusTarget = Float.NaN;
    private volatile long mFocusStepStartNs;
    private volatile Runnable mFocusTimeout;
    private final AtomicBoolean mFocusStepPending = new AtomicBoolean(false);
    private final AtomicBoolean mFocusStackRunning = new AtomicBoolean(false);

    /**
     * Drive a focus stack one settled step at a time.
     *
     * @param shots number of slices; the plan is centred on the current autofocus result
     *              and stepped by the depth of field, so this is "how thick a subject".
     */
    public void captureFocusStack(int shots, boolean writeRaw, File outputDir,
                                  RecordingWriter writer) {
        if (mStillCaptureManager == null || mCaptureSession == null
                || mPreviewRequestBuilder == null || mCameraDevice == null) {
            Log.w(TAG, "focus stack requested before the session exists");
            return;
        }
        if (!mFocusStackRunning.compareAndSet(false, true)) {
            Log.w(TAG, "focus stack already running; ignoring");
            return;
        }
        final float[] plan = mStillCaptureManager.planFocusStack(mLastResult, shots);
        mStillCaptureManager.beginFocusStack(plan.length, writeRaw, outputDir, writer);
        // Remember what the preview was doing so autofocus can be handed back afterwards.
        // If the preview never named a mode, hand back CONTINUOUS_PICTURE rather than the
        // OFF this sequence is about to set — otherwise a finished stack leaves the camera
        // stuck at the last slice's focus with no way back but a restart.
        final Integer afMode = mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE);
        final int restoreMode = afMode != null
                ? afMode : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        final Float afDist = mPreviewRequestBuilder.get(CaptureRequest.LENS_FOCUS_DISTANCE);
        mBackgroundHandler.post(() -> runFocusStep(plan, 0, restoreMode, afDist));
    }

    private void runFocusStep(float[] plan, int index, int restoreAfMode,
                              Float restoreAfDist) {
        if (index >= plan.length) {
            restoreAfterFocusStack(restoreAfMode, restoreAfDist);
            mFocusStackRunning.set(false);
            Log.i(TAG, "focus stack complete: " + plan.length + " slices");
            return;
        }
        final float target = plan[index];
        mFocusTarget = target;
        mFocusStepStartNs = SystemClock.elapsedRealtimeNanos();
        mFocusStepPending.set(true);

        final Runnable timeout = () -> fireFocusShot(plan, index, target, false,
                restoreAfMode, restoreAfDist);
        mFocusTimeout = timeout;
        mFocusSettleSignal = () -> fireFocusShot(plan, index, target, true,
                restoreAfMode, restoreAfDist);

        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_OFF);
            mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, target);
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "could not drive focus to " + target + ": " + e);
        }
        mBackgroundHandler.postDelayed(timeout, FOCUS_SETTLE_TIMEOUT_MS);
    }

    /**
     * Take the shot for one step. Reached from either the settle callback or the timeout;
     * the AtomicBoolean guarantees exactly one of them wins, and `settled` is passed in by
     * whichever did rather than inferred from the elapsed time.
     */
    private void fireFocusShot(float[] plan, int index, float target, boolean settled,
                               int restoreAfMode, Float restoreAfDist) {
        if (!mFocusStepPending.compareAndSet(true, false)) {
            return;
        }
        Runnable t = mFocusTimeout;
        if (t != null) {
            mBackgroundHandler.removeCallbacks(t);
        }
        final long waited = SystemClock.elapsedRealtimeNanos() - mFocusStepStartNs;
        mStillCaptureManager.captureFocusShot(mCameraDevice, mCaptureSession,
                mPreviewRequestBuilder, index, target, waited, settled);
        mBackgroundHandler.postDelayed(
                () -> runFocusStep(plan, index + 1, restoreAfMode, restoreAfDist),
                FOCUS_SHOT_SPACING_MS);
    }

    /**
     * Called for every preview result while a focus step is outstanding. Accepts only
     * results whose OWN request carried the target distance — the pipeline is several
     * frames deep, so results for the previous lens position keep arriving after the new
     * request goes out, and grading those is precisely how the burst version fooled itself
     * into reporting five focus positions it never reached.
     */
    private void onFocusResult(CaptureRequest request, CaptureResult result) {
        if (!mFocusStepPending.get()) {
            return;
        }
        Float requested = request.get(CaptureRequest.LENS_FOCUS_DISTANCE);
        if (requested == null || Math.abs(requested - mFocusTarget) > 1e-4f) {
            return;   // a result from before this step's request took effect
        }
        Integer state = result.get(CaptureResult.LENS_STATE);
        if (state != null && state != CameraMetadata.LENS_STATE_STATIONARY) {
            return;   // still moving
        }
        Float actual = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (actual != null && Math.abs(actual - mFocusTarget) > FOCUS_TOLERANCE_D) {
            return;   // parked, but not where we asked
        }
        mFocusSettleSignal.run();
    }

    /**
     * Set by runFocusStep, invoked by onFocusResult. Held as a field rather than passed so
     * the result callback needs no knowledge of which step it is completing.
     */
    private volatile Runnable mFocusSettleSignal = () -> {
    };

    private void restoreAfterFocusStack(int afMode, Float afDist) {
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            if (afDist != null) {
                mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, afDist);
            }
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "could not restore focus mode: " + e);
        }
    }

    /**
     * One simultaneous frame from each of the ultrawide and main lenses.
     *
     * WARM-UP IS REQUIRED, and finding that out cost a capture. A logical multi-camera
     * does not keep every physical sensor running — only the ones feeding current
     * output. Firing a one-shot request at an idle physical stream returns
     * ERROR_CAMERA_BUFFER (errorCode 5) for it: measured here as errorStreamId=3, and
     * the pair came back with the main frame present and the ultrawide missing.
     *
     * So the physical streams are added to the REPEATING request first, which starts
     * the second sensor and lets its exposure settle, and only then is the pair
     * captured. The normal preview request is restored afterwards so two sensors are
     * not left running — that is real power and heat for a capability used once per
     * composite.
     */
    public void captureStereoPair(File outputDir, RecordingWriter writer) {
        if (mStillCaptureManager == null || !mStillCaptureManager.stereoSupported()
                || mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        try {
            CaptureRequest.Builder warm =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            // Copy the preview's settings so the pair is exposed like everything else in
            // the composite, then add the preview surface plus both physical streams.
            for (CaptureRequest.Key key : new CaptureRequest.Key[]{
                    CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AE_LOCK, CaptureRequest.CONTROL_AWB_LOCK,
                    CaptureRequest.SENSOR_EXPOSURE_TIME, CaptureRequest.SENSOR_SENSITIVITY,
                    CaptureRequest.FLASH_MODE, CaptureRequest.LENS_FOCUS_DISTANCE}) {
                Object v = mPreviewRequestBuilder.get(key);
                if (v != null) {
                    warm.set(key, v);
                }
            }
            warm.addTarget(mPreviewSurface);
            for (Surface s : mStillCaptureManager.getStereoSurfaces().values()) {
                warm.addTarget(s);
            }
            mCaptureSession.setRepeatingRequest(
                    warm.build(), mSessionCaptureCallback, mBackgroundHandler);
            Log.d(TAG, "stereo warm-up streaming");

            mBackgroundHandler.postDelayed(() -> mStillCaptureManager.captureStereoPair(
                    mCameraDevice, mCaptureSession, mPreviewRequestBuilder,
                    outputDir, writer), 900L);
            mBackgroundHandler.postDelayed(() -> {
                try {
                    mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                            mSessionCaptureCallback, mBackgroundHandler);
                    Log.d(TAG, "stereo warm-up ended, preview restored");
                } catch (CameraAccessException | IllegalStateException e) {
                    Log.w(TAG, "could not restore preview: " + e);
                }
            }, 2200L);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "stereo warm-up failed: " + e);
        }
    }

    public void startRecordingCaptureResult(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
        mRecordingMetadata = true;
        // NO unconditional lock here. This used to call setAutoAlgorithmLock(true) outright --
        // upstream's behaviour, from before the choice existed -- which silently overruled the
        // "Freeze exposure while recording" setting v0.14 added. CaptureModeManager reads the
        // preference and locks or does not; four milliseconds later this line locked anyway.
        //
        // The log said both things in sequence and neither of us read it:
        //     CaptureMode: video session radiometry floating
        //     Camera2Proxy: AE/AWB lock engaged
        //
        // So every clip this fork has ever recorded carried ONE radiometry, whatever the
        // setting said, and the blur budget could never work at all -- it caps exposure through
        // the AE target-FPS range, and AE was not listening. Measured on a 51.6 s overcast walk:
        // exposure frozen at 16.67 ms and ISO at 16 for all 1547 frames, AE_STATE LOCKED on
        // 1540 of them. CaptureModeManager owns this decision now, in both directions.
        writeCameraInfo();
    }

    public void stopRecordingCaptureResult() {
        if (mRecordingMetadata) {
            mRecordingMetadata = false;
        }
        setAutoAlgorithmLock(false);
    }

    /**
     * Freeze the auto exposure and auto white balance algorithms for the duration of a
     * recording: converge while framing, then hold.
     *
     * A drifting AE ramps global brightness mid-clip (measured on the 2026-07-31 test
     * clips: ISO 93 -> 1529 within one 27 s recording) and a drifting AWB shifts colour;
     * a splat then has to explain both as scene content. Locking is preferred over
     * forcing manual values because it keeps the converged, correct exposure for
     * whatever is actually in front of the camera.
     *
     * No-op when the user has selected fully manual AE — the locks are ignored then.
     */
    /**
     * Continuous torch, not a strobe flash.
     *
     * Torch rather than FLASH_MODE_SINGLE deliberately: it is lit during preview so the
     * scene can be framed as it will be exposed, it is constant across every frame of a
     * burst, and it avoids the pre-flash metering dance that would put an uncontrolled
     * delay between the trigger deciding to fire and the shutter opening.
     *
     * When this is the right tool: close work in the dark — bark, leaves, anything the
     * camera is within a couple of metres of and that is being photographed to be
     * IDENTIFIED rather than reconstructed. It buys a short exposure and a low ISO,
     * which is the difference between a readable macro frame and a noisy smear.
     *
     * When it is the wrong tool: anything solving for the scene's own appearance. The
     * torch travels with the camera, so every surface is lit differently in every frame
     * and the shading becomes a function of viewpoint. It is flatly wrong for PANO,
     * where the whole point is to record the environment's light rather than your own.
     */
    public void setTorch(boolean on) {
        mTorchOn = on;
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        try {
            mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                    on ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
            Log.d(TAG, "torch " + (on ? "on" : "off"));
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "could not set torch: " + e);
        }
    }

    public boolean isTorchOn() {
        return mTorchOn;
    }

    public boolean torchAvailable() {
        Boolean b = mCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        return b != null && b;
    }

    /** Public entry for modes that want the whole run radiometrically frozen. */
    public void lockAutoAlgorithms(boolean lock) {
        setAutoAlgorithmLock(lock);
    }

    // --- Blur budget (#38): a gyro-driven cap on exposure, expressed through the AE target FPS
    // range because that is the portable, auto-exposure-compatible way to bound exposure time
    // (exposure <= 1 / lowerFps). Everything here is a no-op unless the controller is enabled.

    /** The device's available AE target FPS ranges, or an empty array. */
    /**
     * Push the current settings onto the running session.
     *
     * A preference change normally waits for the next session, because that is when the request
     * builder is filled. Anything that has to change WITHIN an outing -- the test matrix flipping
     * OIS between two 30 s clips -- needs the request re-issued, or the clip records one state
     * while the settings say another.
     */
    public void reapplyCameraSettings() {
        if (mCaptureSession == null || mPreviewRequestBuilder == null
                || mCameraSettingsManager == null) {
            return;
        }
        try {
            mCameraSettingsManager.updateRequestBuilder(mPreviewRequestBuilder);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Could not re-apply camera settings: " + e);
        }
    }

    /** Most recent metered ISO, or 0. Paired with getLastExposureNs() it is the exposure value
     *  the AE had settled on, which is what a manual override has to preserve. */
    public int getLastIso() {
        if (mLastResult != null) {
            Integer s = mLastResult.get(CaptureResult.SENSOR_SENSITIVITY);
            if (s != null) {
                return s;
            }
        }
        return 0;
    }

    /**
     * Nudge exposure compensation by `steps` device units and return the new value in STOPS
     * (ReconStab #28).
     *
     * The complaint this answers: there was no way to change exposure without ending the
     * session, and ending the session is not free -- it restarts frame numbering, breaks the
     * clip in two and costs the walk its continuity. This applies to the repeating request in
     * place, so a walk that goes from shade into sun is one recording with a step in it, and
     * the step is recorded per frame (ae_exposure_compensation) so a bake can undo it.
     *
     * Returns Float.NaN when the device declines to be compensated -- AE off, or no range.
     */
    public float nudgeExposureCompensation(int steps) {
        if (mCaptureSession == null || mPreviewRequestBuilder == null
                || mCameraCharacteristics == null) {
            return Float.NaN;
        }
        Range<Integer> range =
                mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        android.util.Rational step =
                mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (range == null || step == null || range.getUpper() == range.getLower()) {
            return Float.NaN;
        }
        int units = range.clamp(mExposureCompensation + steps);
        if (units == mExposureCompensation) {
            return units * step.floatValue();   // already at the rail; report, do not re-issue
        }
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, units);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
            mExposureCompensation = units;
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Could not set exposure compensation: " + e);
            return Float.NaN;
        }
        return units * step.floatValue();
    }

    /** Current exposure compensation in stops. */
    public float getExposureCompensationStops() {
        android.util.Rational step = mCameraCharacteristics != null
                ? mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                : null;
        return step == null ? 0f : mExposureCompensation * step.floatValue();
    }

    /** CONTROL_AE_STATE of the last result, or -1. 2 is CONVERGED. */
    public int getLastAeState() {
        if (mLastResult != null) {
            Integer s = mLastResult.get(CaptureResult.CONTROL_AE_STATE);
            if (s != null) {
                return s;
            }
        }
        return -1;
    }

    /** The device's own shutter limits, ns, or null if it does not say. */
    public Range<Long> getExposureTimeRange() {
        return mCameraCharacteristics != null
                ? mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                : null;
    }

    /** The device's own ISO limits, or null. */
    public Range<Integer> getSensitivityRange() {
        return mCameraCharacteristics != null
                ? mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                : null;
    }

    /**
     * Take the shutter off auto exposure and hold it at exposureNs, spending iso to compensate
     * (ReconStab #38, the manual half).
     *
     * This is the only lever on this device that can meet a blur budget while walking: the AE
     * target-FPS-range path bottoms out at 1/30 s, and a 3 px budget at 2777 px focal and a
     * walking 0.263 rad/s needs 1/243 s. It is also the dangerous one, which is why it is
     * opt-in, released the moment the motion stops, and bounded here rather than by the caller:
     *
     *  - the frame duration is pinned to the encoder's rate, so the camera cannot outrun the
     *    encoder the way a [60,60] AE range did on 2026-09-02 and kill the recording;
     *  - exposure and ISO are both clamped to the device's declared ranges;
     *  - AE_MODE goes OFF only for as long as a cap is in force, and clearManualExposure()
     *    puts it back.
     */
    public void setManualExposure(long exposureNs, int iso) {
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        Range<Long> expRange = getExposureTimeRange();
        if (expRange != null) {
            exposureNs = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), exposureNs));
        }
        Range<Integer> isoRange = getSensitivityRange();
        if (isoRange != null) {
            iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));
        }
        long frameDurationNs = 1000000000L / VideoEncoderCore.FRAME_RATE;
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_OFF);
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
            mManualExposureHeld = true;
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Could not set manual exposure: " + e);
        }
    }

    /** Hand the shutter back to auto exposure. Safe to call when nothing is held. */
    public void clearManualExposure() {
        if (!mManualExposureHeld || mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CameraMetadata.CONTROL_AE_MODE_ON);
            mPreviewRequestBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, null);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Could not release manual exposure: " + e);
        }
        mManualExposureHeld = false;
    }

    public boolean isManualExposureHeld() {
        return mManualExposureHeld;
    }

    public Range<Integer>[] getAvailableFpsRanges() {
        Range<Integer>[] r = mCameraCharacteristics != null
                ? mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                : null;
        return r != null ? r : new Range[0];
    }

    /** Set the AE target FPS range on the LIVE preview/record request, or clear it (null). */
    public void setAeTargetFpsRange(Range<Integer> range) {
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "Could not set AE target FPS range: " + e);
        }
    }

    /**
     * Focal length in pixels for the current frame, or 0 if not yet known.
     *
     * Prefer the HAL's own per-frame LENS_INTRINSIC_CALIBRATION (#31) over the derived
     * estimate. They disagree, and the derived one is wrong: measured on an S24U walk,
     * FocalLengthHelper reported 4607 px where the HAL's fx for the same frames was 2777.5.
     * A blur budget computed from the larger number overestimates smear by 1.66x, so the
     * hold-still cue fires at 60% of the motion it should -- and the operator is being told
     * to slow down for blur that is not there.
     */
    public float getFocalPixels() {
        if (mLastResult != null) {
            float[] k = mLastResult.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
            if (k != null && k.length >= 1 && k[0] > 0f) {
                return k[0];
            }
        }
        Float f = mFocalLengthHelper.getFocalLengthPixel();
        return f != null ? f : 0f;
    }

    /** Most recent metered exposure time in ns, or 0. */
    public long getLastExposureNs() {
        if (mLastResult != null) {
            Long e = mLastResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (e != null) {
                return e;
            }
        }
        return 0L;
    }

    /**
     * Hand the stillness trigger the optics it should be modelling.
     *
     * Focal length in pixels comes from the census-grade intrinsics where present and
     * falls back to focal-length-over-pixel-pitch; exposure comes from the most recent
     * metered result, because it moves by orders of magnitude between sun and shade and
     * a blur budget computed against a stale exposure is the wrong budget.
     */
    public void refreshTriggerOptics(StillnessTrigger trigger) {
        if (trigger == null) {
            return;
        }
        // One source for focal pixels, not three. This used to read the STATIC characteristic
        // while the blur budget read the per-frame result and the on-screen readout read the
        // derived helper -- three numbers for one quantity, and they disagreed by up to 1.66x.
        float focalPx = getFocalPixels();
        if (focalPx <= 0f) {
            Rect active = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            android.util.SizeF physical = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Float focalMm = mLastResult != null
                    ? mLastResult.get(CaptureResult.LENS_FOCAL_LENGTH) : null;
            if (active != null && physical != null && focalMm != null
                    && physical.getWidth() > 0) {
                focalPx = focalMm * active.width() / physical.getWidth();
            }
        }
        long exposureNs = 0;
        if (mLastResult != null) {
            Long e = mLastResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (e != null) {
                exposureNs = e;
            }
        }
        trigger.updateOptics(focalPx, exposureNs);
    }

    private void setAutoAlgorithmLock(boolean lock) {
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        try {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, lock);
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_LOCK, lock);
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
            Log.d(TAG, "AE/AWB lock " + (lock ? "engaged" : "released"));
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "Could not change AE/AWB lock: " + e);
        }
    }

    public Camera2Proxy(Activity activity, CameraSettingsManager cameraSettingsManager) {
        mActivity = activity;
        mCameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        mCameraSettingsManager = cameraSettingsManager;
    }

    public Size configureCamera() {
        try {
            mCameraIdStr = CameraUtils.getRearCameraId(mCameraManager);
            mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraIdStr);

            // Update settings to reflect Characteristics
            mCameraSettingsManager.updateSettings(mCameraCharacteristics);

            Size videoSize = mCameraSettingsManager.getVideoSize();

            sensorArraySize = mCameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);


            mFocalLengthHelper.setLensParams(mCameraCharacteristics);
            mFocalLengthHelper.setImageSize(videoSize);


            // Find out if we need to swap dimension to get the preview size relative to sensor coordinate.
            mSensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSwappedDimensions = (mSensorOrientation == 90 || mSensorOrientation == 270);


            StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics
                    .SCALER_STREAM_CONFIGURATION_MAP);

            mPreviewSize = CameraUtils.chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    videoSize.getWidth(), videoSize.getHeight(), videoSize);
            Log.d(TAG, "Video size " + videoSize.toString() +
                    " preview size " + mPreviewSize.toString());

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return mPreviewSize;
    }

    public void openCamera() {
        Log.v(TAG, "openCamera");
        startBackgroundThread();
        if (mCameraIdStr.isEmpty()) {
            Log.v(TAG, "openCamera - needs configuring");
            configureCamera();
        }
        try {
            mCameraManager.openCamera(mCameraIdStr, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
        }
    }

    public void releaseCamera() {
        Log.v(TAG, "releaseCamera");
        stopRecordingCaptureResult();
        if (null != mCaptureSession) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (mStillCaptureManager != null) {
            mStillCaptureManager.release();
            mStillCaptureManager = null;
        }
        mPreviewSurfaceTexture = null;
        mCameraIdStr = "";
        stopBackgroundThread();
    }

    public void setPreviewSurfaceTexture(SurfaceTexture surfaceTexture) {
        mPreviewSurfaceTexture = surfaceTexture;
    }

    private void initPreviewRequest() {
        try {
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Set control elements, we want auto white balance
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            mPreviewRequestBuilder.set(
                    CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
            // Chromatic aberration correction resamples the colour channels relative to one
            // another — a per-channel geometric warp on top of the lens model the solve is
            // trying to fit. Off, where the device allows it.
            mPreviewRequestBuilder.set(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF);

            mCameraSettingsManager.updateRequestBuilder(mPreviewRequestBuilder);

            if (mPreviewSurfaceTexture != null && mPreviewSurface == null) { // use texture view
                mPreviewSurfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight());
                mPreviewSurface = new Surface(mPreviewSurfaceTexture);
            }
            mPreviewRequestBuilder.addTarget(mPreviewSurface);

            // Stills share the preview session: the JPEG (and RAW) readers must be declared
            // as outputs at configuration time, even though they only receive frames when a
            // burst is fired.
            mStillCaptureManager =
                    new StillCaptureManager(mCameraCharacteristics, mCameraManager,
                            mBackgroundHandler,
                            ((CameraCaptureActivity) mActivity).getmImuManager());
            final List<Surface> stillSurfaces =
                    mStillCaptureManager.getSurfaces(mStillCaptureManager.rawSupported());

            CameraCaptureSession.StateCallback cb =
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mCaptureSession = session;
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "ConfigureFailed. session: mCaptureSession");
                        }
                    };
            if (Build.VERSION.SDK_INT >= 28) {
                List<OutputConfiguration> outputs = new ArrayList<>();
                OutputConfiguration outputConfiguration = new OutputConfiguration(mPreviewSurface);
                mCameraSettingsManager.updateOutputConfiguration(outputConfiguration);
                outputs.add(outputConfiguration);
                for (Surface s : stillSurfaces) {
                    outputs.add(new OutputConfiguration(s));
                }
                // Physical-camera streams for the simultaneous stereo pair. The probe
                // confirmed preview+JPEG+RAW+2 physical configures on this device, so
                // they can live in the main session rather than needing their own.
                for (java.util.Map.Entry<String, Surface> e
                        : mStillCaptureManager.getStereoSurfaces().entrySet()) {
                    OutputConfiguration oc = new OutputConfiguration(e.getValue());
                    oc.setPhysicalCameraId(e.getKey());
                    outputs.add(oc);
                }
                mCameraDevice.createCaptureSession(new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputs,
                        r -> mBackgroundHandler.post(r),
                        cb));
            } else {
                mCameraDevice.createCaptureSession(
                        Collections.singletonList(mPreviewSurface),
                        cb,
                        mBackgroundHandler);
            }

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void startPreview() {
        Log.v(TAG, "startPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "startPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequest, mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // IllegalStateException may happen if shutting down the camera session prior to
            // full initialization.
            e.printStackTrace();
        }
    }

    public void stopPreview() {
        Log.v(TAG, "stopPreview");
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            Log.w(TAG, "stopPreview: mCaptureSession or mPreviewRequestBuilder is null");
            return;
        }
        try {
            mCaptureSession.stopRepeating();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback mSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               TotalCaptureResult result) {

                    // Keep the latest metered result: a bracket steps away from this.
                    mLastResult = result;

                    // A focus stack step may be waiting on the lens to arrive. Checked
                    // before anything else touches AF state, and cheap when idle.
                    onFocusResult(request, result);

                    if (mCameraSettingsManager.focusOnTouch()) {
                        mFocusTriggered |= (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN);
                    }
                    if (mCameraSettingsManager.exposureOnTouch()) {
                        mExposureTriggered |= (result.get(CaptureResult.CONTROL_AE_STATE) == CaptureResult.CONTROL_AE_STATE_SEARCHING);
                    }

                    if (mFocusTriggered) {
                        //Log.d(TAG, "Focus state:" + result.get(CaptureResult.CONTROL_AF_STATE));
                        // We are handling auto-focus, cancel if focused to go back inactive state.
                        // Seems necessary on some phones, even though the documentation says otherwise.
                        if ((result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED) ||
                                (result.get(CaptureResult.CONTROL_AF_STATE) == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {

                            mFocusTriggered = false;

                            // Send single cancel event
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                            try {
                                mCaptureSession.capture(
                                        mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                            //Reset trigger for future calls
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        }

                    }

                    if (mCameraSettingsManager.exposureOnTouch() && !mFocusTriggered && mExposureTriggered) {
                        // We are handling auto-exposure, lock if converged.
                        // Wait for auto-focus to finish first
                        Log.d(TAG, "Exposure state:" + result.get(CaptureResult.CONTROL_AE_STATE));
                        if (result.get(CaptureResult.CONTROL_AE_STATE) != CaptureResult.CONTROL_AE_STATE_SEARCHING) {
                            mExposureTriggered = false;
                            //Lock AE
                            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                            try {
                                mCaptureSession.setRepeatingRequest(
                                        mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }

                        }
                    }

                    Long exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);

                    Float fl = result.get(CaptureResult.LENS_FOCAL_LENGTH);

                    Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

                    Rect rect = result.get(CaptureResult.SCALER_CROP_REGION);
                    mFocalLengthHelper.setmFocalLength(fl);
                    mFocalLengthHelper.setmFocusDistance(fd);
                    mFocalLengthHelper.setmCropRegion(rect);
                    Float focal_length_pix = mFocalLengthHelper.getFocalLengthPixel();

                    if (mRecordingMetadata) {
                        // est_focal_length_pix keeps the DERIVED estimate: the field is named
                        // "est" and the HAL's own value has its own field
                        // (lens_intrinsic_calibration), so both provenances survive in the file.
                        writeCaptureData(result, focal_length_pix);
                    }
                    // The readout shows the number the app actually computes smear from -- the
                    // HAL's per-frame fx where it exists. Showing the derived estimate instead
                    // put 4406 px on screen against a recorded 2884, and a readout that
                    // disagrees with the file teaches the operator to distrust the file.
                    ((CameraCaptureActivity) mActivity).getmCameraCaptureFragment()
                            .updateCaptureResultPanel(getFocalPixels(), exposureTimeNs);
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
//                    Log.d(TAG, "mSessionCaptureCallback,  onCaptureProgressed");
                }
            };


    void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
        if (!mCameraSettingsManager.focusOnTouch() && !mCameraSettingsManager.exposureOnTouch()) {
            return;
        }
        // Set region for focus, must be present in all capture requests during auto focus.
        int x,y;
        if (mSwappedDimensions) {
            y = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.height());
            x = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.width());
        } else {
            y = (int) ((eventY / (float) viewHeight) * (float) sensorArraySize.height());
            x = (int) ((eventX / (float) viewWidth) * (float) sensorArraySize.width());
        }
        final int halfTouchWidth = 400;
        final int halfTouchHeight = 400;
        MeteringRectangle focusAreaTouch = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                Math.max(y - halfTouchHeight, 0),
                halfTouchWidth * 2,
                halfTouchHeight * 2,
                MeteringRectangle.METERING_WEIGHT_MAX - 1);

        // Set metering regions and AE mode
        if (mCameraSettingsManager.focusOnTouch()) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                    new MeteringRectangle[]{focusAreaTouch});
        }
        if (mCameraSettingsManager.exposureOnTouch()) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                    new MeteringRectangle[]{focusAreaTouch});
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
        }
        // Update running requests with metering regions
        try {
            mCaptureSession.setRepeatingRequest(
                    mPreviewRequestBuilder.build(),
                    mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        // Send triggers
        if (mCameraSettingsManager.focusOnTouch()) {
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);

            try {
                mCaptureSession.capture(
                        mPreviewRequestBuilder.build(),
                        mSessionCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            // Reset Trigger state for future requests
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
        }
    }

    public void writeCameraInfo() {

        RecordingProtos.CameraInfo.Builder metaBuilder = RecordingProtos.CameraInfo.newBuilder()
                .setOpticalImageStabilization(mCameraSettingsManager.OISEnabled())
                .setVideoStabilization(mCameraSettingsManager.DVSEnabled())
                .setDistortionCorrection(mCameraSettingsManager.DistortionCorrectionEnabled())
                .setSensorOrientation(mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));

        Size resolution = mCameraSettingsManager.getVideoSize();
        metaBuilder.setResolution(
                RecordingProtos.CameraInfo.Size.newBuilder()
                        .setHeight(mSwappedDimensions ? resolution.getWidth() : resolution.getHeight())
                        .setWidth(mSwappedDimensions ?  resolution.getHeight() : resolution.getWidth())
        );
        Rect arraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        metaBuilder.setPreCorrectionActiveArraySize(
                RecordingProtos.CameraInfo.Size.newBuilder()
                        .setHeight(arraySize.height())
                        .setWidth(arraySize.width())
        );

        Integer timestamp_source = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (timestamp_source != null) {
            metaBuilder.setTimestampSourceValue(timestamp_source);
        }

        Integer focus_cal = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        if (focus_cal != null) {
            metaBuilder.setFocusCalibrationValue(focus_cal);
        }

        float[] lensTranslation = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
        if (lensTranslation != null) {
            for (float lT : lensTranslation) {
                metaBuilder.addLensPoseTranslation(lT);
            }
        }

        float[] lensRotation = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
        if (lensRotation != null) {
            for (float lR : lensRotation) {
                metaBuilder.addLensPoseRotation(lR);
            }
        }

        float[] intrinsics = mCameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if ((intrinsics != null) && (abs(intrinsics[0]) > 0)) {
            for (float e : mFocalLengthHelper.getTransformedIntrinsic()) {
                metaBuilder.addIntrinsicParams(e);
            }
            for (float e : intrinsics) {
                metaBuilder.addOriginalIntrinsicParams(e);
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            float[] distortion = mCameraCharacteristics.get(CameraCharacteristics.LENS_DISTORTION);
            if ((distortion != null) && (abs(distortion[0]) > 0)) {
                for (float e : distortion) {
                    metaBuilder.addDistortionParams(e);
                }
            }
            Integer lensPoseReference = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
            if (lensPoseReference != null) {
                metaBuilder.setLensPoseReferenceValue(lensPoseReference);
            }
        }
        mRecordingWriter.queueData(metaBuilder.build());

    }

    private void writeCaptureData(CaptureResult result, Float focal_length_pix) {
        RecordingProtos.VideoFrameMetaData.Builder frameBuilder = RecordingProtos.VideoFrameMetaData.newBuilder()
                .setTimeNs(result.get(CaptureResult.SENSOR_TIMESTAMP))
                .setFocalLengthMm(result.get(CaptureResult.LENS_FOCAL_LENGTH))
                .setEstFocalLengthPix(focal_length_pix);

        int focus_state = result.get(CaptureResult.CONTROL_AF_STATE);
        frameBuilder.setFocusLocked(focus_state != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                                 && focus_state != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN);

        // The following values are allowed to be null
        Long sExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (sExp != null) {
            frameBuilder.setExposureTimeNs(sExp);
        }

        Long sDur = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (sDur != null) {
            frameBuilder.setFrameDurationNs(sDur);
        }

        Long sRoll = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        if (sRoll != null) {
            frameBuilder.setFrameReadoutNs(sRoll);
        }

        Integer sSens = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sSens != null) {
            frameBuilder.setIso(sSens);
        }

        Float fDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (fDist != null) {
            frameBuilder.setFocusDistanceDiopters(fDist);
        }

        // Crop region in active-array coordinates: if it moves frame to frame, EIS is on.
        Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
        if (crop != null) {
            frameBuilder.setCropRegion(RecordingProtos.VideoFrameMetaData.Rect.newBuilder()
                    .setLeft(crop.left)
                    .setTop(crop.top)
                    .setRight(crop.right)
                    .setBottom(crop.bottom));
        }

        if (Build.VERSION.SDK_INT >= 28) {
            OisSample[] oisSamples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES);
            if (oisSamples != null) {
                for (OisSample sample : oisSamples) {
                    float[] scaledSample = mFocalLengthHelper.transformOISSample(sample);
                    RecordingProtos.VideoFrameMetaData.OISSample.Builder oisBuilder =
                            RecordingProtos.VideoFrameMetaData.OISSample.newBuilder()
                                    .setTimeNs(sample.getTimestamp())
                                    .setXShift(scaledSample[0])
                                    .setYShift(scaledSample[1]);
                    frameBuilder.addOISSamples(oisBuilder);
                }
            }
        }

        writeFrameRadiometry(result, frameBuilder);

        mRecordingWriter.queueData(frameBuilder.build());

    }

    /**
     * Per-frame radiometry (ReconStab #39), everything the CaptureResult already carries so a
     * floating auto-exposure can be undone at bake time and the ISO ceiling of #38 has a number.
     * Every field is null-guarded: a HAL may report any subset, and a missing one is silence,
     * not a zero.
     */
    private void writeFrameRadiometry(CaptureResult result,
                                      RecordingProtos.VideoFrameMetaData.Builder b) {
        android.hardware.camera2.params.RggbChannelVector gains =
                result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (gains != null) {
            b.addColorCorrectionGains(gains.getRed());
            b.addColorCorrectionGains(gains.getGreenEven());
            b.addColorCorrectionGains(gains.getGreenOdd());
            b.addColorCorrectionGains(gains.getBlue());
        }
        android.hardware.camera2.params.ColorSpaceTransform xform =
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (xform != null) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    b.addColorCorrectionTransform(xform.getElement(col, row).floatValue());
                }
            }
        }
        Integer tonemap = result.get(CaptureResult.TONEMAP_MODE);
        if (tonemap != null) {
            b.setTonemapMode(tonemap);
        }
        Integer boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        if (boost != null) {
            b.setPostRawSensitivityBoost(boost);
        }
        float[] blackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (blackLevel != null) {
            for (float v : blackLevel) {
                b.addDynamicBlackLevel(v);
            }
        }
        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        if (aeState != null) {
            b.setAeState(aeState);
        }
        Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
        if (awbState != null) {
            b.setAwbState(awbState);
        }
        Integer aeMode = result.get(CaptureResult.CONTROL_AE_MODE);
        if (aeMode != null) {
            b.setAeMode(aeMode);
        }
        Integer awbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
        if (awbMode != null) {
            b.setAwbMode(awbMode);
        }
        Float aperture = result.get(CaptureResult.LENS_APERTURE);
        if (aperture != null) {
            b.setLensAperture(aperture);
        }
        Integer lensState = result.get(CaptureResult.LENS_STATE);
        if (lensState != null) {
            b.setLensState(lensState);
        }
        // What the hardware DID about stabilization, not what we asked for (ReconStab #41).
        // The request is set from a preference; the result is the HAL's answer, and on a vendor
        // HAL the two are allowed to differ. A gyro-derived blur kernel is only valid while the
        // optical path is fixed, so an unrecorded OIS is a silent invalidation of every kernel.
        Integer ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        if (ois != null) {
            b.setLensOpticalStabilizationMode(ois);
        }
        Integer eis = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
        if (eis != null) {
            b.setVideoStabilizationMode(eis);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            Integer distortion = result.get(CaptureResult.DISTORTION_CORRECTION_MODE);
            if (distortion != null) {
                b.setDistortionCorrectionMode(distortion);
            }
            Integer oisDataMode = result.get(CaptureResult.STATISTICS_OIS_DATA_MODE);
            if (oisDataMode != null) {
                b.setOisDataMode(oisDataMode);
            }
        }
        Integer ev = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        if (ev != null) {
            b.setAeExposureCompensation(ev);
        }
        android.util.Pair<Double, Double>[] noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (noise != null) {
            for (android.util.Pair<Double, Double> p : noise) {
                b.addNoiseProfile(p.first);
                b.addNoiseProfile(p.second);
            }
        }
        // Per-frame lens intrinsics, IF the HAL reports them dynamically (#31). Most devices only
        // expose the static characteristic; where this is non-null it captures focus breathing.
        float[] intrinsics = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
        if (intrinsics != null) {
            for (float v : intrinsics) {
                b.addLensIntrinsicCalibration(v);
            }
        }
    }

    private void startBackgroundThread() {
        if (mBackgroundThread == null || mBackgroundHandler == null) {
            Log.v(TAG, "startBackgroundThread");
            mBackgroundThread = new HandlerThread("CameraBackground");
            mBackgroundThread.start();
            mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        Log.v(TAG, "stopBackgroundThread");
        try {
            if (mBackgroundThread != null) {
                mBackgroundThread.quitSafely();
                mBackgroundThread.join();
            }
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
