package com.vylorq.anticheat.core.movement;

import com.vylorq.anticheat.core.util.Vec3;

/**
 * Everything the predictor needs to know about one movement packet. Filled by the Minecraft layer from the
 * server's view of the world (never from what the client claims, except {@link #clientOnGround}).
 */
public final class MoveInput {
    public Vec3 from = Vec3.ZERO;
    public Vec3 to = Vec3.ZERO;
    public float yaw;
    public float pitch;
    public boolean clientOnGround;
    /** Server collision test: the player box at {@code to} is resting on a block. */
    public boolean serverOnGround;
    /** Server collision test: the player box at {@code from} was resting on a block. */
    public boolean serverWasOnGround;
    /** Any solid block within 0.6 below the player's feet at {@code to} (loose ground test used by NoFall). */
    public boolean nearGround;
    /** Player box at {@code to} overlaps a solid block that the box at {@code from} did not. */
    public boolean movedIntoSolid;
    public boolean horizontalCollision;
    /** A block directly above the head (head bump). */
    public boolean blockAbove;
    /** Standing on liquid with nothing solid underneath (water walking). */
    public boolean onLiquidSurfaceOnly;

    // Blocks the player touches or stands on.
    /** Slipperiness of the block below: 0.6 normal, 0.8 slime, 0.98 ice, 0.989 blue ice. */
    public float slipperiness = 0.6f;
    public boolean onSlime;
    public boolean onHoney;
    public boolean onSoulSand;
    public boolean inCobweb;
    public boolean inPowderSnow;
    public boolean inBerryBush;
    public boolean onClimbable;
    public boolean inScaffolding;
    public boolean inWater;
    public boolean inLava;
    public boolean inBubbleColumn;
    public boolean onBed;
    /** Pistons or slime-block machines touched the player recently. */
    public boolean pistonNearby;

    // Player state.
    public boolean sprinting;
    public boolean sneaking;
    public boolean swimming;
    public boolean crawling;
    public boolean gliding;
    public boolean usingItem;
    public boolean inVehicle;
    public boolean flightAllowed;
    public boolean creativeOrSpectator;
    public boolean riptiding;
    public boolean sleeping;
    public boolean dead;

    // Attributes (1.21 exposes these directly and they already include effect modifiers).
    public double movementSpeed = 0.1;
    public double jumpStrength = 0.42;
    public double gravity = 0.08;
    public double stepHeight = 0.6;
    public double waterMovementEfficiency;
    public double movementEfficiency;
    public double sneakingSpeed = 0.3;

    // Effects (-1 = none).
    public int jumpBoost = -1;
    public int levitation = -1;
    public boolean slowFalling;
    public boolean dolphinsGrace;
    public int depthStrider;
    public int soulSpeed;

    // Timing and safeguards.
    /** Ticks since the server applied velocity (knockback, explosion, wind charge, breeze, fishing rod, mace). */
    public int ticksSinceVelocity = 1000;
    /** Magnitude of that velocity (blocks/tick). */
    public double lastVelocity;
    public int ticksSinceTeleport = 1000;
    public int ticksSinceJoinOrRespawn = 1000;
    public int ticksSinceDimensionChange = 1000;
    public int ticksSinceGlide = 1000;
    public int ticksSinceFirework = 1000;
    public int ticksSinceVehicle = 1000;
    public int ticksSinceRiptide = 1000;
    public int ticksSinceClimbable = 1000;
    public int ticksSinceLiquid = 1000;
    public int ticksSinceSlime = 1000;
    public int ticksSinceIce = 1000;
    public boolean chunksLoaded = true;
    public int pingMs;
    public double tps = 20.0;
    public boolean bedrock;
    /** When the packet arrived (for the timer check). */
    public long arrivalNanos;

    public double dx() {
        return to.x() - from.x();
    }

    public double dy() {
        return to.y() - from.y();
    }

    public double dz() {
        return to.z() - from.z();
    }

    public double horizontal() {
        return Math.sqrt(dx() * dx() + dz() * dz());
    }
}
