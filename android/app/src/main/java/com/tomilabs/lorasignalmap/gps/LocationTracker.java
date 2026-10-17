package com.tomilabs.lorasignalmap.gps;

import android.content.Context;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * GPS via the platform LocationManager (works on LineageOS without Google services).
 * Callbacks run on the given looper.
 */
public final class LocationTracker implements LocationListener {
    public interface Callback {
        void onFix(Fix fix);

        void onGpsState(String state);
    }

    private static final String TAG = "LocationTracker";

    private final LocationManager lm;
    private final PositionHistory history;
    private final Callback callback;
    private volatile Integer satsUsed;
    private volatile int satsVisible;
    private GnssStatus.Callback gnssCallback;
    private boolean running;

    public LocationTracker(Context ctx, PositionHistory history, Callback callback) {
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        this.history = history;
        this.callback = callback;
    }

    /** @return false if GPS could not be started (no permission / no GPS hardware). */
    public boolean start(Looper looper) {
        if (running) {
            return true;
        }
        if (lm == null) {
            return false;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, looper);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "GPS start failed", e);
            return false;
        }
        running = true;
        gnssCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                int used = 0;
                int n = status.getSatelliteCount();
                for (int i = 0; i < n; i++) {
                    if (status.usedInFix(i)) {
                        used++;
                    }
                }
                satsUsed = used;
                satsVisible = n;
            }
        };
        try {
            lm.registerGnssStatusCallback(gnssCallback, new Handler(looper));
        } catch (SecurityException | IllegalArgumentException e) {
            gnssCallback = null;
        }
        if (!isGpsEnabled()) {
            callback.onGpsState("disabled");
        }
        return true;
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            lm.removeUpdates(this);
        } catch (RuntimeException ignored) {
            // already removed
        }
        if (gnssCallback != null) {
            try {
                lm.unregisterGnssStatusCallback(gnssCallback);
            } catch (RuntimeException ignored) {
                // already removed
            }
            gnssCallback = null;
        }
    }

    public boolean isGpsEnabled() {
        try {
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Integer getSatsUsed() {
        return satsUsed;
    }

    public int getSatsVisible() {
        return satsVisible;
    }

    /** True if the newest fix is younger than maxAgeMs. */
    public boolean hasRecentFix(long maxAgeMs) {
        Fix f = history.latest();
        return f != null && (SystemClock.elapsedRealtimeNanos() - f.elapsedNanos) / 1_000_000L <= maxAgeMs;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) {
            return;
        }
        Fix fix = Fix.from(location, satsUsed);
        history.add(fix);
        callback.onFix(fix);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // deprecated, required on API 29
    }

    @Override
    public void onProviderEnabled(String provider) {
        callback.onGpsState("enabled");
    }

    @Override
    public void onProviderDisabled(String provider) {
        callback.onGpsState("disabled");
    }
}
