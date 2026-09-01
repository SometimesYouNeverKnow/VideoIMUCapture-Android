package se.lth.math.videoimucapture;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Heat, written next to pressure.
 *
 * The phone cooks after a minute or two of recording, and the barometer drifts about 79 hPa
 * an hour while it does. Until this class the file said nothing about temperature, so every
 * throttling event, dropped frame or exposure change under heat was an inference. Three
 * numbers every few seconds, on the same clock as the sensors:
 *
 *   battery temperature   the battery thermistor, from the sticky ACTION_BATTERY_CHANGED
 *                         broadcast, tenths of a degree converted to degrees C
 *   thermal status        PowerManager.THERMAL_STATUS_* (API 29+): 0 none, 1 light, 2 moderate,
 *                         3 severe, 4 critical, 5 emergency, 6 shutdown; -1 if unavailable
 *   thermal headroom      PowerManager.getThermalHeadroom (API 30+): the forecast fraction of
 *                         the throttling threshold, 1.0 meaning throttling now; NaN if unavailable
 *
 * None of these is a measurement of the camera module itself, which is the part that heats
 * first; they are what the OS exposes, and the status is the one it acts on. Sampled on the
 * main looper because the battery broadcast and the power service are cheap to ask and the
 * sensor thread has enough to do.
 */
public class ThermalLogger {
    private static final String TAG = "VIMUC-Thermal";
    public static final long PERIOD_MS = 5000;

    private final Context mAppContext;
    private final PowerManager mPower;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RecordingWriter mWriter = null;

    private volatile float mLastBatteryC = Float.NaN;
    private volatile int mLastStatus = -1;
    private volatile float mLastHeadroom = Float.NaN;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            sample();
            if (mWriter != null) {
                mHandler.postDelayed(this, PERIOD_MS);
            }
        }
    };

    public ThermalLogger(Context context) {
        mAppContext = context.getApplicationContext();
        mPower = (PowerManager) mAppContext.getSystemService(Context.POWER_SERVICE);
    }

    public void startRecording(RecordingWriter writer) {
        mWriter = writer;
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    public void stopRecording() {
        mWriter = null;
        mHandler.removeCallbacks(mTick);
    }

    /** Battery thermistor in degrees C, or NaN. A sticky broadcast: no receiver is registered. */
    public float batteryTempC() {
        Intent i = mAppContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) {
            return Float.NaN;
        }
        int tenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
    }

    public int thermalStatus() {
        if (Build.VERSION.SDK_INT >= 29 && mPower != null) {
            return mPower.getCurrentThermalStatus();
        }
        return -1;
    }

    public float thermalHeadroom() {
        if (Build.VERSION.SDK_INT >= 30 && mPower != null) {
            try {
                return mPower.getThermalHeadroom(10);
            } catch (RuntimeException e) {
                return Float.NaN;
            }
        }
        return Float.NaN;
    }

    private void sample() {
        mLastBatteryC = batteryTempC();
        mLastStatus = thermalStatus();
        mLastHeadroom = thermalHeadroom();
        RecordingWriter w = mWriter;
        if (w == null) {
            return;
        }
        RecordingProtos.ThermalData.Builder b = RecordingProtos.ThermalData.newBuilder()
                .setTimeNs(SystemClock.elapsedRealtimeNanos())
                .setThermalStatus(mLastStatus);
        if (!Float.isNaN(mLastBatteryC)) {
            b.setBatteryTempC(mLastBatteryC);
        }
        if (!Float.isNaN(mLastHeadroom)) {
            b.setThermalHeadroom(mLastHeadroom);
        }
        w.queueData(b.build());
        if (mLastStatus >= 3) {
            Log.w(TAG, "thermal status " + mLastStatus + " at " + mLastBatteryC + " C");
        }
    }

    /** One line for the screen: what the phone says about its own heat right now. */
    public String summary() {
        StringBuilder s = new StringBuilder();
        if (!Float.isNaN(mLastBatteryC)) {
            s.append(String.format(java.util.Locale.US, "%.1f C", mLastBatteryC));
        }
        if (mLastStatus >= 0) {
            s.append(s.length() > 0 ? "  " : "").append("thermal ").append(mLastStatus);
        }
        if (!Float.isNaN(mLastHeadroom)) {
            s.append(s.length() > 0 ? "  " : "")
                    .append(String.format(java.util.Locale.US, "headroom %.2f", mLastHeadroom));
        }
        return s.toString();
    }
}
