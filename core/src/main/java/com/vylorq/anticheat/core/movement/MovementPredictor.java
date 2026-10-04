package com.vylorq.anticheat.core.movement;

import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.Locale;

/**
 * Server-side movement prediction (section 7). For each movement packet the predictor works out the most
 * the player could legitimately have moved, from vanilla physics (friction, acceleration, gravity, drag,
 * jump velocity, step height) plus everything that legitimately changes movement, and compares it with
 * what the client sent.
 *
 * <p>It predicts upper bounds rather than an exact position: an exact replay of every vanilla edge case
 * would false-flag on anything it gets slightly wrong, and a false flag is worse than a missed one. Each
 * check goes through a buffer, so one odd packet never counts.
 */
public final class MovementPredictor {
    /** Tunables, taken from config. */
    public static final class Settings {
        public boolean setbacks = true;
        public double graceSeconds = 3.0;
        public double bufferLimit = 8.0;
        public double bufferDecay = 0.25;
        public double speedTolerance = 0.05;
        public double verticalTolerance = 0.06;
        public double bedrockLeniency = 2.5;
        public int velocityGraceTicks = 40;
        public int maxPingCompensationMs = 500;
        public double lagTpsThreshold = 18.0;
    }

    private static final double DRAG = 0.98;
    private static final double AIR_ACCEL = 0.026;
    private static final double SPRINT_JUMP_BOOST = 0.2;

    private final Settings s;

    public MovementPredictor(Settings settings) {
        this.s = settings;
    }

    public Settings settings() {
        return s;
    }

    public MoveResult process(MoveInput in, MoveState st) {
        int graceTicks = (int) Math.round(s.graceSeconds * 20);
        boolean skip = in.dead || in.sleeping || in.creativeOrSpectator || in.flightAllowed
                || !in.chunksLoaded || in.tps < s.lagTpsThreshold
                || in.ticksSinceTeleport < graceTicks || in.ticksSinceJoinOrRespawn < graceTicks
                || in.ticksSinceDimensionChange < graceTicks
                || in.inVehicle || in.ticksSinceVehicle < 10;
        if (skip) {
            st.resetMotion(in.to);
            // Keep the real momentum (capped) so the first checked tick after a grace period isn't too strict.
            st.lastHorizontal = Math.min(in.horizontal(), 1.0);
            st.lastDy = Math.max(-3.92, Math.min(in.dy(), 0.42));
            st.lastFriction = 0.91;
            st.lastClientOnGround = in.clientOnGround;
            st.lastArrivalNanos = in.arrivalNanos;
            return MoveResult.OK;
        }

        double lenient = in.bedrock ? s.bedrockLeniency : 1.0;
        double tolH = s.speedTolerance * lenient;
        double tolV = s.verticalTolerance * lenient;
        int pingTicks = Math.min(in.pingMs, s.maxPingCompensationMs) / 50;
        boolean velocityGrace = in.ticksSinceVelocity < s.velocityGraceTicks + pingTicks * 2;

        MoveResult r = new MoveResult();
        double dy = in.dy();
        double h = in.horizontal();

        // ---- Timer: more movement packets than ticks. ----
        if (in.arrivalNanos > 0 && st.lastArrivalNanos > 0) {
            double elapsedMs = (in.arrivalNanos - st.lastArrivalNanos) / 1_000_000.0;
            st.timerBalanceMs += 50.0 - elapsedMs;
            // Packets that arrive bunched after a lag spike are fine: the delay before them is kept as "debt"
            // (up to a few seconds) so the burst that follows cancels out.
            st.timerBalanceMs = Math.max(st.timerBalanceMs, -3000);
            if (st.timerBalanceMs > 500 * lenient) {
                fail(r, st, CheckType.TIMER, 1.0, String.format(Locale.ROOT, "%.0fms ahead", st.timerBalanceMs), false, in);
                st.timerBalanceMs = 0;
            }
        }
        st.lastArrivalNanos = in.arrivalNanos;

        // ---- Phase: moving into solid blocks. ----
        boolean ghost = in.ticksSinceGhostBlock < 40;
        if (in.movedIntoSolid && !in.pistonNearby && !velocityGrace && !ghost && !in.crawling && !in.swimming) {
            fail(r, st, CheckType.PHASE, 2.0, "moved into a block", true, in);
        }

        // ---- Elytra ----
        if (in.gliding || in.ticksSinceGlide < 20) {
            st.pendingFriction = 0.91;
            checkElytra(in, st, r, velocityGrace);
            finish(in, st, r, dy, Math.min(h, 4.0));
            return r;
        }

        boolean skipAll = in.riptiding || in.ticksSinceRiptide < 30 || in.pistonNearby;

        // ---- Horizontal speed ----
        double maxH = maxHorizontal(in, st);
        if (velocityGrace) {
            maxH = Math.max(maxH, in.lastVelocity * 1.2 + 0.3);
        }
        if (!skipAll && h > maxH + tolH && h > 0.05) {
            double over = h - maxH;
            fail(r, st, CheckType.SPEED, over, String.format(Locale.ROOT, "%.3f > %.3f b/t%s", h, maxH,
                    in.usingItemTicks >= 6 ? " while using an item" : ""), over > 1.0, in);
        } else {
            relax(st, CheckType.SPEED);
        }
        double feedH = Math.min(h, maxH + tolH);

        // ---- Vertical ----
        boolean vertExempt = skipAll || velocityGrace || in.inWater || in.inLava || in.ticksSinceLiquid < 10
                || in.onClimbable || in.ticksSinceClimbable < 5 || in.inScaffolding || in.inBubbleColumn
                || in.levitation >= 0 || in.inCobweb || in.inPowderSnow || in.inBerryBush
                || in.touchingHoney || ghost;
        double feedDy = dy;
        if (!vertExempt) {
            feedDy = checkVertical(in, st, r, dy, tolV);
        } else {
            relax(st, CheckType.FLY);
            relax(st, CheckType.SPIDER);
            relax(st, CheckType.STEP);
        }

        // ---- Hovering: floating in place far above the ground (works the same for Bedrock) ----
        if (!vertExempt && !in.groundBelow && !in.onLiquidSurfaceOnly && Math.abs(dy) < 0.02) {
            st.hoverTicks++;
            if (st.hoverTicks >= 40) {
                st.hoverTicks = 0;
                r.failed.add(CheckType.FLY);
                r.violations.add(new MoveResult.Violation(CheckType.FLY, 1.5, "hovering in the air for 2 seconds"));
                if (s.setbacks) {
                    r.setbackTo = st.lastLegit != null ? st.lastLegit : in.from;
                }
            }
        } else {
            st.hoverTicks = 0;
        }

        // ---- Liquid walking ----
        if (in.clientOnGround && in.onLiquidSurfaceOnly && !skipAll) {
            fail(r, st, CheckType.JESUS, 1.0, "standing on liquid", false, in);
        } else {
            relax(st, CheckType.JESUS);
        }

        // ---- NoFall: claims to be on ground while clearly in the air. ----
        if (in.clientOnGround && !in.nearGround && !in.serverOnGround && !vertExempt && !in.onLiquidSurfaceOnly) {
            fail(r, st, CheckType.NOFALL, 1.0, "ground spoof", false, in);
        } else {
            relax(st, CheckType.NOFALL);
        }

        finish(in, st, r, feedDy, feedH);
        return r;
    }

    /** Most horizontal distance possible this tick, from the physics of the block under {@code from}. */
    double maxHorizontal(MoveInput in, MoveState st) {
        double slip = in.slipperiness;
        if (in.ticksSinceIce < 20) {
            slip = Math.max(slip, 0.989f);
        }
        // Vanilla order per tick: move = storedVelocity + acceleration (+ sprint-jump boost), then the stored
        // velocity is multiplied by the friction of the block the player stood on at the start of the tick.
        // So this tick's bound uses last tick's friction and this tick's acceleration.
        double accel;
        double nextFriction;
        // The server may learn about sprinting a tick late: always allow sprint speed.
        double speed = in.movementSpeed * (in.sprinting ? 1.0 : 1.3);
        if (in.inWater || in.ticksSinceLiquid < 5) {
            double eff = Math.max(in.waterMovementEfficiency, Math.min(3, in.depthStrider) / 3.0);
            accel = Math.max(0.04, 0.02 + (speed - 0.02) * eff) + (in.swimming ? 0.04 : 0.0);
            nextFriction = in.dolphinsGrace ? 0.96 : 0.9;
        } else if (in.inLava) {
            accel = 0.02;
            nextFriction = 0.5;
        } else if (in.serverWasOnGround || st.lastClientOnGround) {
            accel = speed * (0.21600002 / (slip * slip * slip));
            nextFriction = slip * 0.91;
        } else {
            accel = AIR_ACCEL;
            nextFriction = 0.91;
        }
        // Eating, drinking, blocking or drawing a bow slows walking to a fifth. Only judged once the item has been
        // in use for a moment (momentum from before fades through friction, which this already models) and not for
        // Bedrock, whose input arrives through Geyser.
        if (in.usingItemTicks >= 6 && !in.bedrock && !in.inWater && !in.inLava && in.ticksSinceLiquid >= 5) {
            accel *= 0.2;
        }
        double max = st.lastHorizontal * st.lastFriction + accel;
        boolean jumped = (in.serverWasOnGround || st.lastClientOnGround) && in.dy() > 0;
        if (jumped) {
            max += SPRINT_JUMP_BOOST;
        }
        if (in.onSlime || in.ticksSinceSlime < 20) {
            max += 0.1;
        }
        if (in.pushedByEntity) {
            // Crowds and mobs push players around a little every tick.
            max += 0.15;
        }
        if (in.ticksSinceFirework < 40) {
            max = Math.max(max, 2.5);
        }
        st.pendingFriction = nextFriction;
        return max;
    }

    private double jumpVelocity(MoveInput in) {
        double v = in.jumpStrength * (in.onHoney ? 0.5 : 1.0);
        if (in.jumpBoost >= 0) {
            v += 0.1 * (in.jumpBoost + 1);
        }
        return v;
    }

    /** @return the dy to remember for the next tick. */
    private double checkVertical(MoveInput in, MoveState st, MoveResult r, double dy, double tolV) {
        boolean wasGround = in.serverWasOnGround || st.lastClientOnGround;

        // Step: ground to ground in one tick can rise at most the step height.
        if (in.serverWasOnGround && in.serverOnGround && dy > 0) {
            if (dy > in.stepHeight + tolV && dy > jumpVelocity(in) + tolV) {
                fail(r, st, CheckType.STEP, dy - in.stepHeight, String.format(Locale.ROOT, "stepped %.2f", dy), true, in);
            } else {
                relax(st, CheckType.STEP);
            }
            relax(st, CheckType.FLY);
            return dy;
        }

        double expected;
        if (wasGround && dy > 0) {
            // Jumping. Also allow a step-up from the ground.
            expected = Math.max(jumpVelocity(in), in.stepHeight);
            if (in.onSlime || st.ticksSinceFall < 3) {
                expected = Math.max(expected, st.recentFallSpeed);
            }
            if (in.onBed) {
                expected = Math.max(expected, st.recentFallSpeed * 0.66 + 0.1);
            }
        } else {
            double g = (in.slowFalling && st.lastDy <= 0) ? Math.min(in.gravity, 0.01) : in.gravity;
            expected = (st.lastDy - g) * DRAG;
            // Bounces off slime and beds reverse the fall speed.
            if ((in.onSlime || in.ticksSinceSlime < 3) && st.ticksSinceFall < 3) {
                expected = Math.max(expected, st.recentFallSpeed);
            }
            if (in.onBed && st.ticksSinceFall < 3) {
                expected = Math.max(expected, st.recentFallSpeed * 0.66 + 0.1);
            }
            // A step-up while running off an edge onto a slab/stair.
            if (in.serverOnGround && dy > 0 && dy <= in.stepHeight + tolV) {
                expected = Math.max(expected, dy);
            }
        }

        boolean landed = in.serverOnGround && dy <= 0;
        if (!landed && dy > expected + tolV) {
            double over = dy - expected;
            CheckType type = (in.horizontalCollision && dy > 0) ? CheckType.SPIDER : CheckType.FLY;
            fail(r, st, type, over, String.format(Locale.ROOT, "dy %.3f > %.3f", dy, expected), over > 1.0, in);
            // Remember the real motion so one mismatch doesn't cascade, but cap upward motion so a cheat
            // can't launch itself and have the launch accepted as momentum next tick.
            return Math.min(dy, Math.max(expected, 0) + tolV);
        }
        relax(st, CheckType.FLY);
        relax(st, CheckType.SPIDER);
        return dy;
    }

    private void checkElytra(MoveInput in, MoveState st, MoveResult r, boolean velocityGrace) {
        double h = in.horizontal();
        double dy = in.dy();
        boolean boosted = in.ticksSinceFirework < 40 || velocityGrace || in.ticksSinceRiptide < 40;
        if (boosted) {
            relax(st, CheckType.ELYTRA);
            return;
        }
        double speed = Math.sqrt(h * h + dy * dy);
        // Diving can exceed 3.5 b/t; flat speed without a boost cannot stay above it.
        if (speed > 4.2 && dy > -0.5) {
            fail(r, st, CheckType.ELYTRA, speed - 4.2, String.format(Locale.ROOT, "%.2f b/t unboosted", speed), false, in);
        } else if (dy > 0.1 && st.lastHorizontal < 0.25 && st.lastDy <= 0.05) {
            // Gaining height with no speed to convert.
            fail(r, st, CheckType.ELYTRA, dy, "climbing without speed", false, in);
        } else {
            relax(st, CheckType.ELYTRA);
        }
    }

    private void finish(MoveInput in, MoveState st, MoveResult r, double feedDy, double feedH) {
        if (feedDy < 0) {
            st.recentFallSpeed = Math.max(-feedDy, st.ticksSinceFall < 3 ? st.recentFallSpeed : 0);
            st.ticksSinceFall = 0;
        } else {
            st.ticksSinceFall++;
        }
        st.lastDy = feedDy;
        st.lastHorizontal = feedH;
        st.lastFriction = st.pendingFriction;
        st.lastClientOnGround = in.clientOnGround;
        st.airTicks = in.serverOnGround ? 0 : st.airTicks + 1;
        boolean stable = in.serverOnGround || in.inWater || in.onClimbable;
        if (r.failed.isEmpty() && (stable || st.lastLegit == null)) {
            st.lastLegit = in.to;
        }
        if (r.setbackTo == null && !r.failed.isEmpty() && st.lastLegit == null) {
            st.lastLegit = in.from;
        }
    }

    private void relax(MoveState st, CheckType c) {
        Double b = st.buffers.get(c);
        if (b != null) {
            double nb = b - s.bufferDecay;
            if (nb <= 0) {
                st.buffers.remove(c);
            } else {
                st.buffers.put(c, nb);
            }
        }
    }

    /**
     * Buffers a failed check. Points are only produced once the buffer is full, so a single odd packet never
     * counts. A setback happens once the buffer is half full, or immediately for large violations.
     */
    private void fail(MoveResult r, MoveState st, CheckType c, double magnitude, String detail, boolean immediateSetback,
                      MoveInput in) {
        double limit = s.bufferLimit * (in.bedrock ? s.bedrockLeniency : 1.0);
        double b = st.buffer(c) + 1.0 + Math.min(Math.abs(magnitude) * 4.0, 3.0);
        r.failed.add(c);
        if (b >= limit) {
            double points = 1.0 + Math.min((b - limit) / limit, 2.0);
            r.violations.add(new MoveResult.Violation(c, points, detail));
            b = limit * 0.5;
        }
        st.buffers.put(c, b);
        if (s.setbacks && (immediateSetback || b >= limit * 0.5 || !r.violations.isEmpty())) {
            r.setbackTo = st.lastLegit != null ? st.lastLegit : in.from;
        }
    }
}
