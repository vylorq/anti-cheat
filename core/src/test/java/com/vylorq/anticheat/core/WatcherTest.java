package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.core.watcher.Gaze;
import com.vylorq.anticheat.core.watcher.WatcherEffect;
import com.vylorq.anticheat.core.watcher.WatcherEligibility;
import com.vylorq.anticheat.core.watcher.WatcherScheduler;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WatcherTest {
    private final UUID p = UUID.randomUUID();

    @Test
    void eventsComeEvery45To120Minutes() {
        Clock.Manual clock = new Clock.Manual(1_000_000);
        WatcherScheduler s = new WatcherScheduler(null, clock, new Random(1));
        WatcherScheduler.Settings set = new WatcherScheduler.Settings();
        for (int i = 0; i < 200; i++) {
            s.schedule(p, set);
            long d = s.nextEventAt(p) - clock.nowMillis();
            assertTrue(d >= 45 * Durations.MINUTE && d <= 121 * Durations.MINUTE, "delay " + d);
        }
        assertFalse(s.due(p));
        clock.advance(121 * Durations.MINUTE);
        assertTrue(s.due(p));
        s.begin(p);
        assertFalse(s.due(p), "never more than one event at once");
        s.end(p, set);
        assertFalse(s.due(p));
    }

    @Test
    void weightsMatchTheTable() {
        WatcherScheduler s = new WatcherScheduler(null, new Clock.Manual(0), new Random(42));
        WatcherScheduler.Settings set = new WatcherScheduler.Settings();
        set.rareChance = 0;
        Map<WatcherEffect, Integer> counts = new EnumMap<>(WatcherEffect.class);
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            counts.merge(s.pick(p, set), 1, Integer::sum);
        }
        assertEquals(0.25, counts.get(WatcherEffect.APPEAR) / (double) n, 0.01);
        assertEquals(0.10, counts.get(WatcherEffect.DOPPELGANGER) / (double) n, 0.01);
        assertEquals(0.15, counts.get(WatcherEffect.MESSAGE) / (double) n, 0.01);
        assertEquals(0.01, counts.get(WatcherEffect.GIFT) / (double) n, 0.004);
        int total = 0;
        for (WatcherEffect e : WatcherEffect.values()) {
            if (e.kind == WatcherEffect.Kind.POOLED) total += e.defaultWeight;
        }
        assertEquals(100, total, "table adds up to 100%");
        assertNull(counts.get(WatcherEffect.RUSH), "rare rush never comes from the pool");
    }

    @Test
    void messageHasCooldownAndDisabledEffectsNeverPicked() {
        Clock.Manual clock = new Clock.Manual(0);
        WatcherScheduler s = new WatcherScheduler(null, clock, new Random(7));
        WatcherScheduler.Settings set = new WatcherScheduler.Settings();
        set.rareChance = 0;
        set.disabled.add(WatcherEffect.APPEAR);
        s.messageShown(p, set);
        for (int i = 0; i < 20_000; i++) {
            WatcherEffect e = s.pick(p, set);
            assertNotEquals(WatcherEffect.MESSAGE, e, "message on cooldown");
            assertNotEquals(WatcherEffect.APPEAR, e, "disabled");
        }
        clock.advance(181 * Durations.MINUTE);
        assertTrue(s.messageAllowed(p));
    }

    @Test
    void rareRushOnlyWhenEnabled() {
        WatcherScheduler s = new WatcherScheduler(null, new Clock.Manual(0), new Random(3));
        WatcherScheduler.Settings set = new WatcherScheduler.Settings();
        set.rareChance = 1;
        for (int i = 0; i < 1000; i++) {
            assertEquals(WatcherEffect.FAKE_JOIN, s.pick(p, set));
        }
        set.rareRush = true;
        boolean sawRush = false;
        for (int i = 0; i < 1000; i++) {
            sawRush |= s.pick(p, set) == WatcherEffect.RUSH;
        }
        assertTrue(sawRush);
    }

    @Test
    void watcherNightAtMostWeekly() {
        Clock.Manual clock = new Clock.Manual(0);
        WatcherScheduler s = new WatcherScheduler(null, clock, new Random(5));
        WatcherScheduler.Settings set = new WatcherScheduler.Settings();
        assertFalse(s.nightDue(set));
        int nights = 0;
        for (int day = 0; day < 70; day++) {
            clock.advance(Durations.DAY);
            if (s.nightDue(set)) nights++;
        }
        assertTrue(nights >= 4 && nights <= 10, "nights in 10 weeks: " + nights);
        set.nightEnabled = false;
        clock.advance(30 * Durations.DAY);
        assertFalse(s.nightDue(set));
    }

    @Test
    void eligibilityBlocksEverySituation() {
        WatcherEligibility e = new WatcherEligibility();
        assertTrue(e.allowed());
        String[] fields = {"excluded", "creativeOrSpectator", "dead", "inCombat", "inArena", "trading", "menuOpen",
                "jailed", "frozen", "waitingRoom", "staffSpectating"};
        for (String f : fields) {
            WatcherEligibility x = new WatcherEligibility();
            try {
                WatcherEligibility.class.getField(f).setBoolean(x, true);
            } catch (ReflectiveOperationException ex) {
                fail(ex);
            }
            assertFalse(x.allowed(), f);
        }
        e.enabled = false;
        assertFalse(e.allowed());
    }

    @Test
    void eligibilityHasNoAntiCheatInputs() {
        for (var f : WatcherEligibility.class.getFields()) {
            String n = f.getName().toLowerCase();
            assertFalse(n.contains("flag") || n.contains("watch") || n.contains("shadow") || n.contains("exempt")
                    || n.contains("suspic"), "anti-cheat input " + f.getName());
        }
    }

    @Test
    void gazeVanishRules() {
        Vec3 eye = new Vec3(0, 65.6, 0);
        Vec3 ahead = new Vec3(0, 65.6, 30);
        assertEquals(0, Gaze.angleTo(eye, 0, 0, ahead), 0.01, "yaw 0 looks toward +Z");
        assertEquals(90, Gaze.angleTo(eye, 90, 0, ahead), 0.01);
        assertEquals(0, Gaze.angleTo(eye, Gaze.yawTowards(eye, new Vec3(10, 65.6, -7)), 0, new Vec3(10, 65.6, -7)), 0.01);

        Gaze g = new Gaze();
        for (int i = 0; i < Gaze.LOOK_TICKS - 1; i++) {
            assertFalse(g.tick(eye, 0, 0, ahead));
        }
        assertTrue(g.tick(eye, 0, 0, ahead), "vanishes after ~3 seconds of staring");

        Gaze away = new Gaze();
        for (int i = 0; i < 500; i++) {
            assertFalse(away.tick(eye, 180, 0, ahead), "looking away never vanishes it");
        }
        assertTrue(new Gaze().tick(eye, 180, 0, new Vec3(0, 65.6, 11)), "vanishes when within 12 blocks");
    }

    @Test
    void spotsAreAtTheEdgeOfView() {
        WatcherScheduler s = new WatcherScheduler(null, new Clock.Manual(0), new Random(9));
        Vec3 eye = new Vec3(0, 0, 0);
        for (double[] c : s.spotCandidates(0, 200, 20, 40)) {
            double dist = Math.hypot(c[0], c[1]);
            assertTrue(dist >= 20 && dist <= 40, "distance " + dist);
            double angle = Gaze.angleTo(eye, 0, 0, new Vec3(c[0], 0, c[1]));
            assertTrue(angle >= 29.9 && angle <= 60.1, "angle " + angle);
        }
    }

    @Test
    void excludeListAndLogAreKept() {
        WatcherScheduler s = new WatcherScheduler(null, new Clock.Manual(0), new Random(1));
        assertTrue(s.exclude(p, true));
        assertTrue(s.excluded(p));
        for (int i = 0; i < WatcherScheduler.LOG_LIMIT + 50; i++) {
            s.log(p, "Steve", WatcherEffect.APPEAR, "schedule");
        }
        assertEquals(WatcherScheduler.LOG_LIMIT, s.data().log.size());
        assertEquals(10, s.recent(10).size());
        assertTrue(s.exclude(p, false));
        assertFalse(s.excluded(p));
    }

    @Test
    void configDefaultsAndIds() {
        AcConfig c = new AcConfig();
        assertTrue(c.watcher.enabled);
        assertFalse(c.watcher.rareRush, "rare rush off by default");
        assertEquals(45, c.watcher.minMinutes);
        assertEquals(120, c.watcher.maxMinutes);
        for (WatcherEffect e : WatcherEffect.values()) {
            assertEquals(e, WatcherEffect.byId(e.id()));
        }
        assertEquals(WatcherEffect.TURN_AROUND, WatcherEffect.byId("turn-around"));
    }
}
