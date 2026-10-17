package com.tomilabs.lorasignalmap.log;

import com.tomilabs.lorasignalmap.model.Measurement;
import com.tomilabs.lorasignalmap.model.SignalMath;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** One CSV row per beacon; columns documented in docs/LOG_FORMAT.md. */
public final class CsvExport {
    public static final String HEADER = "time_utc,t_ms,session,boot,seq,ok,signal,rssi,snr,rrssi,rsnr,"
            + "lat,lon,alt,acc,spd,sats,rtt,crc,other,via";

    private CsvExport() {
    }

    public static File export(Session s, File dir) throws IOException {
        String base = s.fileName.endsWith(SessionStore.EXT)
                ? s.fileName.substring(0, s.fileName.length() - SessionStore.EXT.length())
                : s.fileName;
        File out = new File(dir, base + ".csv");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), Charset.forName("UTF-8")));
        try {
            w.write(HEADER);
            w.write('\n');
            String session = s.sessionId != null ? s.sessionId : s.fileName;
            for (Measurement m : s.measurements) {
                StringBuilder sb = new StringBuilder(200);
                sb.append(iso.format(new Date(m.t))).append(',');
                sb.append(m.t).append(',');
                sb.append(csv(session)).append(',');
                sb.append(csv(m.boot)).append(',');
                sb.append(m.seq).append(',');
                sb.append(m.ok ? 1 : 0).append(',');
                sb.append(num(SignalMath.linkSignal(m), 2)).append(',');
                sb.append(m.rssi != null ? m.rssi.toString() : "").append(',');
                sb.append(num(m.snr, 2)).append(',');
                sb.append(m.rrssi != null ? m.rrssi.toString() : "").append(',');
                sb.append(num(m.rsnr, 2)).append(',');
                sb.append(num(m.lat, 7)).append(',');
                sb.append(num(m.lon, 7)).append(',');
                sb.append(num(m.alt, 1)).append(',');
                sb.append(num(m.acc, 1)).append(',');
                sb.append(num(m.spd, 2)).append(',');
                sb.append(m.sats != null ? m.sats.toString() : "").append(',');
                sb.append(m.rtt != null ? m.rtt.toString() : "").append(',');
                sb.append(m.crc).append(',');
                sb.append(m.other).append(',');
                sb.append(csv(m.via));
                sb.append('\n');
                w.write(sb.toString());
            }
        } finally {
            w.close();
        }
        return out;
    }

    private static String num(Double v, int decimals) {
        String s = JsonOut.formatDecimal(v, decimals);
        return "null".equals(s) ? "" : s;
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
