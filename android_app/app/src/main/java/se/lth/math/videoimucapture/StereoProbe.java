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
            JSONArray results = new JSONArray();
            for (int i = 0; i < physicals.size(); i++) {
                for (int j = i + 1; j < physicals.size(); j++) {
                    results.put(probePair(manager, device, physicals.get(i), physicals.get(j),
                            readers));
                }
            }
            o.put("pairs", results);
        } finally {
            device.close();
            for (ImageReader r : readers) {
                r.close();
            }
        }
        return o;
    }

    private static JSONObject probePair(CameraManager manager, CameraDevice device,
                                        String idA, String idB, List<ImageReader> readers)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("pair", idA + "+" + idB);
        String supportedAt = null;
        String lastError = null;

        for (Size size : CANDIDATE_SIZES) {
            // Both physicals must actually offer the size, or the answer is meaningless.
            if (!offersSize(manager, idA, size) || !offersSize(manager, idB, size)) {
                continue;
            }
            try {
                ImageReader ra = ImageReader.newInstance(
                        size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                ImageReader rb = ImageReader.newInstance(
                        size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
                readers.add(ra);
                readers.add(rb);

                OutputConfiguration ca = new OutputConfiguration(ra.getSurface());
                ca.setPhysicalCameraId(idA);
                OutputConfiguration cb = new OutputConfiguration(rb.getSurface());
                cb.setPhysicalCameraId(idB);

                SessionConfiguration config = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(ca, cb),
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
                    break;
                }
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
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
