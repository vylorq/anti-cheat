package com.vylorq.anticheat;

import com.vylorq.anticheat.core.combat.CombatTracker;
import com.vylorq.anticheat.core.combat.PositionHistory;
import com.vylorq.anticheat.core.deaths.DeathRecord;
import com.vylorq.anticheat.core.movement.MoveState;
import com.vylorq.anticheat.core.xray.MiningAnalyzer;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Runtime (not saved) state for one online player. */
public final class PlayerSession {
    public final UUID id;
    public String name;
    public boolean bedrock;
    public String ip;
    public long joinedAt;

    // Movement safeguards (ticks since...).
    public final MoveState move = new MoveState();
    public int ticksSinceTeleport;
    public int ticksSinceJoin;
    public int ticksSinceDimension = 1000;
    public int ticksSinceVelocity = 1000;
    public double lastVelocity;
    public int ticksSinceGlide = 1000;
    public int ticksSinceFirework = 1000;
    public int ticksSinceVehicle = 1000;
    public int ticksSinceRiptide = 1000;
    public int ticksSinceClimbable = 1000;
    public int ticksSinceLiquid = 1000;
    public int ticksSinceSlime = 1000;
    public int ticksSinceIce = 1000;
    public int movesThisTick;

    // Combat.
    public final CombatTracker combat = new CombatTracker();
    public final PositionHistory history = new PositionHistory(2000);
    public float lastYaw;
    public float lastPitch;
    public float rotationThisTick;
    public long lastAttackTick = -1;
    public int attacksThisTick;

    // World.
    public final MiningAnalyzer mining = new MiningAnalyzer();
    public int trapHits;
    public long lastTrapHit;

    // Tools.
    public BlockPos corner1;
    public BlockPos corner2;
    public String cornerWorld;
    public boolean nextCornerIsSecond;
    public UUID traderMove;

    // Death log: damage taken in the last seconds.
    public final Deque<DeathRecord.DamageEntry> recentDamage = new ArrayDeque<>();

    // Chat questions (waiting room / trader rename etc.).
    public java.util.function.Consumer<String> chatPrompt;

    /** Position every 5 seconds for the last 10 minutes (the /inspect movement trail). */
    public final Deque<String> trail = new ArrayDeque<>();
    public int trailTimer;
    /** Open container being watched for the block log: position, world and item counts at open. */
    public BlockPos openContainer;
    public String openContainerWorld;
    public java.util.Map<String, Integer> openContainerCounts;
    public int dupeSampleTimer;
    public int lastDupeValue;
    public String lastClaimId;
    public boolean pinPrompted;

    public PlayerSession(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.joinedAt = System.currentTimeMillis();
    }

    /** Called every server tick. */
    public void tick() {
        ticksSinceTeleport++;
        ticksSinceJoin++;
        ticksSinceDimension++;
        ticksSinceVelocity++;
        ticksSinceGlide++;
        ticksSinceFirework++;
        ticksSinceVehicle++;
        ticksSinceRiptide++;
        ticksSinceClimbable++;
        ticksSinceLiquid++;
        ticksSinceSlime++;
        ticksSinceIce++;
        movesThisTick = 0;
        rotationThisTick = 0;
    }

    public void teleported() {
        ticksSinceTeleport = 0;
        move.resetMotion(null);
    }
}
