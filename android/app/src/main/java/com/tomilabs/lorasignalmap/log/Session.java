package com.tomilabs.lorasignalmap.log;

import com.tomilabs.lorasignalmap.model.Measurement;

import java.util.ArrayList;
import java.util.List;

/** A session log file loaded into memory. */
public final class Session {
    public String fileName;
    public long fileLength;
    public long fileModified;

    public boolean validHeader;
    public String sessionId;
    public long startT;
    public long lastT;
    public boolean ended;
    public String appVersion;
    public String deviceFw;
    public Double repeaterLat;
    public Double repeaterLon;
    public int badLines;

    public final List<Measurement> measurements = new ArrayList<>();

    public int count() {
        return measurements.size();
    }

    public int okCount() {
        int n = 0;
        for (Measurement m : measurements) {
            if (m.ok) {
                n++;
            }
        }
        return n;
    }

    public int withPosition() {
        int n = 0;
        for (Measurement m : measurements) {
            if (m.hasPosition()) {
                n++;
            }
        }
        return n;
    }

    public long durationMs() {
        return lastT > startT ? lastT - startT : 0;
    }
}
