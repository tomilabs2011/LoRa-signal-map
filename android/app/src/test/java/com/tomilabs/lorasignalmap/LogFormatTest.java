package com.tomilabs.lorasignalmap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tomilabs.lorasignalmap.log.CsvExport;
import com.tomilabs.lorasignalmap.log.JsonOut;
import com.tomilabs.lorasignalmap.log.LogRecords;
import com.tomilabs.lorasignalmap.log.Session;
import com.tomilabs.lorasignalmap.log.SessionReader;
import com.tomilabs.lorasignalmap.model.Measurement;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;

/** Runs on Robolectric so it uses Android's own org.json. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LogFormatTest {

    static Measurement sample() {
        Measurement m = new Measurement();
        m.t = 1792222215012L;
        m.boot = "9f3a1c2e";
        m.seq = 17;
        m.payload = "Tomi0H";
        m.ok = true;
        m.rssi = -97;
        m.snr = -3.5;
        m.rrssi = -101;
        m.rsnr = -6.25;
        m.rtt = 1850;
        m.crc = 1;
        m.other = 0;
        m.toa = 1188;
        m.txms = 1190;
        m.lat = 48.09812345678;
        m.lon = 19.80312345678;
        m.alt = 312.44;
        m.acc = 4.25;
        m.spd = 1.333;
        m.brg = 87.04;
        m.sats = 14;
        m.pos = "interp";
        m.fixAge = 120L;
        m.rxLat = 48.0981301;
        m.rxLon = 19.8031412;
        m.via = "usb";
        return m;
    }

    @Test
    public void numbersAreFormattedStably() {
        assertEquals("48.0981235", JsonOut.formatDecimal(48.09812345678, 7));
        assertEquals("-3.5", JsonOut.formatDecimal(-3.5, 2));
        assertEquals("-6.25", JsonOut.formatDecimal(-6.25, 2));
        assertEquals("0", JsonOut.formatDecimal(-0.0, 2));
        assertEquals("7", JsonOut.formatDecimal(7.0, 2));
        assertEquals("null", JsonOut.formatDecimal(Double.NaN, 2));
        assertEquals("null", JsonOut.formatDecimal(null, 2));
        assertEquals("{\"a\":\"x\\\"y\\\\z\\n\",\"b\":null,\"c\":true}",
                new JsonOut().str("a", "x\"y\\z\n").num("b", (Integer) null).bool("c", true).toString());
    }

    @Test
    public void measurementLineMatchesTheDocumentedFormat() throws Exception {
        String line = LogRecords.measurement(sample());
        JSONObject o = new JSONObject(line);
        assertEquals("m", o.getString("type"));
        assertEquals(1792222215012L, o.getLong("t"));
        assertEquals(-107.25, o.getDouble("signal"), 1e-9); // weaker of -97-3.5 and -101-6.25
        assertTrue(line.startsWith("{\"type\":\"m\",\"t\":1792222215012,\"boot\":\"9f3a1c2e\",\"seq\":17,"));
    }

    @Test
    public void measurementRoundTrips() throws Exception {
        Measurement a = sample();
        Measurement b = SessionReader.parseMeasurement(new JSONObject(LogRecords.measurement(a)));
        assertEquals(a.t, b.t);
        assertEquals(a.boot, b.boot);
        assertEquals(a.seq, b.seq);
        assertEquals(a.payload, b.payload);
        assertEquals(a.ok, b.ok);
        assertEquals(a.rssi, b.rssi);
        assertEquals(a.snr, b.snr);
        assertEquals(a.rrssi, b.rrssi);
        assertEquals(a.rsnr, b.rsnr);
        assertEquals(a.rtt, b.rtt);
        assertEquals(a.crc, b.crc);
        assertEquals(48.0981235, b.lat, 1e-9);
        assertEquals(19.8031235, b.lon, 1e-9);
        assertEquals(312.4, b.alt, 1e-9);
        assertEquals(a.sats, b.sats);
        assertEquals(a.pos, b.pos);
        assertEquals(a.fixAge, b.fixAge);
        assertEquals(a.via, b.via);
    }

    @Test
    public void failedBeaconHasNullsNotZeros() throws Exception {
        Measurement m = new Measurement();
        m.t = 5;
        m.boot = "b";
        m.seq = 1;
        m.ok = false;
        m.pos = "none";
        JSONObject o = new JSONObject(LogRecords.measurement(m));
        assertTrue(o.isNull("rssi"));
        assertTrue(o.isNull("signal"));
        assertTrue(o.isNull("lat"));
        assertFalse(o.getBoolean("ok"));
        Measurement back = SessionReader.parseMeasurement(o);
        assertNull(back.rssi);
        assertFalse(back.hasPosition());
    }

    @Test
    public void readerSkipsGarbageAndTruncatedLines() throws Exception {
        String header = LogRecords.header("id-1", 1000, "Europe/Budapest", 120, "pkg", "1.0.0", 1,
                "Xiaomi", "M2007J3SG", "15", 35, 48.1, 19.8);
        StringBuilder sb = new StringBuilder();
        sb.append(header).append('\n');
        sb.append(LogRecords.device(1001, com.tomilabs.lorasignalmap.model.DeviceInfo.legacy())).append('\n');
        sb.append("not json at all\n\n");
        sb.append(LogRecords.measurement(sample())).append('\n');
        sb.append("{\"type\":\"future_record\",\"t\":1792222215999,\"x\":1}\n");
        sb.append(LogRecords.measurement(sample()).substring(0, 40)); // crash mid-write
        Session s = SessionReader.read(new ByteArrayInputStream(sb.toString().getBytes(Charset.forName("UTF-8"))), "x.jsonl");
        assertTrue(s.validHeader);
        assertEquals("id-1", s.sessionId);
        assertEquals(1000, s.startT);
        assertEquals(48.1, s.repeaterLat, 1e-9);
        assertEquals(1, s.count());
        assertEquals(2, s.badLines);
        assertEquals(1792222215999L, s.lastT);
        assertFalse(s.ended);
        assertEquals("original", s.deviceFw);
    }

    @Test
    public void csvExportHasOneRowPerBeacon() throws Exception {
        Session s = new Session();
        s.fileName = "lsm_test.jsonl";
        s.sessionId = "abc";
        s.measurements.add(sample());
        Measurement fail = new Measurement();
        fail.t = 1792222219000L;
        fail.boot = "9f3a1c2e";
        fail.seq = 18;
        fail.via = "wifi";
        s.measurements.add(fail);
        File dir = Files.createTempDirectory("csv").toFile();
        File out = CsvExport.export(s, dir);
        List<String> lines = Files.readAllLines(out.toPath(), Charset.forName("UTF-8"));
        assertEquals(3, lines.size());
        assertEquals(CsvExport.HEADER, lines.get(0));
        assertTrue(lines.get(1).contains(",1792222215012,abc,9f3a1c2e,17,1,-107.25,-97,-3.5,-101,-6.25,48.0981235,19.8031235,"));
        assertTrue(lines.get(2).contains(",18,0,,"));
        assertEquals(CsvExport.HEADER.split(",").length, lines.get(2).split(",", -1).length);
    }
}
