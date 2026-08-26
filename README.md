# VideoIMUCapture-Android (a hobbyist's fork)

An Android capture instrument for 3D reconstruction work: video, IMU, and the rest of
the phone's sensor suite, all recorded on one clock. Modernized and extended in 2026
from [DavidGillsjo/VideoIMUCapture-Android](https://github.com/DavidGillsjo/VideoIMUCapture-Android)
(last released 2021).

## The spirit of this fork

This is a hobby instrument. I modernized and extended it because my own reconstruction
experiments needed a capture tool I could trust, and it's shared here in the same
spirit — for the fun of it, in case something in it saves you an afternoon.

So, said warmly and plainly: there is no warranty, no support contract, and no roadmap.
Everything below was measured carefully, but on exactly one phone (a Galaxy S24 Ultra
on Android 16) — your device will differ, especially in what its cameras expose. Take
whatever benefit you can from it; the [GPL-3.0 license](LICENSE) exists so you can.
If something here helps your project, that's the whole reward. Issues and field notes
are welcome, and fixes arrive on hobby time, if at all.

<img src="images/Capture.png" width="33%" border="1" ><img src="images/Settings.png" width="33%" border="1" ><img src="images/Warning_small.png" width="33%" border="1" >

## What it records

- **Video** at ~30 Hz to H.264/MP4 (full 4:3 sensor frame on the test device), with
  per-frame metadata — timestamps, ISO, exposure, focus distance, and per-frame
  `SCALER_CROP_REGION`, which doubles as an EIS detector.
- **IMU** requested at 200 Hz (the S24 Ultra's hardware caps at ~189), on the same
  clock as the frames where the device supports
  [`SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME`](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE).
- **The rest of the suite** (each optional; no permission = no stream): barometer,
  hardware step counter with a baseline row at record start, the rotation-vector
  family (tagged as OS-fused), and a 1 Hz GNSS track carrying `elapsedRealtimeNanos`
  so it joins the sensor clock.
- **Stills as an instrument**: WALK / OBJECT / PANO modes with a stillness-triggered
  shutter, full-resolution RAW+JPEG bursts, exposure brackets, focus stacks stepped by
  depth of field, a simultaneous ultrawide+main stereo pair (a metric baseline on
  devices whose factory calibration publishes `LENS_POSE_TRANSLATION`), torch control,
  and per-mode JPEG quality.
- **`camera_census.json`**, written locally on every launch: each camera's
  characteristics, including factory intrinsics, distortion, and lens pose where the
  vendor populates them.

Everything lands in `Android/data/se.lth.math.videoimucapture/files/<date>/` as
`video_recording.mp4` plus a protobuf sidecar (`video_meta.pb3`), stills alongside.
**Nothing leaves the device** — the upstream Firebase analytics were removed entirely.

## Changed from upstream, briefly

Builds on current tooling (Gradle 8.7, AGP 8.5.2, JDK 17, compileSdk/targetSdk 34,
protobuf 3.25). Proto changes are field-number additive, so tooling written for
upstream files still parses these. AE/AWB can lock during recording, and the OIS/DVS
warnings and settings from upstream remain.

One fix worth knowing about even if you stay on upstream: the original recorded
**stale IMU data** — the sensor queues filled from app launch but drained only during
recording, so a file could carry samples as old as the app session. Calibration tools
that fit a camera–IMU time offset absorb this silently. Fixed here; if you've built
VIO datasets with upstream builds after lingering on the preview screen, it's worth a
look at your time offsets.

## Install

No prebuilt APK at the moment — build from source: open `android_app/` in Android
Studio (JDK 17) and run it on your device. Upstream's released APK predates everything
described here.

## Reading the data

The schema is [`protobuf/recording.proto`](protobuf/recording.proto). Compile a Python
module with `protoc` and read `video_meta.pb3` directly:

```bash
protoc --python_out=<your_project_dir> protobuf/recording.proto
pip3 install protobuf pyquaternion
```

Upstream's [calibration guide](calibration/README.md) and example scripts (e.g.
[`data2statistics.py`](calibration/data2statistics.py)) still apply.

## Lineage and thanks

None of this would exist without the original work:
[DavidGillsjo/VideoIMUCapture-Android](https://github.com/DavidGillsjo/VideoIMUCapture-Android),
itself building on [mobile-ar-sensor-logger](https://github.com/OSUPCVLab/mobile-ar-sensor-logger)
and [grafika](https://github.com/google/grafika). GPL-3.0, as inherited.

## Feedback

Bugs, questions, or notes from your own device:
[open an issue here on the fork](https://github.com/SometimesYouNeverKnow/VideoIMUCapture-Android/issues) —
please not on upstream's tracker, they've earned their quiet.
