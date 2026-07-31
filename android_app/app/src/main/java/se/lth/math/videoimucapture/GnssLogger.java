package se.lth.math.videoimucapture;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * GPS track logger built on LocationManager (GPS provider directly — no Play Services,
 * no fused black box). Registers while the activity is resumed so the receiver is warm
 * before recording starts; fixes are written only while a recording is active.
 *
 * Location.getElapsedRealtimeNanos() is on the same clock family as the IMU sensor
 * timestamps, which is what makes the GNSS stream joinable to everything else.
 * Altitude is WGS84 ellipsoid metres — NOT geoid/MSL (the separation is ~30 m in
 * New England); the consumer decides what to do about that, not this logger.
 */
public class GnssLogger implements LocationListener {
    private static final String TAG = "GnssLogger";
    private static final long UPDATE_INTERVAL_MS = 1000;

    private final LocationManager mLocationManager;
    private volatile RecordingWriter mRecordingWriter = null;
    private boolean mRegistered = false;

    public GnssLogger(Context context) {
        mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void register(Context context) {
        if (mRegistered || mLocationManager == null) {
            return;
        }
        if (!hasPermission(context)) {
            Log.i(TAG, "No location permission — GNSS stream disabled.");
            return;
        }
        if (!mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.i(TAG, "GPS provider disabled — GNSS stream disabled.");
            return;
        }
        try {
            mLocationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, this, Looper.getMainLooper());
            mRegistered = true;
            Log.d(TAG, "GNSS updates registered.");
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission revoked mid-flight: " + e);
        }
    }

    public void unregister() {
        if (mRegistered) {
            mLocationManager.removeUpdates(this);
            mRegistered = false;
        }
    }

    public void startRecording(RecordingWriter recordingWriter) {
        mRecordingWriter = recordingWriter;
    }

    public void stopRecording() {
        mRecordingWriter = null;
    }

    @Override
    public void onLocationChanged(Location loc) {
        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        RecordingProtos.GnssData.Builder builder = RecordingProtos.GnssData.newBuilder()
                .setElapsedRealtimeNs(loc.getElapsedRealtimeNanos())
                .setUtcTimeMs(loc.getTime())
                .setLatitudeDeg(loc.getLatitude())
                .setLongitudeDeg(loc.getLongitude())
                .setProvider(loc.getProvider() == null ? "" : loc.getProvider())
                .setHasAltitude(loc.hasAltitude())
                .setHasSpeed(loc.hasSpeed())
                .setHasBearing(loc.hasBearing());
        if (loc.hasAltitude()) {
            builder.setAltitudeWgs84M(loc.getAltitude());
        }
        if (loc.hasAccuracy()) {
            builder.setHorizontalAccuracyM(loc.getAccuracy());
        }
        if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) {
            builder.setVerticalAccuracyM(loc.getVerticalAccuracyMeters());
        }
        if (loc.hasSpeed()) {
            builder.setSpeedMps(loc.getSpeed());
        }
        if (loc.hasBearing()) {
            builder.setBearingDeg(loc.getBearing());
        }
        writer.queueData(builder.build());
    }

    // Required by the LocationListener interface on older API levels.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
