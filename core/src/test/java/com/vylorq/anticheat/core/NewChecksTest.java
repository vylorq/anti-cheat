package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.combat.*;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.movement.*;
import com.vylorq.anticheat.core.packets.MiningTime;
import com.vylorq.anticheat.core.util.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NewChecksTest {
    // ---- Anti-knockback ----

    VelocityWatch.Result hit(VelocityWatch w, long t, boolean rises, boolean excused, int moves) {
        w.onVelocity(t, 0.4, 100, false, false);
        double vy = 0.4;
        for (int i = 0; i < moves; i++) {
            // Real physics: up 0.4, then gravity. A cheat stays on the ground.
            w.onMove(rises && i >= 2 ? vy : 0, excused);
            if (rises && i >= 2) {
                vy = (vy - 0.08) * 0.98;
            }
        }
        return w.check(t + 5_000);
    }

    @Test
    void knockbackLegitAndCheat() {
        VelocityWatch legit = new VelocityWatch();
        for (int i = 0; i < 20; i++) {
            assertNotEquals(VelocityWatch.Result.FLAG, hit(legit, i * 10_000L, true, false, 20));
        }
        VelocityWatch cheat = new VelocityWatch();
        boolean flagged = false;
        for (int i = 0; i < 5; i++) {
            flagged |= hit(cheat, i * 10_000L, false, false, 20) == VelocityWatch.Result.FLAG;
        }
        assertTrue(flagged);
    }

    @Test
    void knockbackExcusesCeilingsAndLag() {
        VelocityWatch w = new VelocityWatch();
        for (int i = 0; i < 10; i++) {
            // Under a ceiling or in water: can't rise, never counts.
            assertNotEquals(VelocityWatch.Result.FLAG, hit(w, i * 10_000L, false, true, 20));
        }
        VelocityWatch lag = new VelocityWatch();
        for (int i = 0; i < 10; i++) {
            // A lag spike: hardly any movement packets arrive, so it can't be judged.
            assertNotEquals(VelocityWatch.Result.FLAG, hit(lag, i * 10_000L, false, false, 2));
        }
        VelocityWatch small = new VelocityWatch();
        small.onVelocity(0, 0.1, 50, false, false);
        assertFalse(small.pending(), "sideways or weak pushes aren't judged");
    }

    // ---- Nuker ----

    @Test
    void miningTimeLegitAndNuker() {
        MiningTime legit = new MiningTime();
        long t = 0;
        // Fastest legit mining of 2-tick blocks with the 5-tick cooldown, for a minute, with random lag bunching.
        Random r = new Random(4);
        for (int i = 0; i < 400; i++) {
            t += r.nextInt(10) == 0 ? 50 : 350;
            assertEquals(0, legit.onBreak(t, 0.5f), "legit mining flagged at " + i);
        }
        // Instant blocks (efficiency + haste on stone) never count.
        MiningTime insta = new MiningTime();
        for (int i = 0; i < 400; i++) {
            assertEquals(0, insta.onBreak(i * 10L, 1.2f));
        }
        MiningTime nuker = new MiningTime();
        boolean flagged = false;
        for (int i = 0; i < 100; i++) {
            flagged |= nuker.onBreak(1000 + i * 20L, 0.1f) > 0;
        }
        assertTrue(flagged);
    }

    // ---- Auto-armor, chest stealer ----

    @Test
    void autoArmorNeedsTwoBursts() {
        ArmorWatch human = new ArmorWatch();
        long ms = 1_000_000L;
        for (int i = 0; i < 40; i++) {
            // A quick person: one piece every 150 ms.
            assertFalse(human.onEquip(i * 150 * ms));
        }
        ArmorWatch cheat = new ArmorWatch();
        boolean first = false;
        for (int i = 0; i < 4; i++) {
            first |= cheat.onEquip(10_000 * ms + i * 20 * ms);
        }
        assertFalse(first, "one burst could be a mod");
        boolean second = false;
        for (int i = 0; i < 4; i++) {
            second |= cheat.onEquip(60_000 * ms + i * 20 * ms);
        }
        assertTrue(second);
    }

    @Test
    void chestStealer() {
        long ms = 1_000_000L;
        ReactionWatch human = new ReactionWatch();
        for (int i = 0; i < 10; i++) {
            long t = i * 10_000 * ms;
            human.onOpen(t, i);
            human.onPong(t + 80 * ms, i);
            assertEquals(0, human.onFirstTake(t + 80 * ms + 200 * ms, 80));
        }
        ReactionWatch cheat = new ReactionWatch();
        int last = 0;
        for (int i = 0; i < 3; i++) {
            long t = i * 10_000 * ms;
            cheat.onOpen(t, i);
            cheat.onPong(t + 80 * ms, i);
            last = cheat.onFirstTake(t + 90 * ms, 80);
        }
        assertTrue(last >= 2);
    }

    // ---- Criticals ----

    @Test
    void fakeCrits() {
        CritWatch legit = new CritWatch();
        for (int i = 0; i < 30; i++) {
            // Real jump crits: fell about a block; now and then a snow-layer step.
            assertFalse(legit.onCrit(i % 7 == 0 ? 0.12 : 0.9, i % 7 == 0));
        }
        CritWatch cheat = new CritWatch();
        boolean flagged = false;
        for (int i = 0; i < 6; i++) {
            flagged |= cheat.onCrit(0.06, true);
        }
        assertTrue(flagged);
    }

    // ---- Bots ----

    @Test
    void miningBotVsPerson() {
        // A person: mouse steps of 0.15 degrees, sometimes a fast flick, then small corrections.
        BotPattern person = new BotPattern();
        Random r = new Random(1);
        boolean flagged = false;
        for (int i = 0; i < 6000; i++) {
            double dy = (r.nextInt(80) - 40) * 0.15;
            double dp = (r.nextInt(20) - 10) * 0.15;
            if (i % 50 == 0) {
                dy = 40 * 0.15 * 8;
            }
            if (i % 50 == 1 || i % 50 == 2 || i % 50 == 3) {
                dy = 0;
                dp = 0;
            }
            flagged |= person.onMove(i * 50L, dy, dp, true);
        }
        assertFalse(flagged);
        // A bot: instant 37.3 degree turns, held perfectly still, with no mouse step in between.
        BotPattern bot = new BotPattern();
        flagged = false;
        for (int i = 0; i < 6000; i++) {
            double dy = 0;
            double dp = 0;
            if (i % 20 == 0) {
                dy = 37.31234;
                dp = 31.0 + r.nextDouble();
            } else if (i % 20 == 10) {
                dp = 0.3 + r.nextDouble() * 0.5;
            }
            flagged |= bot.onMove(i * 50L, dy, dp, true);
        }
        assertTrue(flagged);
    }

    // ---- Hovering ----

    @Test
    void hoverCaughtButNotWhenSupported() {
        MovementPredictor.Settings set = new MovementPredictor.Settings();
        set.graceSeconds = 0;
        MovementPredictor pr = new MovementPredictor(set);
        MoveState st = new MoveState();
        boolean flagged = false;
        Vec3 pos = new Vec3(0, 100, 0);
        for (int i = 0; i < 60; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0.1, 0, 0));
            in.to = pos;
            in.groundBelow = false;
            in.nearGround = false;
            flagged |= pr.process(in, st).violations.stream().anyMatch(v -> v.check() == CheckType.FLY);
        }
        assertTrue(flagged, "hovering not caught");
        // Same, but standing on something 1.5 blocks down isn't possible to hover over: and in water it's fine.
        MoveState st2 = new MoveState();
        pos = new Vec3(0, 100, 0);
        for (int i = 0; i < 100; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0.1, 0, 0));
            in.to = pos;
            in.groundBelow = false;
            in.inWater = true;
            assertTrue(pr.process(in, st2).violations.stream().noneMatch(v -> v.check() == CheckType.FLY));
        }
    }
}
