package com.tomilabs.lorasignalmap;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.tomilabs.lorasignalmap.offline.OfflineMapStore;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class App extends Application {
    public static final String CHANNEL_MAPPING = "mapping";
    public static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger(this);
        configureOsmdroid(this);
        createChannels(this);
        OfflineMapStore.loadAsync(this);
    }

    /** Keeps the stack trace of a crash so the next launch can show it. */
    private static void installCrashLogger(final Context ctx) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                            + "  app " + BuildInfo.VERSION_NAME + "  thread " + t.getName());
                    pw.println("Android " + Build.VERSION.RELEASE + " (" + Build.VERSION.SDK_INT + ") "
                            + Build.MANUFACTURER + " " + Build.MODEL);
                    e.printStackTrace(pw);
                    pw.flush();
                    FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_FILE));
                    out.write(sw.toString().getBytes("UTF-8"));
                    out.close();
                } catch (Throwable ignored) {
                    // never let the crash logger crash
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            }
        });
    }

    private static void configureOsmdroid(Context ctx) {
        IConfigurationProvider c = Configuration.getInstance();
        c.load(ctx, ctx.getSharedPreferences("osmdroid", MODE_PRIVATE));
        c.setUserAgentValue("LoRaSignalMap/" + BuildInfo.VERSION_NAME + " (" + ctx.getPackageName() + ")");
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        File osm = new File(base, "osmdroid");
        File tiles = new File(osm, "tiles"); // persistent: maps keep working offline
        //noinspection ResultOfMethodCallIgnored
        osm.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        tiles.mkdirs();
        c.setOsmdroidBasePath(osm);
        c.setOsmdroidTileCache(tiles);
        c.setTileFileSystemCacheMaxBytes(500L * 1024 * 1024);
        c.setTileFileSystemCacheTrimBytes(400L * 1024 * 1024);
        c.setMapViewHardwareAccelerated(true);
    }

    private static void createChannels(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_MAPPING,
                ctx.getString(R.string.channel_mapping), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(ctx.getString(R.string.channel_mapping_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
