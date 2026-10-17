package com.tomilabs.lorasignalmap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.tomilabs.lorasignalmap.log.SessionStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only provider used to share session logs and CSV exports with other apps. */
public final class LogFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.tomilabs.lorasignalmap.files";
    public static final String DIR_SESSIONS = "sessions";
    public static final String DIR_EXPORTS = "exports";

    public static Uri uriFor(String dir, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(dir).appendPath(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("no context");
        }
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2) {
            throw new FileNotFoundException(uri.toString());
        }
        String name = seg.get(1);
        if (name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("\\")) {
            throw new FileNotFoundException(uri.toString());
        }
        File base;
        if (DIR_SESSIONS.equals(seg.get(0))) {
            base = SessionStore.dir(getContext());
        } else if (DIR_EXPORTS.equals(seg.get(0))) {
            base = SessionStore.exportDir(getContext());
        } else {
            throw new FileNotFoundException(uri.toString());
        }
        File f = new File(base, name);
        try {
            if (!f.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException(uri.toString());
            }
        } catch (IOException e) {
            throw new FileNotFoundException(uri.toString());
        }
        if (!f.isFile()) {
            throw new FileNotFoundException(uri.toString());
        }
        return f;
    }

    @Override
    public String getType(Uri uri) {
        String last = uri.getLastPathSegment();
        if (last != null && last.endsWith(".csv")) {
            return "text/csv";
        }
        return "text/plain";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !"r".equals(mode)) {
            throw new FileNotFoundException("read-only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
