package com.tomilabs.lorasignalmap.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.tomilabs.lorasignalmap.App;
import com.tomilabs.lorasignalmap.BuildInfo;
import com.tomilabs.lorasignalmap.LiveState;
import com.tomilabs.lorasignalmap.MainActivity;
import com.tomilabs.lorasignalmap.Prefs;
import com.tomilabs.lorasignalmap.R;
import com.tomilabs.lorasignalmap.gps.Fix;
import com.tomilabs.lorasignalmap.gps.LocationTracker;
import com.tomilabs.lorasignalmap.gps.PositionHistory;
import com.tomilabs.lorasignalmap.link.LinkListener;
import com.tomilabs.lorasignalmap.link.UdpLink;
import com.tomilabs.lorasignalmap.link.UsbLink;
import com.tomilabs.lorasignalmap.log.JsonOut;
import com.tomilabs.lorasignalmap.log.LogRecords;
import com.tomilabs.lorasignalmap.log.SessionStore;
import com.tomilabs.lorasignalmap.log.SessionWriter;
import com.tomilabs.lorasignalmap.model.DeviceInfo;
import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.model.SignalMath;
import com.tomilabs.lorasignalmap.ui.Fmt;

import java.io.File;
import java.io.IOException;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Foreground service that runs a mapping session: GPS + USB + Wi-Fi links + log file.
 * Everything except Android lifecycle calls runs on a single worker thread.
 */
public final class MappingService extends Service
        implements LinkListener, LocationTracker.Callback, Recorder.Output {
    public static final String ACTION_START = "com.tomilabs.lorasignalmap.START";
    public static final String ACTION_STOP = "com.tomilabs.lorasignalmap.STOP";
    private static final String TAG = "MappingService";
    private static final int NOTIFICATION_ID = 4782;
    private static final long FIX_LOST_MS = 10_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private boolean active; // main thread

    // worker-thread state
    private final PositionHistory history = new PositionHistory();
    private SessionWriter writer;
    private Recorder recorder;
    private UsbLink usb;
    private UdpLink udp;
    private LocationTracker gps;
    private boolean gpsHasFix;
    private ToneGenerator tone;
    private long lastNotifyMs;
    private long sessionStartElapsedMs;
    private String lastSignalText = "";
    private PowerManager.WakeLock wakeLock;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MappingService.class).setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, MappingService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "stop failed", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("mapper");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            // startForeground must be called for every startForegroundService() call
            if (!goForeground(buildNotification(getString(R.string.notif_starting), null))) {
                LiveState.get().error = getString(R.string.error_foreground);
                LiveState.get().changed();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!active) {
                startSession();
            }
        } else if (ACTION_STOP.equals(action)) {
            stopSession();
        } else if (!active) {
            // restarted by the system without a request: nothing to resume
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (active) {
            active = false;
            worker.post(new Runnable() {
                @Override
                public void run() {
                    shutdownComponents();
                }
            });
            LiveState.get().endSession();
        }
        releaseWakeLock();
        workerThread.quitSafely();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ session lifecycle

    private void startSession() {
        active = true;
        final long now = System.currentTimeMillis();
        final File file = SessionStore.newSessionFile(this, now);
        LiveState.get().beginSession(file.getName());
        acquireWakeLock();
        worker.post(new Runnable() {
            @Override
            public void run() {
                startComponents(file, now);
            }
        });
    }

    private void stopSession() {
        if (!active) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        active = false;
        worker.post(new Runnable() {
            @Override
            public void run() {
                shutdownComponents();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (active) {
                            return; // a new session was started meanwhile
                        }
                        LiveState.get().endSession();
                        releaseWakeLock();
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    }
                });
            }
        });
    }

    /** Worker thread. */
    private void startComponents(File file, long now) {
        sessionStartElapsedMs = SystemClock.elapsedRealtime();
        SharedPreferences prefs = Prefs.get(this);
        try {
            writer = new SessionWriter(file);
            Double[] rep = Prefs.repeater(prefs);
            TimeZone tz = TimeZone.getDefault();
            writer.append(LogRecords.header(UUID.randomUUID().toString(), now, tz.getID(),
                    tz.getOffset(now) / 60000, getPackageName(), BuildInfo.VERSION_NAME, BuildInfo.VERSION_CODE,
                    Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                    rep != null ? rep[0] : null, rep != null ? rep[1] : null));
        } catch (IOException e) {
            postError(getString(R.string.error_log_create, e.getMessage()));
        }
        final SessionWriter w = writer;
        recorder = new Recorder(new Recorder.Clock() {
            @Override
            public long elapsedNanos() {
                return SystemClock.elapsedRealtimeNanos();
            }

            @Override
            public long wallMillis() {
                return System.currentTimeMillis();
            }
        }, history, new Recorder.LineSink() {
            @Override
            public void write(String jsonLine) throws IOException {
                if (w == null) {
                    throw new IOException("no log file");
                }
                w.append(jsonLine);
            }
        }, this, file.getName());
        refreshSettings();

        history.clear();
        gps = new LocationTracker(this, history, this);
        if (!gps.start(worker.getLooper())) {
            postError(getString(R.string.error_gps_start));
        }
        usb = new UsbLink(this, worker, this);
        usb.start();
        String hello = new JsonOut().num("lsm", 1L).str("cmd", "hello").str("app", BuildInfo.VERSION_NAME).toString();
        udp = new UdpLink(worker, this, hello);
        udp.start();
        worker.postDelayed(tick, 1000);
        updateNotification(true);
    }

    /** Worker thread. Safe to call twice. */
    private void shutdownComponents() {
        worker.removeCallbacks(tick);
        if (usb != null) {
            usb.stop();
            usb = null;
        }
        if (udp != null) {
            udp.stop();
            udp = null;
        }
        if (gps != null) {
            gps.stop();
            gps = null;
        }
        if (recorder != null) {
            recorder.finish();
        }
        if (writer != null) {
            writer.close();
            writer = null;
        }
        recorder = null;
        if (tone != null) {
            tone.release();
            tone = null;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Recorder r = recorder;
            if (r == null) {
                return;
            }
            try {
                refreshSettings();
                r.tick();
                if (gpsHasFix && gps != null && !gps.hasRecentFix(FIX_LOST_MS)) {
                    gpsHasFix = false;
                    r.onGpsState("lost", null);
                }
                if (writer != null) {
                    writer.sync();
                }
                updateNotification(false);
            } catch (RuntimeException e) {
                Log.e(TAG, "tick", e);
            }
            worker.postDelayed(this, 1000);
        }
    };

    private void refreshSettings() {
        SharedPreferences p = Prefs.get(this);
        if (recorder != null) {
            recorder.setWifiConfig(p.getString(Prefs.WIFI_SSID, ""), p.getString(Prefs.WIFI_PASS, ""),
                    p.getBoolean(Prefs.WIFI_PUSH, true));
        }
    }

    // ------------------------------------------------------------------ links (any thread)

    @Override
    public void onLine(final String link, final String port, final String line, final long rxNanos) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                if (recorder == null) {
                    return;
                }
                recorder.onLine(link, port, line, rxNanos);
                final long now = SystemClock.elapsedRealtime();
                LiveState.get().post(new Runnable() {
                    @Override
                    public void run() {
                        LiveState.get().lastDeviceMsgElapsedMs = now;
                    }
                });
            }
        });
    }

    @Override
    public void onLinkState(final String link, final boolean up, final String detail) {
        worker.post(new Runnable() {
            @Override
            public void run() {
                if (recorder != null) {
                    recorder.onLinkState(link, up, detail);
                }
                if (USB.equals(link) && up) {
                    // ask for a status right away instead of waiting for the periodic one
                    worker.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (usb != null) {
                                usb.send(new JsonOut().num("lsm", 1L).str("cmd", "hello")
                                        .str("app", BuildInfo.VERSION_NAME).toString());
                            }
                        }
                    }, 400);
                }
                LiveState.get().post(new Runnable() {
                    @Override
                    public void run() {
                        LiveState s = LiveState.get();
                        if (USB.equals(link)) {
                            s.usbUp = up;
                            s.usbDetail = detail;
                        } else {
                            s.wifiUp = up;
                            s.wifiDetail = detail;
                        }
                        s.changed();
                    }
                });
                updateNotification(true);
            }
        });
    }

    // ------------------------------------------------------------------ GPS (worker thread)

    @Override
    public void onFix(final Fix fix) {
        if (!gpsHasFix && recorder != null) {
            gpsHasFix = true;
            recorder.onGpsState("fix", fix.acc);
        }
        final long now = SystemClock.elapsedRealtime();
        LiveState.get().post(new Runnable() {
            @Override
            public void run() {
                LiveState s = LiveState.get();
                s.lastFixElapsedMs = now;
                s.gpsAcc = fix.acc;
                s.gpsSats = fix.sats;
                s.gpsEnabled = true;
                s.changed();
            }
        });
    }

    @Override
    public void onGpsState(final String state) {
        if ("disabled".equals(state) && recorder != null) {
            gpsHasFix = false;
            recorder.onGpsState("disabled", null);
        }
        LiveState.get().post(new Runnable() {
            @Override
            public void run() {
                LiveState.get().gpsEnabled = !"disabled".equals(state);
                LiveState.get().changed();
            }
        });
    }

    // ------------------------------------------------------------------ recorder output (worker)

    @Override
    public void onMeasurement(final Measurement m) {
        LiveState.get().post(new Runnable() {
            @Override
            public void run() {
                LiveState.get().addMeasurement(m);
            }
        });
        Double sig = SignalMath.linkSignal(m);
        lastSignalText = m.ok ? (sig != null ? Fmt.dbm(sig) : getString(R.string.reply)) : getString(R.string.no_reply);
        feedback(m.ok);
        updateNotification(true);
    }

    @Override
    public void onDevice(final DeviceInfo d) {
        final long now = SystemClock.elapsedRealtime();
        LiveState.get().post(new Runnable() {
            @Override
            public void run() {
                LiveState s = LiveState.get();
                s.device = d;
                s.lastDeviceMsgElapsedMs = now;
                s.changed();
            }
        });
    }

    @Override
    public void sendCommand(String json) {
        boolean sent = usb != null && usb.isUp() && usb.send(json);
        if (!sent && udp != null) {
            udp.send(json);
        }
    }

    @Override
    public void onProtocolPort(String link, String port) {
        if (USB.equals(link) && usb != null) {
            usb.markProtocolPort(port);
        }
    }

    @Override
    public void onDeviceMessage(final String text) {
        LiveState.get().post(new Runnable() {
            @Override
            public void run() {
                LiveState.get().showMessage(text);
            }
        });
    }

    @Override
    public void onWriteError(String message) {
        postError(message);
    }

    private void postError(final String message) {
        LiveState.get().post(new Runnable() {
            @Override
            public void run() {
                LiveState.get().error = message;
                LiveState.get().changed();
            }
        });
    }

    // ------------------------------------------------------------------ feedback & notification

    private void feedback(boolean ok) {
        if (!Prefs.get(this).getBoolean(Prefs.SOUND, false)) {
            return;
        }
        try {
            if (tone == null) {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            }
            tone.startTone(ok ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_NACK, ok ? 120 : 300);
        } catch (RuntimeException e) {
            tone = null; // audio not available; stay silent
        }
    }

    private void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotifyMs < 5000) {
            return;
        }
        if (force && now - lastNotifyMs < 1000) {
            return;
        }
        lastNotifyMs = now;
        Recorder r = recorder;
        int count = r != null ? r.count : 0;
        int ok = r != null ? r.okCount : 0;
        String title = getString(R.string.notif_title, Fmt.duration(now - sessionStartElapsedMs));
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.notif_counts, count, Fmt.percent(ok, count)));
        if (!lastSignalText.isEmpty()) {
            text.append(" · ").append(lastSignalText);
        }
        boolean usbUp = usb != null && usb.isUp();
        boolean wifiUp = udp != null && udp.isUp();
        String links = getString(R.string.notif_links, usbUp ? "✓" : "–", wifiUp ? "✓" : "–");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(title, text + "\n" + links));
            } catch (RuntimeException ignored) {
                // notifications blocked
            }
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MappingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, App.CHANNEL_MAPPING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stopPi).build());
        if (text != null) {
            String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')) : text;
            b.setContentText(firstLine);
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private boolean goForeground(Notification n) {
        int[] attempts = {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        };
        for (int type : attempts) {
            try {
                startForeground(NOTIFICATION_ID, n, type);
                return true;
            } catch (RuntimeException e) {
                Log.w(TAG, "startForeground type " + type + " failed", e);
            }
        }
        return false;
    }

    private void acquireWakeLock() {
        if (wakeLock != null) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) {
            return;
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoRaSignalMap:mapping");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(12L * 60 * 60 * 1000);
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) {
                wakeLock.release();
            }
            wakeLock = null;
        }
    }
}
