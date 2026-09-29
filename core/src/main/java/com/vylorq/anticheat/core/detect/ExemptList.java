package com.vylorq.anticheat.core.detect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * System-exempt players (section 13): never warned, kicked, banned, muted or given review cases by the system.
 * Admins can still punish them manually. Only the owner edits this list (enforced by the caller via
 * {@link com.vylorq.anticheat.core.perm.PermissionPolicy#canEditExempt}).
 */
public final class ExemptList {
    public static final class Entry {
        public UUID uuid;
        public String name;
        public String addedBy;
        public long addedAt;
        /** Setbacks still apply by default. */
        public boolean setbacks = true;
    }

    public static final class Data {
        public Map<UUID, Entry> entries = new LinkedHashMap<>();
    }

    private final Data data;

    public ExemptList(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    public synchronized boolean isExempt(UUID id) {
        return data.entries.containsKey(id);
    }

    public synchronized boolean setbacksApply(UUID id) {
        Entry e = data.entries.get(id);
        return e == null || e.setbacks;
    }

    public synchronized boolean add(UUID id, String name, String by, long now) {
        if (data.entries.containsKey(id)) {
            return false;
        }
        Entry e = new Entry();
        e.uuid = id;
        e.name = name;
        e.addedBy = by;
        e.addedAt = now;
        data.entries.put(id, e);
        return true;
    }

    public synchronized boolean remove(UUID id) {
        return data.entries.remove(id) != null;
    }

    public synchronized boolean setSetbacks(UUID id, boolean on) {
        Entry e = data.entries.get(id);
        if (e == null) {
            return false;
        }
        e.setbacks = on;
        return true;
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(data.entries.values());
    }
}
