package com.vylorq.anticheat.core.detect;

import com.vylorq.anticheat.core.util.Clock;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Violation points per player per check. Points fade linearly over time (section 6.1).
 * Also counts raw flags in a sliding window for the auto-watch rule.
 */
public final class ViolationTracker {
    private static final class Entry {
        final EnumMap<CheckType, Double> points = new EnumMap<>(CheckType.class);
        final EnumMap<CheckType, Integer> totalFlags = new EnumMap<>(CheckType.class);
        final Deque<Long> recentFlags = new ArrayDeque<>();
        long lastDecay;
    }

    private final Map<UUID, Entry> players = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile double decayPerMinute;
    private volatile double suspicionScale;

    public ViolationTracker(Clock clock, double decayPerMinute, double suspicionScale) {
        this.clock = clock;
        this.decayPerMinute = decayPerMinute;
        this.suspicionScale = suspicionScale;
    }

    public void configure(double decayPerMinute, double suspicionScale) {
        this.decayPerMinute = decayPerMinute;
        this.suspicionScale = suspicionScale;
    }

    private Entry entry(UUID id) {
        return players.computeIfAbsent(id, k -> {
            Entry e = new Entry();
            e.lastDecay = clock.nowMillis();
            return e;
        });
    }

    private void decay(Entry e) {
        long now = clock.nowMillis();
        double minutes = (now - e.lastDecay) / 60000.0;
        if (minutes <= 0) {
            return;
        }
        e.lastDecay = now;
        double amount = minutes * decayPerMinute;
        e.points.replaceAll((k, v) -> Math.max(0, v - amount));
        e.points.values().removeIf(v -> v <= 0);
    }

    /** Adds points and returns the new points for that check. */
    public synchronized double add(UUID id, CheckType check, double points) {
        Entry e = entry(id);
        decay(e);
        double v = e.points.getOrDefault(check, 0.0) + points;
        e.points.put(check, v);
        e.totalFlags.merge(check, 1, Integer::sum);
        e.recentFlags.addLast(clock.nowMillis());
        return v;
    }

    public synchronized double points(UUID id, CheckType check) {
        Entry e = players.get(id);
        if (e == null) {
            return 0;
        }
        decay(e);
        return e.points.getOrDefault(check, 0.0);
    }

    public synchronized Map<CheckType, Double> snapshot(UUID id) {
        Entry e = players.get(id);
        if (e == null) {
            return Collections.emptyMap();
        }
        decay(e);
        return new EnumMap<>(e.points.isEmpty() ? new EnumMap<>(CheckType.class) : e.points);
    }

    public synchronized Map<CheckType, Integer> flagCounts(UUID id) {
        Entry e = players.get(id);
        return e == null ? Collections.emptyMap() : new EnumMap<>(e.totalFlags.isEmpty() ? new EnumMap<>(CheckType.class) : e.totalFlags);
    }

    /** Number of flags in the last {@code windowMillis}. */
    public synchronized int recentFlagCount(UUID id, long windowMillis) {
        Entry e = players.get(id);
        if (e == null) {
            return 0;
        }
        long cutoff = clock.nowMillis() - windowMillis;
        while (!e.recentFlags.isEmpty() && e.recentFlags.peekFirst() < cutoff) {
            e.recentFlags.pollFirst();
        }
        return e.recentFlags.size();
    }

    /** Weighted total of current points. */
    public synchronized double weightedTotal(UUID id) {
        double total = 0;
        for (Map.Entry<CheckType, Double> en : snapshot(id).entrySet()) {
            total += en.getValue() * en.getKey().weight();
        }
        return total;
    }

    /**
     * Suspicion score 0..100. Uses a saturating curve so a few flags barely move it but sustained
     * flags across checks push it toward 100.
     */
    public synchronized int suspicion(UUID id) {
        return scoreFor(weightedTotal(id), suspicionScale);
    }

    public static int scoreFor(double weightedTotal, double scale) {
        if (weightedTotal <= 0) {
            return 0;
        }
        double s = 100.0 * (1.0 - Math.exp(-weightedTotal / Math.max(1e-6, scale)));
        return (int) Math.round(Math.min(100, s));
    }

    public synchronized void reset(UUID id) {
        players.remove(id);
    }

    public synchronized void resetCheck(UUID id, CheckType check) {
        Entry e = players.get(id);
        if (e != null) {
            e.points.remove(check);
        }
    }

    public enum Level { GREEN, YELLOW, RED }

    public static Level level(int suspicion) {
        if (suspicion >= 60) {
            return Level.RED;
        }
        if (suspicion >= 25) {
            return Level.YELLOW;
        }
        return Level.GREEN;
    }
}
