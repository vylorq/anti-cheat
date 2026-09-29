package com.vylorq.anticheat.core.jail;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Jail (section 21). By default only online time counts toward the sentence. */
public final class JailManager {
    public static final class Record {
        public UUID player;
        public String name;
        public String cell;
        public String reason;
        public String by;
        public long jailedAt;
        /** Remaining sentence in millis (online-time mode), or PERMANENT. */
        public long remaining;
        /** Wall-clock expiry (real-time mode). */
        public long expiresAt;
        public Location returnTo;
        public boolean muted;
    }

    public static final class Data {
        public Map<String, Location> cells = new LinkedHashMap<>();
        public Map<UUID, Record> jailed = new LinkedHashMap<>();
    }

    private final Data data;
    private final Clock clock;

    public JailManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized void setCell(String name, Location loc) {
        data.cells.put(name.toLowerCase(Locale.ROOT), loc);
    }

    public synchronized boolean removeCell(String name) {
        return data.cells.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public synchronized Location cell(String name) {
        return data.cells.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized Collection<String> cells() {
        return new ArrayList<>(data.cells.keySet());
    }

    /** Picks the cell with the fewest prisoners. */
    public synchronized String pickCell() {
        String best = null;
        int bestCount = Integer.MAX_VALUE;
        for (String c : data.cells.keySet()) {
            int n = 0;
            for (Record r : data.jailed.values()) {
                if (c.equals(r.cell)) {
                    n++;
                }
            }
            if (n < bestCount) {
                best = c;
                bestCount = n;
            }
        }
        return best;
    }

    public synchronized Record jail(UUID player, String name, String reason, String by, long duration, Location returnTo,
                                    boolean muted) {
        Record r = new Record();
        r.player = player;
        r.name = name;
        r.reason = reason;
        r.by = by;
        r.jailedAt = clock.nowMillis();
        r.remaining = duration;
        r.expiresAt = Durations.expiryFrom(r.jailedAt, duration);
        r.returnTo = returnTo;
        r.cell = pickCell();
        r.muted = muted;
        data.jailed.put(player, r);
        return r;
    }

    public synchronized boolean isJailed(UUID player) {
        return data.jailed.containsKey(player);
    }

    public synchronized Record get(UUID player) {
        return data.jailed.get(player);
    }

    public synchronized Record release(UUID player) {
        return data.jailed.remove(player);
    }

    public synchronized List<Record> list() {
        return new ArrayList<>(data.jailed.values());
    }

    public synchronized long remaining(UUID player, boolean onlineTimeOnly) {
        Record r = data.jailed.get(player);
        if (r == null) {
            return 0;
        }
        if (onlineTimeOnly) {
            return r.remaining;
        }
        return r.expiresAt == Durations.PERMANENT ? Durations.PERMANENT : Math.max(0, r.expiresAt - clock.nowMillis());
    }

    /**
     * Advances sentences. Call once a second with the online players.
     *
     * @return players whose sentence is over (still in the map until {@link #release} is called by the caller
     *         for online ones; offline ones are released on their next login)
     */
    public synchronized List<Record> tick(Set<UUID> online, long elapsedMillis, boolean onlineTimeOnly) {
        List<Record> done = new ArrayList<>();
        long now = clock.nowMillis();
        for (Record r : data.jailed.values()) {
            if (onlineTimeOnly) {
                if (r.remaining == Durations.PERMANENT) {
                    continue;
                }
                if (online.contains(r.player)) {
                    r.remaining = Math.max(0, r.remaining - elapsedMillis);
                }
                if (r.remaining <= 0) {
                    done.add(r);
                }
            } else if (Durations.isExpired(r.expiresAt, now)) {
                done.add(r);
            }
        }
        return done;
    }
}
