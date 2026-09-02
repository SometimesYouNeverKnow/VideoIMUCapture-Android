package se.lth.math.videoimucapture;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
    // Raw GNSS (ReconStab #39/#31): the per-satellite measurements and constellation status,
    // registered alongside the fixes. Callbacks fire on the main looper; they write only while
    // a recording is active, like the fixes.
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private GnssMeasurementsEvent.Callback mMeasCallback;
    private GnssStatus.Callback mStatusCallback;

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
            registerRaw();
            mRegistered = true;
            Log.d(TAG, "GNSS updates registered.");
        } catch (SecurityException e) {
            Log.w(TAG, "Location permission revoked mid-flight: " + e);
        }
    }

    /** Raw measurements + constellation status. Best-effort: a device or ROM may support neither. */
    private void registerRaw() {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            mMeasCallback = new GnssMeasurementsEvent.Callback() {
                @Override
                public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
                    onRawMeasurements(event);
                }
            };
            mLocationManager.registerGnssMeasurementsCallback(mMeasCallback, mMainHandler);

            mStatusCallback = new GnssStatus.Callback() {
                @Override
                public void onSatelliteStatusChanged(GnssStatus status) {
                    onSatelliteStatus(status);
                }
            };
            mLocationManager.registerGnssStatusCallback(mStatusCallback, mMainHandler);
        } catch (RuntimeException e) {
            // SecurityException is a RuntimeException; catch the wider type once.
            Log.w(TAG, "Raw GNSS callbacks unavailable: " + e);
        }
    }

    public void unregister() {
        if (mRegistered) {
            mLocationManager.removeUpdates(this);
            if (mMeasCallback != null) {
                mLocationManager.unregisterGnssMeasurementsCallback(mMeasCallback);
                mMeasCallback = null;
            }
            if (mStatusCallback != null) {
                mLocationManager.unregisterGnssStatusCallback(mStatusCallback);
                mStatusCallback = null;
            }
            mRegistered = false;
        }
    }

    private void onRawMeasurements(GnssMeasurementsEvent event) {
        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        GnssClock clock = event.getClock();
        RecordingProtos.GnssMeasurementData.Builder b =
                RecordingProtos.GnssMeasurementData.newBuilder()
                        .setTimeNs(clock.getTimeNanos());
        if (clock.hasFullBiasNanos()) {
            b.setFullBiasNs(clock.getFullBiasNanos());
        }
        if (clock.hasBiasNanos()) {
            b.setBiasNs(clock.getBiasNanos());
        }
        if (clock.hasDriftNanosPerSecond()) {
            b.setDriftNsps(clock.getDriftNanosPerSecond());
        }
        for (GnssMeasurement m : event.getMeasurements()) {
            RecordingProtos.GnssMeasurementData.Measurement.Builder mb =
                    RecordingProtos.GnssMeasurementData.Measurement.newBuilder()
                            .setSvid(m.getSvid())
                            .setConstellation(m.getConstellationType())
                            .setCn0Dbhz(m.getCn0DbHz())
                            .setPseudorangeRateMps(m.getPseudorangeRateMetersPerSecond())
                            .setPseudorangeRateUncertaintyMps(
                                    m.getPseudorangeRateUncertaintyMetersPerSecond())
                            .setAccumulatedDeltaRangeM(m.getAccumulatedDeltaRangeMeters())
                            .setAccumulatedDeltaRangeState(m.getAccumulatedDeltaRangeState())
                            .setMultipathIndicator(m.getMultipathIndicator())
                            .setState(m.getState())
                            .setReceivedSvTimeNs(m.getReceivedSvTimeNanos());
            if (Build.VERSION.SDK_INT >= 26 && m.hasCarrierFrequencyHz()) {
                mb.setCarrierFrequencyHz(m.getCarrierFrequencyHz());
            }
            b.addMeasurements(mb);
        }
        writer.queueData(b.build());
    }

    private void onSatelliteStatus(GnssStatus status) {
        RecordingWriter writer = mRecordingWriter;
        if (writer == null || !writer.isRecording()) {
            return;
        }
        int n = status.getSatelliteCount();
        int used = 0;
        RecordingProtos.GnssStatusData.Builder b =
                RecordingProtos.GnssStatusData.newBuilder()
                        .setTimeNs(SystemClock.elapsedRealtimeNanos())
                        .setSatelliteCount(n);
        for (int i = 0; i < n; i++) {
            boolean inFix = status.usedInFix(i);
            if (inFix) {
                used++;
            }
            RecordingProtos.GnssStatusData.Satellite.Builder sb =
                    RecordingProtos.GnssStatusData.Satellite.newBuilder()
                            .setSvid(status.getSvid(i))
                            .setConstellation(status.getConstellationType(i))
                            .setCn0Dbhz(status.getCn0DbHz(i))
                            .setUsedInFix(inFix)
                            .setElevationDeg(status.getElevationDegrees(i))
                            .setAzimuthDeg(status.getAzimuthDegrees(i));
            if (Build.VERSION.SDK_INT >= 26 && status.hasCarrierFrequencyHz(i)) {
                sb.setCarrierFrequencyHz(status.getCarrierFrequencyHz(i));
            }
            b.addSatellites(sb);
        }
        b.setUsedInFixCount(used);
        writer.queueData(b.build());
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
