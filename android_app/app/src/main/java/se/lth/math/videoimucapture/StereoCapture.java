package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Frames from the PHYSICAL lenses: one reader per configured lens, and the two ways a pair is
 * kept from them -- on an interval for the length of a video, or once, from a warmed stream.
 *
 * Nothing here issues a capture request. Camera2Proxy decides which physical streams the
 * repeating request carries; this class drains every frame that arrives, keeps the ones that
 * were asked for, and writes the row that describes each kept frame.
 *
 * Which lens is which is LensRoles' business.
 */
public class StereoCapture {
    private static final String TAG = "StereoCapture";

    private final CameraCharacteristics mCharacteristics;
    // Needed to read the PHYSICAL sensors' own characteristics: a logical camera's
    // characteristics describe the logical camera, and the ultrawide's active array is not
    // in there.
    private final CameraManager mCameraManager;
    private final Handler mHandler;
    private final IMUManager mImuManager;
    /** The still writer's thread, shared so that writes stay ordered and never contend. */
    private final ExecutorService mIo;

    private RecordingWriter mRecordingWriter;
    private File mOutputDir;

    /**
     * Physical streams are constrained: the probe found YUV at 1920x1080 configures
     * alongside preview, JPEG and RAW, while larger did not. At 1920 wide the main
     * camera's factory focal scales to ~1296 px, so an 18.02 mm baseline gives 47 px of
     * disparity at 0.5 m and 23 px at 1 m — ample across OBJECT mode's working range.
     */
    private static final Size STEREO_SIZE = new Size(1920, 1080);
    private static final int STEREO_READER_DEPTH = 4;

    /**
     * One reader per physical lens configured into the session, keyed by physical id.
     *
     * This was a pair of named fields -- an ultrawide reader and a main reader -- because the
     * only question anyone had asked of two lenses at once was the 18.02 mm baseline. The
     * device turns out to support far more than that: the probe on this handset configures
     * 2+5+6+7 together, and preview + JPEG + RAW + four physical streams at 1920x1080 is a
     * supported combination, seven streams in one session.
     *
     * Insertion-ordered, so the physical ids appear in the file in the order they were
     * configured rather than in whatever order a hash gives.
     */
    private final LinkedHashMap<String, ImageReader> mLensReaders =
            new LinkedHashMap<>();
    private boolean mStereoSupported;

    StereoCapture(CameraCharacteristics characteristics, CameraManager cameraManager,
                  Handler handler, IMUManager imuManager, ExecutorService io) {
        mCharacteristics = characteristics;
        mCameraManager = cameraManager;
        mHandler = handler;
        mImuManager = imuManager;
        mIo = io;
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
        Set<String> physicals = mCharacteristics.getPhysicalCameraIds();
        LensRoles.resolve(mCameraManager, mCharacteristics, physicals);
        if (!physicals.contains(LensRoles.physUltrawide())
                || !physicals.contains(LensRoles.physMain())) {
            Log.i(TAG, "no ultrawide+main physical pair; stereo stage disabled");
            return;
        }
        // WHICH LENSES GET A STREAM. The metric pair always; every other physical too when
        // the operator has asked for the all-lens shot. This is a SESSION-level decision --
        // streams are bound at createCaptureSession and cannot be added to a live session --
        // so changing it takes effect when the camera is next opened, which is why the cells
        // that use it cycle the camera rather than only writing the preference.
        //
        // Configuring all four is not free even when they are not being targeted, which is
        // why it is off by default. The pair is what every clip in the archive was shot with.
        boolean allLenses = LensRoles.allLensShot();
        LinkedHashSet<String> active = new LinkedHashSet<>();
        active.add(LensRoles.physMain());
        active.add(LensRoles.physUltrawide());
        if (allLenses) {
            for (String id : physicals) {
                active.add(id);
            }
        }
        LensRoles.setActiveLensIds(new ArrayList<>(active));
        final List<String> activeIds = LensRoles.activeLensIds();

        // Depth 4, not 2. In periodic mode (ReconStab #36) both readers receive every frame of
        // the recording and are drained on the camera handler; if that thread is held for two
        // frame periods -- a metadata queue stall, a burst of results -- a depth-2 reader fills,
        // and a full physical stream stalls the request pipeline it shares with the VIDEO.
        for (String id : activeIds) {
            final String tag = LensRoles.lensTag(id);
            ImageReader reader = ImageReader.newInstance(STEREO_SIZE.getWidth(),
                    STEREO_SIZE.getHeight(), ImageFormat.YUV_420_888, STEREO_READER_DEPTH);
            reader.setOnImageAvailableListener(r -> onStereoImage(r, id, tag), mHandler);
            mLensReaders.put(id, reader);
        }
        mStereoSupported = true;
        Log.i(TAG, "lens streams configured: " + activeIds
                + (allLenses ? " (all-lens shot)" : " (metric pair)"));
        Integer sync = Build.VERSION.SDK_INT >= 28
                ? mCharacteristics.get(CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)
                : null;
        Log.d(TAG, "stereo pair ready at " + STEREO_SIZE + "; sensor sync type "
                + sync + " (0 approximate, 1 calibrated)");
    }

    public boolean stereoSupported() {
        return mStereoSupported;
    }

    /** Every physical surface to bind at session configuration: one per configured lens. */
    public Map<String, Surface> getStereoSurfaces() {
        LinkedHashMap<String, Surface> out = new LinkedHashMap<>();
        if (mStereoSupported) {
            for (Map.Entry<String, ImageReader> e : mLensReaders.entrySet()) {
                out.put(e.getKey(), e.getValue().getSurface());
            }
        }
        return out;
    }

    /**
     * Just the two lenses with a published baseline.
     *
     * The periodic stream (#36) and the anchoring pair want THESE and not whatever else the
     * session happens to have configured: a pair is a measurement because its separation is
     * known, and adding a telephoto whose offset the device will not state turns one metric
     * pair into three pictures, two of which cannot contribute scale.
     */
    public Map<String, Surface> getMetricPairSurfaces() {
        LinkedHashMap<String, Surface> out = new LinkedHashMap<>();
        if (mStereoSupported) {
            for (String id : new String[]{LensRoles.physUltrawide(), LensRoles.physMain()}) {
                ImageReader r = mLensReaders.get(id);
                if (r != null) {
                    out.put(id, r.getSurface());
                }
            }
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
    private StillCaptureManager.CaptureMode mPeriodicCaptureMode = StillCaptureManager.CaptureMode.WALK;
    private volatile int mPeriodicPairs = 0;
    private volatile int mPeriodicUnmatched = 0;

    /** A kept image whose result has not arrived yet, or vice versa. */
    private static final class PeriodicKept {
        final String physicalId, tag;
        final long imageTs, burstId;
        final int index;
        final StillCaptureManager.CaptureMode mode;   // the periodic run's, or the stream-kept pair's own
        PeriodicKept(String physicalId, String tag, long imageTs, long burstId, int index,
                     StillCaptureManager.CaptureMode mode) {
            this.physicalId = physicalId;
            this.tag = tag;
            this.imageTs = imageTs;
            this.burstId = burstId;
            this.index = index;
            this.mode = mode;
        }
    }
    private final ArrayList<PeriodicKept> mPeriodicPending = new ArrayList<>();
    private final LinkedHashMap<Long, TotalCaptureResult> mPeriodicResults =
            new LinkedHashMap<>();

    /**
     * Start keeping a pair every intervalNs of the recording. The caller has already put both
     * physical streams into the repeating request; nothing here issues a request.
     */
    public void startPeriodicStereo(long intervalNs, File outputDir, RecordingWriter writer,
                                    StillCaptureManager.CaptureMode mode) {
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
        mStereoBurstSize = 2;
        // This session's counts start here. The row counter was only reset by a stills run or
        // the OBJECT stage, so a second video clip reported its rows on top of the first's
        // (W1 24, W2 48 on 2026-09-20) and a third would have said 72.
        resetOneShotBursts();
        mPeriodicPending.clear();
        mPeriodicResults.clear();
        mPeriodicActive = true;
        Log.i(TAG, String.format(Locale.US,
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
                writeStereoMeta(null, k.physicalId, k.tag, k.index, k.burstId, k.mode,
                        k.imageTs);
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
     * nothing is armed; otherwise it drives the periodic arming clock, sets the stream-keep
     * target, keeps the result window, and resolves kept images waiting for their row.
     */
    public void onRepeatingResult(TotalCaptureResult result) {
        if (result == null) {
            return;
        }
        // Keep going while anything kept is still waiting for its row: a pair from the stream
        // completes before its results arrive, and the request feeding this may already be
        // the next warm-up or the restored preview -- the stamps still match.
        if (!mPeriodicActive && !mStreamKeepActive && mPeriodicPending.isEmpty()) {
            return;
        }
        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (ts == null) {
            return;
        }
        if (mStreamKeepActive && mStreamKeepTargetTs == Long.MAX_VALUE) {
            // A few frames ahead of NOW, past the image/result lead, so the frames kept are
            // ones whose results are still to come -- not warm-up frames already in flight,
            // which is exactly what the still request used to end up with.
            mStreamKeepTargetTs = ts + PERIODIC_LEAD_NS;
        }
        if (mPeriodicActive) {
            // Arm on the logical clock. The first result of the recording arms immediately,
            // so a short clip still gets its first pair within PERIODIC_LEAD_NS of the start.
            if (ts >= mPeriodicNextDueTs && mPeriodicKeptUw && mPeriodicKeptMain) {
                mPeriodicTargetTs = ts + PERIODIC_LEAD_NS;
                mPeriodicBurstId = mPeriodicTargetTs;
                mPeriodicKeptUw = false;
                mPeriodicKeptMain = false;
                mPeriodicNextDueTs = ts + mPeriodicIntervalNs;
                mPeriodicPairs++;
            } else if (ts >= mPeriodicNextDueTs) {
                // The previous arm never completed on one lens (a stream that stopped
                // delivering, or a frame the reader dropped). Log it, abandon it, and re-arm
                // rather than wait forever on a frame that is not coming.
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
        }
        // Keep a short window of results so a kept image can find its own frame's metadata.
        mPeriodicResults.put(ts, result);
        while (mPeriodicResults.size() > PERIODIC_RECENT_RESULTS) {
            Long oldest = mPeriodicResults.keySet().iterator().next();
            mPeriodicResults.remove(oldest);
        }
        // Resolve any kept image that was waiting for this result.
        for (Iterator<PeriodicKept> it = mPeriodicPending.iterator(); it.hasNext(); ) {
            PeriodicKept k = it.next();
            if (Math.abs(k.imageTs - ts) <= PERIODIC_MATCH_NS) {
                writeStereoMeta(result, k.physicalId, k.tag, k.index, k.burstId, k.mode,
                        k.imageTs);
                it.remove();
            } else if (ts - k.imageTs > PERIODIC_RECENT_RESULTS * 40_000_000L) {
                // Its result is not coming. Write the row from the image alone.
                writeStereoMeta(null, k.physicalId, k.tag, k.index, k.burstId, k.mode,
                        k.imageTs);
                mPeriodicUnmatched++;
                it.remove();
            }
        }
    }

    /** The nearest recent result to an image stamp, or null if none is close enough. */
    private TotalCaptureResult nearestPeriodicResult(long imageTs) {
        TotalCaptureResult best = null;
        long bestDt = Long.MAX_VALUE;
        for (Map.Entry<Long, TotalCaptureResult> e : mPeriodicResults.entrySet()) {
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
        if (LensRoles.physUltrawide().equals(physicalId)) {
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

    private void onStereoImage(ImageReader reader, String physicalId, String tag) {
        final byte[] nv21;
        final int w, h;
        final long imageTs;
        final long burstId;
        final boolean periodic;
        final boolean streamKept;
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
            if (mStreamKeepActive) {
                // A one-shot pair, chosen from the stream by timestamp (see armPairFromStream).
                if (!streamKeep(physicalId, imageTs)) {
                    return;
                }
                periodic = true;
                streamKept = true;
                burstId = mStreamKeepBurstId;
            } else if (mPeriodicActive) {
                periodic = true;
                streamKept = false;
                if (!periodicKeep(physicalId, imageTs)) {
                    return;
                }
                burstId = mPeriodicBurstId;
            } else {
                // Nothing armed: every frame is drained and discarded.
                return;
            }
            // Copy the planes out and release the buffer. The JPEG encode is NOT done here:
            // this is the camera handler, which also carries every capture result and, in
            // periodic mode, thirty drains a second from each reader. A 1080p encode is
            // tens of milliseconds, and once a second that was a frame's worth of metadata
            // held up behind it.
            w = image.getWidth();
            h = image.getHeight();
            nv21 = YuvJpeg.yuvToNv21(image);
        } catch (IllegalStateException e) {
            Log.e(TAG, "stereo acquire failed: " + e);
            return;
        }
        if (nv21 == null) {
            return;
        }
        final String name = String.format(Locale.US, "stereo_%d_%s.jpg",
                burstId, tag);
        final File out = new File(mOutputDir, name);
        mIo.execute(() -> {
            byte[] jpeg = YuvJpeg.nv21ToJpeg(nv21, w, h);
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
            int index = streamKept
                    ? (physicalId.equals(mStreamKeepPair[0]) ? 0 : 1)
                    : (LensRoles.physUltrawide().equals(physicalId) ? 0 : 1);
            StillCaptureManager.CaptureMode mode = streamKept ? mStreamKeepMode : mPeriodicCaptureMode;
            TotalCaptureResult r = nearestPeriodicResult(imageTs);
            if (r != null) {
                writeStereoMeta(r, physicalId, tag, index, burstId, mode, imageTs);
            } else {
                mPeriodicPending.add(new PeriodicKept(physicalId, tag, imageTs, burstId, index,
                        mode));
            }
        }
    }

    /**
     * @param result  the frame's TotalCaptureResult, or null when none could be matched --
     *                then only what the image itself carries (its stamp) is written.
     * @param imageTs the kept image's own stamp; used when the result has none, or is null.
     */
    private void writeStereoMeta(TotalCaptureResult result, String physicalId,
                                 String tag, int index, long burstId, StillCaptureManager.CaptureMode mode,
                                 long imageTs) {
        if (mRecordingWriter == null) {
            return;
        }
        // Every path below queues exactly one row, so this is the row count.
        mStereoMetaRows++;
        RecordingProtos.StillMetaData.Builder b =
                RecordingProtos.StillMetaData.newBuilder()
                        .setBurstId(burstId)
                        // How many frames THIS capture carries, as set by the path issuing
                        // it. A reader joining frames by burst needs to know how many to
                        // expect before it can notice that one is missing -- and it was the
                        // number of configured readers, which said 4 on every pair.
                        .setBurstSize(mStereoBurstSize)
                        .setBurstIndex(index)
                        .setKindValue(StillCaptureManager.Mode.SINGLE.ordinal())
                        .setCaptureMode(mode.ordinal())
                        .setPhysicalCameraId(physicalId)
                        .setJpegFile(String.format(Locale.US,
                                "stereo_%d_%s.jpg", burstId, tag));
        if (result == null) {
            if (imageTs != 0L) {
                b.setTimeNs(imageTs);
            }
            StillRows.addOrientation(b, mImuManager);
            mRecordingWriter.queueData(b.build());
            return;
        }

        // Prefer this lens's OWN physical result where the device supplies one: the two
        // sensors can be exposed independently, so the logical result's exposure is not
        // necessarily either lens's.
        CaptureResult per = result;
        if (Build.VERSION.SDK_INT >= 28) {
            Map<String, CaptureResult> physResults =
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
        // The LOGICAL result's stamp, alongside the frame's own: equal means the kept pixels
        // are this request's frame; different means an armed reader kept a warm-up frame that
        // was already in flight. Without both in the row that race is invisible.
        Long logicalTs = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (logicalTs != null) {
            b.setLogicalResultTimeNs(logicalTs);
        }
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
        StillRows.recordCrop(b, per, result);
        StillRows.recordIntrinsics(b, per);
        Integer flash = result.get(TotalCaptureResult.FLASH_MODE);
        b.setTorchOn(flash != null
                && flash == CameraMetadata.FLASH_MODE_TORCH);

        StillRows.addOrientation(b, mImuManager);
        mRecordingWriter.queueData(b.build());
    }

    /**
     * Per-physical crop = each sensor's own full array, on a builder that was created FOR
     * those physical ids. Public so the periodic path's repeating request can carry the
     * same keys the OBJECT pair does; whether the HAL honours them is what crop_region
     * on each row records.
     */
    /**
     * @param ids the physical ids the builder was CREATED for. setPhysicalCameraKey validates
     *            against that set, so iterating every configured reader against a builder made
     *            for the metric pair threw "Physical camera id: 6 is not valid!" on every clip
     *            (caught, logged, and wrong).
     */
    public void applyPhysicalFullArrays(CaptureRequest.Builder b,
                                        Collection<String> ids) {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        // Each lens's own full array. A telephoto that comes back cropped is not a smaller
        // picture, it is a picture whose focal length in pixels no longer follows from the
        // factory intrinsics by the stream's scale factor -- and that scaling is the whole of
        // how G1/G2 turn a disparity into a baseline in millimetres.
        for (String pid : ids) {
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

    /** The lens pairs this session can capture, given what it was built with. */
    public List<String[]> configuredLensPairs() {
        return LensRoles.lensPairs(new ArrayList<>(mLensReaders.keySet()),
                LensRoles.physUltrawide(), LensRoles.physMain());
    }

    public Surface lensSurface(String physicalId) {
        ImageReader r = mLensReaders.get(physicalId);
        return r == null ? null : r.getSurface();
    }

    /** One-shot pair captures issued this run, so the receipt can expect that many bursts. */
    private volatile int mOneShotBursts = 0;

    /**
     * Stereo metadata rows queued this run -- one per lens per burst that a capture callback
     * actually described. A stereo file on the card with no row behind it is a warm-up frame
     * that an armed reader kept, not a capture, and only this count can tell the two apart.
     */
    private volatile int mStereoMetaRows = 0;

    /**
     * How many frames the CURRENT simultaneous capture carries. Set by whichever path is
     * issuing it: 2 for a pair (the periodic stream, a pair from the sequence, the metric pair
     * alone), every configured lens for the single all-lens request that this phone cannot
     * run. It was computed from the number of configured readers, which reported 4 on every
     * row of a pair sequence -- a reader joining halves by burst would have waited for two
     * frames that were never going to come. Distinct from mBurstSize, which is the ordinary
     * still burst's and can be mid-flight when a pair fires.
     */
    private volatile int mStereoBurstSize = 2;

    public int oneShotStereoBursts() {
        return mOneShotBursts;
    }

    public int stereoMetaRows() {
        return mStereoMetaRows;
    }

    public void resetOneShotBursts() {
        mOneShotBursts = 0;
        mStereoMetaRows = 0;
    }

    /**
     * A new SESSION: every count its receipt will read starts from zero, the periodic one too.
     *
     * Resetting where pairs are STARTED was not enough, in two directions. A session that
     * starts none -- a video with no interval -- reset nothing and sealed with the previous
     * session's counts. And a stills run JOINING a video reset the row count under the
     * video's own periodic pairs. The session's opening is the one moment that is right for
     * both, and it is the caller that knows when that is.
     */
    public void resetSessionCounts() {
        resetOneShotBursts();
        mPeriodicPairs = 0;
        mPeriodicUnmatched = 0;
    }

    // ------------------------------------------------------------ a pair from the stream
    //
    // ONE PAIR, CHOSEN FROM THE WARMED STREAM BY TIMESTAMP, its rows written from the matching
    // repeating result. This replaces the one-shot still request, and field 29
    // (logical_result_time_ns) is why: on every burst of the 19:53 L1 sequence the kept
    // pixels were a warm-up frame 213-634 ms OLDER than the request's frame, because an armed
    // reader keeps the next image it sees and the warm-up's frames were already in flight.
    // The row then described the request's frame -- its exposure, its ISO, a zoom_ratio of
    // 1.00 -- while the file held a frame the HAL had produced at 0.6. Half the pairs were
    // also one frame apart, each lens having kept its own next image.
    //
    // The warm-up stream is already at the widest zoom and already carries both physical
    // outputs of every frame, so the frame worth keeping is IN it. So: arm a target stamp a
    // few frames ahead of the current result (past the image/result lead -- the periodic
    // path's trick, PERIODIC_LEAD_NS), keep the first image at or after it from each lens
    // (the same sensor period on both, being outputs of one request), and write each row
    // from the repeating result whose stamp matches the image's, via the same pending list
    // the periodic path uses. No second request, nothing kept that was not asked for, and
    // the row describes the pixels.

    private volatile boolean mStreamKeepActive = false;
    private String[] mStreamKeepPair = null;
    private long mStreamKeepTargetTs = Long.MAX_VALUE;
    private long mStreamKeepBurstId = 0L;
    private StillCaptureManager.CaptureMode mStreamKeepMode = StillCaptureManager.CaptureMode.OBJECT;
    private final Set<String> mStreamKeepDone = new HashSet<>();

    /**
     * Arm one pair from the stream. The caller has already put exactly these two physical
     * streams into the repeating request, at the widest zoom, and let them settle.
     */
    public void armPairFromStream(String[] pair, File outputDir, RecordingWriter writer,
                                  StillCaptureManager.CaptureMode mode) {
        if (!mStereoSupported || pair == null || pair.length != 2) {
            Log.w(TAG, "pair from stream requested but unavailable");
            return;
        }
        if (!mLensReaders.containsKey(pair[0]) || !mLensReaders.containsKey(pair[1])) {
            Log.w(TAG, "pair " + pair[0] + "+" + pair[1] + " is not configured");
            return;
        }
        finishStreamKeep();
        mOutputDir = outputDir;
        mRecordingWriter = writer;
        mStreamKeepPair = pair;
        mStreamKeepMode = mode;
        mStreamKeepBurstId = SystemClock.elapsedRealtimeNanos();
        mStreamKeepTargetTs = Long.MAX_VALUE;      // set by the next repeating result
        mStreamKeepDone.clear();
        mStereoBurstSize = 2;
        mStreamKeepActive = true;
        mOneShotBursts++;
        Log.i(TAG, "pair from stream armed: physical " + pair[0] + "+" + pair[1] + " ("
                + LensRoles.lensTag(pair[0]) + "+" + LensRoles.lensTag(pair[1]) + ") burst " + mStreamKeepBurstId);
    }

    /** Whether a stream-kept frame is kept. Camera handler, inside the acquire: immediate. */
    private boolean streamKeep(String physicalId, long imageTs) {
        if (!mStreamKeepActive || imageTs < mStreamKeepTargetTs) {
            return false;
        }
        if (!physicalId.equals(mStreamKeepPair[0]) && !physicalId.equals(mStreamKeepPair[1])) {
            return false;
        }
        if (!mStreamKeepDone.add(physicalId)) {
            return false;
        }
        if (mStreamKeepDone.size() == 2) {
            mStreamKeepActive = false;     // the rows still resolve through the pending list
        }
        return true;
    }

    /** End an arm that did not complete, and say so. A no-op after a complete one. */
    public void finishStreamKeep() {
        if (mStreamKeepActive) {
            Log.w(TAG, "pair from stream " + mStreamKeepBurstId + " incomplete: kept "
                    + mStreamKeepDone + " of " + Arrays.toString(mStreamKeepPair));
            mStreamKeepActive = false;
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
    /**
     * Put the logical camera at its widest zoom, which is what makes the ultrawide's physical
     * stream carry the ultrawide's field of view.
     *
     * Measured 2026-09-20 with the zoom probe: at CONTROL_ZOOM_RATIO 1.0 the wide sensor's
     * stream is cropped toward the main camera's framing (1.39x in the probe's session, 1.62x
     * in the app's -- it varies with the session), and every crop_region still reports the
     * full array. At 0.6 the same stream measures 1.658x wider than the main camera against a
     * census prediction of 1.636, and the main camera's own stream is unchanged (1.000). The
     * zoom has to be in the REPEATING request the pair is warmed on, not only in the one-shot:
     * a one-shot at 0.6 dropped into a stream at 1.0 came back reporting 1.0 in five of six
     * bursts, because the HAL will not switch master lens for a single frame.
     */
    public void applyFullFieldOfView(CaptureRequest.Builder b) {
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
        // NO PER-PHYSICAL CROP HERE. This used to set SCALER_CROP_REGION for the ultrawide
        // and the main camera unconditionally, and setPhysicalCameraKey validates its id
        // against the set the builder was created with. A pair request built for {2, 6}
        // threw "Physical camera id: 5 is not valid!" -- five of the six pairs of the first
        // L1 sequence on 2026-09-20 -- and the readers, already armed, kept warm-up frames
        // in their place: files that looked like pairs, with no crop, no metadata row and no
        // guarantee of one sensor period. Every caller applies crops for exactly the ids in
        // ITS request, which is the only place that knows them. (Since the stream-keep change
        // the one-shot pair issues no request at all; only the periodic path applies them.)
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

    /** Stop keeping frames. The readers stay open until {@link #close()}. */
    void deactivate() {
        mPeriodicActive = false;
    }

    /** Close every lens reader. After the IO thread has drained, so no write loses its file. */
    void close() {
        for (ImageReader r : mLensReaders.values()) {
            r.close();
        }
        mLensReaders.clear();
        mStereoSupported = false;
    }
}
