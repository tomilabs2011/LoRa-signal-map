package com.tomilabs.lorasignalmap.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds the offline map: loads it from the app's files (or, if none was downloaded yet, from a
 * map bundled in the APK as assets/offline_map.bin) and swaps in new ones.
 */
public final class OfflineMapStore {
    public interface Listener {
        void onOfflineMapChanged(OfflineMap map);
    }

    private static final String TAG = "OfflineMapStore";
    private static final String ASSET = "offline_map.bin";
    private static final Object LOCK = new Object();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static volatile OfflineMap current;
    private static volatile boolean loaded;
    private static volatile boolean loading;

    private OfflineMapStore() {
    }

    public static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "offline");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "map.bin");
    }

    public static OfflineMap get() {
        return current;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static void addListener(Listener l) {
        synchronized (LOCK) {
            if (!LISTENERS.contains(l)) {
                LISTENERS.add(l);
            }
        }
    }

    public static void removeListener(Listener l) {
        synchronized (LOCK) {
            LISTENERS.remove(l);
        }
    }

    /** Loads the stored map in the background (once). Listeners are told on the main thread. */
    public static void loadAsync(final Context ctx) {
        synchronized (LOCK) {
            if (loaded || loading) {
                return;
            }
            loading = true;
        }
        final Context app = ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                OfflineMap m = null;
                File f = file(app);
                try {
                    if (f.isFile()) {
                        m = OfflineMap.load(f);
                    } else {
                        m = loadBundled(app);
                    }
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    Log.w(TAG, "offline map not loaded", e);
                }
                publish(m);
            }
        }, "offline-map-load");
        t.start();
    }

    private static OfflineMap loadBundled(Context app) {
        try {
            InputStream in = app.getAssets().open(ASSET);
            try {
                return OfflineMap.read(in);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null; // no bundled map in this build
        }
    }

    /** Saves a freshly built map and makes it current. Call from a background thread. */
    public static void install(Context ctx, OfflineMap m) throws IOException {
        m.save(file(ctx));
        publish(m);
    }

    private static void publish(final OfflineMap m) {
        synchronized (LOCK) {
            current = m;
            loaded = true;
            loading = false;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                List<Listener> copy;
                synchronized (LOCK) {
                    copy = new ArrayList<>(LISTENERS);
                }
                for (Listener l : copy) {
                    l.onOfflineMapChanged(m);
                }
            }
        });
    }
}
