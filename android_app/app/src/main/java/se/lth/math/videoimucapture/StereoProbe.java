package se.lth.math.videoimucapture;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Answers one question: can this device stream TWO PHYSICAL LENSES AT ONCE?
 *
 * Why it matters here: the ultrawide publishes LENS_POSE_TRANSLATION = 18.02 mm from the
 * main camera on the SM-S928U, and both publish factory intrinsics. A simultaneous pair
 * across a known baseline is a metric-scale stereo rig — it fixes scale from a single
 * capture, which photogrammetry from a monocular walk cannot do without external control.
 *
 * getConcurrentCameraIds() only offers rear+front on this device, so two independent
 * sessions are out. The remaining route is the Android logical-multi-camera mechanism:
 * one session on the logical camera, with individual OutputConfigurations bound to
 * physical ids via setPhysicalCameraId(). This probe enumerates candidate pairs and asks
 * CameraDevice.isSessionConfigurationSupported() about each, which answers without
 * committing to a session.
 *
 * Triggered by an intent extra rather than UI, so it can be run over adb:
 *   adb shell am start -n se.lth.math.videoimucapture/.CameraCaptureActivity \
 *       --ez run_stereo_probe true
 * Result lands in the app files dir as stereo_probe.json.
 */
public class StereoProbe {
    private static final String TAG = "StereoProbe";
    public static final String PROBE_FILE = "stereo_probe.json";
    public static final String EXTRA_RUN = "run_stereo_probe";

    /** Candidate stream sizes, largest first — a pair that fails big may still pass small. */
    private static final Size[] CANDIDATE_SIZES = {
            new Size(1920, 1080),
            new Size(1280, 720),
            new Size(640, 480),
    };

    public static void run(Context context) {
        JSONObject root = new JSONObject();
        HandlerThread thread = new HandlerThread("StereoProbe");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            root.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            root.put("android_sdk", Build.VERSION.SDK_INT);

            if (Build.VERSION.SDK_INT < 29) {
                root.put("error", "isSessionConfigurationSupported needs API 29+");
            } else {
                JSONArray logicals = new JSONArray();
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                    List<String> physicals = new ArrayList<>(ch.getPhysicalCameraIds());
                    if (physicals.size() < 2) {
                        continue;
                    }
                    logicals.put(probeLogical(manager, id, ch, physicals, handler));
                }
                root.put("logical_cameras", logicals);
            }
        } catch (Exception e) {
            Log.e(TAG, "probe failed: " + e);
            try {
                root.put("exception", String.valueOf(e));
            } catch (Exception ignored) {
            }
        } finally {
            thread.quitSafely();
        }
        write(context, root);
    }

    private static JSONObject probeLogical(CameraManager manager, String logicalId,
                                           CameraCharacteristics ch, List<String> physicals,
                                           Handler handler) throws Exception {
        JSONObject o = new JSONObject();
        o.put("logical_id", logicalId);
        o.put("physical_ids", new JSONArray(physicals));

        // Which request keys may be set PER PHYSICAL camera — if exposure/sensitivity are
        // here, the two streams can be driven independently, not just co-exposed.
        if (Build.VERSION.SDK_INT >= 28) {
            JSONArray keys = new JSONArray();
            for (android.hardware.camera2.CaptureRequest.Key<?> k
                    : ch.getAvailablePhysicalCameraRequestKeys()) {
                keys.put(k.getName());
            }
            o.put("available_physical_request_keys", keys);
        }

        CameraDevice device = openCamera(manager, logicalId, handler);
        if (device == null) {
            o.put("error", "could not open logical camera (in use?)");
            return o;
        }
        List<ImageReader> readers = new ArrayList<>();
        try {
            // Arities 2..N. Pairs answer "can we do stereo"; triples and quads answer
            // "can we do stereo TWICE AT ONCE", which is the only way a single shutter
            // produces a measurement and an independent check of it.
            JSONArray results = new JSONArray();
            JSONObject byArity = new JSONObject();
            for (int k = 2; k <= physicals.size(); k++) {
                int supported = 0;
                int tried = 0;
                for (List<String> combo : combinations(physicals, k)) {
                    JSONObject r = probeCombo(manager, device, combo);
                    results.put(r);
                    tried++;
                    if (r.optBoolean("supported")) {
                        supported++;
                    }
                }
                byArity.put(String.valueOf(k), supported + "/" + tried + " supported");
            }
            o.put("combos", results);
            o.put("combos_by_arity", byArity);
            o.put("realistic_combinations",
                    probeRealistic(manager, device, logicalId, physicals, readers));
        } finally {
            device.close();
            for (ImageReader r : readers) {
                r.close();
            }
        }
        return o;
    }

    /**
     * The pair test above opens a session containing ONLY the two physical streams,
     * which is not the session the app actually runs. This asks whether a dual-lens
     * capture can coexist with the preview and stills already in flight — because if it
     * cannot, the stereo shot needs its own session and a preview teardown, which is a
     * very different piece of work.
     *
     * Tested largest-first: whichever configuration survives determines the design.
     */
    private static JSONArray probeRealistic(CameraManager manager, CameraDevice device,
                                            String logicalId, List<String> physicals,
                                            List<ImageReader> readers) throws Exception {
        JSONArray out = new JSONArray();

        // Ordered so the calibrated pair leads: 2+5 is the only pair with a published
        // LENS_POSE_TRANSLATION, so any larger set should contain it — a third and fourth lens
        // are only worth having if the metric one is still in the shot to tie them to.
        List<String> ordered = new ArrayList<>();
        for (String pref : new String[]{"2", "5", "6", "7"}) {
            if (physicals.contains(pref)) {
                ordered.add(pref);
            }
        }
        for (String p : physicals) {
            if (!ordered.contains(p)) {
                ordered.add(p);
            }
        }
        if (ordered.size() < 2) {
            return out;
        }

        StreamConfigurationMap map = manager.getCameraCharacteristics(logicalId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return out;
        }
        Size maxJpeg = largest(map.getOutputSizes(ImageFormat.JPEG));
        Size maxRaw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        Size preview = new Size(1920, 1080);
        Size stereo = new Size(1920, 1080);

        String[][] shapes = {
                {"preview+jpeg+raw", "P", "J", "R", "S"},
                {"preview+jpeg", "P", "J", "S"},
                {"preview", "P", "S"},
                {"jpeg", "J", "S"},
        };
        // Arity 2 is the session the app runs today. 3 and 4 are the question: a full-res JPEG
        // and a RAW alongside FOUR physical streams is seven surfaces, and the guaranteed
        // stream combinations run out long before that.
        for (int nPhys = 2; nPhys <= ordered.size(); nPhys++) {
            List<String> chosen = new ArrayList<>(ordered.subList(0, nPhys));
            for (String[] shape : shapes) {
                List<OutputConfiguration> configs = new ArrayList<>();
                List<ImageReader> local = new ArrayList<>();
                String label = shape[0] + "+" + nPhys + "physical";
                try {
                    for (int i = 1; i < shape.length; i++) {
                        switch (shape[i]) {
                            case "P": {
                                ImageReader r = ImageReader.newInstance(preview.getWidth(),
                                        preview.getHeight(), ImageFormat.YUV_420_888, 2);
                                local.add(r);
                                configs.add(new OutputConfiguration(r.getSurface()));
                                break;
                            }
                            case "J": {
                                if (maxJpeg == null) continue;
                                ImageReader r = ImageReader.newInstance(maxJpeg.getWidth(),
                                        maxJpeg.getHeight(), ImageFormat.JPEG, 2);
                                local.add(r);
                                configs.add(new OutputConfiguration(r.getSurface()));
                                break;
                            }
                            case "R": {
                                if (maxRaw == null) continue;
                                ImageReader r = ImageReader.newInstance(maxRaw.getWidth(),
                                        maxRaw.getHeight(), ImageFormat.RAW_SENSOR, 2);
                                local.add(r);
                                configs.add(new OutputConfiguration(r.getSurface()));
                                break;
                            }
                            case "S": {
                                for (String pid : chosen) {
                                    ImageReader r = ImageReader.newInstance(stereo.getWidth(),
                                            stereo.getHeight(), ImageFormat.YUV_420_888, 2);
                                    local.add(r);
                                    OutputConfiguration oc =
                                            new OutputConfiguration(r.getSurface());
                                    oc.setPhysicalCameraId(pid);
                                    configs.add(oc);
                                }
                                break;
                            }
                        }
                    }
                    SessionConfiguration sc = new SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR, configs, Runnable::run,
                            new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(
                                        @NonNull android.hardware.camera2.CameraCaptureSession s) {
                                }

                                @Override
                                public void onConfigureFailed(
                                        @NonNull android.hardware.camera2.CameraCaptureSession s) {
                                }
                            });
                    JSONObject r = new JSONObject();
                    r.put("combo", label);
                    r.put("physicals", TextUtils.join("+", chosen));
                    r.put("streams", configs.size());
                    r.put("supported", device.isSessionConfigurationSupported(sc));
                    out.put(r);
                } catch (IllegalArgumentException | UnsupportedOperationException e) {
                    JSONObject r = new JSONObject();
                    r.put("combo", label);
                    r.put("physicals", TextUtils.join("+", chosen));
                    r.put("supported", false);
                    r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    out.put(r);
                } finally {
                    // A full-res RAW reader is ~25 MB; twelve of these combinations held open
                    // at once would fail the later ones for reasons that are not the camera's.
                    for (ImageReader r : local) {
                        r.close();
                    }
                }
            }
        }
        return out;
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

    /**
     * Ask whether N physical streams configure simultaneously, for any N.
     *
     * Generalised from the pairs-only version 2026-08-02. The question is whether this
     * device can give more than two lenses at one shutter, because a SECOND baseline is
     * what turns a stereo measurement into a checkable one: this device publishes
     * LENS_POSE_TRANSLATION for the ultrawide alone ([0, 0.018018510, 0] — 18.02 mm on Y
     * and nothing else), so every other pair is scale-free until it is calibrated against
     * that one. Two simultaneous pairs would let each capture check itself.
     *
     * Note the readers are closed PER COMBINATION rather than accumulated. With 6 pairs,
     * 4 triples and a quad, each retried across several candidate sizes, holding every
     * ImageReader open to the end would run to hundreds of megabytes of buffers and could
     * fail the later, larger combinations for reasons that have nothing to do with the
     * camera. isSessionConfigurationSupported() is synchronous and does not retain the
     * surfaces, so releasing them immediately is safe.
     */
    private static JSONObject probeCombo(CameraManager manager, CameraDevice device,
                                         List<String> ids) throws Exception {
        JSONObject o = new JSONObject();
        o.put("combo", TextUtils.join("+", ids));
        o.put("n_lenses", ids.size());
        String supportedAt = null;
        String lastError = null;

        for (Size size : CANDIDATE_SIZES) {
            // Every physical must actually offer the size, or the answer is meaningless.
            boolean allOffer = true;
            for (String id : ids) {
                if (!offersSize(manager, id, size)) {
                    allOffer = false;
                    break;
                }
            }
            if (!allOffer) {
                continue;
            }
            List<ImageReader> local = new ArrayList<>();
            try {
                List<OutputConfiguration> configs = new ArrayList<>();
                for (String id : ids) {
                    ImageReader r = ImageReader.newInstance(
                            size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                    local.add(r);
                    OutputConfiguration c = new OutputConfiguration(r.getSurface());
                    c.setPhysicalCameraId(id);
                    configs.add(c);
                }

                SessionConfiguration config = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        configs,
                        Runnable::run,
                        new android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(
                                    @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull android.hardware.camera2.CameraCaptureSession s) {
                            }
                        });

                if (device.isSessionConfigurationSupported(config)) {
                    supportedAt = size.toString();
                }
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                for (ImageReader r : local) {
                    r.close();
                }
            }
            if (supportedAt != null) {
                break;
            }
        }
        o.put("supported", supportedAt != null);
        if (supportedAt != null) {
            o.put("largest_supported_size", supportedAt);
        }
        if (lastError != null) {
            o.put("last_error", lastError);
        }
        return o;
    }

    /** Every combination of {@code k} ids drawn from {@code ids}, in stable order. */
    private static List<List<String>> combinations(List<String> ids, int k) {
        List<List<String>> out = new ArrayList<>();
        int n = ids.size();
        if (k > n || k <= 0) {
            return out;
        }
        int[] idx = new int[k];
        for (int i = 0; i < k; i++) {
            idx[i] = i;
        }
        while (true) {
            List<String> combo = new ArrayList<>();
            for (int i : idx) {
                combo.add(ids.get(i));
            }
            out.add(combo);
            int i = k - 1;
            while (i >= 0 && idx[i] == n - k + i) {
                i--;
            }
            if (i < 0) {
                return out;
            }
            idx[i]++;
            for (int j = i + 1; j < k; j++) {
                idx[j] = idx[j - 1] + 1;
            }
        }
    }

    private static boolean offersSize(CameraManager manager, String id, Size size) {
        try {
            StreamConfigurationMap map = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return false;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            return sizes != null && Arrays.asList(sizes).contains(size);
        } catch (CameraAccessException e) {
            return false;
        }
    }

    private static CameraDevice openCamera(CameraManager manager, String id, Handler handler) {
        final CameraDevice[] out = new CameraDevice[1];
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    out[0] = camera;
                    latch.countDown();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    latch.countDown();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "open error " + error + " on " + id);
                    camera.close();
                    latch.countDown();
                }
            }, handler);
            latch.await(5, TimeUnit.SECONDS);
        } catch (CameraAccessException | SecurityException | InterruptedException e) {
            Log.e(TAG, "openCamera failed: " + e);
        }
        return out[0];
    }

    private static void write(Context context, JSONObject root) {
        try {
            File out = new File(context.getExternalFilesDir(null), PROBE_FILE);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "stereo probe written to " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "could not write probe result: " + e);
        }
    }
}
