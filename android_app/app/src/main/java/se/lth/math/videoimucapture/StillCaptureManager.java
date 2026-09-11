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
    private static final int STEREO_READER_DEPTH = 4;

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
    // Needed to read the PHYSICAL sensors' own characteristics: a logical camera's
    // characteristics describe the logical camera, and the ultrawide's active array is not
    // in there.
    private final android.hardware.camera2.CameraManager mCameraManager;
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

    public StillCaptureManager(CameraCharacteristics characteristics,
                               android.hardware.camera2.CameraManager cameraManager,
                               Handler handler, IMUManager imuManager) {
        mCharacteristics = characteristics;
        mCameraManager = cameraManager;
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
        // Depth 4, not 2. In periodic mode (ReconStab #36) both readers receive every frame of
        // the recording and are drained on the camera handler; if that thread is held for two
        // frame periods -- a metadata queue stall, a burst of results -- a depth-2 reader fills,
        // and a full physical stream stalls the request pipeline it shares with the VIDEO.
        mStereoUwReader = ImageReader.newInstance(STEREO_SIZE.getWidth(),
                STEREO_SIZE.getHeight(), ImageFormat.YUV_420_888, STEREO_READER_DEPTH);
        mStereoUwReader.setOnImageAvailableListener(
                r -> onStereoImage(r, PHYS_ULTRAWIDE, "uw"), mHandler);
        mStereoMainReader = ImageReader.newInstance(STEREO_SIZE.getWidth(),
                STEREO_SIZE.getHeight(), ImageFormat.YUV_420_888, STEREO_READER_DEPTH);
        mStereoMainReader.setOnImageAvailableListener(
                r -> onStereoImage(r, PHYS_MAIN, "main"), mHandler);
        mStereoSupported = true;
        Integer sync = Build.VERSION.SDK_INT >= 28
                ? mCharacteristics.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)
                : null;
        Log.d(TAG, "stereo pair ready at " + STEREO_SIZE + "; sensor sync type "
                + sync + " (0 approximate, 1 calibrated)");
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

    // ------------------------------------------------------- periodic pairs inside a video
    //
    // ReconStab #36: a two-lens pair dropped into the walk video at a fixed interval, so the
    // solve carries the factory 18.019 mm ruler every metre of walk instead of only at the
    // OBJECT stations the operator stops for.
    //
    // HOW, AND WHY NOT A ONE-SHOT REQUEST. The obvious build fires a TEMPLATE_VIDEO_SNAPSHOT
    // at both physical streams once a second. Two things are wrong with it on this device.
    // First, the idle sensor has to be warmed up before it will answer (see captureStereoPair
    // below: ERROR_CAMERA_BUFFER on the cold stream), and at 1 Hz with a 900 ms warm-up the
    // second sensor is running the whole time anyway. Second, a one-shot request inserted into
    // the repeating stream either steals a sensor frame from the video -- a 33 ms hole every
    // second -- or has to reproduce the recording request exactly, and any key it gets wrong
    // (zoom, exposure, the AE range the blur budget is driving) lands in one video frame.
    //
    // So instead: while periodic pairs are on, Camera2Proxy adds both physical streams to the
    // REPEATING request for the whole recording. Every sensor frame then arrives here from both
    // lenses, is acquired and released -- the drain that already exists for the warm-up -- and
    // once per interval the next frame on each lens is KEPT. No extra request, no frame stolen,
    // and the two kept frames are the same sensor period as one of the video's own frames. The
    // pair is simultaneous because it is one request's output, not because two shutters were
    // asked nicely to coincide.
    //
    // The unmeasured cost, which the S1/S2 test cells exist to measure: both sensors run for
    // the whole clip (power, heat, and whatever the HAL does to the logical stream when its
    // physical outputs are requested alongside it).
    //
    // MATCHING. Images lead results by a few frames on this hardware (measured for RAW; see
    // drainRawPairs). Arming "the next image" would therefore keep whichever frame happened to
    // be in flight, and if the two readers were a frame apart the kept pair would be too. So
    // the arm is a TARGET TIMESTAMP a few frames in the future, and each lens keeps its first
    // frame at or after it. The metadata row is matched to the repeating result whose stamp is
    // nearest the kept image's, and written whichever of the two arrives last.

    /** How far ahead of the arming result the target sits: past the image/result lead. */
    private static final long PERIODIC_LEAD_NS = 100_000_000L;      // ~3 frames at 30 fps
    /** An image and a result are the same frame if their stamps are this close. */
    private static final long PERIODIC_MATCH_NS = 12_000_000L;      // under half a frame
    private static final int PERIODIC_RECENT_RESULTS = 12;

    private volatile boolean mPeriodicActive = false;
    private long mPeriodicIntervalNs;
    private long mPeriodicNextDueTs = 0;        // logical SENSOR_TIMESTAMP at which to arm next
    private long mPeriodicTargetTs = Long.MAX_VALUE;   // keep the first frame at or after this
    private long mPeriodicBurstId;
    private boolean mPeriodicKeptUw, mPeriodicKeptMain;
    private CaptureMode mPeriodicCaptureMode = CaptureMode.WALK;
    private volatile int mPeriodicPairs = 0;
    private volatile int mPeriodicUnmatched = 0;

    /** A kept image whose result has not arrived yet, or vice versa. */
    private static final class PeriodicKept {
        final String physicalId, tag;
        final long imageTs, burstId;
        final int index;
        PeriodicKept(String physicalId, String tag, long imageTs, long burstId, int index) {
            this.physicalId = physicalId;
            this.tag = tag;
            this.imageTs = imageTs;
            this.burstId = burstId;
            this.index = index;
        }
    }
    private final java.util.ArrayList<PeriodicKept> mPeriodicPending = new java.util.ArrayList<>();
    private final java.util.LinkedHashMap<Long, TotalCaptureResult> mPeriodicResults =
            new java.util.LinkedHashMap<>();

    /**
     * Start keeping a pair every intervalNs of the recording. The caller has already put both
     * physical streams into the repeating request; nothing here issues a request.
     */
    public void startPeriodicStereo(long intervalNs, File outputDir, RecordingWriter writer,
                                    CaptureMode mode) {
        if (!mStereoSupported) {
            Log.w(TAG, "periodic stereo requested but the lens pair is not available");
            return;
        }
        mOutputDir = outputDir;
        mRecordingWriter = writer;
        mPeriodicIntervalNs = Math.max(intervalNs, 200_000_000L);
        mPeriodicCaptureMode = mode;
        mPeriodicNextDueTs = 0;             // the first result arms the first pair
        mPeriodicTargetTs = Long.MAX_VALUE;
        mPeriodicKeptUw = mPeriodicKeptMain = true;   // nothing armed yet
        mPeriodicPairs = 0;
        mPeriodicUnmatched = 0;
        mPeriodicPending.clear();
        mPeriodicResults.clear();
        // Any OBJECT arm left over must not steal the first periodic frame.
        mStereoWantUw.set(false);
        mStereoWantMain.set(false);
        mPeriodicActive = true;
        Log.i(TAG, String.format(java.util.Locale.US,
                "periodic stereo pairs every %.1f s into %s", mPeriodicIntervalNs / 1e9,
                outputDir));
    }

    public void stopPeriodicStereo() {
        if (!mPeriodicActive) {
            return;
        }
        mPeriodicActive = false;
        // The pending list and result window belong to the camera handler; the stop comes
        // from the UI thread. Finish on the owner's thread.
        mHandler.post(this::flushPeriodic);
    }

    private void flushPeriodic() {
        mPeriodicTargetTs = Long.MAX_VALUE;
        int orphans = mPeriodicPending.size();
        if (orphans > 0) {
            // Their JPEGs are on disk; write what the image alone knows so the file does not
            // carry a picture with no row. exposure/iso stay 0, which the reader treats as
            // "not recorded", not as a reading (proto3 presence rules, see recording.proto).
            for (PeriodicKept k : mPeriodicPending) {
                writeStereoMeta(null, k.physicalId, k.tag, k.index, k.burstId,
                        mPeriodicCaptureMode, k.imageTs);
            }
            mPeriodicUnmatched += orphans;
            mPeriodicPending.clear();
        }
        mPeriodicResults.clear();
        Log.i(TAG, "periodic stereo stopped: " + mPeriodicPairs + " pairs armed, "
                + mPeriodicUnmatched + " frames written without a matched result");
    }

    public boolean periodicActive() {
        return mPeriodicActive;
    }

    /** Pairs armed so far in this recording, for the readout. */
    public int periodicPairCount() {
        return mPeriodicPairs;
    }

    /**
     * Every result of the repeating request, from Camera2Proxy's session callback. Cheap when
     * periodic mode is off; when on, it drives the arming clock and supplies the metadata rows.
     */
    public void onRepeatingResult(TotalCaptureResult result) {
        if (!mPeriodicActive || result == null) {
            return;
        }
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null) {
            return;
        }
        // Arm on the logical clock. The first result of the recording arms immediately, so a
        // short clip still gets its first pair within PERIODIC_LEAD_NS of the start.
        if (ts >= mPeriodicNextDueTs && mPeriodicKeptUw && mPeriodicKeptMain) {
            mPeriodicTargetTs = ts + PERIODIC_LEAD_NS;
            mPeriodicBurstId = mPeriodicTargetTs;
            mPeriodicKeptUw = false;
            mPeriodicKeptMain = false;
            mPeriodicNextDueTs = ts + mPeriodicIntervalNs;
            mPeriodicPairs++;
        } else if (ts >= mPeriodicNextDueTs) {
            // The previous arm never completed on one lens (a stream that stopped delivering,
            // or a frame the reader dropped). Log it, abandon it, and re-arm rather than wait
            // forever on a frame that is not coming.
            Log.w(TAG, "periodic pair " + mPeriodicBurstId + " incomplete (uw "
                    + mPeriodicKeptUw + ", main " + mPeriodicKeptMain + "); re-arming");
            mPeriodicUnmatched++;
            mPeriodicTargetTs = ts + PERIODIC_LEAD_NS;
            mPeriodicBurstId = mPeriodicTargetTs;
            mPeriodicKeptUw = false;
            mPeriodicKeptMain = false;
            mPeriodicNextDueTs = ts + mPeriodicIntervalNs;
            mPeriodicPairs++;
        }
        // Keep a short window of results so a kept image can find its own frame's metadata.
        mPeriodicResults.put(ts, result);
        while (mPeriodicResults.size() > PERIODIC_RECENT_RESULTS) {
            Long oldest = mPeriodicResults.keySet().iterator().next();
            mPeriodicResults.remove(oldest);
        }
        // Resolve any kept image that was waiting for this result.
        for (java.util.Iterator<PeriodicKept> it = mPeriodicPending.iterator(); it.hasNext(); ) {
            PeriodicKept k = it.next();
            if (Math.abs(k.imageTs - ts) <= PERIODIC_MATCH_NS) {
                writeStereoMeta(result, k.physicalId, k.tag, k.index, k.burstId,
                        mPeriodicCaptureMode, k.imageTs);
                it.remove();
            } else if (ts - k.imageTs > PERIODIC_RECENT_RESULTS * 40_000_000L) {
                // Its result is not coming. Write the row from the image alone.
                writeStereoMeta(null, k.physicalId, k.tag, k.index, k.burstId,
                        mPeriodicCaptureMode, k.imageTs);
                mPeriodicUnmatched++;
                it.remove();
            }
        }
    }

    /** The nearest recent result to an image stamp, or null if none is close enough. */
    private TotalCaptureResult nearestPeriodicResult(long imageTs) {
        TotalCaptureResult best = null;
        long bestDt = Long.MAX_VALUE;
        for (java.util.Map.Entry<Long, TotalCaptureResult> e : mPeriodicResults.entrySet()) {
            long dt = Math.abs(e.getKey() - imageTs);
            if (dt < bestDt) {
                bestDt = dt;
                best = e.getValue();
            }
        }
        return bestDt <= PERIODIC_MATCH_NS ? best : null;
    }

    /**
     * Decide whether a periodic frame is kept. Runs on the camera handler, inside the image
     * acquire, so the answer must be immediate.
     */
    private boolean periodicKeep(String physicalId, long imageTs) {
        if (!mPeriodicActive || imageTs < mPeriodicTargetTs) {
            return false;
        }
        if (PHYS_ULTRAWIDE.equals(physicalId)) {
            if (mPeriodicKeptUw) {
                return false;
            }
            mPeriodicKeptUw = true;
        } else {
            if (mPeriodicKeptMain) {
                return false;
            }
            mPeriodicKeptMain = true;
        }
        return true;
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
            // The builder must be created FOR the physical cameras it will address.
            // setPhysicalCameraKey validates its id against the set the builder was made
            // with, and a builder from the plain createCaptureRequest has an EMPTY set —
            // so it threw `Physical camera id: 2 is not valid!`, the whole stereo capture
            // was abandoned, and the only frames that reached disk were warm-up frames
            // from the repeating request. Those looked like a stereo pair and were not
            // one: no per-physical crop, no capture callback, no metadata. The first
            // attempt at the crop fix never ran at all — it threw before it could be
            // tested, and the "still cropped" measurement was of the wrong frames.
            CaptureRequest.Builder b;
            if (Build.VERSION.SDK_INT >= 28) {
                java.util.Set<String> ids = new java.util.HashSet<>(
                        java.util.Arrays.asList(PHYS_ULTRAWIDE, PHYS_MAIN));
                b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE, ids);
            } else {
                b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            }
            copyBase(baseRequest, b, false);
            applyFullFieldOfView(b);
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
        final byte[] nv21;
        final int w, h;
        final long imageTs;
        final long burstId;
        final boolean periodic;
        try (Image image = reader.acquireNextImage()) {
            if (image == null) {
                return;
            }
            // The warm-up runs a REPEATING request so the second sensor spins up, which
            // means frames stream in continuously before and after the shot we want.
            // They still have to be acquired and released or the reader stalls — but
            // only the armed frame is kept. Without this every warm-up frame overwrote
            // the output, which is what the first run did: a dozen writes to two names.
            //
            // In periodic mode (#36) the same drain runs for the whole recording, and the
            // arm is a target timestamp rather than a flag.
            imageTs = image.getTimestamp();
            if (mPeriodicActive) {
                periodic = true;
                if (!periodicKeep(physicalId, imageTs)) {
                    return;
                }
                burstId = mPeriodicBurstId;
            } else {
                periodic = false;
                boolean armed = PHYS_ULTRAWIDE.equals(physicalId)
                        ? mStereoWantUw.compareAndSet(true, false)
                        : mStereoWantMain.compareAndSet(true, false);
                if (!armed) {
                    return;
                }
                burstId = mStereoBurstId;
            }
            // Copy the planes out and release the buffer. The JPEG encode is NOT done here:
            // this is the camera handler, which also carries every capture result and, in
            // periodic mode, thirty drains a second from each reader. A 1080p encode is
            // tens of milliseconds, and once a second that was a frame's worth of metadata
            // held up behind it.
            w = image.getWidth();
            h = image.getHeight();
            nv21 = yuvToNv21(image);
        } catch (IllegalStateException e) {
            Log.e(TAG, "stereo acquire failed: " + e);
            return;
        }
        if (nv21 == null) {
            return;
        }
        final String name = String.format(java.util.Locale.US, "stereo_%d_%s.jpg",
                burstId, tag);
        final File out = new File(mOutputDir, name);
        mIo.execute(() -> {
            byte[] jpeg = nv21ToJpeg(nv21, w, h);
            if (jpeg == null) {
                return;
            }
            try (FileOutputStream s = new FileOutputStream(out)) {
                s.write(jpeg);
                Log.d(TAG, "wrote " + name + " (" + jpeg.length / 1024 + " kB)");
            } catch (IOException e) {
                Log.e(TAG, "stereo write failed: " + e);
            }
        });
        if (periodic) {
            // Still on the camera handler, same thread as onRepeatingResult, so the pending
            // list and the result window need no lock. Match now if the result is already
            // here; otherwise the result's arrival writes the row.
            int index = PHYS_ULTRAWIDE.equals(physicalId) ? 0 : 1;
            TotalCaptureResult r = nearestPeriodicResult(imageTs);
            if (r != null) {
                writeStereoMeta(r, physicalId, tag, index, burstId, mPeriodicCaptureMode,
                        imageTs);
            } else {
                mPeriodicPending.add(new PeriodicKept(physicalId, tag, imageTs, burstId, index));
            }
        }
    }

    /** The OBJECT-station pair: burst id and mode are the composite's. */
    private void writeStereoMeta(TotalCaptureResult result, String physicalId,
                                 String tag, int index) {
        writeStereoMeta(result, physicalId, tag, index, mStereoBurstId, CaptureMode.OBJECT, 0L);
    }

    /**
     * @param result  the frame's TotalCaptureResult, or null when none could be matched --
     *                then only what the image itself carries (its stamp) is written.
     * @param imageTs the kept image's own stamp; used when the result has none, or is null.
     */
    private void writeStereoMeta(TotalCaptureResult result, String physicalId,
                                 String tag, int index, long burstId, CaptureMode mode,
                                 long imageTs) {
        if (mRecordingWriter == null) {
            return;
        }
        RecordingProtos.StillMetaData.Builder b =
                RecordingProtos.StillMetaData.newBuilder()
                        .setBurstId(burstId)
                        .setBurstSize(2)
                        .setBurstIndex(index)
                        .setKindValue(Mode.SINGLE.ordinal())
                        .setCaptureMode(mode.ordinal())
                        .setPhysicalCameraId(physicalId)
                        .setJpegFile(String.format(java.util.Locale.US,
                                "stereo_%d_%s.jpg", burstId, tag));
        if (result == null) {
            if (imageTs != 0L) {
                b.setTimeNs(imageTs);
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
            return;
        }

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
        // THE STAMP IS THE IMAGE'S, when there is one. Measured on the S2 cell of 2026-09-10:
        // both lenses' images carry the IDENTICAL stamp, equal to the logical result's, so a kept
        // pair is one sensor period on both -- but the ultrawide's per-physical result reports a
        // SENSOR_TIMESTAMP 180-240 ms away on a grid of exactly 1.000 s, another clock entirely.
        // The first S2 wrote that value and every one of its 30 pairs failed the 5 ms pairing
        // tolerance downstream. The image stamp is also the frame table's stamp, which is what
        // lets a pair join the video's own frame without a lookup. Exposure and ISO still come
        // from the per-physical result: the two sensors really are exposed differently.
        Long ts = per.get(CaptureResult.SENSOR_TIMESTAMP);
        if (imageTs != 0L) {
            b.setTimeNs(imageTs);
        } else if (ts != null) {
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
        Long dur = per.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (dur != null) {
            b.setFrameDurationNs(dur);
        }
        Long skew = per.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        if (skew != null) {
            b.setFrameReadoutNs(skew);
        }
        // The measurement this pair exists to settle: what the HAL read out of THIS sensor.
        // Compare against the physical camera's own SENSOR_INFO_ACTIVE_ARRAY_SIZE in the
        // census — 4000x3000 for the ultrawide, 4080x3060 for the main. Anything narrower
        // is the crop that made the two frames match.
        recordCrop(b, per, result);
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
    private static byte[] yuvToNv21(Image image) {
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
            return nv21;
        } catch (Exception e) {
            Log.e(TAG, "YUV->NV21 failed: " + e);
            return null;
        }
    }

    /** The encode half, off the camera thread. */
    private static byte[] nv21ToJpeg(byte[] nv21, int w, int h) {
        try {
            android.graphics.YuvImage yuv =
                    new android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 95, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "NV21->JPEG failed: " + e);
            return null;
        }
    }

    /**
     * Per-physical crop = each sensor's own full array, on a builder that was created FOR
     * those physical ids. Public so the periodic path's repeating request can carry the
     * same keys the OBJECT pair does; whether the HAL honours them is what crop_region
     * on each row records.
     */
    public void applyPhysicalFullArrays(CaptureRequest.Builder b) {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        for (String pid : new String[]{PHYS_ULTRAWIDE, PHYS_MAIN}) {
            Rect active = physicalActiveArray(pid);
            if (active != null) {
                try {
                    b.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, active, pid);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "physical " + pid + " crop refused: " + e);
                }
            }
        }
    }

    public static java.util.Set<String> stereoPhysicalIds() {
        return new java.util.HashSet<>(java.util.Arrays.asList(PHYS_ULTRAWIDE, PHYS_MAIN));
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
        mPeriodicActive = false;
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
            b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new android.util.Size(0, 0));
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
     * Give each physical stream its OWN sensor's full array, and open the logical zoom to the
     * WIDEST ratio the device offers, so nothing between the request and the readout narrows
     * the wide lens.
     *
     * CORRECTED 2026-08-02, and the earlier version of this method was the bug it claimed to
     * fix. It pinned CONTROL_ZOOM_RATIO to 1.0f and called that "no zoom". On a logical
     * multi-camera 1.0 is not neutral — it is main-camera framing BY DEFINITION, because the
     * ratio is expressed relative to the logical camera's default field of view. Ratios below
     * 1.0 are what widen it onto the ultrawide. This device's own numbers say so exactly:
     * factory fx is 1651.15 (ultrawide) against 2755.65 (main), and 1651.15/2755.65 = 0.599.
     * So 0.6 IS the ultrawide's native field of view, and 1.0 asks the HAL to crop it away.
     * The operator found this from the other end, by setting the app's zoom_ratio preference
     * to 0.6 and watching the full sensor appear.
     *
     * Worse, the old code read CONTROL_ZOOM_RATIO_RANGE, confirmed its lower bound could go
     * below 1.0, and then pinned 1.0 anyway — and from API 30 the zoom ratio governs, so it
     * overrode the per-physical crop regions set immediately below it.
     *
     * MEASURED CONSEQUENCE: all 40 stereo pairs in data/capture_raw/s24u_20260801 that
     * recorded a zoom ratio recorded 1.0, and none recorded 0.6. Every stereo pair ever shot
     * with this app is main-framed — the ultrawide's entire reason for being in the pair was
     * discarded at capture time, on every single one.
     *
     * Note the user's zoom_ratio preference does NOT reach here: copyBase() does not carry
     * CONTROL_ZOOM_RATIO, so the preference governs preview and video while the stereo still
     * took whatever this method set. Setting the preference alone would have produced pairs
     * that looked corrected on screen and were not.
     */
    private void applyFullFieldOfView(CaptureRequest.Builder b) {
        if (Build.VERSION.SDK_INT >= 30) {
            Range<Float> zoom =
                    mCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (zoom != null) {
                // The LOWER bound is the widest field of view the device will give us. Ask for
                // it explicitly rather than for 1.0, and rather than leaving it at whatever the
                // HAL last had.
                float widest = zoom.getLower();
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, widest);
                Log.d(TAG, "zoom ratio set to the widest available " + widest
                        + " (range " + zoom + "); 1.0 would be main-camera framing");
            }
        }
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        for (String pid : new String[]{PHYS_ULTRAWIDE, PHYS_MAIN}) {
            Rect active = physicalActiveArray(pid);
            if (active != null) {
                b.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, active, pid);
                Log.d(TAG, "physical " + pid + " crop set to its own array " + active);
            }
        }
    }

    /**
     * Record the readout rectangle and zoom for one shot.
     *
     * @param per    this lens's own result where the device supplies one, else the logical
     *               result — the crop is per-sensor, so the physical result is the one that
     *               answers the question.
     * @param outer  the logical result, which is where CONTROL_ZOOM_RATIO lives.
     */
    private void recordCrop(RecordingProtos.StillMetaData.Builder b, CaptureResult per,
                            TotalCaptureResult outer) {
        Rect crop = per.get(CaptureResult.SCALER_CROP_REGION);
        if (crop != null) {
            b.setCropRegion(RecordingProtos.VideoFrameMetaData.Rect.newBuilder()
                    .setLeft(crop.left).setTop(crop.top)
                    .setRight(crop.right).setBottom(crop.bottom));
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Float z = outer.get(CaptureResult.CONTROL_ZOOM_RATIO);
            if (z != null) {
                b.setZoomRatio(z);
            }
        }
    }

    /** The full active array of one physical sensor, or null if it cannot be read. */
    private Rect physicalActiveArray(String physicalId) {
        if (Build.VERSION.SDK_INT < 28 || mCameraManager == null) {
            return null;
        }
        try {
            return mCameraManager.getCameraCharacteristics(physicalId)
                    .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "no characteristics for physical " + physicalId + ": " + e);
            return null;
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
                + " D: " + java.util.Arrays.toString(out));
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
            b.set(CaptureRequest.JPEG_THUMBNAIL_SIZE, new android.util.Size(0, 0));
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
            Log.i(TAG, String.format(java.util.Locale.US,
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
    // How long the lens took to reach each step, and whether it got there at all. Recorded
    // per shot so a bracket that silently collapses shows up in the metadata rather than
    // only under a sharpness measure after the fact.
    private final java.util.HashMap<Integer, Long> mFocusSettleNs = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Boolean> mFocusSettled = new java.util.HashMap<>();

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
        recordCrop(b, result, result);
        // How long the lens took to arrive at this frame's target, and whether it got
        // there before the shutter opened. Zero for anything that did not drive focus.
        Long settle = mFocusSettleNs.get(index);
        if (settle != null) {
            b.setFocusSettleNs(settle);
            Boolean ok = mFocusSettled.get(index);
            b.setFocusSettled(ok != null && ok);
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
