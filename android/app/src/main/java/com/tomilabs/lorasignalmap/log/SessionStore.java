package com.tomilabs.lorasignalmap.log;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Where session files live, plus a small cache of parsed sessions. */
public final class SessionStore {
    public static final String EXT = ".jsonl";
    private static final Map<String, Session> CACHE = new HashMap<>();

    private SessionStore() {
    }

    /** Android/data/<pkg>/files/sessions - reachable over USB from a PC. */
    public static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        File d = new File(base, "sessions");
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File exportDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "exports");
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File newSessionFile(Context ctx, long now) {
        String base = "lsm_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now));
        File d = dir(ctx);
        File f = new File(d, base + EXT);
        int n = 2;
        while (f.exists()) {
            f = new File(d, base + "_" + n + EXT);
            n++;
        }
        return f;
    }

    /** Session files, newest first. */
    public static List<File> list(Context ctx) {
        File[] files = dir(ctx).listFiles();
        List<File> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(EXT)) {
                out.add(f);
            }
        }
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return new ArrayList<>(Arrays.asList(arr));
    }

    public static File find(Context ctx, String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return null;
        }
        File f = new File(dir(ctx), name);
        return f.isFile() ? f : null;
    }

    /** Loads (or returns the cached copy of) a session. Never throws. */
    public static Session load(File f) {
        String key = f.getAbsolutePath();
        synchronized (CACHE) {
            Session cached = CACHE.get(key);
            if (cached != null && cached.fileLength == f.length() && cached.fileModified == f.lastModified()) {
                return cached;
            }
        }
        Session s;
        try {
            s = SessionReader.read(f);
        } catch (IOException | RuntimeException e) {
            s = new Session();
            s.fileName = f.getName();
            s.badLines = -1;
        }
        synchronized (CACHE) {
            CACHE.put(key, s);
        }
        return s;
    }

    public static boolean delete(Context ctx, String name) {
        File f = find(ctx, name);
        if (f == null) {
            return false;
        }
        synchronized (CACHE) {
            CACHE.remove(f.getAbsolutePath());
        }
        return f.delete();
    }

    /**
     * Copies a log picked with the system file picker into the sessions folder.
     * Returns the new file, or throws with a user-readable message.
     */
    public static File importLog(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        InputStream probe = cr.openInputStream(uri);
        if (probe == null) {
            throw new IOException("Cannot open the file");
        }
        try {
            if (!SessionReader.looksLikeSessionLog(probe)) {
                throw new IOException("Not a LoRa Signal Map log (.jsonl)");
            }
        } finally {
            probe.close();
        }
        String name = displayName(cr, uri);
        if (name == null || !name.endsWith(EXT)) {
            name = "import_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + EXT;
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.startsWith(".")) {
            name = "_" + name;
        }
        File d = dir(ctx);
        File target = new File(d, name);
        int n = 2;
        while (target.exists()) {
            target = new File(d, name.substring(0, name.length() - EXT.length()) + "_" + n + EXT);
            n++;
        }
        InputStream in = cr.openInputStream(uri);
        if (in == null) {
            throw new IOException("Cannot open the file");
        }
        OutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        } catch (IOException e) {
            out.close();
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        } finally {
            in.close();
            out.close();
        }
        return target;
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall back to a generated name
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }
}
