package se.lth.math.videoimucapture;

import android.graphics.Rect;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Build;

/** The parts of a StillMetaData row that a still and a stereo half fill in the same way. */
final class StillRows {

    private StillRows() {
    }

    /**
     * Record the readout rectangle and zoom for one shot.
     *
     * @param per    this lens's own result where the device supplies one, else the logical
     *               result — the crop is per-sensor, so the physical result is the one that
     *               answers the question.
     * @param outer  the logical result, which is where CONTROL_ZOOM_RATIO lives.
     */
    static void recordCrop(RecordingProtos.StillMetaData.Builder b, CaptureResult per,
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

    /**
     * This lens's focal and distortion at this shot, as the HAL reports them. A lens's focal
     * moves with its focus, so this row, beside focus_distance_diopters, is one point on that
     * lens's focus law.
     *
     * @param per this lens's own result where the device supplies one, else the logical result.
     */
    static void recordIntrinsics(RecordingProtos.StillMetaData.Builder b, CaptureResult per) {
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        float[] k = per.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
        if (k != null) {
            for (float v : k) {
                b.addLensIntrinsicCalibration(v);
            }
        }
        float[] d = per.get(CaptureResult.LENS_DISTORTION);
        if (d != null) {
            for (float v : d) {
                b.addLensDistortion(v);
            }
        }
    }

    /** The orientation at the shutter instant, which is what lets a pan be stitched from angles. */
    static void addOrientation(RecordingProtos.StillMetaData.Builder b, IMUManager imu) {
        if (imu == null) {
            return;
        }
        float[] q = imu.getLatestOrientation();
        if (q != null) {
            for (float v : q) {
                b.addOrientationQuaternion(v);
            }
            b.setOrientationTimeNs(imu.getLatestOrientationTimeNs());
        }
    }
}
