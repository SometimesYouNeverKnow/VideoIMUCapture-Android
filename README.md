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
- **Heat** (v0.14): battery temperature, Android's thermal status and thermal headroom,
  every five seconds while recording, on the sensor clock — because the phone cooks
  after a minute or two of recording and the barometer drifts with it, and until now
  the file could not say how hot it was when that happened.
- **More of the sensor suite** (v0.15): ambient light (lux) and, where the device has a
  vendor colour-temperature sensor, CCT and wide-IR; raw GNSS measurements (per-satellite
  pseudorange rate, carrier phase, C/N0, dual-frequency) and constellation status; a full
  `SensorManager` inventory in the census; and per-frame camera radiometry (white-balance
  gains and colour transform, tonemap mode, dynamic black level, post-RAW boost, AE/AWB
  state, lens state and aperture, noise profile). The file records what the phone offers,
  not only what an earlier build thought to take.
- **Blur budget** (v0.15, experimental, default off): caps exposure from the gyro so motion
  smear stays under a pixel budget — letting ISO rise instead of the shutter opening — via the
  AE target-FPS range, and shows HOLD STILL when you are moving too fast for a sharp frame at
  the current focal length. Needs *Freeze exposure* off. Never sets a manual exposure, so it
  cannot black out a frame; but it is untested on a moving device — verify before trusting it.

Everything lands in `Android/data/se.lth.math.videoimucapture/files/<date>/` as
`video_recording.mp4` plus a protobuf sidecar (`video_meta.pb3`), stills alongside.
**Nothing leaves the device** — the upstream Firebase analytics were removed entirely.

## Changed from upstream, briefly

Builds on current tooling (Gradle 8.7, AGP 8.5.2, JDK 17, compileSdk/targetSdk 34,
protobuf 3.25). Proto changes are field-number additive, so tooling written for
upstream files still parses these. AE/AWB can lock during recording, and the OIS/DVS
warnings and settings from upstream remain.

v0.14 added four settings, all aimed at the two things that stop a long capture — heat
and disk: **Video codec** (H.265/HEVC is 40–50% smaller than H.264 at the same quality
and keeps more feature matches at a given bitrate; it falls back to H.264 if the device
has no HEVC encoder), **Video bitrate** (0 = automatic: the BPP formula for H.264, 0.55× of it
for HEVC — measured on the S24U as 94 vs 52 Mbit/s at the full sensor), **Camera sleep** (after
N idle seconds the preview stops and the sensor goes quiet instead of cooking the phone
while it waits; tap to wake), and **Freeze exposure while recording** (on = the old
behaviour, one radiometry per clip; off = auto exposure keeps running through a scene
whose light changes every few steps, with every frame's exposure and ISO recorded).

One fix worth knowing about even if you stay on upstream: the original recorded
**stale IMU data** — the sensor queues filled from app launch but drained only during
recording, so a file could carry samples as old as the app session. Calibration tools
that fit a camera–IMU time offset absorb this silently. Fixed here; if you've built
VIO datasets with upstream builds after lingering on the preview screen, it's worth a
look at your time offsets.

## Install

Grab the APK from [Releases](https://github.com/SometimesYouNeverKnow/VideoIMUCapture-Android/releases)
and sideload it. If upstream's app (or a debug build) is already installed, uninstall it
first — the signing keys differ and Android refuses cross-key upgrades. Or build from
source: open `android_app/` in Android Studio (JDK 17) and run it on your device.
Upstream's released APK predates everything described here.

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
