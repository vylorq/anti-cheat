package com.vylorq.anticheat.core.deaths;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Death logs with once-only restores (section 16). */
public final class DeathLog {
    public enum RestoreResult { OK, NOT_FOUND, ALREADY_RESTORED, ITEMS_RECOVERED }

    public static final class Data {
        public long nextId = 1;
        public Map<Long, DeathRecord> deaths = new TreeMap<>();
    }

    private final Data data;
    private final Clock clock;

    public DeathLog(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized DeathRecord add(DeathRecord r) {
        r.id = data.nextId++;
        if (r.at == 0) {
            r.at = clock.nowMillis();
        }
        if (r.dropTag == null) {
            r.dropTag = "d" + r.id + "-" + Long.toHexString(r.at);
        }
        data.deaths.put(r.id, r);
        return r;
    }

    public synchronized DeathRecord get(long id) {
        return data.deaths.get(id);
    }

    public synchronized List<DeathRecord> forPlayer(UUID player, int limit) {
        List<DeathRecord> out = new ArrayList<>();
        for (DeathRecord r : data.deaths.values()) {
            if (r.player.equals(player)) {
                out.add(0, r);
            }
        }
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public synchronized DeathRecord byDropTag(String tag) {
        for (DeathRecord r : data.deaths.values()) {
            if (tag.equals(r.dropTag)) {
                return r;
            }
        }
        return null;
    }

    public synchronized void recordPickup(String dropTag, UUID by, String byName, String item) {
        DeathRecord r = byDropTag(dropTag);
        if (r == null) {
            return;
        }
        DeathRecord.Pickup p = new DeathRecord.Pickup();
        p.by = by;
        p.byName = byName;
        p.item = item;
        p.at = clock.nowMillis();
        r.pickups.add(p);
    }

    /**
     * Checks and marks a restore. Each death can be restored once, and never when the owner already picked
     * the items back up (that would duplicate them).
     */
    public synchronized RestoreResult markRestored(long id, String by) {
        DeathRecord r = data.deaths.get(id);
        if (r == null) {
            return RestoreResult.NOT_FOUND;
        }
        if (r.restored) {
            return RestoreResult.ALREADY_RESTORED;
        }
        for (DeathRecord.Pickup p : r.pickups) {
            if (p.by.equals(r.player)) {
                return RestoreResult.ITEMS_RECOVERED;
            }
        }
        r.restored = true;
        r.restoredBy = by;
        r.restoredAt = clock.nowMillis();
        return RestoreResult.OK;
    }

    public synchronized int purge(int retentionDays, int maxPerPlayer) {
        long cutoff = clock.nowMillis() - retentionDays * Durations.DAY;
        int removed = 0;
        Map<UUID, Integer> counts = new java.util.HashMap<>();
        List<Long> ids = new ArrayList<>(data.deaths.keySet());
        java.util.Collections.reverse(ids);
        for (Long id : ids) {
            DeathRecord r = data.deaths.get(id);
            int c = counts.merge(r.player, 1, Integer::sum);
            if (r.at < cutoff || c > maxPerPlayer) {
                data.deaths.remove(id);
                removed++;
            }
        }
        return removed;
    }

}
