package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
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

    /** Attach trigger provenance to the next burst. */
    public void setTriggerContext(CaptureMode mode, float predictedBlurPx,
                                  float omegaRadPerS, boolean forced) {
        mCaptureMode = mode;
        mPredictedBlurPx = predictedBlurPx;
        mOmegaAtTrigger = omegaRadPerS;
        mTriggerForced = forced;
    }

    public void release() {
        if (mJpegReader != null) {
            mJpegReader.close();
            mJpegReader = null;
        }
        if (mRawReader != null) {
            mRawReader.close();
            mRawReader = null;
        }
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
            b.addTarget(mJpegReader.getSurface());
            if (writeRaw && mRawReader != null) {
                b.addTarget(mRawReader.getSurface());
            }

            float ev = 0f;
            if (mMode == Mode.EXPOSURE_BRACKET && mBurstSize > 1) {
                ev = -stops + 2f * stops * i / (mBurstSize - 1);
                applyExposureOffset(b, lastResult, ev);
            } else if (mMode == Mode.FOCUS_STACK && mBurstSize > 1) {
                applyFocusStep(b, i);
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

    /** Step focus linearly in DIOPTRES across the usable range — that is the linear axis. */
    private void applyFocusStep(CaptureRequest.Builder b, int index) {
        Float minDist =
                mCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minDist == null || minDist == 0f) {
            return; // fixed-focus lens
        }
        // Dioptres: 0 = infinity, minDist = closest. Uniform steps in dioptres give
        // roughly uniform depth-of-field overlap, which uniform metres would not.
        float d = minDist * index / Math.max(1, mBurstSize - 1);
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d);
    }

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
        // Indexed, not peeked off the pending queue: the JPEG writer drains that queue on
        // its own thread, so peeking here returned whichever shot happened to be at the
        // head and mislabelled the bracket (-1,-1,+1,+1,+2 for a -2..+2 sweep).
        if (index < mEvOffsets.size()) {
            b.setEvOffset(mEvOffsets.get(index));
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
        try (Image image = reader.acquireNextImage()) {
            if (image == null) {
                return;
            }
            PendingShot shot = mPendingJpeg.poll();
            int index = shot != null ? shot.index : 0;
            File out = new File(mOutputDir,
                    String.format(java.util.Locale.US, "still_%d_%02d.jpg", mBurstId, index));
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            try (FileOutputStream s = new FileOutputStream(out)) {
                s.write(bytes);
            }
            Log.d(TAG, "wrote " + out.getName() + " (" + bytes.length / 1024 + " kB)");
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "JPEG write failed: " + e);
        }
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

    private final Object mRawLock = new Object();
    private final java.util.LinkedHashMap<Long, Image> mRawImages = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<Long, TotalCaptureResult> mRawResults =
            new java.util.LinkedHashMap<>();
    private int mRawWritten;
}
