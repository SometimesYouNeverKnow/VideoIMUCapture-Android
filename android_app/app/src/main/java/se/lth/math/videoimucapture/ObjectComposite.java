package se.lth.math.videoimucapture;

import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * OBJECT mode's one press: a focus stack across the subject's depth, an exposure bracket, a
 * full-quality RAW, and the stereo pair that makes the capture metric -- in sequence, in a
 * session of its own.
 *
 * A 15-second SEQUENCE of posted stages, not an instant, and the button that fires it had no
 * guard. Two presses started two composites: two directories, two writers racing for the same
 * file, two sets of stage handlers reconfiguring focus and exposure under each other, and a
 * lockAutoAlgorithms(false) from the first landing in the middle of the second. The button
 * gives no hint that it is busy, so this was one impatient tap away at all times. Hence
 * {@link #isActive()}.
 */
final class ObjectComposite {
    private static final String TAG = "CaptureMode";

    /** Told as each stage starts, and once more when the composite is over. */
    interface Listener {
        void onStage(String summary);
    }

    private final CameraCaptureActivity mActivity;
    private final Handler mMain;
    private final Listener mListener;
    private boolean mCompositeActive = false;

    ObjectComposite(CameraCaptureActivity activity, Handler main, Listener listener) {
        mActivity = activity;
        mMain = main;
        mListener = listener;
    }

    /** An OBJECT composite is part-way through its sequence. */
    boolean isActive() {
        return mCompositeActive;
    }

    /** One press. Ignored while a composite is already part-way through. */
    void fire(final String modeName, final String testTag) {
        if (mCompositeActive) {
            Log.w(TAG, "composite already running; ignoring the press");
            return;
        }
        Camera2Proxy proxy = mActivity.getmCamera2Proxy();
        if (proxy == null) {
            return;
        }
        File dir = mActivity.newCaptureDir("object");
        if (dir == null) {
            return;
        }
        mCompositeActive = true;
        // OBJECT runs its own session start to finish and never joins one, so its receipt is
        // local to the composite rather than the field the two continuous paths share.
        final SessionManifest manifest = SessionReceipts.open(mActivity, dir, modeName, testTag);
        manifest.noteStillsRequested();
        // The same notes sealSession() applies to the two continuous paths. OBJECT writes its
        // own manifest and therefore never got any of them: the 2026-09-20 M5 receipt claimed
        // zero stills fired against eleven on the card, reported thermal status -1 for a
        // composite that runs the sensor flat out for fifteen seconds, and carried no battery
        // reading at either end. A receipt for the heaviest capture in the app was the only
        // one that could not say what the capture cost.
        SessionReceipts.noteLensSet(manifest, proxy);
        // Counted as each stage is ISSUED rather than assumed from the recipe, so a composite
        // that is cut short reports what it actually asked for.
        final int[] fired = {0};
        RecordingWriter writer = mActivity.getsRecordingWriter();
        boolean owns = false;
        if (!writer.isRecording()) {
            try {
                writer.startRecording(new File(dir, "video_meta.pb3").getAbsolutePath());
                owns = true;
            } catch (IOException e) {
                Log.e(TAG, "could not open metadata file: " + e);
                // Not left set: nothing below will run to clear it, and a composite that
                // could not start would otherwise refuse every press after it.
                mCompositeActive = false;
                return;
            }
        }
        // OBJECT recorded no IMU and no GNSS at all — measured across every stack from
        // 07-31 and 08-01: imu=0, gnss=0 in each. startRun() begins those streams for WALK
        // and PANO and this path simply never did. A tripod composite still wants both: the
        // stereo pair's baseline is metric but its POSITION is not, the orientation stamped
        // on each still comes from a stream that was not being written, and a stack shot
        // beside a walk cannot be tied to it without a shared clock carrying shared motion.
        // ...and thermal, for the same reason and with the same history: an OBJECT composite is
        // a focus stack plus brackets plus a stereo pair, minutes of full-resolution work.
        mActivity.startSensorStreams(writer);
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
        mListener.onStage("OBJECT · focus stack");
        proxy.captureFocusStack(5, false, dir, writer);
        fired[0] += 5;

        // RAW ON EVERY BRACKET FRAME (0.24). The JPEGs cannot be merged into radiance: measured
        // by ReconStab on object_2026_10_21_11_32_49 (docs/notes-2026-10-22-stills-solve.md),
        // adjacent shots of a -2..+2 bracket disagree by up to 0.49 stops after the sRGB decode
        // -- the dark frames lifted, the bright ones pulled down, i.e. tone-mapped per frame --
        // and a JPEG-to-linear curve measured against this composite's own DNG does worse, with
        // 34% spread inside a single JPEG value, because the tone mapping is local. A DNG is
        // linear by construction, so a bracket of them merges with nothing to undo. Costs
        // ~125 MB per press.
        mMain.postDelayed(() -> {
            mListener.onStage("OBJECT · exposure bracket (RAW)");
            proxy.captureStills(StillCaptureManager.Mode.EXPOSURE_BRACKET, 5, 2.0f,
                    true, dir, writer);
            fired[0] += 5;
        }, 4000L);

        mMain.postDelayed(() -> {
            mListener.onStage("OBJECT · full-quality RAW");
            proxy.captureStills(StillCaptureManager.Mode.SINGLE, 1, 0f, true, dir, writer);
            fired[0] += 1;
        }, 7000L);

        // The stereo pair. Last, because it is the one stage whose value does not
        // degrade if the operator has already drifted — both frames are simultaneous,
        // so the 18.02 mm baseline between them holds regardless of what the hand did
        // before it. Everything else in this composite is monocular and therefore
        // scale-free; this is the stage that makes the capture metric.
        final boolean ownsWriter = owns;
        final boolean hasStereo = scm != null && scm.stereo().stereoSupported();
        // With every lens configured the stereo stage is a sequence of six pairs at ~1.15 s
        // each rather than one 2.2 s warm-up-and-fire, and the composite has to wait for it.
        final boolean multiLens = hasStereo && scm.stereo().getStereoSurfaces().size() > 2;
        if (hasStereo) {
            mMain.postDelayed(() -> {
                mListener.onStage(multiLens ? "OBJECT · lens pairs (metric scale + baselines)"
                        : "OBJECT · stereo pair (metric scale)");
                scm.stereo().resetOneShotBursts();
                proxy.captureStereoPair(dir, writer, StillCaptureManager.CaptureMode.OBJECT);
            }, 10000L);
        }

        mMain.postDelayed(() -> {
            proxy.lockAutoAlgorithms(false);
            // Stop the streams this composite started — but only if it owns the session.
            // If a video recording is running alongside, killing the IMU here would blind
            // it mid-clip.
            if (ownsWriter) {
                mActivity.stopSensorStreams();
                writer.stopRecording();
            }
            mCompositeActive = false;
            mListener.onStage(hasStereo ? "OBJECT complete + stereo" : "OBJECT complete");
            // What the stage actually issued: one burst on the metric pair, six on the
            // all-lens set, and fewer than that if the sequence was cut short -- which the
            // receipt should then disagree with.
            manifest.noteStereoPairs(hasStereo
                    ? Math.max(1, proxy.oneShotStereoBursts()) : 0);
            manifest.noteStereoMetaRows(proxy.stereoMetaRows());
            manifest.noteStillsFired(fired[0]);
            SessionReceipts.noteCost(manifest, mActivity);
            mMain.postDelayed(() -> manifest.write(writer.accounting()), 1200L);
            Log.i(TAG, "object composite complete: " + dir);
        }, multiLens ? 18500L : hasStereo ? 15500L : 10500L);   // stereo adds its warm-up(s)
    }
}
