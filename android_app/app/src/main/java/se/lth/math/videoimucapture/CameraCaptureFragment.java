package se.lth.math.videoimucapture;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import se.lth.math.videoimucapture.gles.FullFrameRect;
import se.lth.math.videoimucapture.gles.Texture2dProgram;

import static android.content.Context.WINDOW_SERVICE;

public class CameraCaptureFragment extends Fragment
        implements SurfaceTexture.OnFrameAvailableListener, TextureMovieEncoder.EncoderListener {

    public static final String TAG = "VIMUC-CaptureFragment";
    private static final boolean VERBOSE = false;
    private SampleGLView mGLView;
    private CameraSurfaceRenderer mRenderer;
    private TextView mCaptureResultText;
    private AspectFrameLayout mAspectFrameLayout;

    private boolean mRecordingEnabled;      // controls button state
    private FloatingActionButton mRecordingButton;
    private FloatingActionButton mWarningButton;
    private FloatingActionButton mCaptureButton;
    private FloatingActionButton mTorchButton;
    private TextView mCaptureStatusText;
    private TextView[] mModeViews;

    private int mCameraPreviewWidth, mCameraPreviewHeight;

    // CAMERA SLEEP. The phone cooks while the app merely sits open, because the preview keeps
    // the sensor streaming at full rate and the ISP awake so recording can start instantly.
    // After `idle_sleep_s` seconds without a touch or a run, the repeating request is stopped:
    // the preview freezes, the sensor goes quiet, and any touch brings it back in about half a
    // second — the honest price of not cooking. Never while recording or during a stills run.
    private final android.os.Handler mIdleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean mCameraAsleep = false;
    private final Runnable mIdleSleep = this::sleepCamera;
    private long mRecordStartMs = 0;    // elapsedRealtime at record start, for the on-screen clock
    private BlurBudgetController mBlurBudget = null;   // #38; created on demand, default off
    private volatile boolean mHoldStill = false;      // shown in the readout when moving too fast
    private volatile float mSmearPx = -1f;            // predicted motion smear of the current frame

    //Owned by the activity
    private CameraCaptureActivity.CameraHandler getmCameraHandler() {
        return ((CameraCaptureActivity) getActivity()).getmCameraHandler();
    };
    private TextureMovieEncoder getsVideoEncoder() {
        return ((CameraCaptureActivity) getActivity()).getsVideoEncoder();
    };
    private RecordingWriter getsRecordingWriter() {
        return ((CameraCaptureActivity) getActivity()).getsRecordingWriter();
    };
    private IMUManager getmImuManager() {
        return ((CameraCaptureActivity) getActivity()).getmImuManager();
    };
    private Camera2Proxy getmCamera2Proxy() {
        return ((CameraCaptureActivity) getActivity()).getmCamera2Proxy();
    };
    private CameraSettingsManager getmCameraSettingsManager() {
        return ((CameraCaptureActivity) getActivity()).getmCameraSettingsManager();
    };

    private String renewOutputDir() {
        SimpleDateFormat dateFormat =
                new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        String folderName = dateFormat.format(new Date());

        String dir = ((CameraCaptureActivity) getActivity()).getResultRoot();

        String outputDir = dir + File.separator + folderName;
        (new File(outputDir)).mkdirs();
        return outputDir;
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate: " + this);

        mRecordingEnabled = getsVideoEncoder().isRecording();
        getsVideoEncoder().setEncoderListener(this);

        Log.d(TAG, "onCreate complete: " + this);
    }

    // View initialization logic
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView: " + this);
        return inflater.inflate(R.layout.capture_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated: " + this);

        // Hook to aspectframe
        mAspectFrameLayout = view.findViewById(R.id.cameraPreview_afl);

        // Setup buttons
        mRecordingButton = view.findViewById(R.id.toggleRecording_button);
        mRecordingButton.setOnClickListener(this::clickToggleRecording);

        mWarningButton = view.findViewById(R.id.OIS_warning_button);
        mWarningButton.setOnClickListener(this::clickWarning);

        mCaptureButton = view.findViewById(R.id.capture_button);
        mCaptureStatusText = view.findViewById(R.id.captureStatus_text);
        mModeViews = new TextView[]{
                view.findViewById(R.id.mode_walk),
                view.findViewById(R.id.mode_object),
                view.findViewById(R.id.mode_pano),
        };
        final CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        mCaptureButton.setOnClickListener(v -> {
            wakeCamera();
            modes.onCaptureButton();
        });
        CaptureModeManager.Mode[] all = CaptureModeManager.Mode.values();
        for (int i = 0; i < mModeViews.length; i++) {
            final CaptureModeManager.Mode m = all[i];
            mModeViews[i].setOnClickListener(v -> {
                if (modes.isRunning()) {
                    return;   // a run owns the mode until it ends
                }
                modes.setMode(m);
                highlightMode(modes.getMode());
            });
        }
        modes.setStateListener(this::onCaptureRunState);
        highlightMode(modes.getMode());

        mTorchButton = view.findViewById(R.id.torch_button);
        mTorchButton.setOnClickListener(v -> {
            Camera2Proxy proxy = getmCamera2Proxy();
            if (proxy == null || !proxy.torchAvailable()) {
                return;
            }
            proxy.setTorch(!proxy.isTorchOn());
            updateTorchButton(proxy.isTorchOn());
        });

        // Configure the GLSurfaceView.  This will start the Renderer thread, with an
        // appropriate EGL context.
        mGLView = view.findViewById(R.id.cameraPreview_surfaceView);
        mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(
                getmCameraHandler(), getsVideoEncoder());
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mGLView.setTouchListener((event, width, height) -> {
            boolean wasAsleep = mCameraAsleep;
            wakeCamera();
            if (!wasAsleep && getmCameraHandler() != null) {
                getmCameraHandler().changeManualFocusPoint(
                        event.getX(), event.getY(), width, height);
            }
        });

        mCaptureResultText = view.findViewById(R.id.captureResult_text);

    }

    private int idleSleepSeconds() {
        if (getActivity() == null) {
            return 0;
        }
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(getActivity())
                .getInt("idle_sleep_s", 60);
    }

    private void armIdleTimer() {
        mIdleHandler.removeCallbacks(mIdleSleep);
        int s = idleSleepSeconds();
        if (s > 0) {
            mIdleHandler.postDelayed(mIdleSleep, s * 1000L);
        }
    }

    private void cancelIdleTimer() {
        mIdleHandler.removeCallbacks(mIdleSleep);
    }

    private void sleepCamera() {
        CameraCaptureActivity act = (CameraCaptureActivity) getActivity();
        if (act == null) {
            return;
        }
        CaptureModeManager modes = act.getmCaptureModeManager();
        if (mRecordingEnabled || (modes != null && modes.isRunning())) {
            armIdleTimer();          // busy after all; ask again later
            return;
        }
        Camera2Proxy proxy = act.getmCamera2Proxy();
        if (proxy == null) {
            return;
        }
        proxy.stopPreview();
        mCameraAsleep = true;
        if (mCaptureStatusText != null) {
            mCaptureStatusText.setText("camera asleep — tap to wake");
        }
        Log.i(TAG, "camera asleep after " + idleSleepSeconds() + " s idle");
    }

    private void wakeCamera() {
        if (mCameraAsleep) {
            CameraCaptureActivity act = (CameraCaptureActivity) getActivity();
            Camera2Proxy proxy = act == null ? null : act.getmCamera2Proxy();
            if (proxy != null) {
                proxy.startPreview();
            }
            mCameraAsleep = false;
            if (mCaptureStatusText != null) {
                mCaptureStatusText.setText("");
            }
            Log.i(TAG, "camera awake");
        }
        armIdleTimer();
    }

    private void updateTorchButton(boolean on) {
        if (mTorchButton == null) {
            return;
        }
        mTorchButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        getResources().getColor(
                                on ? R.color.torchOnBkg : R.color.torchOffBkg, null)));
        // PANO records the environment's own light; a torch riding the camera records
        // the operator's instead. Say so once rather than silently allowing it.
        if (on && ((CameraCaptureActivity) getActivity()).getmCaptureModeManager()
                .getMode() == CaptureModeManager.Mode.PANO && mCaptureStatusText != null) {
            mCaptureStatusText.setText("torch lights YOUR light, not the scene's");
        }
    }

    private void highlightMode(CaptureModeManager.Mode mode) {
        if (mModeViews == null) {
            return;
        }
        CaptureModeManager.Mode[] all = CaptureModeManager.Mode.values();
        for (int i = 0; i < mModeViews.length; i++) {
            boolean on = all[i] == mode;
            mModeViews[i].setAlpha(on ? 1.0f : 0.45f);
            mModeViews[i].setTypeface(null, on ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
    }

    /**
     * Called on the main thread by CaptureModeManager as a run starts and stops.
     *
     * The summary goes to its OWN line. It used to be written over the mode label, which
     * left "5 shots" sitting where the mode should be after a run ended — the state
     * readout permanently replaced by the last thing that happened to it.
     */
    private void onCaptureRunState(boolean running, String summary) {
        if (mCaptureButton != null) {
            mCaptureButton.setImageResource(
                    running ? R.drawable.ic_capture_stop : R.drawable.ic_capture_still);
            mCaptureButton.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                            getResources().getColor(running
                                    ? R.color.captureButtonActiveBkg
                                    : R.color.captureButtonBkg, null)));
        }
        if (running) {
            cancelIdleTimer();
        } else {
            armIdleTimer();
        }
        if (mCaptureStatusText != null) {
            mCaptureStatusText.setText(summary == null ? "" : summary);
            if (!running) {
                // Clear the finished-run summary after a beat so the strip is the only
                // persistent state on screen.
                mCaptureStatusText.postDelayed(() -> {
                    if (mCaptureStatusText != null) {
                        mCaptureStatusText.setText("");
                    }
                }, 4000L);
            }
        }
        // Dim the strip while a run owns the mode, so it reads as unavailable.
        if (mModeViews != null) {
            for (TextView v : mModeViews) {
                v.setEnabled(!running);
            }
        }
    }


    // updates mCameraPreviewWidth/Height
    public void setLayoutAspectRatio(Size cameraPreviewSize) {
        if (mAspectFrameLayout == null) {
            return;
        }
        Display display = ((WindowManager) getActivity().getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
        mCameraPreviewWidth = cameraPreviewSize.getWidth();
        mCameraPreviewHeight = cameraPreviewSize.getHeight();
        if (display.getRotation() == Surface.ROTATION_0) {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else if (display.getRotation() == Surface.ROTATION_180) {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewHeight / mCameraPreviewWidth);
        } else {
            mAspectFrameLayout.setAspectRatio((double) mCameraPreviewWidth / mCameraPreviewHeight);
        }
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
        ((CameraCaptureActivity) getActivity()).initializeCamera();
        Log.d(TAG, "Keeping screen on for previewing recording.");
        getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateControls();
        mCameraAsleep = false;   // the camera is re-initialised on resume; the timer restarts
        armIdleTimer();

        mGLView.onResume();
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight, getmCamera2Proxy().getSwappedDimensions());
            }
        });
        Log.d(TAG, "onResume complete: " + this);
    }

    @Override
    public void onPause() {
        super.onPause();
        cancelIdleTimer();
        mCameraAsleep = false;

        if (mRecordingEnabled) {
            stopRecording();
        }

        ((CameraCaptureActivity) getActivity()).releaseCamera();
        Log.d(TAG, "onPause -- Pause Camera preview");
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.notifyStopPreview();
            }
        });
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // Tell the renderer that it's about to be paused so it can clean up.
                mRenderer.notifyPausing();
            }
        });
        mGLView.onPause();

    }

    //Callback from encoder when it is finished and thread is shutting down.
    public void onEncodingFinished() {
        Log.d(TAG, "Got Encoder listener call");
        mRecordingEnabled = false;
        getActivity().runOnUiThread(() -> updateControls());
    }

    /**
     * onClick handler for "record" button.
     */
    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        wakeCamera();
        mRecordingEnabled = !mRecordingEnabled;
        if (mRecordingEnabled) {
            cancelIdleTimer();
            mRecordStartMs = android.os.SystemClock.elapsedRealtime();
            startRecording();
            updateControls();
        } else {
            mRecordStartMs = 0;
            // disable button until recording finish
            if (mRecordingButton != null){
                mRecordingButton.setEnabled(false);
            }
            stopRecording();
            armIdleTimer();
        }
    }

    public void clickWarning(View view) {
        //Display warning dialog
        CameraSettingsManager cameraSettingsManager = getmCameraSettingsManager();

        Bundle args = new Bundle();
        args.putInt("title", R.string.warning_dialog_title);

        StringBuilder builder = new StringBuilder();
        if (cameraSettingsManager.OISEnabled()) {
            builder.append("- ");
            if (cameraSettingsManager.OISDataEnabled())
                builder.append(getResources().getString(R.string.warning_text_ois_with_data));
            else
                builder.append(getResources().getString(R.string.warning_text_ois_no_data));
            builder.append("\n\n");
        }
        if (cameraSettingsManager.DVSEnabled()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_dvs));
        }
        if (cameraSettingsManager.DistortionCorrectionEnabled()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_distortion));
        }
        if (!getmImuManager().sensorsExist()) {
            builder.append("- ");
            builder.append(getResources().getString(R.string.warning_text_imu_missing));
        }

        args.putString("message", builder.toString());

        InfoDialogFragment newFragment = new InfoDialogFragment();
        newFragment.setArguments(args);
        newFragment.show(getActivity().getSupportFragmentManager(), "warning");
    }

    private void enableWarning(Boolean enable) {
        if (mWarningButton == null) {
            return;
        }
        if (enable) {
            Animation anim = new AlphaAnimation(0.8f, 1.0f);
            anim.setDuration(500); //Manage the blinking time with this parameter
            anim.setStartOffset(20);
            anim.setRepeatMode(Animation.REVERSE);
            anim.setRepeatCount(Animation.INFINITE);
            mWarningButton.setAnimation(anim);
            mWarningButton.setVisibility(View.VISIBLE);
        } else {
            mWarningButton.clearAnimation();
            mWarningButton.setVisibility(View.GONE);
        }
    }

    private void startRecording() {
        Camera2Proxy camera2Proxy = getmCamera2Proxy();
        CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        // The mode owns the session: it names the directory, locks the auto algorithms so
        // the clip has one radiometry, and decides whether this is a new session or a join
        // onto a stills run that is already streaming.
        File dir = modes.beginVideoSession();
        if (dir == null) {
            mRecordingEnabled = false;
            updateControls();
            return;
        }
        String outputDir = dir.getAbsolutePath();
        String outputFile = outputDir + File.separator + "video_recording.mp4";
        String metaFile = outputDir + File.separator + "video_meta.pb3";
        RecordingWriter recordingWriter = getsRecordingWriter();
        // Only open the writer if nothing else already has it. Calling startRecording on a
        // live writer would put a second header over the first and orphan everything the
        // stills run had already queued.
        boolean writerWasIdle = !recordingWriter.isRecording();
        if (writerWasIdle) {
            try {
                recordingWriter.startRecording(metaFile);
            } catch (IOException e) {
                throw new RuntimeException("Could not start meta data recording:" + e);
            }
        }

        mRenderer.resetOutputFiles(outputFile, recordingWriter); // this will not cause sync issues
        if (writerWasIdle) {
            getmImuManager().startRecording(recordingWriter);
            ((CameraCaptureActivity) getActivity()).getmGnssLogger().startRecording(recordingWriter);
            ((CameraCaptureActivity) getActivity()).getmThermalLogger().startRecording(recordingWriter);
        }

        if (camera2Proxy != null) {
            camera2Proxy.startRecordingCaptureResult(recordingWriter);
        } else {
            throw new RuntimeException("mCamera2Proxy should not be null upon toggling record button");
        }
        // Codec and bitrate are read here, on the UI thread, and handed to the renderer before
        // the state change is queued, so the GL thread sees them when it builds the encoder.
        android.content.SharedPreferences prefs =
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(getActivity());
        mRenderer.setEncoderPrefs(prefs.getString("video_codec", VideoEncoderCore.DEFAULT_MIME_TYPE),
                prefs.getInt("video_bitrate_mbps", 0) * 1_000_000);

        // Blur budget (#38): only when explicitly enabled AND exposure is allowed to float —
        // a locked radiometry has nothing for it to do. Default off, so this is normally skipped.
        boolean budgetOn = prefs.getBoolean("blur_budget", false)
                && !prefs.getBoolean("lock_radiometry", true);
        if (budgetOn && camera2Proxy != null) {
            if (mBlurBudget == null) {
                mBlurBudget = new BlurBudgetController(getmImuManager(), camera2Proxy,
                        (holdStill, smearPx, capNs) -> {
                            mHoldStill = holdStill;
                            mSmearPx = smearPx;
                        });
            }
            mBlurBudget.start(prefs.getInt("blur_budget_px", 3),
                    prefs.getInt("hold_still_px", 40));
        }

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(true);
            }
        });
    }

    private void stopRecording() {
        Log.d(TAG, "Stop recording");
        if (mBlurBudget != null) {
            mBlurBudget.stop();
        }
        mHoldStill = false;
        mSmearPx = -1f;
        Camera2Proxy camera2Proxy = getmCamera2Proxy();
        if (camera2Proxy != null) {
            camera2Proxy.stopRecordingCaptureResult();
        }
        CaptureModeManager modes =
                ((CameraCaptureActivity) getActivity()).getmCaptureModeManager();
        // Tear down the sensor streams only if the video owned them. If a stills run is
        // still going, stopping its IMU here would blind the stillness trigger mid-walk.
        boolean ownedSession = modes.videoOwnsSession();
        modes.endVideoSession();
        if (ownedSession) {
            getmImuManager().stopRecording();
            ((CameraCaptureActivity) getActivity()).getmGnssLogger().stopRecording();
            ((CameraCaptureActivity) getActivity()).getmThermalLogger().stopRecording();
        }

        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(false);
            }
        });
        getsRecordingWriter().stopRecording();
    }


    public void updateCaptureResultPanel(
            final Float fl,
            final Long exposureTimeNs) {
        final String sfl = String.format(Locale.getDefault(), "FL: %.3f", fl);
        final String sexpotime =
                exposureTimeNs == null ?
                        "null ms" :
                        String.format(Locale.getDefault(), "Exp: %.2f ms",
                                exposureTimeNs / 1000000.0);
        final String imuHz = String.format(Locale.getDefault(),  "IMU: %.0fHz",
                getmImuManager().getSensorFrequency());
        // THE CLOCK. The operator could not see how long a take had run. Refreshed with every
        // capture result, so it costs nothing extra; the battery temperature rides along because
        // a five-minute walk is exactly when it starts to matter.
        String clock = "";
        if (mRecordingEnabled && mRecordStartMs > 0) {
            long s = (android.os.SystemClock.elapsedRealtime() - mRecordStartMs) / 1000;
            clock = String.format(Locale.getDefault(), "REC %02d:%02d|", s / 60, s % 60);
        }
        String heat = "";
        CameraCaptureActivity act = (CameraCaptureActivity) getActivity();
        if (act != null && act.getmThermalLogger() != null) {
            float c = act.getmThermalLogger().lastBatteryTempC();
            if (!Float.isNaN(c)) {
                heat = String.format(Locale.getDefault(), "%.0f°C|", c);
            }
        }
        // The measured smear, always, when the budget is running. The alarm is the exception;
        // the number is the information, and it is what lets the operator learn where their own
        // threshold belongs instead of being nagged by a constant.
        final String hold = (mHoldStill && mRecordingEnabled) ? "HOLD STILL|" : "";
        final String smear = (mSmearPx >= 0f && mRecordingEnabled)
                ? String.format(Locale.getDefault(), "SMEAR %.0fpx|", mSmearPx) : "";
        final String line = "|" + hold + smear + clock + sfl + "|" + sexpotime + "|" + imuHz + "|" + heat;

        getActivity().runOnUiThread(() -> {
            if (mCaptureResultText != null) {
                mCaptureResultText.setText(line);
            }
        });
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    public void updateControls() {
        if (mRecordingButton != null) {
            int id = mRecordingEnabled ?
                    R.drawable.ic_stop_record : R.drawable.ic_start_record;
            Log.d(TAG, "DRAWING: " + id);
            mRecordingButton.setImageResource(id);
            mRecordingButton.setEnabled(true);
        }

        CameraSettingsManager cameraSettingsManager = getmCameraSettingsManager();
        if ((cameraSettingsManager == null) || !cameraSettingsManager.isInitialized()) {
            // Camera settings not ready yet, may be due to slow start or waiting for camera permission. Post delayed call.
            getmCameraHandler().sendMessageDelayed(
                    getmCameraHandler().obtainMessage(CameraCaptureActivity.CameraHandler.MSG_UPDATE_WARNING),
                    200
            );
            enableWarning(false);
        } else {
            // We have camera settings, update warning accordingly.
            enableWarning(cameraSettingsManager.OISEnabled()
                    || cameraSettingsManager.DVSEnabled()
                    || cameraSettingsManager.DistortionCorrectionEnabled()
                    || !getmImuManager().sensorsExist());
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        // The SurfaceTexture uses this to signal the availability of a new frame.  The
        // thread that "owns" the external texture associated with the SurfaceTexture (which,
        // by virtue of the context being shared, *should* be either one) needs to call
        // updateTexImage() to latch the buffer.
        //
        // Once the buffer is latched, the GLSurfaceView thread can signal the encoder thread.
        // This feels backward -- we want recording to be prioritized over rendering -- but
        // since recording is only enabled some of the time it's easier to do it this way.
        //
        // Since GLSurfaceView doesn't establish a Looper, this will *probably* execute on
        // the main UI thread.  Fortunately, requestRender() can be called from any thread,
        // so it doesn't really matter.
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        mGLView.requestRender();

        final String sfps = String.format(Locale.getDefault(), "%.1f FPS",
                getsVideoEncoder().mFrameRate);
        String previewFacts = mCameraPreviewWidth + "x" + mCameraPreviewHeight + "@" + sfps;

        View fragmentView = getView();

        if (fragmentView != null) {
            TextView text = (TextView) fragmentView.findViewById(R.id.cameraParams_text);
            text.setText(previewFacts);
        }

    }

    /**
     * Connects the SurfaceTexture to the Camera preview output, and starts the preview.
     */
    public void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        Camera2Proxy camera2Proxy = getmCamera2Proxy();

        if (camera2Proxy != null) {
            camera2Proxy.setPreviewSurfaceTexture(st);
            camera2Proxy.openCamera();
        } else {
            throw new RuntimeException(
                    "Try to set surface texture while camera2proxy is null");
        }
    }

    public void handleDisableSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(null);
    }

}

/**
 * Renderer object for our GLSurfaceView.
 * <p>
 * Do not call any methods here directly from another thread -- use the
 * GLSurfaceView#queueEvent() call.
 */
class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = CameraCaptureActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    private CameraCaptureActivity.CameraHandler mCameraHandler;
    private TextureMovieEncoder mVideoEncoder;
    private String mOutputFile;
    private RecordingWriter mMetadataRecorder;
    // Set from the UI thread before the recording state change is queued; read on the GL
    // thread when the encoder is built. A bitrate of 0 means the BPP formula in CameraUtils.
    private volatile String mEncoderMime = VideoEncoderCore.DEFAULT_MIME_TYPE;
    private volatile int mEncoderBitRate = 0;

    private FullFrameRect mFullScreen;

    private final float[] mSTMatrix = new float[16];
    private int mTextureId;

    private SurfaceTexture mSurfaceTexture;
    private boolean mRecordingEnabled;
    private int mRecordingStatus;
    private int mFrameCount;

    // width/height of the incoming camera preview frames
    private boolean mIncomingSizeUpdated;
    private int mIncomingWidth;
    private int mIncomingHeight;
    private boolean mSwappedVideoDimensions;


    /**
     * Constructs CameraSurfaceRenderer.
     * <p>
     *
     * @param cameraHandler Handler for communicating with UI thread
     * @param movieEncoder  video encoder object
     */
    public CameraSurfaceRenderer(CameraCaptureActivity.CameraHandler cameraHandler,
                                 TextureMovieEncoder movieEncoder) {
        mCameraHandler = cameraHandler;
        mVideoEncoder = movieEncoder;
        mTextureId = -1;

        mRecordingStatus = -1;
        mRecordingEnabled = false;
        mFrameCount = -1;

        mIncomingSizeUpdated = false;
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void resetOutputFiles(String outputFile, RecordingWriter metaRecorder) {
        mOutputFile = outputFile;
        mMetadataRecorder = metaRecorder;
    }

    public void setEncoderPrefs(String mimeType, int bitRate) {
        mEncoderMime = (mimeType == null || mimeType.isEmpty())
                ? VideoEncoderCore.DEFAULT_MIME_TYPE : mimeType;
        mEncoderBitRate = Math.max(0, bitRate);
    }

    /**
     * The automatic bitrate, codec-aware. Measured 2026-09-01 on the S24U: the first HEVC clip
     * came out the same size as the H.264 one (343.7 MB, 93.8 Mbit/s), because both encoders
     * were handed the BPP formula's 93.6 Mbit/s. At equal bitrate HEVC buys quality, not disk;
     * the disk comes from asking it for less. 0.55 is the middle of the 40-52% saving the codec
     * is known for at equal visual quality, and it is a starting point to be measured against
     * feature counts, not a fact about this scene.
     */
    private int autoBitRate(int width, int height) {
        int avc = CameraUtils.calcBitRate(width, height, VideoEncoderCore.FRAME_RATE);
        if (VideoEncoderCore.HEVC_MIME_TYPE.equals(mEncoderMime)) {
            return (int) (avc * 0.55f);
        }
        return avc;
    }

    /**
     * Notifies the renderer thread that the activity is pausing.
     * <p>
     * For best results, call this *after* disabling Camera preview.
     */
    public void notifyPausing() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(false);     // assume the GLSurfaceView EGL context is about
            mFullScreen = null;             //  to be destroyed
        }
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void notifyStopPreview() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "Stop preview");
            mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                    CameraCaptureActivity.CameraHandler.MSG_DISABLE_SURFACE_TEXTURE, mSurfaceTexture));
        }
    }

    /**
     * Notifies the renderer that we want to stop or start recording.
     */
    public void changeRecordingState(boolean isRecording) {
        Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
        mRecordingEnabled = isRecording;
        //Extra state update, in case no frame comes. May happen when recording stops.
        updateState();
    }

    /**
     * Records the size of the incoming camera preview frames.
     * <p>
     * It's not clear whether this is guaranteed to execute before or after onSurfaceCreated(),
     * so we assume it could go either way.  (Fortunately they both run on the same thread,
     * so we at least know that they won't execute concurrently.)
     */
    public void setCameraPreviewSize(int width, int height, boolean swappedDimensions) {
        Log.d(TAG, "setCameraPreviewSize");
        mIncomingWidth = width;
        mIncomingHeight = height;
        mSwappedVideoDimensions = swappedDimensions;
        mIncomingSizeUpdated = true;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");

        // We're starting up or coming back.  Either way we've got a new EGLContext that will
        // need to be shared with the video encoder, so figure out if a recording is already
        // in progress.
        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            mRecordingStatus = RECORDING_OFF;
        }

        // Set up the texture blitter that will be used for on-screen display.  This
        // is *not* applied to the recording, because that uses a separate shader.
        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));

        mTextureId = mFullScreen.createTextureObject();

        // Create a SurfaceTexture, with an external texture, in this EGL context.  We don't
        // have a Looper in this thread -- GLSurfaceView doesn't create one -- so the frame
        // available messages will arrive on the main thread.
        mSurfaceTexture = new SurfaceTexture(mTextureId);

        // Tell the UI thread to enable the camera preview.
        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                CameraCaptureActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onDrawFrame(GL10 unused) {
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        boolean showBox = false;

        // Latch the latest frame.  If there isn't anything new, we'll just re-use whatever
        // was there before.
        mSurfaceTexture.updateTexImage();

        // If the recording state is changing, take care of it here.  Ideally we wouldn't
        // be doing all this in onDrawFrame(), but the EGLContext sharing with GLSurfaceView
        // makes it hard to do elsewhere.
        updateState();

        // Set the video encoder's texture name.  We only need to do this once, but in the
        // current implementation it has to happen after the video encoder is started, so
        // we just do it here.
        //
        // TODO: be less lame.
        mVideoEncoder.setTextureId(mTextureId);

        // Tell the video encoder thread that a new frame is available.
        // This will be ignored if we're not actually recording.
        mVideoEncoder.frameAvailable(mSurfaceTexture);

        if (mIncomingWidth <= 0 || mIncomingHeight <= 0) {
            // Texture size isn't set yet.  This is only used for the filters, but to be
            // safe we can just skip drawing while we wait for the various races to resolve.
            // (This seems to happen if you toggle the screen off/on with power button.)
            Log.i(TAG, "Drawing before incoming texture size set; skipping");
            return;
        }

        if (mIncomingSizeUpdated) {
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            mIncomingSizeUpdated = false;
        }

        // Draw the video frame.
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        mFullScreen.drawFrame(mTextureId, mSTMatrix);

        // Draw a flashing box if we're recording.  This only appears on screen.
        showBox = (mRecordingStatus == RECORDING_ON);
        if (showBox && (++mFrameCount & 0x04) == 0) {
            drawBox();
        }
    }

    void updateState() {
        if (mRecordingEnabled) {
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    Log.d(TAG, "START recording");
                    mVideoEncoder.startRecording(
                            new TextureMovieEncoder.EncoderConfig(
                                    mOutputFile,
                                    mSwappedVideoDimensions ? mIncomingHeight : mIncomingWidth,
                                    mSwappedVideoDimensions ? mIncomingWidth : mIncomingHeight,
                                    mEncoderBitRate > 0 ? mEncoderBitRate
                                            : autoBitRate(mIncomingWidth, mIncomingHeight),
                                    mEncoderMime,
                                    EGL14.eglGetCurrentContext(),
                                    mMetadataRecorder));
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    Log.d(TAG, "RESUME recording");
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // stop recording
                    Log.d(TAG, "STOP recording");
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }
    }

    /**
     * Draws a red box in the corner.
     */
    private void drawBox() {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, 100, 100);
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}