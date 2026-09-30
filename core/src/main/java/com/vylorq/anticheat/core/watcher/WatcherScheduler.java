package com.vylorq.anticheat.core.watcher;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Decides when each player gets a Watcher event and which one (33.4). Completely random: it takes nothing from the
 * anti-cheat, so the Watcher never behaves differently for players under investigation (33.8).
 */
public final class WatcherScheduler {
    /** Persisted part: exclude list, log, next Watcher Night. */
    public static final class Data {
        public Set<UUID> excluded = new LinkedHashSet<>();
        public List<LogEntry> log = new ArrayList<>();
        public long nextNightAt;
    }

    public record LogEntry(long time, UUID player, String name, String effect, String by) {
    }

    /** Settings used when picking (copied from the config so the core stays config-agnostic). */
    public static final class Settings {
        public int minMinutes = 45;
        public int maxMinutes = 120;
        public Map<WatcherEffect, Integer> weights = new EnumMap<>(WatcherEffect.class);
        public Set<WatcherEffect> disabled = java.util.EnumSet.noneOf(WatcherEffect.class);
        public boolean rareRush = false;
        /** Chance a scheduled event is instead one of the rare effects. */
        public double rareChance = 0.03;
        /** Chance the watching message is the glitched one. */
        public double glitchChance = 0.1;
        public int messageCooldownMinMinutes = 60;
        public int messageCooldownMaxMinutes = 180;
        public int nightMinDays = 7;
        public int nightMaxDays = 14;
        public boolean nightEnabled = true;

        public int weight(WatcherEffect e) {
            if (disabled.contains(e)) {
                return 0;
            }
            return Math.max(0, weights.getOrDefault(e, e.defaultWeight));
        }
    }

    public static final int LOG_LIMIT = 500;

    private final Data data;
    private final Clock clock;
    private final Random random;
    private final Map<UUID, Long> nextEvent = new HashMap<>();
    /** When each player may next see the "Something is watching…" text. */
    private final Map<UUID, Long> messageAllowedAt = new HashMap<>();
    private final Set<UUID> busy = new java.util.HashSet<>();

    public WatcherScheduler(Data data, Clock clock, Random random) {
        this.data = data == null ? new Data() : data;
        if (this.data.excluded == null) this.data.excluded = new LinkedHashSet<>();
        if (this.data.log == null) this.data.log = new ArrayList<>();
        this.clock = clock;
        this.random = random;
    }

    public Data data() {
        return data;
    }

    // ---- Per-player timing ----

    public void onJoin(UUID player, Settings s) {
        schedule(player, s);
    }

    public void onLeave(UUID player) {
        nextEvent.remove(player);
        busy.remove(player);
    }

    public void schedule(UUID player, Settings s) {
        int min = Math.max(1, Math.min(s.minMinutes, s.maxMinutes));
        int max = Math.max(min, s.maxMinutes);
        long delay = (min + (long) random.nextInt(max - min + 1)) * Durations.MINUTE + random.nextInt(60_000);
        nextEvent.put(player, clock.nowMillis() + delay);
    }

    public long nextEventAt(UUID player) {
        return nextEvent.getOrDefault(player, Long.MAX_VALUE);
    }

    /** True when an event is due and no other event is running for the player. */
    public boolean due(UUID player) {
        return !busy.contains(player) && clock.nowMillis() >= nextEventAt(player);
    }

    /**
     * Called when an event is due but the player isn't eligible right now: try again soon instead of waiting a
     * whole new interval.
     */
    public void postpone(UUID player) {
        nextEvent.put(player, clock.nowMillis() + 30_000 + random.nextInt(90_000));
    }

    public boolean busy(UUID player) {
        return busy.contains(player);
    }

    public void begin(UUID player) {
        busy.add(player);
    }

    /** Effect finished: free the player and schedule the next event. */
    public void end(UUID player, Settings s) {
        busy.remove(player);
        schedule(player, s);
    }

    // ---- Picking ----

    /** Picks the effect for a scheduled event, or null when every effect is disabled. */
    public WatcherEffect pick(UUID player, Settings s) {
        if (random.nextDouble() < s.rareChance) {
            WatcherEffect rare = pickRare(s);
            if (rare != null) {
                return rare;
            }
        }
        boolean messageAllowed = clock.nowMillis() >= messageAllowedAt.getOrDefault(player, 0L);
        int total = 0;
        for (WatcherEffect e : WatcherEffect.values()) {
            if (e.kind == WatcherEffect.Kind.POOLED && (messageAllowed || e != WatcherEffect.MESSAGE)) {
                total += s.weight(e);
            }
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (WatcherEffect e : WatcherEffect.values()) {
            if (e.kind != WatcherEffect.Kind.POOLED || (!messageAllowed && e == WatcherEffect.MESSAGE)) {
                continue;
            }
            roll -= s.weight(e);
            if (roll < 0) {
                return e;
            }
        }
        return null;
    }

    private WatcherEffect pickRare(Settings s) {
        List<WatcherEffect> options = new ArrayList<>();
        if (!s.disabled.contains(WatcherEffect.FAKE_JOIN)) options.add(WatcherEffect.FAKE_JOIN);
        if (s.rareRush && !s.disabled.contains(WatcherEffect.RUSH)) options.add(WatcherEffect.RUSH);
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    /** Whether the watching text should be the glitched "ɪ ꜱᴇᴇ ʏᴏᴜ" this time. */
    public boolean glitch(Settings s) {
        return !s.disabled.contains(WatcherEffect.GLITCH_TEXT) && random.nextDouble() < s.glitchChance;
    }

    /** Records that the "Something is watching…" text was shown, starting its 1–3 hour cooldown. */
    public void messageShown(UUID player, Settings s) {
        int min = Math.max(0, s.messageCooldownMinMinutes);
        int max = Math.max(min, s.messageCooldownMaxMinutes);
        messageAllowedAt.put(player, clock.nowMillis() + (min + (long) random.nextInt(max - min + 1)) * Durations.MINUTE);
    }

    public boolean messageAllowed(UUID player) {
        return clock.nowMillis() >= messageAllowedAt.getOrDefault(player, 0L);
    }

    public boolean roll(double chance) {
        return random.nextDouble() < chance;
    }

    public Random random() {
        return random;
    }

    // ---- Watcher Night ----

    /** True once when the random weekly-at-most Watcher Night is due; schedules the next one. */
    public boolean nightDue(Settings s) {
        long now = clock.nowMillis();
        if (!s.nightEnabled) {
            return false;
        }
        if (data.nextNightAt == 0) {
            scheduleNight(s);
            return false;
        }
        if (now < data.nextNightAt) {
            return false;
        }
        scheduleNight(s);
        return true;
    }

    public void scheduleNight(Settings s) {
        int min = Math.max(7, s.nightMinDays);
        int max = Math.max(min, s.nightMaxDays);
        data.nextNightAt = clock.nowMillis() + min * Durations.DAY + (long) (random.nextDouble() * (max - min) * Durations.DAY);
    }

    // ---- Exclude list and log ----

    public boolean excluded(UUID player) {
        return data.excluded.contains(player);
    }

    public boolean exclude(UUID player, boolean on) {
        return on ? data.excluded.add(player) : data.excluded.remove(player);
    }

    public void log(UUID player, String name, WatcherEffect effect, String by) {
        data.log.add(new LogEntry(clock.nowMillis(), player, name, effect.id(), by));
        while (data.log.size() > LOG_LIMIT) {
            data.log.remove(0);
        }
    }

    public List<LogEntry> recent(int n) {
        int from = Math.max(0, data.log.size() - n);
        return new ArrayList<>(data.log.subList(from, data.log.size()));
    }

    // ---- Spot choice (the part that doesn't need a world) ----

    /**
     * Candidate horizontal offsets at the edge of the player's view, 20–40 blocks away (33.2). The game side checks
     * each for ground, line of sight and light, and keeps the darkest visible one.
     */
    public List<double[]> spotCandidates(float playerYaw, int count, double minDist, double maxDist) {
        List<double[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double side = random.nextBoolean() ? 1 : -1;
            double offset = side * (30 + random.nextDouble() * 30);
            double yaw = Math.toRadians(playerYaw + offset);
            double dist = minDist + random.nextDouble() * (maxDist - minDist);
            out.add(new double[]{-Math.sin(yaw) * dist, Math.cos(yaw) * dist});
        }
        return out;
    }
}
