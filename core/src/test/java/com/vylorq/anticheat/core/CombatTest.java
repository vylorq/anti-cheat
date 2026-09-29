package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.combat.*;
import com.vylorq.anticheat.core.util.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatTest {
    final ClickAnalyzer.Settings java = new ClickAnalyzer.Settings(20, 0.08, 1.5);

    int findings(ClickAnalyzer a, long[] times) {
        int n = 0;
        for (int i = 0; i < times.length; i++) {
            n += a.onClick(times[i], times[i] / 50, java).size();
        }
        return n;
    }

    @Test
    void steadyMacroCaught() {
        long[] t = new long[200];
        for (int i = 0; i < t.length; i++) {
            t[i] = 1000 + i * 71L; // ~14 cps, perfectly steady
        }
        assertTrue(findings(new ClickAnalyzer(), t) > 5);
    }

    @Test
    void highCpsCaught() {
        Random r = new Random(1);
        long[] t = new long[200];
        long now = 1000;
        for (int i = 0; i < t.length; i++) {
            now += 30 + r.nextInt(15); // ~27 cps
            t[i] = now;
        }
        assertTrue(findings(new ClickAnalyzer(), t) > 5);
    }

    @Test
    void humanClickingClean() {
        Random r = new Random(7);
        long[] t = new long[300];
        long now = 1000;
        for (int i = 0; i < t.length; i++) {
            // Human: ~9 cps with gaussian noise and occasional pauses
            double iv = 110 + r.nextGaussian() * 35;
            if (r.nextInt(25) == 0) iv += 300;
            now += (long) Math.max(35, iv);
            t[i] = now;
        }
        assertEquals(0, findings(new ClickAnalyzer(), t));
    }

    @Test
    void jitterClickingClean() {
        // Jitter clicking: 12-16 cps, very irregular (right-skewed intervals)
        Random r = new Random(3);
        long[] t = new long[300];
        long now = 1000;
        for (int i = 0; i < t.length; i++) {
            double iv = 45 + Math.abs(r.nextGaussian()) * 40 + r.nextInt(20);
            now += (long) iv;
            t[i] = now;
        }
        assertEquals(0, findings(new ClickAnalyzer(), t));
    }

    @Test
    void butterflyClickingClean() {
        // Butterfly: pairs of fast clicks (often same tick) with irregular gaps, ~16-18 cps
        Random r = new Random(5);
        long[] t = new long[300];
        long now = 1000;
        for (int i = 0; i < t.length; i += 2) {
            now += 70 + r.nextInt(70);
            t[i] = now;
            if (i + 1 < t.length) {
                now += 5 + r.nextInt(30);
                t[i + 1] = now;
            }
        }
        assertEquals(0, findings(new ClickAnalyzer(), t));
    }

    @Test
    void uniformRandomClickerCaught() {
        Random r = new Random(11);
        long[] t = new long[300];
        long now = 1000;
        for (int i = 0; i < t.length; i++) {
            now += 60 + r.nextInt(30); // "randomized" 11-16 cps
            t[i] = now;
        }
        assertTrue(findings(new ClickAnalyzer(), t) > 0);
    }

    @Test
    void tripleClicksPerTickCaught() {
        long[] t = new long[120];
        for (int i = 0; i < t.length; i++) {
            t[i] = 1000 + (i / 3) * 150L + (i % 3) * 5L;
        }
        assertTrue(findings(new ClickAnalyzer(), t) > 0);
    }

    @Test
    void repeatingPatternCaught() {
        long[] pattern = {60, 90, 75, 110, 65};
        long[] t = new long[200];
        long now = 1000;
        for (int i = 0; i < t.length; i++) {
            now += pattern[i % pattern.length];
            t[i] = now;
        }
        assertTrue(findings(new ClickAnalyzer(), t) > 0);
    }

    @Test
    void reachCompensatesForPing() {
        PositionHistory h = new PositionHistory(2000);
        // Target runs away at 0.28 blocks/tick.
        for (int i = 0; i <= 20; i++) {
            h.add(1000 + i * 50L, new Vec3(2.5 + i * 0.28, 64, 0), 0.6, 1.8);
        }
        Vec3 eye = new Vec3(0, 65.62, 0);
        long now = 2000;
        // Current position is ~8 blocks away, but with 200ms ping the attacker saw it much closer.
        double noComp = ReachCheck.compensatedDistance(eye, h, now, 0);
        double comp = ReachCheck.compensatedDistance(eye, h, now, 400);
        assertTrue(comp < noComp);
        assertTrue(comp < 7);
    }

    @Test
    void multiTargetAngle() {
        CombatTracker c = new CombatTracker();
        Vec3 eye = Vec3.ZERO;
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(0, c.onHitMultiTarget(0, a, eye, new Vec3(0, 0, 3), 150));
        double angle = c.onHitMultiTarget(50, b, eye, new Vec3(0, 0, -3), 150);
        assertTrue(angle > 170, "behind: " + angle);
        assertEquals(0, c.onHitMultiTarget(1000, a, eye, new Vec3(0, 0, 3), 150), "outside window");
    }

    @Test
    void robotAimCaught() {
        AimAnalyzer a = new AimAnalyzer();
        float yaw = 0;
        for (int i = 0; i < 40; i++) {
            yaw += 7.5f;
            a.onRotation(yaw, 10);
        }
        assertTrue(a.roboticScore() > 0.5);
        AimAnalyzer human = new AimAnalyzer();
        Random r = new Random(2);
        yaw = 0;
        for (int i = 0; i < 40; i++) {
            yaw += (float) (5 + r.nextGaussian() * 4);
            human.onRotation(yaw, (float) (r.nextGaussian() * 5));
        }
        assertEquals(0, human.roboticScore());
    }

    @Test
    void angleTo() {
        assertEquals(0, CombatTracker.angleTo(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 5)), 1e-6);
        assertEquals(90, CombatTracker.angleTo(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(5, 0, 0)), 1e-6);
    }
}
