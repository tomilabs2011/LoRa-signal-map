package com.tomilabs.lorasignalmap.log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Append-only JSONL writer. Every line is flushed to the OS immediately (survives an app
 * crash) and fsync'ed regularly (survives the phone dying).
 */
public final class SessionWriter {
    private static final long SYNC_INTERVAL_MS = 5000;

    private final File file;
    private final FileOutputStream fos;
    private final Writer writer;
    private long lastSync;
    private boolean dirty;
    private boolean closed;
    private int lines;

    public SessionWriter(File file) throws IOException {
        this.file = file;
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        fos = new FileOutputStream(file, true);
        writer = new BufferedWriter(new OutputStreamWriter(fos, Charset.forName("UTF-8")), 8192);
        lastSync = System.currentTimeMillis();
    }

    public File getFile() {
        return file;
    }

    public synchronized int getLines() {
        return lines;
    }

    public synchronized void append(String jsonLine) throws IOException {
        if (closed) {
            throw new IOException("writer closed");
        }
        writer.write(jsonLine);
        writer.write('\n');
        writer.flush();
        lines++;
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSync >= SYNC_INTERVAL_MS) {
            sync();
        }
    }

    public synchronized void sync() {
        if (closed || !dirty) {
            return;
        }
        try {
            fos.getFD().sync();
        } catch (IOException ignored) {
            // best effort: data is already handed to the OS
        }
        dirty = false;
        lastSync = System.currentTimeMillis();
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            writer.flush();
            fos.getFD().sync();
        } catch (IOException ignored) {
            // nothing more we can do
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // already closed
        }
        closed = true;
    }
}
