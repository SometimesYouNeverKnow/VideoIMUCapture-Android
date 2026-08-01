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
import androidx.annotation.NonNull;

import androidx.preference.PreferenceManager;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

    public void startRecordingCaptureResult(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
        mRecordingMetadata = true;
        setAutoAlgorithmLock(true);
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
        float focalPx = 0f;
        float[] intrinsics = mCameraCharacteristics.get(
                CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if (intrinsics != null && intrinsics.length >= 1 && intrinsics[0] > 0) {
            focalPx = intrinsics[0];
        } else {
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
                    new StillCaptureManager(mCameraCharacteristics, mBackgroundHandler,
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
                        writeCaptureData(result, focal_length_pix);
                    }
                    ((CameraCaptureActivity) mActivity).getmCameraCaptureFragment()
                            .updateCaptureResultPanel(focal_length_pix, exposureTimeNs);
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

        mRecordingWriter.queueData(frameBuilder.build());

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
