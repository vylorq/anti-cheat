package com.vylorq.anticheat.core.movement;

import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.EnumMap;
import java.util.Map;

/** Per-player movement history kept between packets. */
public final class MoveState {
    public double lastDy;
    public double lastHorizontal;
    /** Friction that vanilla applied to the stored velocity at the end of the last tick. */
    public double lastFriction = 0.91;
    double pendingFriction = 0.91;
    public boolean lastClientOnGround = true;
    public int airTicks;
    /** Fastest fall speed in the last few ticks (for slime and bed bounces). */
    public double recentFallSpeed;
    public int ticksSinceFall = 1000;
    public Vec3 lastLegit;
    public final Map<CheckType, Double> buffers = new EnumMap<>(CheckType.class);
    public double timerBalanceMs;
    public long lastArrivalNanos;
    public int vehicleAirUpTicks;
    public double lastVehicleDy;
    public double lastVehicleHorizontal;

    public double buffer(CheckType c) {
        return buffers.getOrDefault(c, 0.0);
    }

    /** Clears velocity memory after teleports, respawns and similar server-side jumps. */
    public void resetMotion(Vec3 at) {
        lastDy = 0;
        lastHorizontal = 0;
        lastFriction = 0.91;
        airTicks = 0;
        recentFallSpeed = 0;
        timerBalanceMs = 0;
        lastArrivalNanos = 0;
        lastLegit = at;
    }
}
