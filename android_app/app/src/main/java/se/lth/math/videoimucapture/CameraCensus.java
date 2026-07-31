package se.lth.math.videoimucapture;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.SizeF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Dumps every camera's CameraCharacteristics to a JSON file — a per-device sensor census.
 *
 * This replaces the old Firebase logAnalyticsConfig(): the same enumeration, but written
 * where the operator can read it instead of someone else's analytics console. The output
 * answers, per camera id: which stabilization modes exist, whether OIS sample data is
 * available, whether intrinsics/distortion/lens pose are factory-populated, the focus
 * distance calibration tier, the timestamp source (the sync gate for IMU fusion), and
 * whether any camera exposes a depth output.
 */
public class CameraCensus {
    private static final String TAG = "CameraCensus";
    public static final String CENSUS_FILE = "camera_census.json";

    public static void writeCensus(Context context) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return;
            }
            JSONObject root = new JSONObject();
            root.put("build_manufacturer", Build.MANUFACTURER);
            root.put("build_model", Build.MODEL);
            root.put("build_device", Build.DEVICE);
            root.put("android_sdk", Build.VERSION.SDK_INT);
            root.put("android_release", Build.VERSION.RELEASE);
            root.put("build_fingerprint", Build.FINGERPRINT);
            root.put("app_version", appVersion(context));

            JSONObject cameras = new JSONObject();
            for (String id : manager.getCameraIdList()) {
                cameras.put(id, cameraJson(manager.getCameraCharacteristics(id)));
            }
            root.put("cameras", cameras);

            File out = new File(context.getExternalFilesDir(null), CENSUS_FILE);
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Camera census written to " + out.getAbsolutePath());
        } catch (Exception e) {
            // The census is diagnostics, never worth crashing a capture app over.
            Log.e(TAG, "Failed to write camera census: " + e);
        }
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static JSONObject cameraJson(CameraCharacteristics ch) throws JSONException {
        JSONObject o = new JSONObject();

        putValue(o, "LENS_FACING", ch.get(CameraCharacteristics.LENS_FACING));
        putValue(o, "INFO_SUPPORTED_HARDWARE_LEVEL",
                ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
        putArray(o, "REQUEST_AVAILABLE_CAPABILITIES",
                ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES));

        // Optics.
        putArray(o, "LENS_INFO_AVAILABLE_FOCAL_LENGTHS",
                ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS));
        putArray(o, "LENS_INFO_AVAILABLE_APERTURES",
                ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES));
        SizeF physicalSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (physicalSize != null) {
            o.put("SENSOR_INFO_PHYSICAL_SIZE", physicalSize.toString());
        }
        Rect active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active != null) {
            o.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE", active.flattenToString());
        }
        Rect preCorrection = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (preCorrection != null) {
            o.put("SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE", preCorrection.flattenToString());
        }
        putValue(o, "SENSOR_ORIENTATION", ch.get(CameraCharacteristics.SENSOR_ORIENTATION));

        // Stabilization.
        putArray(o, "LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION",
                ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
        putArray(o, "CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES",
                ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES));

        // Calibration: the fields that decide whether this device hands us geometry for free.
        putValue(o, "LENS_INFO_FOCUS_DISTANCE_CALIBRATION",
                ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION));
        putValue(o, "LENS_INFO_MINIMUM_FOCUS_DISTANCE",
                ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));
        putArray(o, "LENS_INTRINSIC_CALIBRATION",
                ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION));
        putArray(o, "LENS_RADIAL_DISTORTION",
                ch.get(CameraCharacteristics.LENS_RADIAL_DISTORTION));
        // The sync gate: REALTIME means camera and IMU share a clock.
        putValue(o, "SENSOR_INFO_TIMESTAMP_SOURCE",
                ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));

        if (Build.VERSION.SDK_INT >= 23) {
            putArray(o, "LENS_DISTORTION", ch.get(CameraCharacteristics.LENS_DISTORTION));
        }

        if (Build.VERSION.SDK_INT >= 28) {
            putArray(o, "STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES",
                    ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES));
            putValue(o, "LENS_POSE_REFERENCE", ch.get(CameraCharacteristics.LENS_POSE_REFERENCE));
            putArray(o, "LENS_POSE_TRANSLATION", ch.get(CameraCharacteristics.LENS_POSE_TRANSLATION));
            putArray(o, "LENS_POSE_ROTATION", ch.get(CameraCharacteristics.LENS_POSE_ROTATION));

            // Logical multi-camera: which physical lenses hide behind this id.
            JSONArray physical = new JSONArray();
            for (String pid : ch.getPhysicalCameraIds()) {
                physical.put(pid);
            }
            if (physical.length() > 0) {
                o.put("physical_camera_ids", physical);
            }
        }

        // AE/AF/fps capabilities relevant to capture planning.
        putArray(o, "CONTROL_AF_AVAILABLE_MODES",
                ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES));
        Range<Integer>[] fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fpsRanges != null) {
            o.put("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES", Arrays.toString(fpsRanges));
        }

        return o;
    }

    private static void putValue(JSONObject o, String key, Object value) throws JSONException {
        if (value != null) {
            o.put(key, value);
        }
    }

    private static void putArray(JSONObject o, String key, Object array) throws JSONException {
        if (array == null) {
            return;
        }
        if (array instanceof int[]) {
            o.put(key, new JSONArray(Arrays.toString((int[]) array)));
        } else if (array instanceof float[]) {
            JSONArray ja = new JSONArray();
            for (float v : (float[]) array) {
                ja.put((double) v);
            }
            o.put(key, ja);
        } else {
            o.put(key, array.toString());
        }
    }
}
