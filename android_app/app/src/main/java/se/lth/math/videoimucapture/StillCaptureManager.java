package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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

    /**
     * The one pair on this device with a published baseline: ultrawide (physical 2) sits
     * LENS_POSE_TRANSLATION = 18.02 mm from the main camera (physical 5), and both carry
     * factory intrinsics. A SIMULTANEOUS pair across a known baseline is metric scale
     * from a single capture — the quantity a monocular walk cannot produce without
     * external control, and the reason this stage exists at all.
     */
    public static final String PHYS_ULTRAWIDE = "2";
    public static final String PHYS_MAIN = "5";

    /**
     * Physical streams are constrained: the probe found YUV at 1920x1080 configures
     * alongside preview, JPEG and RAW, while larger did not. At 1920 wide the main
     * camera's factory focal scales to ~1296 px, so an 18.02 mm baseline gives 47 px of
     * disparity at 0.5 m and 23 px at 1 m — ample across OBJECT mode's working range.
     */
    private static final Size STEREO_SIZE = new Size(1920, 1080);

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

    private ImageReader mJpegReader;
    private ImageReader mRawReader;
    private boolean mRawSupported;
    private ImageReader mStereoUwReader;
    private ImageReader mStereoMainReader;
    private boolean mStereoSupported;
    private volatile long mStereoBurstId;

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

    public StillCaptureManager(CameraCharacteristics characteristics, Handler handler,
                               IMUManager imuManager) {
        mCharacteristics = characteristics;
        mHandler = handler;
        mImuManager = imuManager;
        setupReaders();
    }

    private void setupReaders() {
        android.hardware.camera2.params.StreamConfigurationMap map =
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
        setupStereoReaders();
    }

    /**
     * Build the two physical-camera readers, if this is a logical multi-camera that
     * offers both lenses. Silently absent otherwise — the stereo stage then skips.
     */
    private void setupStereoReaders() {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        java.util.Set<String> physicals = mCharacteristics.getPhysicalCameraIds();
        if (!physicals.contains(PHYS_ULTRAWIDE) || !physicals.contains(PHYS_MAIN)) {
            Log.i(TAG, "no ultrawide+main physical pair; stereo stage disabled");
            return;
        }
        mStereoUwReader = ImageReader.newInstance(STEREO_SIZE.getWidth(),
                STEREO_SIZE.getHeight(), ImageFormat.YUV_420_888, 2);
        mStereoUwReader.setOnImageAvailableListener(
                r -> onStereoImage(r, PHYS_ULTRAWIDE, "uw"), mHandler);
        mStereoMainReader = ImageReader.newInstance(STEREO_SIZE.getWidth(),
                STEREO_SIZE.getHeight(), ImageFormat.YUV_420_888, 2);
        mStereoMainReader.setOnImageAvailableListener(
                r -> onStereoImage(r, PHYS_MAIN, "main"), mHandler);
        mStereoSupported = true;
        Log.d(TAG, "stereo pair ready at " + STEREO_SIZE);
    }

    public boolean stereoSupported() {
        return mStereoSupported;
    }

    /** Surfaces that must be bound to a physical id in the session configuration. */
    public java.util.Map<String, android.view.Surface> getStereoSurfaces() {
        java.util.LinkedHashMap<String, android.view.Surface> out = new java.util.LinkedHashMap<>();
        if (mStereoSupported) {
            out.put(PHYS_ULTRAWIDE, mStereoUwReader.getSurface());
            out.put(PHYS_MAIN, mStereoMainReader.getSurface());
        }
        return out;
    }

    /**
     * One frame from each lens, in a single request, so both shutters open together.
     * Simultaneity is the whole point: a pair taken sequentially across a moving
     * handheld camera has an unknown baseline, which is exactly what the factory
     * 18.02 mm was going to supply.
     */
    public void captureStereoPair(CameraDevice device, CameraCaptureSession session,
                                  CaptureRequest.Builder baseRequest, File outputDir,
                                  RecordingWriter writer) {
        if (!mStereoSupported || session == null) {
            Log.w(TAG, "stereo capture requested but unavailable");
            return;
        }
        mOutputDir = outputDir;
        mRecordingWriter = writer;
        mStereoBurstId = SystemClock.elapsedRealtimeNanos();
        // Arm exactly one frame per lens; every other warm-up frame is drained and
        // discarded.
        mStereoWantUw.set(true);
        mStereoWantMain.set(true);
        try {
            CaptureRequest.Builder b =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            copyBase(baseRequest, b);
            b.addTarget(mStereoUwReader.getSurface());
            b.addTarget(mStereoMainReader.getSurface());
            session.capture(b.build(), mStereoCallback, mHandler);
            Log.i(TAG, "stereo pair requested (physical " + PHYS_ULTRAWIDE
                    + " + " + PHYS_MAIN + ")");
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "stereo capture failed: " + e);
        }
    }

    private final CameraCaptureSession.CaptureCallback mStereoCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    // Both metadata rows are written HERE, not in the image handlers.
                    // The images arrive first — measured: the pair landed with
                    // exposure 0, iso 0 and timestamp 0 because the handlers ran before
                    // this callback, the same race that broke DNG writing. The
                    // filenames are deterministic from the burst id, so nothing has to
                    // wait for the pixels.
                    writeStereoMeta(result, PHYS_ULTRAWIDE, "uw", 0);
                    writeStereoMeta(result, PHYS_MAIN, "main", 1);
                }
            };

    private volatile long mStereoResultTimeNs;
    private volatile long mStereoExposureNs;
    private volatile int mStereoIso;
    private final java.util.concurrent.atomic.AtomicBoolean mStereoWantUw =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean mStereoWantMain =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private void onStereoImage(ImageReader reader, String physicalId, String tag) {
        final byte[] jpeg;
        try (Image image = reader.acquireNextImage()) {
            if (image == null) {
                return;
            }
            // The warm-up runs a REPEATING request so the second sensor spins up, which
            // means frames stream in continuously before and after the shot we want.
            // They still have to be acquired and released or the reader stalls — but
            // only the armed frame is kept. Without this every warm-up frame overwrote
            // the output, which is what the first run did: a dozen writes to two names.
            boolean armed = PHYS_ULTRAWIDE.equals(physicalId)
                    ? mStereoWantUw.compareAndSet(true, false)
                    : mStereoWantMain.compareAndSet(true, false);
            if (!armed) {
                return;
            }
            jpeg = yuvToJpeg(image);
        } catch (IllegalStateException e) {
            Log.e(TAG, "stereo acquire failed: " + e);
            return;
        }
        if (jpeg == null) {
            return;
        }
        final String name = String.format(java.util.Locale.US, "stereo_%d_%s.jpg",
                mStereoBurstId, tag);
        final File out = new File(mOutputDir, name);
        mIo.execute(() -> {
            try (FileOutputStream s = new FileOutputStream(out)) {
                s.write(jpeg);
                Log.d(TAG, "wrote " + name + " (" + jpeg.length / 1024 + " kB)");
            } catch (IOException e) {
                Log.e(TAG, "stereo write failed: " + e);
            }
        });

    }

    private void writeStereoMeta(TotalCaptureResult result, String physicalId,
                                 String tag, int index) {
        if (mRecordingWriter == null) {
            return;
        }
        RecordingProtos.StillMetaData.Builder b =
                RecordingProtos.StillMetaData.newBuilder()
                        .setBurstId(mStereoBurstId)
                        .setBurstSize(2)
                        .setBurstIndex(index)
                        .setKindValue(Mode.SINGLE.ordinal())
                        .setCaptureMode(CaptureMode.OBJECT.ordinal())
                        .setPhysicalCameraId(physicalId)
                        .setJpegFile(String.format(java.util.Locale.US,
                                "stereo_%d_%s.jpg", mStereoBurstId, tag));

        // Prefer this lens's OWN physical result where the device supplies one: the two
        // sensors can be exposed independently, so the logical result's exposure is not
        // necessarily either lens's.
        CaptureResult per = result;
        if (Build.VERSION.SDK_INT >= 28) {
            java.util.Map<String, CaptureResult> physResults =
                    result.getPhysicalCameraResults();
            CaptureResult pr = physResults.get(physicalId);
            if (pr != null) {
                per = pr;
            }
        }
        Long ts = per.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts != null) {
            b.setTimeNs(ts);
        }
        Long exp = per.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (exp != null) {
            b.setExposureTimeNs(exp);
        }
        Integer iso = per.get(CaptureResult.SENSOR_SENSITIVITY);
        if (iso != null) {
            b.setIso(iso);
        }
        Float fl = per.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (fl != null) {
            b.setFocalLengthMm(fl);
        }
        Float fd = per.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (fd != null) {
            b.setFocusDistanceDiopters(fd);
        }
        Integer flash = result.get(TotalCaptureResult.FLASH_MODE);
        b.setTorchOn(flash != null
                && flash == android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH);

        if (mImuManager != null) {
            float[] q = mImuManager.getLatestOrientation();
            if (q != null) {
                for (float v : q) {
                    b.addOrientationQuaternion(v);
                }
                b.setOrientationTimeNs(mImuManager.getLatestOrientationTimeNs());
            }
        }
        mRecordingWriter.queueData(b.build());
    }

    /**
     * YUV_420_888 -> NV21 -> JPEG.
     *
     * The plane layout is not fixed by the format: chroma may arrive planar
     * (pixelStride 1) or already semi-planar (pixelStride 2), and every plane carries a
     * rowStride that need not equal the width. Assuming either would produce a picture
     * that looks almost right, which is the worst kind of wrong.
     */
    private static byte[] yuvToJpeg(Image image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane[] p = image.getPlanes();
            byte[] nv21 = new byte[w * h * 3 / 2];

            ByteBuffer y = p[0].getBuffer();
            int yRow = p[0].getRowStride();
            int yPix = p[0].getPixelStride();
            int o = 0;
            if (yRow == w && yPix == 1) {
                y.get(nv21, 0, w * h);
                o = w * h;
            } else {
                byte[] row = new byte[yRow];
                for (int r = 0; r < h; r++) {
                    y.position(r * yRow);
                    int n = Math.min(yRow, y.remaining());
                    y.get(row, 0, n);
                    for (int c = 0; c < w; c++) {
                        nv21[o++] = row[c * yPix];
                    }
                }
            }

            // NV21 chroma is interleaved V then U, at half resolution.
            ByteBuffer u = p[1].getBuffer();
            ByteBuffer v = p[2].getBuffer();
            int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
            int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
            for (int r = 0; r < h / 2; r++) {
                for (int c = 0; c < w / 2; c++) {
                    int vi = r * vRow + c * vPix;
                    int ui = r * uRow + c * uPix;
                    nv21[o++] = vi < v.limit() ? v.get(vi) : 0;
                    nv21[o++] = ui < u.limit() ? u.get(ui) : 0;
                }
            }

            android.graphics.YuvImage yuv =
                    new android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 95, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "YUV->JPEG failed: " + e);
            return null;
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

    /** Surfaces that must be included when the capture session is created. */
    public List<android.view.Surface> getSurfaces(boolean includeRaw) {
        List<android.view.Surface> out = new ArrayList<>();
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
        mIo.shutdown();
        try {
            // A burst in flight is tens of MB; losing it to a fast teardown would be
            // silent data loss.
            if (!mIo.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
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
        if (mStereoUwReader != null) {
            mStereoUwReader.close();
            mStereoUwReader = null;
        }
        if (mStereoMainReader != null) {
            mStereoMainReader.close();
            mStereoMainReader = null;
        }
        mStereoSupported = false;
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
            b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new android.util.Size(0, 0));
            b.addTarget(mJpegReader.getSurface());
            if (writeRaw && mRawReader != null) {
                b.addTarget(mRawReader.getSurface());
            }

            float ev = 0f;
            if (mMode == Mode.EXPOSURE_BRACKET && mBurstSize > 1) {
                ev = -stops + 2f * stops * i / (mBurstSize - 1);
                applyExposureOffset(b, lastResult, ev);
            } else if (mMode == Mode.FOCUS_STACK && mBurstSize > 1) {
                applyFocusStep(b, i, lastResult);
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
                CaptureRequest.SCALER_CROP_REGION,
                // Carried across so a torch lit for the preview stays lit for the shot.
                CaptureRequest.FLASH_MODE,
        };
        for (CaptureRequest.Key key : keys) {
            Object v = from.get(key);
            if (v != null) {
                to.set(key, v);
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
            android.util.Rational step =
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
     */
    private void applyFocusStep(CaptureRequest.Builder b, int index,
                                TotalCaptureResult base) {
        Float minDist =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minDist == null || minDist == 0f) {
            return; // fixed-focus lens
        }
        Float centre = base != null ? base.get(TotalCaptureResult.LENS_FOCUS_DISTANCE) : null;
        if (centre == null) {
            Log.w(TAG, "no metered focus distance; skipping focus bracket");
            return;
        }
        float step = dofDioptres() * 0.8f;   // 20% overlap between adjacent slices
        float span = step * (mBurstSize - 1);
        // SHIFT the bracket to fit the lens's range rather than clamping into it.
        // Clamping wastes frames on duplicates: with AF locked at 10 m, two of five
        // requests fell past infinity, were pinned to the same value and returned the
        // same picture. Shifting keeps every frame a distinct slice.
        float start = centre - span / 2f;
        start = Math.max(0f, Math.min(minDist - span, start));
        float d = Math.max(0f, Math.min(minDist, start + step * index));
        mRequestedFocus.put(index, d);
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d);
    }

    /** Width of one depth-of-field slice, in dioptres, for a one-pixel blur circle. */
    private float dofDioptres() {
        float[] apertures =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        float[] focals =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        android.util.SizeF physical =
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

    private final java.util.HashMap<Integer, Float> mRequestedFocus = new java.util.HashMap<>();

    private final CameraCaptureSession.CaptureCallback mCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    if (mRawReader != null) {
                        Long ts = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);
                        if (ts != null) {
                            synchronized (mRawLock) {
                                mRawResults.put(ts, result);
                            }
                            drainRawPairs();
                        }
                    }
                    writeStillMeta(result);
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

    private void writeStillMeta(TotalCaptureResult result) {
        if (mRecordingWriter == null) {
            return;
        }
        int index = mShotCounter++;
        String stem = String.format(java.util.Locale.US, "still_%d_%02d", mBurstId, index);

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
                && flash == android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH);
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

        if (mImuManager != null) {
            float[] q = mImuManager.getLatestOrientation();
            if (q != null) {
                for (float v : q) {
                    b.addOrientationQuaternion(v);
                }
                b.setOrientationTimeNs(mImuManager.getLatestOrientationTimeNs());
            }
        }
        mRecordingWriter.queueData(b.build());
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
                String.format(java.util.Locale.US, "still_%d_%02d.jpg", mBurstId, index));
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
     */
    private void drainRawPairs() {
        while (true) {
            Image image;
            TotalCaptureResult result;
            long ts;
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
                ts = match;
                image = mRawImages.remove(ts);
                result = mRawResults.remove(ts);
            }
            int index = mRawWritten++;
            File out = new File(mOutputDir,
                    String.format(java.util.Locale.US, "still_%d_%02d.dng", mBurstId, index));
            try (DngCreator dng = new DngCreator(mCharacteristics, result);
                 FileOutputStream s = new FileOutputStream(out)) {
                dng.writeImage(s, image);
                Log.d(TAG, "wrote " + out.getName());
            } catch (IOException | IllegalStateException e) {
                Log.e(TAG, "DNG write failed: " + e);
            } finally {
                image.close();
            }
        }
    }

    /** Single thread, so writes stay ordered and never contend with each other. */
    private final java.util.concurrent.ExecutorService mIo =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "StillWriter"));

    private final Object mRawLock = new Object();
    private final java.util.LinkedHashMap<Long, Image> mRawImages = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<Long, TotalCaptureResult> mRawResults =
            new java.util.LinkedHashMap<>();
    private int mRawWritten;
}
