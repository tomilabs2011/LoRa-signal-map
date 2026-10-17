package com.tomilabs.lorasignalmap;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.tomilabs.lorasignalmap.model.DeviceInfo;
import com.tomilabs.lorasignalmap.model.Measurement;

import java.util.ArrayList;
import java.util.List;

/**
 * In-process state of the mapping service, read by the UI. All fields are owned by the main
 * thread; other threads change them through {@link #post(Runnable)}.
 */
public final class LiveState {
    public interface Listener {
        void onLiveStateChanged();

        void onNewMeasurement(Measurement m);
    }

    private static final LiveState INSTANCE = new LiveState();

    public static LiveState get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();

    public boolean running;
    public String sessionFile;
    public long startWallMs;
    public long startElapsedMs;
    public long endElapsedMs;

    public int count;
    public int okCount;
    public int noFixCount;
    public Measurement last;
    public Measurement lastOk;

    public boolean usbUp;
    public String usbDetail;
    public boolean wifiUp;
    public String wifiDetail;
    public DeviceInfo device;
    public long lastDeviceMsgElapsedMs;

    public boolean gpsEnabled = true;
    public long lastFixElapsedMs;
    public Double gpsAcc;
    public Integer gpsSats;

    public String message;
    public long messageElapsedMs;
    public String error;

    /** Measurements of the running (or just finished) session, in arrival order. */
    public final ArrayList<Measurement> points = new ArrayList<>();

    private LiveState() {
    }

    public void post(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            main.post(r);
        }
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Main thread. */
    public void changed() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.onLiveStateChanged();
        }
    }

    /** Main thread. */
    public void addMeasurement(Measurement m) {
        points.add(m);
        count++;
        if (m.ok) {
            okCount++;
            lastOk = m;
        }
        if (!m.hasPosition()) {
            noFixCount++;
        }
        last = m;
        for (Listener l : new ArrayList<>(listeners)) {
            l.onNewMeasurement(m);
        }
        changed();
    }

    /** Main thread. Resets counters for a new session. */
    public void beginSession(String file) {
        running = true;
        sessionFile = file;
        startWallMs = System.currentTimeMillis();
        startElapsedMs = SystemClock.elapsedRealtime();
        endElapsedMs = 0;
        count = 0;
        okCount = 0;
        noFixCount = 0;
        last = null;
        lastOk = null;
        usbUp = false;
        usbDetail = null;
        wifiUp = false;
        wifiDetail = null;
        device = null;
        lastDeviceMsgElapsedMs = 0;
        lastFixElapsedMs = 0;
        gpsAcc = null;
        gpsSats = null;
        message = null;
        error = null;
        points.clear();
        changed();
    }

    /** Main thread. */
    public void endSession() {
        running = false;
        endElapsedMs = SystemClock.elapsedRealtime();
        usbUp = false;
        wifiUp = false;
        changed();
    }

    /** Forgets everything (tests). */
    void reset() {
        beginSession(null);
        running = false;
        error = null;
        listeners.clear();
    }

    /** Main thread. */
    public void showMessage(String text) {
        message = text;
        messageElapsedMs = SystemClock.elapsedRealtime();
        changed();
    }

    public boolean deviceSeenRecently(long maxAgeMs) {
        return lastDeviceMsgElapsedMs > 0 && SystemClock.elapsedRealtime() - lastDeviceMsgElapsedMs <= maxAgeMs;
    }

    public boolean gpsFixRecent(long maxAgeMs) {
        return lastFixElapsedMs > 0 && SystemClock.elapsedRealtime() - lastFixElapsedMs <= maxAgeMs;
    }
}
