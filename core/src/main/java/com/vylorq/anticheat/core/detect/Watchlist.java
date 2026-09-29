package com.vylorq.anticheat.core.detect;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Watchlist (section 12). */
public final class Watchlist {
    public static final class Entry {
        public UUID uuid;
        public String name;
        public String reason;
        public String addedBy;
        public long addedAt;
        /** {@link Durations#PERMANENT} for permanent. */
        public long expiresAt;
        public boolean auto;
        /** Last IP seen, for "joined from a new IP" alerts. */
        public String lastIp;
    }

    public static final class HistoryEvent {
        public UUID uuid;
        public String action;
        public String by;
        public String reason;
        public long at;
    }

    public static final class Data {
        public Map<UUID, Entry> entries = new LinkedHashMap<>();
        public List<HistoryEvent> history = new ArrayList<>();
    }

    private final Data data;
    private final Clock clock;

    public Watchlist(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized boolean isWatched(UUID id) {
        Entry e = data.entries.get(id);
        if (e == null) {
            return false;
        }
        if (Durations.isExpired(e.expiresAt, clock.nowMillis())) {
            data.entries.remove(id);
            record(id, "expired", "system", e.reason);
            return false;
        }
        return true;
    }

    public synchronized Entry get(UUID id) {
        return isWatched(id) ? data.entries.get(id) : null;
    }

    public synchronized Entry add(UUID id, String name, String reason, String by, long duration, boolean auto) {
        Entry e = new Entry();
        e.uuid = id;
        e.name = name;
        e.reason = reason == null || reason.isBlank() ? "No reason" : reason;
        e.addedBy = by;
        e.addedAt = clock.nowMillis();
        e.expiresAt = Durations.expiryFrom(e.addedAt, duration);
        e.auto = auto;
        Entry old = data.entries.put(id, e);
        if (old != null) {
            e.lastIp = old.lastIp;
        }
        record(id, auto ? "auto-added" : "added", by, e.reason);
        return e;
    }

    public synchronized boolean remove(UUID id, String by) {
        Entry e = data.entries.remove(id);
        if (e != null) {
            record(id, "removed", by, e.reason);
            return true;
        }
        return false;
    }

    public synchronized List<Entry> list() {
        purgeExpired();
        return new ArrayList<>(data.entries.values());
    }

    public synchronized List<HistoryEvent> history(UUID id) {
        List<HistoryEvent> out = new ArrayList<>();
        for (HistoryEvent h : data.history) {
            if (h.uuid.equals(id)) {
                out.add(h);
            }
        }
        return out;
    }

    /** @return true when a watched player joined from an IP that differs from the last one. */
    public synchronized boolean updateIp(UUID id, String ip) {
        Entry e = get(id);
        if (e == null) {
            return false;
        }
        boolean changed = e.lastIp != null && !e.lastIp.equals(ip);
        e.lastIp = ip;
        return changed;
    }

    public synchronized void purgeExpired() {
        long now = clock.nowMillis();
        Iterator<Entry> it = data.entries.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (Durations.isExpired(e.expiresAt, now)) {
                it.remove();
                record(e.uuid, "expired", "system", e.reason);
            }
        }
    }

    private void record(UUID id, String action, String by, String reason) {
        HistoryEvent h = new HistoryEvent();
        h.uuid = id;
        h.action = action;
        h.by = by;
        h.reason = reason;
        h.at = clock.nowMillis();
        data.history.add(h);
        if (data.history.size() > 5000) {
            data.history.subList(0, data.history.size() - 5000).clear();
        }
    }
}
