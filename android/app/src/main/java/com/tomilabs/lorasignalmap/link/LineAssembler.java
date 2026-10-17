package com.tomilabs.lorasignalmap.link;

import java.nio.charset.Charset;

/** Turns a byte stream into text lines. Over-long lines are dropped, not truncated. */
public final class LineAssembler {
    public interface Sink {
        void onLine(String line);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private final byte[] buf;
    private int len;
    private boolean overflow;

    public LineAssembler(int maxLine) {
        buf = new byte[maxLine];
    }

    public void feed(byte[] data, int n, Sink sink) {
        for (int i = 0; i < n; i++) {
            byte b = data[i];
            if (b == '\n') {
                if (!overflow && len > 0) {
                    sink.onLine(new String(buf, 0, len, UTF8));
                }
                len = 0;
                overflow = false;
            } else if (b == '\r') {
                // ignore; lines end with \n or \r\n
            } else if (len < buf.length) {
                buf[len++] = b;
            } else {
                overflow = true;
            }
        }
    }

    public void reset() {
        len = 0;
        overflow = false;
    }
}
