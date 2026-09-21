package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Context;
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
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
            // Not only at open: this fires whenever the device fails, including mid-session.
            // 2026-09-20: ERROR_CAMERA_DEVICE (3) on the first frame of a four-lens warm-up,
            // two seconds into a twenty-second run.
            Log.e(TAG, "camera device error " + error + " (1 in use, 2 max in use, "
                    + "3 device, 4 disabled, 5 service)");
            releaseCamera();
            final DeviceErrorListener l = mDeviceErrorListener;
            if (l != null) {
                // Background handler here; the session is driven from main.
                new Handler(Looper.getMainLooper())
                        .post(() -> l.onCameraDeviceError(error));
            }
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

    // ---------------------------------------------------------------- borrowed requests
    //
    // Three things take the repeating request away from the preview for a while: a focus
    // stack, a pair warm-up, and periodic pairs. Each lives in its own class and reaches the
    // session through mHost; all of them come back through restorePreview().

    private final RepeatingRequestHost mHost = new RepeatingRequestHost() {
        @Override
        public CameraDevice device() {
            return mCameraDevice;
        }

        @Override
        public CameraCaptureSession session() {
            return mCaptureSession;
        }

        @Override
        public CaptureRequest.Builder previewBuilder() {
            return mPreviewRequestBuilder;
        }

        @Override
        public void replacePreviewBuilder(CaptureRequest.Builder b) {
            mPreviewRequestBuilder = b;
        }

        @Override
        public Surface previewSurface() {
            return mPreviewSurface;
        }

        @Override
        public Handler handler() {
            return mBackgroundHandler;
        }

        @Override
        public StillCaptureManager stills() {
            return mStillCaptureManager;
        }

        @Override
        public void reissuePreview() throws CameraAccessException {
            Camera2Proxy.this.reissuePreview();
        }

        @Override
        public void setRepeating(CaptureRequest request) throws CameraAccessException {
            if (mCaptureSession == null) {
                throw new IllegalStateException("no capture session");
            }
            mCaptureSession.setRepeatingRequest(
                    request, mSessionCaptureCallback, mBackgroundHandler);
        }

        @Override
        public void restorePreview(String why) {
            Camera2Proxy.this.restorePreview(why);
        }
    };

    private final FocusStackSequencer mFocusStack = new FocusStackSequencer(mHost);
    private final StereoRequests mStereoRequests = new StereoRequests(mHost);

    /** The preview builder, as it now stands, becomes the repeating request. */
    private void reissuePreview() throws CameraAccessException {
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            throw new IllegalStateException("no capture session");
        }
        mCaptureSession.setRepeatingRequest(
                mPreviewRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
    }

    /**
     * THE way back to the preview, for everything that borrowed the repeating request.
     *
     * There were three: the single pair's inline lambda, the pair sequence's own method, and
     * the focus stack's. Only one of them closed a pair arm that had not completed, so an arm
     * left open by the single-pair path would have claimed the first frames of the NEXT
     * physical stream to start -- a periodic run's, say -- as its own. Putting the preview
     * back ends whatever stream an open arm was waiting on, so whoever does the one does the
     * other.
     */
    private void restorePreview(String why) {
        if (mStillCaptureManager != null) {
            // An arm that never got both frames is logged here rather than left to the next
            // arm to notice; its rows, if any, still resolve through the pending list.
            mStillCaptureManager.stereo().finishStreamKeep();
        }
        if (mCaptureSession == null || mPreviewRequestBuilder == null) {
            return;
        }
        try {
            reissuePreview();
            Log.d(TAG, why + ", preview restored");
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "could not restore preview (" + why + "): " + e);
        }
    }

    /**
     * Drive a focus stack one settled step at a time. See {@link FocusStackSequencer}.
     *
     * @param shots number of slices; the plan is centred on the current autofocus result
     *              and stepped by the depth of field, so this is "how thick a subject".
     */
    public void captureFocusStack(int shots, boolean writeRaw, File outputDir,
                                  RecordingWriter writer) {
        mFocusStack.start(shots, writeRaw, outputDir, writer, mLastResult);
    }

    // ---------------------------------------------------------------- the physical lenses
    // Requests: StereoRequests. Frames and rows: StereoCapture. Roles: LensRoles.

    /** One simultaneous pair, or every configured pair in sequence. See StereoRequests. */
    public void captureStereoPair(File outputDir, RecordingWriter writer,
                                  StillCaptureManager.CaptureMode mode) {
        mStereoRequests.capturePair(outputDir, writer, mode);
    }

    /**
     * End any pair warm-up in flight, because a recording is about to need the repeating
     * request. Returns {pairs armed, pairs planned}, or null if nothing was in flight.
     */
    public int[] cancelStereoPairs(String why) {
        return mStereoRequests.cancelPairs(why);
    }

    /** Whether a pair sequence is between its first warm-up and its final preview restore. */
    public boolean isStereoSequenceActive() {
        return mStereoRequests.sequenceActive();
    }

    public int oneShotStereoBursts() {
        return mStillCaptureManager == null ? 0 : mStillCaptureManager.stereo().oneShotStereoBursts();
    }

    /** A new session's pairs count from zero, whether or not it goes on to shoot any. */
    public void resetStereoCounts() {
        if (mStillCaptureManager != null) {
            mStillCaptureManager.stereo().resetSessionCounts();
        }
    }

    public int stereoMetaRows() {
        return mStillCaptureManager == null ? 0 : mStillCaptureManager.stereo().stereoMetaRows();
    }

    public boolean stereoSupported() {
        return mStereoRequests.supported();
    }

    public boolean periodicStereoActive() {
        return mStereoRequests.periodicActive();
    }

    public int periodicStereoPairs() {
        return mStillCaptureManager != null ? mStillCaptureManager.stereo().periodicPairCount() : 0;
    }

    /** Both physical streams into the repeating request, a pair kept every intervalMs. */
    public void startPeriodicStereo(long intervalMs, File outputDir, RecordingWriter writer,
                                    StillCaptureManager.CaptureMode mode) {
        mStereoRequests.startPeriodic(intervalMs, outputDir, writer, mode);
    }

    /** Take the physical streams back out of the repeating request. */
    public void stopPeriodicStereo() {
        mStereoRequests.stopPeriodic();
    }

    // FRAME 0 WAS EXPOSED BEFORE THE PRESS. The pipeline is a few frames deep, so the first
    // frame the encoder numbers left the sensor before the record button's press reached this
    // class -- and its result row was written or not depending on which side of
    // mRecordingMetadata the result happened to arrive. About half of all clips lost it (fork
    // issue #3: time_dropped_unmatched = 1, the first row is frame 1), which is cosmetic for a
    // join by timestamp and an off-by-one from the first frame for a join by position.
    //
    // So the last few results are kept while NOT recording, and handed to the writer when
    // recording starts. Frame 0's is among them; the ones older than it are discarded by
    // FramePairing as pre-roll and are not counted as losses. References only -- no row is
    // built while idle, so the preview costs what it did.
    private static final int PRE_ROLL_RESULTS = 12;     // 400 ms at 30 fps
    private final TotalCaptureResult[] mPreRollResults = new TotalCaptureResult[PRE_ROLL_RESULTS];
    private final Float[] mPreRollFocal = new Float[PRE_ROLL_RESULTS];
    private int mPreRollNext = 0;       // camera thread only

    /** Camera thread. Remember a result that arrived while nothing was recording. */
    private void rememberForPreRoll(TotalCaptureResult result, Float focalLengthPix) {
        mPreRollResults[mPreRollNext] = result;
        mPreRollFocal[mPreRollNext] = focalLengthPix;
        mPreRollNext = (mPreRollNext + 1) % PRE_ROLL_RESULTS;
    }

    /**
     * Camera thread. Write the remembered results, oldest first, then start recording live
     * ones -- on this thread, so that a live result cannot land in the file between two
     * remembered ones: FramePairing drops whatever is older than the row it has reached.
     */
    private void beginFrameRows() {
        for (int k = 0; k < PRE_ROLL_RESULTS; k++) {
            int i = (mPreRollNext + k) % PRE_ROLL_RESULTS;
            TotalCaptureResult r = mPreRollResults[i];
            if (r != null && r.get(CaptureResult.SENSOR_TIMESTAMP) != null) {
                mRecordingWriter.queueData(FrameRecords.frame(r, mPreRollFocal[i],
                        mFocalLengthHelper));
            }
            mPreRollResults[i] = null;
            mPreRollFocal[i] = null;
        }
        mRecordingMetadata = true;
    }

    public void startRecordingCaptureResult(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
        Handler camera = mBackgroundHandler;
        if (camera != null) {
            camera.post(this::beginFrameRows);
        } else {
            mRecordingMetadata = true;
        }
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
            reissuePreview();
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
            reissuePreview();
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
        Rational step =
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
            reissuePreview();
            mExposureCompensation = units;
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Could not set exposure compensation: " + e);
            return Float.NaN;
        }
        return units * step.floatValue();
    }

    /** Current exposure compensation in stops. */
    public float getExposureCompensationStops() {
        Rational step = mCameraCharacteristics != null
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
            reissuePreview();
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
            reissuePreview();
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
            reissuePreview();
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
                // NOT multiplied by cropMagnification(), on measurement rather than on argument.
                // The crop reasoning below is sound and its conclusion was WRONG for this device
                // at this zoom setting: measured from gyro rotation against optical flow on four
                // 30 s clips, the recorded frames' focal is 2,304-3,085 px at 4080 -- the HAL's
                // own number, uncorrected. See ReconStab #48; until that is settled the app uses
                // the value that a measurement supports.
                return k[0];
            }
        }
        Float f = mFocalLengthHelper.getFocalLengthPixel();
        return f != null ? f : 0f;
    }

    /**
     * How much bigger a recorded pixel is than an active-array pixel.
     *
     * LENS_INTRINSIC_CALIBRATION is expressed in ACTIVE ARRAY pixels -- on this device fx 2777.6
     * with cx 2044.9, which is half of 4080. The recorded stream is not the active array: it is
     * SCALER_CROP_REGION, a 2448x1836 window here, scaled up to 3060x4080. So the focal length
     * of the frames actually written to disk is 2777.6 x 4080/2448 = 4629 px, and using the raw
     * characteristic understates every angle-to-pixel conversion by 1.67x.
     *
     * This was got backwards once already, on 2026-09-03: FocalLengthHelper's larger number was
     * assumed to be the wrong one because it disagreed with the HAL, when in fact the two describe
     * DIFFERENT COORDINATE SYSTEMS and the helper was the one describing the file. The right
     * answer takes the HAL's measured, per-frame value -- which tracks focus breathing, as the
     * helper's nominal computation cannot -- and puts it in the frame's own pixels.
     */
    private float cropMagnification() {
        if (mLastResult == null || mCameraSettingsManager == null) {
            return 1f;
        }
        Rect crop = mLastResult.get(CaptureResult.SCALER_CROP_REGION);
        Size video = mCameraSettingsManager.getVideoSize();
        if (crop == null || video == null || crop.width() <= 0 || crop.height() <= 0) {
            return 1f;
        }
        // Long side to long side, so the sensor's 90-degree rotation into the stream cannot
        // silently pair a width with a height.
        float videoLong = Math.max(video.getWidth(), video.getHeight());
        float cropLong = Math.max(crop.width(), crop.height());
        float m = videoLong / cropLong;
        return m > 0 ? m : 1f;
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
            SizeF physical = mCameraCharacteristics.get(
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
            reissuePreview();
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
        // The session is going away with it; no request to restore, just the bookkeeping.
        mStereoRequests.sessionGone();
        mFocusStack.sessionGone();
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

    /**
     * Rebuild the capture session, so a SESSION-LEVEL setting can take effect without the
     * operator leaving the app and coming back.
     *
     * Which streams exist is fixed when createCaptureSession runs and there is no adding one
     * to a live session. Until now that meant the lens-set cells could not set their own
     * setting: L1 and G1/G2 told the operator to go into Settings, flip it, background the
     * app and return, and the cell then recorded whatever was actually in force rather than
     * what it asked for. On 2026-09-20 that failed in both directions in the space of two
     * minutes -- one L1 ran on the pair because the camera had not been cycled, the next ran
     * on the all-lens set and lost three of seven stills -- and both receipts said "agrees".
     * A cell that cannot set its own conditions is not a test, it is a suggestion.
     *
     * The DEVICE stays open. Only the session, the still readers and the physical streams are
     * rebuilt, which is the part that reads the preference; the preview SurfaceTexture is
     * still bound and is reused as-is. Takes a few hundred milliseconds, so the caller waits
     * before pressing anything.
     */
    public void reconfigureLensStreams() {
        if (mCameraDevice == null) {
            Log.w(TAG, "reconfigureLensStreams: no camera device open");
            return;
        }
        Log.i(TAG, "rebuilding the capture session to pick up a session-level setting");
        mStereoRequests.sessionGone();
        mFocusStack.sessionGone();
        if (mCaptureSession != null) {
            try {
                mCaptureSession.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "closing the old session: " + e);
            }
            mCaptureSession = null;
        }
        if (mStillCaptureManager != null) {
            mStillCaptureManager.release();
            mStillCaptureManager = null;
        }
        initPreviewRequest();
    }

    /** How long {@link #reconfigureLensStreams} needs before the session is usable again. */
    public static final long SESSION_REBUILD_MS = 1200L;

    /**
     * Told when the camera DEVICE fails, as opposed to a request failing.
     *
     * Until 2026-09-20 onError released the camera and told nobody. The session it had been
     * serving kept running: the stillness trigger went on deciding shots, each one fell out
     * of captureNow with no camera to take it, and the run ended on its timer twenty seconds
     * later with a receipt that could count the missing stills and could not say why. The
     * error is a fact about the session and belongs in its receipt, and the activity that
     * owns the session is the one to stop it -- exactly as it does for space, heat and
     * charge.
     */
    public interface DeviceErrorListener {
        void onCameraDeviceError(int error);
    }

    private DeviceErrorListener mDeviceErrorListener;

    public void setDeviceErrorListener(DeviceErrorListener l) {
        mDeviceErrorListener = l;
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
            LensRoles.setAllLensShot("all".equals(PreferenceManager
                    .getDefaultSharedPreferences(mActivity).getString("lens_set", "pair")));
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
                for (Map.Entry<String, Surface> e
                        : mStillCaptureManager.stereo().getStereoSurfaces().entrySet()) {
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
            // Built HERE, not replayed from the snapshot taken at onConfigured. That snapshot
            // is the bare preview: no physical stereo streams, none of the keys the run has
            // set since. startPeriodicStereo replaces mPreviewRequestBuilder with a
            // TEMPLATE_RECORD request carrying both physical streams, and a repeating request
            // is the ONLY thing feeding the periodic path -- so any startPreview() after it
            // silently ended the pairs for the rest of the clip and left the receipt saying
            // the run had a metric anchor it stopped collecting. The idle-sleep timer
            // (idle_sleep_s, 30 s by default) calls exactly this pair of methods, which puts
            // it inside every walk longer than its timeout.
            mPreviewRequest = mPreviewRequestBuilder.build();
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

                    // Periodic stereo pairs (#36) arm on this clock and match their rows
                    // from these results. A no-op when the feature is off.
                    if (mStillCaptureManager != null) {
                        mStillCaptureManager.stereo().onRepeatingResult(result);
                    }

                    // A focus stack step may be waiting on the lens to arrive. Checked
                    // before anything else touches AF state, and cheap when idle.
                    mFocusStack.onResult(request, result);

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
                                reissuePreview();
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
                        mRecordingWriter.queueData(
                                FrameRecords.frame(result, focal_length_pix, mFocalLengthHelper));
                    } else {
                        rememberForPreRoll(result, focal_length_pix);
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
            reissuePreview();
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
        mRecordingWriter.queueData(FrameRecords.cameraInfo(mCameraSettingsManager,
                mCameraCharacteristics, mFocalLengthHelper, mSwappedDimensions));
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
