package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.movement.*;
import com.vylorq.anticheat.core.util.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the predictor with a small re-implementation of vanilla player physics on flat ground,
 * so legit movement patterns can be checked for false flags and cheat patterns for detection.
 */
class MovementTest {
    MovementPredictor predictor;
    MoveState st;

    /** Minimal vanilla physics along the X axis. */
    static final class Sim {
        double x, y, vx, vy;
        boolean onGround = true;
        double slip = 0.6;
        double groundY = 0;
        double speedAttr = 0.1 * 1.3; // sprinting
        double jumpBoostAmp = -1;

        /** @return the move input for this tick. */
        MoveInput tick(boolean jump, boolean forward) {
            MoveInput in = new MoveInput();
            in.from = new Vec3(x, y, 0);
            in.serverWasOnGround = onGround;
            in.slipperiness = (float) slip;
            in.sprinting = true;
            in.movementSpeed = speedAttr;
            if (jumpBoostAmp >= 0) {
                in.jumpBoost = (int) jumpBoostAmp;
            }
            boolean startGround = onGround;
            if (jump && onGround) {
                vy = 0.42 + (jumpBoostAmp >= 0 ? 0.1 * (jumpBoostAmp + 1) : 0);
                vx += 0.2;
            }
            if (forward) {
                vx += startGround ? speedAttr * (0.21600002 / (slip * slip * slip)) : 0.026;
            }
            x += vx;
            y += vy;
            if (y <= groundY) {
                y = groundY;
                vy = 0;
                onGround = true;
            } else {
                onGround = false;
            }
            vx *= startGround ? slip * 0.91 : 0.91;
            vy = (vy - 0.08) * 0.98;
            in.to = new Vec3(x, y, 0);
            in.serverOnGround = onGround;
            in.clientOnGround = onGround;
            in.nearGround = y - groundY < 0.6;
            in.ticksSinceIce = slip > 0.9 ? 0 : 1000;
            return in;
        }
    }

    @BeforeEach
    void setup() {
        MovementPredictor.Settings s = new MovementPredictor.Settings();
        s.graceSeconds = 0;
        predictor = new MovementPredictor(s);
        st = new MoveState();
    }

    List<CheckType> run(Sim sim, int ticks, boolean jump) {
        List<CheckType> flagged = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            MoveResult r = predictor.process(sim.tick(jump, true), st);
            for (MoveResult.Violation v : r.violations) {
                flagged.add(v.check());
            }
            assertNull(r.setbackTo, "no setback on legit move at tick " + i + " failed=" + r.failed);
        }
        return flagged;
    }

    @Test
    void sprintingIsClean() {
        assertTrue(run(new Sim(), 200, false).isEmpty());
    }

    @Test
    void sprintJumpingIsClean() {
        assertTrue(run(new Sim(), 400, true).isEmpty());
    }

    @Test
    void jumpBoostIsClean() {
        Sim sim = new Sim();
        sim.jumpBoostAmp = 1;
        assertTrue(run(sim, 400, true).isEmpty());
    }

    @Test
    void iceSprintJumpIsClean() {
        Sim sim = new Sim();
        sim.slip = 0.989;
        assertTrue(run(sim, 400, true).isEmpty());
    }

    @Test
    void speedEffectIsClean() {
        Sim sim = new Sim();
        sim.speedAttr = 0.1 * 1.3 * 1.4; // Speed II
        assertTrue(run(sim, 300, true).isEmpty());
    }

    @Test
    void fallingFromHeightIsClean() {
        Sim sim = new Sim();
        sim.y = 60;
        sim.onGround = false;
        assertTrue(run(sim, 200, false).isEmpty());
    }

    @Test
    void speedHackIsCaught() {
        Vec3 pos = Vec3.ZERO;
        boolean flagged = false;
        boolean setback = false;
        for (int i = 0; i < 40; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0.6, 0, 0));
            in.to = pos;
            in.serverOnGround = in.serverWasOnGround = in.clientOnGround = in.nearGround = true;
            in.movementSpeed = 0.13;
            in.sprinting = true;
            MoveResult r = predictor.process(in, st);
            flagged |= r.violations.stream().anyMatch(v -> v.check() == CheckType.SPEED);
            setback |= r.setbackTo != null;
        }
        assertTrue(flagged);
        assertTrue(setback);
    }

    @Test
    void hoverFlyIsCaught() {
        Vec3 pos = new Vec3(0, 70, 0);
        boolean flagged = false;
        for (int i = 0; i < 40; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0.1, 0, 0));
            in.to = pos;
            in.clientOnGround = false;
            MoveResult r = predictor.process(in, st);
            flagged |= r.violations.stream().anyMatch(v -> v.check() == CheckType.FLY);
        }
        assertTrue(flagged);
    }

    @Test
    void spiderIsCaught() {
        Vec3 pos = new Vec3(0, 64, 0);
        boolean flagged = false;
        for (int i = 0; i < 40; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0, 0.2, 0));
            in.to = pos;
            in.horizontalCollision = true;
            in.serverWasOnGround = i == 0;
            MoveResult r = predictor.process(in, st);
            flagged |= r.violations.stream().anyMatch(v -> v.check() == CheckType.SPIDER);
        }
        assertTrue(flagged);
    }

    @Test
    void ladderClimbIsClean() {
        Vec3 pos = new Vec3(0, 64, 0);
        for (int i = 0; i < 60; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0, 0.1176, 0));
            in.to = pos;
            in.onClimbable = true;
            in.horizontalCollision = true;
            assertTrue(predictor.process(in, st).violations.isEmpty());
        }
    }

    @Test
    void knockbackGraceIsClean() {
        Vec3 pos = new Vec3(0, 64, 0);
        MoveInput in = new MoveInput();
        in.from = pos;
        in.to = pos.add(new Vec3(1.5, 1.2, 0));
        in.ticksSinceVelocity = 1;
        in.lastVelocity = 1.8;
        assertTrue(predictor.process(in, st).isClean());
    }

    @Test
    void windChargeLaunchIsClean() {
        // Wind charge: server applies big upward velocity, then normal gravity.
        Vec3 pos = new Vec3(0, 64, 0);
        double vy = 1.6;
        for (int i = 0; i < 60; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0, vy, 0));
            in.to = pos;
            in.ticksSinceVelocity = i;
            in.lastVelocity = 1.6;
            vy = (vy - 0.08) * 0.98;
            assertTrue(predictor.process(in, st).violations.isEmpty(), "tick " + i);
        }
    }

    @Test
    void jesusIsCaught() {
        Vec3 pos = new Vec3(0, 62, 0);
        boolean flagged = false;
        for (int i = 0; i < 30; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            pos = pos.add(new Vec3(0.2, 0, 0));
            in.to = pos;
            in.clientOnGround = true;
            in.onLiquidSurfaceOnly = true;
            in.serverWasOnGround = true;
            flagged |= predictor.process(in, st).violations.stream().anyMatch(v -> v.check() == CheckType.JESUS);
        }
        assertTrue(flagged);
    }

    @Test
    void noFallIsCaught() {
        Vec3 pos = new Vec3(0, 100, 0);
        boolean flagged = false;
        double vy = 0;
        for (int i = 0; i < 30; i++) {
            MoveInput in = new MoveInput();
            in.from = pos;
            vy = (vy - 0.08) * 0.98;
            pos = pos.add(new Vec3(0, vy, 0));
            in.to = pos;
            in.clientOnGround = true;
            flagged |= predictor.process(in, st).violations.stream().anyMatch(v -> v.check() == CheckType.NOFALL);
        }
        assertTrue(flagged);
    }

    @Test
    void phaseSetsBackImmediately() {
        MoveInput in = new MoveInput();
        in.from = new Vec3(0, 64, 0);
        in.to = new Vec3(0.3, 64, 0);
        in.movedIntoSolid = true;
        in.serverWasOnGround = in.serverOnGround = in.clientOnGround = true;
        MoveResult r = predictor.process(in, st);
        assertNotNull(r.setbackTo);
    }

    @Test
    void singleOddPacketNeverFlags() {
        MoveInput in = new MoveInput();
        in.from = new Vec3(0, 64, 0);
        in.to = new Vec3(0.9, 64, 0);
        in.serverWasOnGround = in.serverOnGround = in.clientOnGround = in.nearGround = true;
        assertTrue(predictor.process(in, st).violations.isEmpty());
    }

    @Test
    void creativeAndGraceSkipped() {
        MoveInput in = new MoveInput();
        in.from = new Vec3(0, 64, 0);
        in.to = new Vec3(5, 70, 0);
        in.creativeOrSpectator = true;
        assertTrue(predictor.process(in, st).isClean());
        in.creativeOrSpectator = false;
        in.tps = 12;
        assertTrue(predictor.process(in, st).isClean());
    }

    @Test
    void bedrockMoreLenient() {
        MovementPredictor.Settings s = predictor.settings();
        int javaFlags = 0;
        int bedrockFlags = 0;
        for (int b = 0; b < 2; b++) {
            MoveState state = new MoveState();
            Vec3 pos = Vec3.ZERO;
            for (int i = 0; i < 40; i++) {
                MoveInput in = new MoveInput();
                in.from = pos;
                pos = pos.add(new Vec3(0.33, 0, 0));
                in.to = pos;
                in.serverOnGround = in.serverWasOnGround = in.clientOnGround = in.nearGround = true;
                in.movementSpeed = 0.13;
                in.sprinting = true;
                in.bedrock = b == 1;
                int n = predictor.process(in, state).violations.size();
                if (b == 0) javaFlags += n; else bedrockFlags += n;
            }
        }
        assertTrue(bedrockFlags <= javaFlags, "java " + javaFlags + " bedrock " + bedrockFlags);
        assertNotNull(s);
    }

    @Test
    void safeSpotAvoidsLavaAndVoid() {
        WorldView w = new WorldView() {
            public boolean isSolid(int x, int y, int z) { return y == 63 && x != 5; }
            public boolean isPassable(int x, int y, int z) { return y != 63 || x == 5; }
            public boolean isDangerous(int x, int y, int z) { return x == 5 && y == 63; }
            public int minY() { return -64; }
            public int maxY() { return 320; }
        };
        assertTrue(SafeSpot.isSafe(w, new Vec3(0.5, 64, 0.5)));
        assertFalse(SafeSpot.isSafe(w, new Vec3(5.5, 64, 0.5)), "above lava");
        assertFalse(SafeSpot.isSafe(w, new Vec3(0.5, 63, 0.5)), "inside block");
        Vec3 found = SafeSpot.find(w, new Vec3(5.5, 64, 0.5), 4);
        assertNotNull(found);
        assertNotEquals(5, (int) Math.floor(found.x()));
    }
}
