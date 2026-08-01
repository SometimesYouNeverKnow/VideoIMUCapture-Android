/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.lth.math.videoimucapture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 * recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 * app lifecycle changes.  In particular, we need to release and reacquire the Camera
 * so that, if the user switches away from us, we're not preventing another app from
 * using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 * SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 * Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 * thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 * the Camera preview external texture with the GLSurfaceView renderer, which means the
 * EGLContext in this thread must be created with a reference to the renderer thread's
 * context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 * is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 * thread startup/shutdown, though we could generate messages from the Activity for most
 * of these things.  The EGLContext created on this thread must be shared with the
 * video encoder, and must be used to create a SurfaceTexture that is used by the
 * Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 * updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 * which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends AppCompatActivity {
    public static final String TAG = "VIMUC-Main";
    public static final String FRAGMENT_TOOLBAR_TAG = "CaptureToolbar";
    public static final String FRAGMENT_MAIN_TAG = "CaptureWindow";
    private static final boolean VERBOSE = false;

    private Camera2Proxy mCamera2Proxy = null;
    // Diagnostics-only launch: no preview is created, so the probe has the camera to itself.
    private boolean mProbeMode = false;
    private CameraHandler mCameraHandler;
    private CameraCaptureFragment mCameraCaptureFragment = null;
    private PermissionRationaleFragment mPermissionFragment = null;
    private CameraSettingsManager mCameraSettingsManager;

    // this is static so it survives activity restarts
    private static TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();
    private static IMUManager mImuManager;
    private static GnssLogger mGnssLogger;
    private static RecordingWriter sRecordingWriter = new RecordingWriter();
    private CaptureModeManager mCaptureModeManager;

    public CameraSettingsManager getmCameraSettingsManager() {
        return mCameraSettingsManager;
    }
    public CameraCaptureFragment getmCameraCaptureFragment() {
        return mCameraCaptureFragment;
    }
    public TextureMovieEncoder getsVideoEncoder() {
        return sVideoEncoder;
    }
    public RecordingWriter getsRecordingWriter() {
        return sRecordingWriter;
    }
    public CameraHandler getmCameraHandler() {
        return mCameraHandler;
    }
    public IMUManager getmImuManager() {
        return mImuManager;
    }
    public GnssLogger getmGnssLogger() {
        return mGnssLogger;
    }
    public CaptureModeManager getmCaptureModeManager() {
        return mCaptureModeManager;
    }
    public Camera2Proxy getmCamera2Proxy() {
        return mCamera2Proxy;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        // Decided before any fragment exists: in probe mode nothing else may hold the camera.
        mProbeMode = getIntent().getBooleanExtra(StereoProbe.EXTRA_RUN, false);

        // Define a handler that receives camera-control messages from other threads.  All calls
        // to Camera must be made on the same thread.  Note we create this before the renderer
        // thread, so we know the fully-constructed object will be visible.
        mCameraHandler = new CameraHandler(this);
        mCameraSettingsManager = new CameraSettingsManager(this);

        mImuManager = new IMUManager(this);
        mGnssLogger = new GnssLogger(this);
        mCaptureModeManager = new CaptureModeManager(this);

        if (savedInstanceState == null) {
            ToolBarFragment fragment = new ToolBarFragment();
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.main_content, fragment, FRAGMENT_TOOLBAR_TAG)
                    .commit();
            if (PermissionHelper.hasCameraPermission(this) && !mProbeMode) {
                createCameraCaptureFragment();
            }
        }
        Log.d(TAG, "onCreate complete: " + this);
    }

    public static class ToolBarFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            return inflater.inflate(R.layout.capture_toolbar_fragment, container, false);
        }
    }

    public static class PermissionRationaleFragment extends Fragment {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.permission_fragment, container, false);

            rootView.findViewById(R.id.grant_permission_button).setOnClickListener(this::onGrantClick);
            rootView.findViewById(R.id.exit_permission_button).setOnClickListener(this::onExitClick);
            return rootView;
        }

        private void onGrantClick(View unused) {
            PermissionHelper.requestCameraPermission(getActivity());
        }

        private void onExitClick(View unused) {
            getActivity().finish();
        }
    }

    private void setSettingsEnabled(Boolean enable) {
        MaterialToolbar bar = findViewById(R.id.topAppBar);
        bar.getMenu().findItem(R.id.settings).setEnabled(enable);
    }

    private void  createCameraCaptureFragment() {
        CameraCaptureFragment fragment = new CameraCaptureFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .add(R.id.main_content, fragment, FRAGMENT_MAIN_TAG)
                .commit();
    }

    private void  createPermissionFragment() {
        PermissionRationaleFragment fragment = new PermissionRationaleFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .add(R.id.main_content, fragment, FRAGMENT_MAIN_TAG)
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();

        /*This block is only used if we did not have camera permission in onCreate
        If we don't have permission and haven't asked the user before, simply ask.
        If we asked previously but got denied, show permission fragment with information.
        If user accepts, the onResume will be called again (app pause during prompt)
        and we need to create the capture fragment.
         */
        if (!PermissionHelper.hasCameraPermission(this)) {
            // We don't have permission to use camera
            if (PermissionHelper.mustShowRationale(this)) {
                setSettingsEnabled(false);
                createPermissionFragment();
            }
            else {
                PermissionHelper.requestCameraPermission(this);
            }
        } else if (mCameraCaptureFragment == null && !mProbeMode) {
            // We now have permission, but have no capture fragment yet.
            if (mPermissionFragment != null) {
                getSupportFragmentManager().beginTransaction().remove(mPermissionFragment).commit();
            }
            setSettingsEnabled(true);
            createCameraCaptureFragment();
        }

        mImuManager.register();
        mGnssLogger.register(this);

        // Census AFTER the camera permission exists, not in onCreate. Without the
        // permission the framework returns a REDUCED CameraCharacteristics set —
        // LENS_INTRINSIC_CALIBRATION, LENS_DISTORTION and LENS_POSE_TRANSLATION are all
        // absent — and a census written then reports "device does not publish factory
        // calibration" when in fact it does. Measured on SM-S928U 2026-07-31.
        if (PermissionHelper.hasCameraPermission(this)) {
            final Context appContext = getApplicationContext();
            new Thread(() -> CameraCensus.writeCensus(appContext), "CameraCensus").start();
            maybeRunStereoProbe();
            maybeCaptureStillsFromIntent();
        }
        Log.d(TAG, "onResume complete: " + this);
    }

    /**
     * Diagnostics entry point driven by an intent extra rather than UI, so it can be run
     * over adb. The probe needs exclusive access to the logical camera, so the preview
     * camera is released first and reacquired afterwards.
     */
    private void maybeRunStereoProbe() {
        if (!mProbeMode) {
            return;
        }
        mProbeMode = false;
        getIntent().removeExtra(StereoProbe.EXTRA_RUN);
        Log.i(TAG, "running stereo probe (no preview created this launch)");
        releaseCamera();
        final Context appContext = getApplicationContext();
        new Thread(() -> StereoProbe.run(appContext), "StereoProbe").start();
    }

    public void initializeCamera() {
        Log.d(TAG, "acquiring camera");
        if (mCamera2Proxy == null) {
            mCamera2Proxy = new Camera2Proxy(this, mCameraSettingsManager);
            Size previewSize =
                    mCamera2Proxy.configureCamera();
            mCameraCaptureFragment.setLayoutAspectRatio(previewSize);
        }
    }

    public void releaseCamera() {
        Log.d(TAG, "releasing camera");
        if (mCamera2Proxy != null) {
            mCamera2Proxy.releaseCamera();
            mCamera2Proxy = null;
        }
    }

    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        // no more frame metadata will be saved during pause


        mImuManager.unregister();
        mGnssLogger.unregister();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        mCameraHandler.invalidateHandler();     // paranoia
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (fragment instanceof CameraCaptureFragment) {
            mCameraCaptureFragment = (CameraCaptureFragment) fragment;
        } else if (fragment instanceof PermissionRationaleFragment) {
            mPermissionFragment = (PermissionRationaleFragment) fragment;
        }
    }

    public void navigateToSettings(MenuItem unused) {
        Fragment fragment = new CaptureSettings();

        getSupportFragmentManager().beginTransaction()
                .addToBackStack(CaptureSettings.TAG)
                .replace(R.id.main_content, fragment, CaptureSettings.TAG)
                .commit();
    }

    public void displayInfo(MenuItem unused) {
        Bundle args = new Bundle();
        args.putInt("title", R.string.info_dialog_title);
        args.putString("message", String.format(
                getResources().getString(R.string.info_dialog_message),
                getResultRoot()
        ));

        InfoDialogFragment newFragment = new InfoDialogFragment();
        newFragment.setArguments(args);
        newFragment.show(getSupportFragmentManager(), "info");
    }


    public String getResultRoot() {
        return getExternalFilesDir(null).getAbsolutePath();
    }

    /**
     * Fire a still burst. Stills land in their own timestamped directory alongside video
     * recordings, with a video_meta.pb3 carrying one StillMetaData row per shot, so a
     * tripod session is described exactly like a video session.
     *
     * Exposed as an intent extra so it can be driven over adb without UI:
     *   adb shell am start -n se.lth.math.videoimucapture/.CameraCaptureActivity \
     *       --es still_mode exposure --ei still_shots 5 --ef still_stops 2.0 --ez still_raw true
     */
    public File newCaptureDir(String prefix) {
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", java.util.Locale.US);
        File dir = new File(getResultRoot(), prefix + "_" + fmt.format(new java.util.Date()));
        if (!dir.mkdirs() && !dir.isDirectory()) {
            Log.e(TAG, "could not create " + dir);
            return null;
        }
        return dir;
    }

    public void captureStills(StillCaptureManager.Mode mode, int shots, float stops,
                              boolean writeRaw) {
        if (mCamera2Proxy == null) {
            Log.w(TAG, "captureStills with no camera");
            return;
        }
        File dir = newCaptureDir("stills");
        if (dir == null) {
            return;
        }
        RecordingWriter writer = sRecordingWriter;
        boolean startedForStills = false;
        if (!writer.isRecording()) {
            try {
                writer.startRecording(new File(dir, "video_meta.pb3").getAbsolutePath());
                startedForStills = true;
            } catch (IOException e) {
                Log.e(TAG, "could not open still metadata file: " + e);
                return;
            }
        }
        mCamera2Proxy.captureStills(mode, shots, stops, writeRaw, dir, writer);

        if (startedForStills) {
            // Close the metadata file once the burst has drained. The delay is generous:
            // a RAW burst writes tens of MB per shot.
            final RecordingWriter w = writer;
            new Handler(getMainLooper()).postDelayed(w::stopRecording, 2000L + 1500L * shots);
        }
        Log.i(TAG, "still burst " + mode + " x" + shots + " -> " + dir);
    }

    private void maybeCaptureStillsFromIntent() {
        String mode = getIntent().getStringExtra("still_mode");
        if (mode == null) {
            return;
        }
        getIntent().removeExtra("still_mode");
        final StillCaptureManager.Mode m;
        switch (mode) {
            case "exposure":
                m = StillCaptureManager.Mode.EXPOSURE_BRACKET;
                break;
            case "focus":
                m = StillCaptureManager.Mode.FOCUS_STACK;
                break;
            default:
                m = StillCaptureManager.Mode.SINGLE;
                break;
        }
        final int shots = getIntent().getIntExtra("still_shots", 1);
        final float stops = getIntent().getFloatExtra("still_stops", 2.0f);
        final boolean raw = getIntent().getBooleanExtra("still_raw", true);
        final int jpegQuality = getIntent().getIntExtra("still_jpeg_quality", 0);

        // Wait for the camera to actually exist rather than guessing a delay: an adb-driven
        // launch onto a sleeping screen pauses the activity and releases the camera, so a
        // fixed timer fires into nothing. Poll, with a ceiling.
        final Handler handler = new Handler(getMainLooper());
        final int[] attempts = {0};
        final Runnable tryCapture = new Runnable() {
            @Override
            public void run() {
                if (mCamera2Proxy != null) {
                    if (jpegQuality > 0 && mCamera2Proxy.getStillCaptureManager() != null) {
                        mCamera2Proxy.getStillCaptureManager().setJpegQuality(jpegQuality);
                    }
                    captureStills(m, shots, stops, raw);
                } else if (++attempts[0] < 40) {
                    handler.postDelayed(this, 500L);
                } else {
                    Log.w(TAG, "gave up waiting for the camera to fire a still burst");
                }
            }
        };
        // Initial delay lets AE/AWB converge before the first frame of the bracket.
        handler.postDelayed(tryCapture, 2500L);
    }


    /**
     * Handles camera operation requests from other threads.  Necessary because the Camera
     * must only be accessed from one thread.
     * <p>
     * The object is created on the UI thread, and all handlers run there.  Messages are
     * sent from other threads, using sendMessage().
     */
    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;
        public static final int MSG_DISABLE_SURFACE_TEXTURE = 1;
        public static final int MSG_MANUAL_FOCUS = 2;
        //Not camera control, but camera related  and need to run on UI thread.
        public static final int MSG_UPDATE_WARNING = 3;

        private int viewWidth = 0;
        private int viewHeight = 0;
        private float eventX = 0;
        private float eventY = 0;

        // Weak reference to the Activity; only access this from the UI thread.
        private WeakReference<CameraCaptureActivity> mWeakActivity;

        public CameraHandler(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        /**
         * Drop the reference to the activity.  Useful as a paranoid measure to ensure that
         * attempts to access a stale Activity through a handler are caught.
         */
        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        void changeManualFocusPoint(float eventX, float eventY, int viewWidth, int viewHeight) {
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.eventX = eventX;
            this.eventY = eventY;
            Log.d(TAG, "manual focus " + eventX + " " + eventY + " " + viewWidth + " " + viewHeight);
            sendMessage(obtainMessage(MSG_MANUAL_FOCUS));
        }


        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }
            Camera2Proxy camera2proxy = activity.getmCamera2Proxy();
            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    if (camera2proxy == null) {
                        //Wait for camera to be up, push back message
                        this.sendMessageDelayed(Message.obtain(inputMessage), 100);
                        return;
                    }
                    activity.getmCameraCaptureFragment().handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                case MSG_DISABLE_SURFACE_TEXTURE:
                    activity.getmCameraCaptureFragment().handleDisableSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                case MSG_MANUAL_FOCUS:
                    if (camera2proxy != null) {
                        // make sure it won't cause sync issues with other Camera2Proxy methods
                        camera2proxy.changeManualFocusPoint(
                                eventX, eventY, viewWidth, viewHeight);
                    }
                    break;
                case MSG_UPDATE_WARNING:
                    activity.getmCameraCaptureFragment().updateControls();
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}




