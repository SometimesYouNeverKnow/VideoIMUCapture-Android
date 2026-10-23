package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Full-resolution still capture: single shots, exposure brackets, and focus stacks.
 *
 * Why stills matter alongside video, especially on a tripod: no rolling-shutter smear, no
 * inter-frame video compression, the full sensor array rather than a 16:9 crop, and RAW.
 * RAW is the substantive one for radiance-field work — 3DGS assumes roughly linear
 * radiance, while a JPEG arrives with a tone curve already baked in that the model then
 * spends capacity undoing.
 *
 * Brackets drive SENSOR_EXPOSURE_TIME directly rather than AE compensation: the device
 * exposes ~10.5 stops of shutter (SM-S928U: 83 us .. 117 ms) against only +/-2 EV of AE
 * compensation, and an explicitly requested exposure is recorded exactly rather than
 * negotiated.
 *
 * Each shot is written with a StillMetaData row carrying its own exposure, ISO, focus
 * distance, EV offset and the orientation quaternion at the shutter instant — the last of
 * which is what lets a tripod pan be stitched from measured angles.
 */
public class StillCaptureManager {
    private static final String TAG = "StillCapture";

    public enum Mode {SINGLE, EXPOSURE_BRACKET, FOCUS_STACK}

    /** Which operating mode requested the shot; recorded per still. */
    public enum CaptureMode {MANUAL, WALK, OBJECT, PANO}

    /** Reader depth, and therefore the longest burst that can be held in flight. */
    private static final int MAX_BURST = 9;

    /** Queued per shot so results can be matched to the images they produced. */
    private static class PendingShot {
        final int index;
        final float evOffset;
        String jpegName;
        String dngName;

        PendingShot(int index, float evOffset) {
            this.index = index;
            this.evOffset = evOffset;
        }
    }

    private final CameraCharacteristics mCharacteristics;
    private final Handler mHandler;
    private final IMUManager mImuManager;
    // 0 = leave the device default alone. Otherwise 1..100, applied per request.
    private int mJpegQuality = 0;

    /** The physical-lens half of the session: see {@link StereoCapture}. */
    private final StereoCapture mStereo;

    private ImageReader mJpegReader;
    private ImageReader mRawReader;
    private boolean mRawSupported;
    private RecordingWriter mRecordingWriter;
    private File mOutputDir;
    private long mBurstId;
    // Trigger provenance for the next burst, set by WALK mode before it fires.
    private CaptureMode mCaptureMode = CaptureMode.MANUAL;
    private float mPredictedBlurPx = 0f;
    private float mOmegaAtTrigger = 0f;
    private boolean mTriggerForced = false;
    private boolean mWriteRawThisBurst = false;
    private Mode mMode = Mode.SINGLE;
    private int mBurstSize;
    private final List<Float> mEvOffsets = new ArrayList<>();
    private final Deque<PendingShot> mPendingJpeg = new ArrayDeque<>();
    private final Deque<PendingShot> mPendingRaw = new ArrayDeque<>();
    private int mShotCounter;

    public StillCaptureManager(CameraCharacteristics characteristics,
                               CameraManager cameraManager,
                               Handler handler, IMUManager imuManager) {
        mCharacteristics = characteristics;
        mHandler = handler;
        mImuManager = imuManager;
        setupReaders();
        mStereo = new StereoCapture(mCharacteristics, cameraManager, mHandler, mImuManager, mIo);
    }

    private void setupReaders() {
        StreamConfigurationMap map =
                mCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return;
        }
        Size jpeg = largest(map.getOutputSizes(ImageFormat.JPEG));
        if (jpeg != null) {
            mJpegReader = ImageReader.newInstance(
                    jpeg.getWidth(), jpeg.getHeight(), ImageFormat.JPEG, MAX_BURST);
            mJpegReader.setOnImageAvailableListener(this::onJpeg, mHandler);
            Log.d(TAG, "JPEG stills at " + jpeg);
        }
        Size raw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        mRawSupported = hasCapability(CameraCharacteristics
                .REQUEST_AVAILABLE_CAPABILITIES_RAW) && raw != null;
        if (mRawSupported) {
            // REQUEST_MAX_NUM_OUTPUT_RAW is 1 on this hardware, so the queue depth here is
            // about buffering a burst, not about parallel RAW streams. Deep enough that a
            // whole bracket can sit waiting for its CaptureResults to catch up — acquiring
            // beyond maxImages throws, and the images lead the results.
            mRawReader = ImageReader.newInstance(
                    raw.getWidth(), raw.getHeight(), ImageFormat.RAW_SENSOR, MAX_BURST);
            mRawReader.setOnImageAvailableListener(this::onRaw, mHandler);
            Log.d(TAG, "RAW stills at " + raw);
        }
    }

    private boolean hasCapability(int capability) {
        int[] caps = mCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) {
            return false;
        }
        for (int c : caps) {
            if (c == capability) {
                return true;
            }
        }
        return false;
    }

    private static Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /** The physical-lens readers and pair keeping that share this session. */
    public StereoCapture stereo() {
        return mStereo;
    }

    /** Surfaces that must be included when the capture session is created. */
    public List<Surface> getSurfaces(boolean includeRaw) {
        List<Surface> out = new ArrayList<>();
        if (mJpegReader != null) {
            out.add(mJpegReader.getSurface());
        }
        if (includeRaw && mRawReader != null) {
            out.add(mRawReader.getSurface());
        }
        return out;
    }

    public boolean rawSupported() {
        return mRawSupported;
    }

    /**
     * JPEG quality, 1..100, or 0 to leave the device default in place.
     *
     * Worth setting explicitly: the default was never chosen for this use, and a JPEG
     * for a solve is judged by whether it preserves local gradient structure for feature
     * matching, not by whether it looks clean at 100%. The quality/size curve is
     * strongly concave, so the top few points cost a great deal of storage for detail
     * that no matcher reads.
     */
    public void setJpegQuality(int quality) {
        mJpegQuality = (quality >= 1 && quality <= 100) ? quality : 0;
    }

    public int getJpegQuality() {
        return mJpegQuality;
    }

    /** Attach trigger provenance to the next burst. */
    public void setTriggerContext(CaptureMode mode, float predictedBlurPx,
                                  float omegaRadPerS, boolean forced) {
        mCaptureMode = mode;
        mPredictedBlurPx = predictedBlurPx;
        mOmegaAtTrigger = omegaRadPerS;
        mTriggerForced = forced;
    }

    public void release() {
        mStereo.deactivate();
        mIo.shutdown();
        try {
            // A burst in flight is tens of MB; losing it to a fast teardown would be
            // silent data loss. Ten seconds, not five: an OBJECT composite now queues six
            // 25 MB DNGs (the bracket's five and the single) on this thread.
            if (!mIo.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "still writes did not finish before release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (mJpegReader != null) {
            mJpegReader.close();
            mJpegReader = null;
        }
        if (mRawReader != null) {
            mRawReader.close();
            mRawReader = null;
        }
        mStereo.close();
    }

    /**
     * Fire a burst.
     *
     * @param stops      exposure bracket half-range in EV (bracket spans -stops..+stops)
     *                   or, for a focus stack, ignored.
     * @param shots      number of frames; 1 collapses to a single capture.
     * @param writeRaw   include the RAW stream (DNG alongside each JPEG).
     */
    public void capture(CameraDevice device, CameraCaptureSession session,
                        CaptureRequest.Builder baseRequest, TotalCaptureResult lastResult,
                        Mode mode, int shots, float stops, boolean writeRaw,
                        File outputDir, RecordingWriter writer) {
        if (session == null || mJpegReader == null) {
            Log.w(TAG, "capture requested with no session or no JPEG reader");
            return;
        }
        if (mode == Mode.FOCUS_STACK && shots > 1) {
            throw new IllegalArgumentException(
                    "a focus stack cannot be a burst; use Camera2Proxy.captureFocusStack");
        }
        mOutputDir = outputDir;
        mRecordingWriter = writer;
        mMode = mode;
        mBurstSize = Math.max(1, Math.min(MAX_BURST, shots));
        if (shots > MAX_BURST) {
            Log.w(TAG, "burst clamped to " + MAX_BURST + " (reader depth); asked for " + shots);
        }
        mRawWritten = 0;
        mBurstId = SystemClock.elapsedRealtimeNanos();
        mPendingJpeg.clear();
        mPendingRaw.clear();
        mEvOffsets.clear();
        mRequestedFocus.clear();
        mShotCounter = 0;
        mWriteRawThisBurst = writeRaw && mRawReader != null;

        List<CaptureRequest> requests = new ArrayList<>();
        for (int i = 0; i < mBurstSize; i++) {
            CaptureRequest.Builder b;
            try {
                b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            } catch (CameraAccessException e) {
                Log.e(TAG, "createCaptureRequest failed: " + e);
                return;
            }
            copyBase(baseRequest, b);
            if (mJpegQuality > 0) {
                b.set(CaptureRequest.JPEG_QUALITY, (byte) mJpegQuality);
            }
            // No thumbnail: nothing downstream reads it, and it is encoded per frame.
            b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new Size(0, 0));
            b.addTarget(mJpegReader.getSurface());
            if (writeRaw && mRawReader != null) {
                b.addTarget(mRawReader.getSurface());
            }

            float ev = 0f;
            if (mMode == Mode.EXPOSURE_BRACKET && mBurstSize > 1) {
                ev = -stops + 2f * stops * i / (mBurstSize - 1);
                applyExposureOffset(b, lastResult, ev);
            }
            mEvOffsets.add(ev);
            mPendingJpeg.add(new PendingShot(i, ev));
            if (writeRaw && mRawReader != null) {
                mPendingRaw.add(new PendingShot(i, ev));
            }
            requests.add(b.build());
        }

        try {
            session.captureBurst(requests, mCaptureCallback, mHandler);
            Log.i(TAG, "burst requested: " + mMode + " x" + mBurstSize
                    + (writeRaw && mRawReader != null ? " +RAW" : ""));
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "captureBurst failed: " + e);
        }
    }

    /** Carry the user's chosen camera settings across to the still request. */
    private void copyBase(CaptureRequest.Builder from, CaptureRequest.Builder to) {
        copyBase(from, to, true);
    }

    /**
     * @param includeCrop carry SCALER_CROP_REGION across. TRUE for ordinary stills, so they
     *                    frame like the preview the operator aimed. FALSE for requests that
     *                    target PHYSICAL camera streams.
     *
     * SCALER_CROP_REGION is expressed in the LOGICAL camera's coordinate system. Handing a
     * logical crop to a request whose outputs are bound to physical sensors asks the HAL to
     * map one sensor's rectangle onto another's array, and the mapping it chooses is not
     * specified. The first stereo pair came back with the ultrawide framed exactly like the
     * main camera — a 1.64x crop, measured — which is what that mapping would produce.
     * Whether the crop was the cause is now recorded per shot rather than assumed, but
     * either way a physical-stream request has no business carrying a logical rectangle.
     */
    private void copyBase(CaptureRequest.Builder from, CaptureRequest.Builder to,
                          boolean includeCrop) {
        CaptureRequest.Key<?>[] keys = {
                CaptureRequest.CONTROL_MODE,
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AE_LOCK,
                CaptureRequest.CONTROL_AWB_LOCK,
                CaptureRequest.LENS_FOCUS_DISTANCE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                CaptureRequest.SENSOR_SENSITIVITY,
                // Carried across so a torch lit for the preview stays lit for the shot.
                CaptureRequest.FLASH_MODE,
        };
        for (CaptureRequest.Key key : keys) {
            Object v = from.get(key);
            if (v != null) {
                to.set(key, v);
            }
        }
        if (includeCrop) {
            Rect crop = from.get(CaptureRequest.SCALER_CROP_REGION);
            if (crop != null) {
                to.set(CaptureRequest.SCALER_CROP_REGION, crop);
            }
        }
    }

    /**
     * Shift exposure by evOffset stops from whatever the metered result was, preferring to
     * move shutter and only using ISO once shutter hits its limit — noise is worse than a
     * slightly different motion signature on a tripod.
     */
    private void applyExposureOffset(CaptureRequest.Builder b, TotalCaptureResult base,
                                     float evOffset) {
        Long baseExp = base != null ? base.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME) : null;
        Integer baseIso = base != null ? base.get(TotalCaptureResult.SENSOR_SENSITIVITY) : null;
        if (baseExp == null || baseIso == null) {
            Log.w(TAG, "no metered exposure available; bracketing via AE compensation");
            Range<Integer> evRange =
                    mCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational step =
                    mCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (evRange != null && step != null && step.floatValue() != 0f) {
                int units = Math.round(evOffset / step.floatValue());
                units = Math.max(evRange.getLower(), Math.min(evRange.getUpper(), units));
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, units);
            }
            return;
        }

        Range<Long> expRange =
                mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> isoRange =
                mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

        double factor = Math.pow(2.0, evOffset);
        long exp = Math.round(baseExp * factor);
        int iso = baseIso;

        if (expRange != null) {
            long clamped = Math.max(expRange.getLower(), Math.min(expRange.getUpper(), exp));
            if (clamped != exp && isoRange != null) {
                // Shutter ran out of range: put the remainder into ISO.
                double residual = (double) exp / clamped;
                iso = (int) Math.round(baseIso * residual);
                iso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));
            }
            exp = clamped;
        }

        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
    }

    /**
     * Bracket focus AROUND where autofocus put it, stepping by the depth of field.
     *
     * The first version swept the lens's whole travel, infinity to its 10 cm minimum.
     * That is wrong twice over. It spends almost every frame in the macro end — a
     * subject at half a metre got one useful frame out of five and the rest looked
     * identical — and the opening excursion is so large the voice coil cannot settle
     * within a burst, so frame 0 came back at the previous focus rather than the
     * requested one.
     *
     * The step is derived, not chosen. Depth of field has a CONSTANT width in dioptre
     * space, independent of distance:
     *
     *     DOF_dioptres = 2 * N * c / f^2
     *
     * with N the f-number, c the circle of confusion and f the focal length. On this
     * camera (f/1.7, 6.3 mm, 2.40 um pixels) that is 0.206 dioptres for a one-pixel
     * blur circle — which matches the geometric DOF at every distance: 5.1 cm at 0.5 m,
     * 20.6 cm at 1 m, 2.04 m at 3 m. So stepping by slightly less than one DOF width
     * gives adjacent slices that overlap, which is exactly what a stack merge needs,
     * and it self-adjusts to whichever lens is in use.
     *
     * WHY THIS RETURNS A PLAN INSTEAD OF SETTING A REQUEST. The first build handed five
     * focus distances to `captureBurst` and got five identical pictures: every frame came
     * back reporting 0.100 dioptres, and the global sharpness across the stack spanned
     * 1.0048x. The frames landed 33.3 ms apart — one sensor period — because that is what
     * captureBurst is for. A voice coil cannot slew and settle in one frame time, and with
     * a pipeline three to five deep the per-request CONTROL_AF_MODE_OFF never reached the
     * lens before the next readout.
     *
     * The proof sits in the same recording: the exposure bracket went out through the SAME
     * captureBurst call and tracked an exact 2.0000x ladder, because SENSOR_EXPOSURE_TIME
     * is a register write with nothing to move. Electronic parameter, fine. Mechanical
     * parameter, not fine. So focus is now driven one step at a time by
     * Camera2Proxy.captureFocusStack, which parks the lens with a repeating request and
     * waits for it to arrive before opening the shutter.
     */
    public float[] planFocusStack(TotalCaptureResult base, int shots) {
        int n = Math.max(1, Math.min(MAX_BURST, shots));
        Float minDist =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Float centre = base != null ? base.get(TotalCaptureResult.LENS_FOCUS_DISTANCE) : null;
        if (minDist == null || minDist == 0f || centre == null) {
            Log.w(TAG, "fixed-focus lens or no metered focus; focus stack collapses to 1");
            return new float[]{centre != null ? centre : 0f};
        }
        float step = dofDioptres() * 0.8f;   // 20% overlap between adjacent slices
        float span = step * (n - 1);
        // SHIFT the bracket to fit the lens's range rather than clamping into it.
        float start = Math.max(0f, Math.min(minDist - span, centre - span / 2f));
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.max(0f, Math.min(minDist, start + step * i));
        }
        Log.i(TAG, "focus plan around " + centre + " D, step " + step
                + " D: " + Arrays.toString(out));
        return out;
    }

    /** Bookkeeping for a sequenced focus stack; one call before the first shot. */
    public void beginFocusStack(int shots, boolean writeRaw, File outputDir,
                                RecordingWriter writer) {
        mOutputDir = outputDir;
        mRecordingWriter = writer;
        mMode = Mode.FOCUS_STACK;
        mBurstSize = Math.max(1, Math.min(MAX_BURST, shots));
        mRawWritten = 0;
        mBurstId = SystemClock.elapsedRealtimeNanos();
        mPendingJpeg.clear();
        mPendingRaw.clear();
        mEvOffsets.clear();
        mRequestedFocus.clear();
        mFocusSettleNs.clear();
        mFocusSettled.clear();
        mShotCounter = 0;
        mWriteRawThisBurst = writeRaw && mRawReader != null;
    }

    /**
     * One frame of a sequenced focus stack, at a lens position the caller has already
     * driven the lens to and waited on.
     *
     * @param settleNs how long the wait took, recorded per shot
     * @param settled  whether the lens reported arriving, or the wait timed out on it
     */
    public void captureFocusShot(CameraDevice device, CameraCaptureSession session,
                                 CaptureRequest.Builder baseRequest, int index,
                                 float dioptres, long settleNs, boolean settled) {
        if (session == null || mJpegReader == null) {
            return;
        }
        try {
            CaptureRequest.Builder b =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            copyBase(baseRequest, b);
            if (mJpegQuality > 0) {
                b.set(CaptureRequest.JPEG_QUALITY, (byte) mJpegQuality);
            }
            b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new Size(0, 0));
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, dioptres);
            b.addTarget(mJpegReader.getSurface());
            if (mWriteRawThisBurst) {
                b.addTarget(mRawReader.getSurface());
            }
            mRequestedFocus.put(index, dioptres);
            mFocusSettleNs.put(index, settleNs);
            mFocusSettled.put(index, settled);
            mEvOffsets.add(0f);
            mPendingJpeg.add(new PendingShot(index, 0f));
            if (mWriteRawThisBurst) {
                mPendingRaw.add(new PendingShot(index, 0f));
            }
            session.capture(b.build(), mCaptureCallback, mHandler);
            Log.i(TAG, String.format(Locale.US,
                    "focus shot %d at %.3f D (%s after %.0f ms)",
                    index, dioptres, settled ? "settled" : "TIMED OUT", settleNs / 1e6));
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "focus shot failed: " + e);
        }
    }

    /** Width of one depth-of-field slice, in dioptres, for a one-pixel blur circle. */
    private float dofDioptres() {
        float[] apertures =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        float[] focals =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF physical =
                mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Rect active = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (apertures == null || apertures.length == 0 || focals == null || focals.length == 0
                || physical == null || active == null || active.width() == 0) {
            return 0.2f;   // the measured value for this camera, as a safe default
        }
        float n = apertures[0];
        float f = focals[0];                                   // mm
        float c = physical.getWidth() / active.width();        // mm, one pixel
        return 2f * n * c / (f * f) * 1000f;                   // per metre = dioptres
    }

    private final HashMap<Integer, Float> mRequestedFocus = new HashMap<>();
    // How long the lens took to reach each step, and whether it got there at all. Recorded
    // per shot so a bracket that silently collapses shows up in the metadata rather than
    // only under a sharpness measure after the fact.
    private final HashMap<Integer, Long> mFocusSettleNs = new HashMap<>();
    private final HashMap<Integer, Boolean> mFocusSettled = new HashMap<>();

    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    // The metadata row first: its index names this frame's files, and the
                    // DNG must carry the SAME name as the row that describes its exposure.
                    int index = writeStillMeta(result);
                    if (mRawReader != null) {
                        Long ts = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
                        if (ts != null) {
                            synchronized (mRawLock) {
                                mRawResults.put(ts, result);
                                mRawNames.put(ts, String.format(Locale.US,
                                        "still_%d_%02d.dng", mBurstId, index));
                            }
                            drainRawPairs();
                        }
                    }
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                    Log.e(TAG, "still capture failed, reason " + failure.getReason());
                    mPendingJpeg.poll();
                    mPendingRaw.poll();
                }
            };

    /** Queue the frame's metadata row; returns its index within the burst, which names its files. */
    private int writeStillMeta(TotalCaptureResult result) {
        int index = mShotCounter++;
        if (mRecordingWriter == null) {
            return index;
        }
        String stem = String.format(Locale.US, "still_%d_%02d", mBurstId, index);

        RecordingProtos.StillMetaData.Builder b = RecordingProtos.StillMetaData.newBuilder()
                .setBurstId(mBurstId)
                .setBurstIndex(index)
                .setBurstSize(mBurstSize)
                .setKindValue(mMode.ordinal())
                .setCaptureMode(mCaptureMode.ordinal())
                .setPredictedBlurPx(mPredictedBlurPx)
                .setOmegaAtTrigger(mOmegaAtTrigger)
                .setTriggerForced(mTriggerForced)
                .setJpegFile(stem + ".jpg");
        // Only claim a DNG when one was actually requested for THIS burst. Keying off
        // "the reader exists" made every WALK frame advertise a sidecar that was never
        // written — seven claimed, two on disk.
        if (mWriteRawThisBurst) {
            b.setDngFile(stem + ".dng");
        }

        Long ts = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
        if (ts != null) {
            b.setTimeNs(ts);
        }
        Long exp = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME);
        if (exp != null) {
            b.setExposureTimeNs(exp);
        }
        Integer iso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY);
        if (iso != null) {
            b.setIso(iso);
        }
        Float fd = result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE);
        if (fd != null) {
            b.setFocusDistanceDiopters(fd);
        }
        Float fl = result.get(TotalCaptureResult.LENS_FOCAL_LENGTH);
        if (fl != null) {
            b.setFocalLengthMm(fl);
        }
        StillRows.recordIntrinsics(b, result);
        Long dur = result.get(TotalCaptureResult.SENSOR_FRAME_DURATION);
        if (dur != null) {
            b.setFrameDurationNs(dur);
        }
        Long skew = result.get(TotalCaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        if (skew != null) {
            b.setFrameReadoutNs(skew);
        }
        // Read back from the RESULT rather than from what was requested: this records
        // what the frame was actually lit by, which is the thing downstream needs.
        Integer flash = result.get(TotalCaptureResult.FLASH_MODE);
        b.setTorchOn(flash != null
                && flash == CameraMetadata.FLASH_MODE_TORCH);
        // torch_strength stays 0: CaptureResult.FLASH_STRENGTH_LEVEL is API 35 and this
        // builds against 34. The proto field is reserved for when compileSdk moves.
        // Indexed, not peeked off the pending queue: the JPEG writer drains that queue on
        // its own thread, so peeking here returned whichever shot happened to be at the
        // head and mislabelled the bracket (-1,-1,+1,+1,+2 for a -2..+2 sweep).
        if (index < mEvOffsets.size()) {
            b.setEvOffset(mEvOffsets.get(index));
        }
        Float requested = mRequestedFocus.get(index);
        if (requested != null) {
            b.setRequestedFocusDiopters(requested);
        }
        StillRows.recordCrop(b, result, result);
        // How long the lens took to arrive at this frame's target, and whether it got
        // there before the shutter opened. Zero for anything that did not drive focus.
        Long settle = mFocusSettleNs.get(index);
        if (settle != null) {
            b.setFocusSettleNs(settle);
            Boolean ok = mFocusSettled.get(index);
            b.setFocusSettled(ok != null && ok);
        }

        StillRows.addOrientation(b, mImuManager);
        mRecordingWriter.queueData(b.build());
        return index;
    }

    private void onJpeg(ImageReader reader) {
        // Copy out and release the buffer immediately, then write on the IO thread.
        // The reader callback runs on the camera background handler, which also
        // services capture results — a 7 MB synchronous write per frame there puts
        // filesystem latency directly in the path of the next frame's metadata.
        final byte[] bytes;
        final int index;
        try (Image image = reader.acquireNextImage()) {
            if (image == null) {
                return;
            }
            PendingShot shot = mPendingJpeg.poll();
            index = shot != null ? shot.index : 0;
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        } catch (IllegalStateException e) {
            Log.e(TAG, "JPEG acquire failed: " + e);
            return;
        }
        final File out = new File(mOutputDir,
                String.format(Locale.US, "still_%d_%02d.jpg", mBurstId, index));
        mIo.execute(() -> {
            try (FileOutputStream s = new FileOutputStream(out)) {
                s.write(bytes);
                Log.d(TAG, "wrote " + out.getName() + " (" + bytes.length / 1024 + " kB)");
            } catch (IOException e) {
                Log.e(TAG, "JPEG write failed: " + e);
            }
        });
    }

    private void onRaw(ImageReader reader) {
        Image image = reader.acquireNextImage();
        if (image == null) {
            return;
        }
        synchronized (mRawLock) {
            mRawImages.put(image.getTimestamp(), image);
        }
        drainRawPairs();
    }

    /**
     * Write every RAW image whose CaptureResult has also arrived.
     *
     * The two callbacks race — measured on the SM-S928U, the first three images of a
     * five-shot burst landed BEFORE any result — so neither stream may assume it leads.
     * Pairing is by SENSOR_TIMESTAMP, which Image.getTimestamp() reports identically.
     *
     * The NAME is fixed here, from the metadata row of the same timestamp, and the write
     * goes to the IO thread. Both matter once a whole exposure bracket carries RAW (OBJECT
     * mode since 0.24): this used to write each 25 MB DNG synchronously on the camera
     * handler and name it from mBurstId and a running counter AT WRITE TIME, so five DNGs
     * held the handler for seconds, and the next stage's captureStills -- which resets both
     * -- could land mid-bracket and file the bracket's last frames under the wrong burst
     * with the wrong index, i.e. against another frame's exposure.
     */
    private void drainRawPairs() {
        while (true) {
            final Image image;
            final TotalCaptureResult result;
            String name;
            synchronized (mRawLock) {
                Long match = null;
                for (Long key : mRawImages.keySet()) {
                    if (mRawResults.containsKey(key)) {
                        match = key;
                        break;
                    }
                }
                if (match == null) {
                    return;
                }
                image = mRawImages.remove(match);
                result = mRawResults.remove(match);
                name = mRawNames.remove(match);
            }
            if (name == null) {
                name = String.format(Locale.US, "still_%d_%02d.dng", mBurstId, mRawWritten);
            }
            mRawWritten++;
            final File out = new File(mOutputDir, name);
            mIo.execute(() -> {
                try (DngCreator dng = new DngCreator(mCharacteristics, result);
                     FileOutputStream s = new FileOutputStream(out)) {
                    dng.writeImage(s, image);
                    Log.d(TAG, "wrote " + out.getName());
                } catch (IOException | IllegalStateException e) {
                    Log.e(TAG, "DNG write failed: " + e);
                } finally {
                    image.close();
                }
            });
        }
    }

    /** Single thread, so writes stay ordered and never contend with each other. */
    private final ExecutorService mIo =
            Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "StillWriter"));

    private final Object mRawLock = new Object();
    private final LinkedHashMap<Long, Image> mRawImages = new LinkedHashMap<>();
    private final LinkedHashMap<Long, TotalCaptureResult> mRawResults =
            new LinkedHashMap<>();
    // timestamp -> the DNG's file name, from the metadata row written for that frame
    private final HashMap<Long, String> mRawNames = new HashMap<>();
    private int mRawWritten;
}
