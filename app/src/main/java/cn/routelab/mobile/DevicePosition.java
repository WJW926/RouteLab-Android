package cn.routelab.mobile;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;

import java.util.List;
import java.util.function.IntSupplier;

final class DevicePosition implements LocationListener, SensorEventListener {
    private static final long MAX_FIX_AGE_MS = 30_000;
    private static final long PREFER_ACCURATE_FOR_MS = 8_000;
    private final LocationManager locations;
    private final SensorManager sensors;
    private final Sensor rotationSensor;
    private final IntSupplier displayRotation;
    private final float[] rotation = new float[9];
    private final float[] screenRotation = new float[9];
    private final float[] angles = new float[3];
    private final Runnable onChanged;
    private boolean tracking;
    private boolean precise;
    private Fix latest;
    private float compass = Float.NaN;
    private float declination;

    static final class Fix {
        final Route.Point point;
        final float accuracy;
        final long timestamp;
        final float course;

        Fix(Route.Point point, float accuracy, long timestamp, float course) {
            this.point = point;
            this.accuracy = accuracy;
            this.timestamp = timestamp;
            this.course = course;
        }
    }

    DevicePosition(Context context, IntSupplier displayRotation, Runnable onChanged) {
        locations = context.getSystemService(LocationManager.class);
        sensors = context.getSystemService(SensorManager.class);
        Sensor rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        rotationSensor = rotationVector != null ? rotationVector
                : sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        this.displayRotation = displayRotation;
        this.onChanged = onChanged;
    }

    boolean isEnabled() { return locations.isLocationEnabled(); }

    float heading(Fix fix) {
        return Float.isFinite(fix.course) ? fix.course : compass + declination;
    }

    Fix latest() {
        return latest != null && SystemClock.elapsedRealtime() - latest.timestamp <= MAX_FIX_AGE_MS
                ? latest : null;
    }

    void track(boolean enabled, boolean precise) {
        if (tracking == enabled && (!enabled || this.precise == precise)) return;
        if (tracking) {
            locations.removeUpdates(this);
            sensors.unregisterListener(this);
        }
        tracking = enabled;
        this.precise = precise;
        latest = null;
        compass = Float.NaN;
        if (!enabled) return;
        if (rotationSensor != null) sensors.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);

        List<String> providers = locations.getAllProviders();
        if (precise && providers.contains(LocationManager.GPS_PROVIDER)) {
            subscribe(LocationManager.GPS_PROVIDER);
        }
        if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
            subscribe(LocationManager.NETWORK_PROVIDER);
        }
        if (Build.VERSION.SDK_INT >= 31 && providers.contains(LocationManager.FUSED_PROVIDER)) {
            subscribe(LocationManager.FUSED_PROVIDER);
        } else if (!precise && !providers.contains(LocationManager.NETWORK_PROVIDER)
                && providers.contains(LocationManager.GPS_PROVIDER)) {
            subscribe(LocationManager.GPS_PROVIDER);
        }
    }

    private void subscribe(String provider) {
        try {
            locations.requestLocationUpdates(provider, 1000, 0, this, Looper.getMainLooper());
            Location cached = locations.getLastKnownLocation(provider);
            if (cached != null) onLocationChanged(cached);
        } catch (IllegalArgumentException | SecurityException exception) {
            android.util.Log.w("RouteLab", "无法订阅定位来源 " + provider, exception);
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onLocationChanged(Location location) {
        if (!tracking || !isEnabled()) return;
        if (Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider()) return;
        long timestamp = location.getElapsedRealtimeNanos() / 1_000_000;
        if (SystemClock.elapsedRealtime() - timestamp > MAX_FIX_AGE_MS) return;
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 0;
        if (latest != null) {
            long newerBy = timestamp - latest.timestamp;
            if (newerBy <= 0 || (newerBy < PREFER_ACCURATE_FOR_MS && latest.accuracy > 0
                    && accuracy > latest.accuracy * 2 + 15)) return;
        }
        try {
            Route.Point point = new Route.Point(location.getLatitude(), location.getLongitude());
            boolean moving = location.hasSpeed() && location.getSpeed() >= .9f && location.hasBearing()
                    && (!location.hasBearingAccuracy() || location.getBearingAccuracyDegrees() <= 35);
            latest = new Fix(point, accuracy, timestamp, moving ? location.getBearing() : Float.NaN);
            declination = new GeomagneticField((float) point.lat, (float) point.lon,
                    (float) location.getAltitude(), location.getTime()).getDeclination();
        } catch (IllegalArgumentException exception) {
            return;
        }
        onChanged.run();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!tracking || latest == null || Float.isFinite(latest.course)
                || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return;
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        int x = SensorManager.AXIS_X;
        int y = SensorManager.AXIS_Y;
        switch (displayRotation.getAsInt()) {
            case Surface.ROTATION_90: x = SensorManager.AXIS_Y; y = SensorManager.AXIS_MINUS_X; break;
            case Surface.ROTATION_180: x = SensorManager.AXIS_MINUS_X; y = SensorManager.AXIS_MINUS_Y; break;
            case Surface.ROTATION_270: x = SensorManager.AXIS_MINUS_Y; y = SensorManager.AXIS_X; break;
            default: break;
        }
        SensorManager.remapCoordinateSystem(rotation, x, y, screenRotation);
        float next = (float) Math.toDegrees(SensorManager.getOrientation(screenRotation, angles)[0]);
        if (Float.isFinite(next) && (!Float.isFinite(compass)
                || Math.abs(Route.wrap(next - compass)) >= 2)) {
            compass = next;
            onChanged.run();
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override public void onProviderEnabled(String provider) { onChanged.run(); }

    @Override public void onProviderDisabled(String provider) {
        if (!isEnabled()) latest = null;
        onChanged.run();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
