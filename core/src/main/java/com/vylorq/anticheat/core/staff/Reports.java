package com.vylorq.anticheat.core.staff;

import com.vylorq.anticheat.core.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Player reports queue (section 15). */
public final class Reports {
    public static final class Report {
        public long id;
        public UUID reporter;
        public String reporterName;
        public UUID suspect;
        public String suspectName;
        public String reason;
        public long at;
        public String reporterWorld;
        public Vec3 reporterPos;
        public String suspectWorld;
        public Vec3 suspectPos;
        public boolean handled;
        public String handledBy;
    }

    public static final class Data {
        public long nextId = 1;
        public Map<Long, Report> reports = new TreeMap<>();
        public Map<UUID, Long> lastReportAt = new TreeMap<>();
    }

    private final Data data;

    public Reports(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    /** @return the report, or null if the reporter is on cooldown. */
    public synchronized Report add(Report r, long now, long cooldownMillis) {
        Long last = data.lastReportAt.get(r.reporter);
        if (last != null && now - last < cooldownMillis) {
            return null;
        }
        data.lastReportAt.put(r.reporter, now);
        r.id = data.nextId++;
        r.at = now;
        data.reports.put(r.id, r);
        return r;
    }

    public synchronized List<Report> open() {
        List<Report> out = new ArrayList<>();
        for (Report r : data.reports.values()) {
            if (!r.handled) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized Report get(long id) {
        return data.reports.get(id);
    }

    public synchronized boolean close(long id, String by) {
        Report r = data.reports.get(id);
        if (r == null || r.handled) {
            return false;
        }
        r.handled = true;
        r.handledBy = by;
        return true;
    }
}
