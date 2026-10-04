package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.barrier.BarrierManager;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.claims.ClaimAction;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.movement.MoveInput;
import com.vylorq.anticheat.core.movement.MoveResult;
import com.vylorq.anticheat.core.movement.SafeSpot;
import com.vylorq.anticheat.core.movement.WorldView;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import com.vylorq.anticheat.util.Tps;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Everything that happens when a movement packet arrives (sections 7, 15, 17, 19, 21, 25): freeze, barriers,
 * confinement, private claims, then movement prediction with setbacks.
 */
public final class Movement {
    private Movement() {
    }

    /**
     * Called on the server thread before vanilla handles the packet.
     *
     * @return true to reject the packet (the player has been pulled back)
     */
    public static boolean onMove(ServerPlayerEntity p, double x, double y, double z, float yaw, float pitch,
                                 boolean onGround, boolean changesPos, boolean changesLook) {
        return onMove(p, x, y, z, yaw, pitch, onGround, changesPos, changesLook, System.nanoTime());
    }

    /** A block change by this player was refused: their client shows a ghost block for a moment. */
    public static void ghostBlock(ServerPlayerEntity p) {
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        if (s != null) {
            s.ticksSinceGhostBlock = 0;
        }
    }

    /** @param arrivalNanos when the packet reached the server's network thread */
    public static boolean onMove(ServerPlayerEntity p, double x, double y, double z, float yaw, float pitch,
                                 boolean onGround, boolean changesPos, boolean changesLook, long arrivalNanos) {
        Ac ac = Ac.get();
        if (ac == null || p.isRemoved()) {
            return false;
        }
        PlayerSession s = Ac.session(p);
        s.movesThisTick++;
        if (changesLook) {
            PacketChecks.rotation(p, pitch);
        }
        PacketChecks.sprint(p);
        // Mining bots: camera snaps with no mouse step (Java only: Bedrock has no mouse).
        if (!s.bedrock && Ac.config().combat.enabled) {
            float by = changesLook ? net.minecraft.util.math.MathHelper.wrapDegrees(yaw - s.lastYaw) : 0;
            float bp = changesLook ? pitch - s.lastPitch : 0;
            if (s.bot.onMove(System.currentTimeMillis(), by, bp, System.currentTimeMillis() - s.lastBreakMs < 2000)) {
                ac.engine.flag(p.getUuid(), p.getGameProfile().name(), CheckType.BOT, 1.0,
                        "turns like a mining bot (instant snaps, no mouse step)", s.bedrock);
            }
        }
        Combat.lookCheck(p, s, yaw, pitch);
        if (changesLook) {
            float dYaw = Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(yaw - s.lastYaw));
            s.rotationThisTick += dYaw + Math.abs(pitch - s.lastPitch);
            s.combat.aim.onRotation(yaw, pitch);
            s.lastYaw = yaw;
            s.lastPitch = pitch;
        }
        if (!changesPos) {
            return false;
        }
        ServerWorld world = (ServerWorld) p.getEntityWorld();
        String w = Mc.worldId(world);
        Vec3 from = Mc.vec(p.getEntityPos());
        Vec3 to = new Vec3(x, y, z);
        boolean moved = from.distanceSq(to) > 1.0E-6;

        // Frozen players and arena countdowns: no movement at all (turning is fine).
        if (moved && (ac.staff.isFrozen(p.getUuid()) || Arenas.isCountdownFrozen(p) || ScareWarning.pending(p))) {
            pullBack(p, from);
            return true;
        }

        // Barriers: nobody gets in or out.
        if (moved && Features.on(Features.Feature.BARRIERS)) {
            boolean bypass = Perms.isActiveStaff(p);
            BarrierManager.Verdict v = ac.barriers.check(p.getUuid(), bypass, w, from, w, to);
            if (!v.allowed()) {
                Vec3 back = v.sendBackTo() != null ? v.sendBackTo() : from;
                if (v.wrongSide()) {
                    // Joined, respawned or spawned on the wrong side, or moved there by a teleport: not cheating, just
                    // put them back on their side, standing on the ground.
                    ServerWorld bw = Mc.world(ac.server, v.barrier().world);
                    Barriers.sendTo(p, bw != null ? bw : world, back);
                } else {
                    pullBack(p, from);
                }
                Barriers.showWall(p, v.barrier());
                Ac.markDirty("barriers");
                return true;
            }
        }

        // Jail: stay near the cell.
        if (moved && Jail.confine(p, to)) {
            return true;
        }
        // Waiting room: can't leave.
        if (moved && WaitingRoomFeature.confine(p, to)) {
            return true;
        }

        // Claims: private claims push non-members back at the border.
        if (moved) {
            Claim target = ac.claims.at(w, x, z);
            if (target != null && !Claims.canEnter(p, target)) {
                Claim current = ac.claims.at(w, from.x(), from.z());
                if (current != target) {
                    Claims.denyEntry(p, target);
                    pullBack(p, from);
                    return true;
                }
            }
            ClaimManager.Transition t = ac.claims.updatePresence(p.getUuid(), p.getGameProfile().name(), w, x, z);
            if (t != null) {
                Claims.onTransition(p, t);
            }
        }

        // Evidence: position and rotation every packet.
        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.MOVE, x, y, z, yaw, pitch, null);
        s.history.add(System.currentTimeMillis(), to, p.getWidth(), p.getHeight());

        // Prediction.
        if (!Ac.config().movement.enabled) {
            return false;
        }
        MoveInput in = input(p, s, world, from, to, onGround);
        in.arrivalNanos = arrivalNanos;
        MoveResult r = ac.predictor.process(in, s.move);
        // Anti-knockback: did a push upwards show up? Ceilings, liquids, ladders, cobwebs and the like excuse it.
        s.velocity.onMove(in.dy(), in.inWater || in.inLava || in.onClimbable || in.inCobweb || in.inScaffolding
                || in.inPowderSnow || in.inBubbleColumn || in.levitation >= 0 || in.blockAbove || in.inVehicle || in.gliding
                || in.flightAllowed || in.creativeOrSpectator || in.touchingHoney || in.ticksSinceGhostBlock < 40 || in.riptiding
                || in.inBerryBush);
        for (MoveResult.Violation v : r.violations) {
            ac.engine.flag(p.getUuid(), p.getGameProfile().name(), v.check(), v.points(), v.detail(), s.bedrock);
        }
        if (r.setbackTo != null && ac.exempt.setbacksApply(p.getUuid())) {
            Vec3 safe = SafeSpot.find(view(world), r.setbackTo, 6);
            if (safe == null) {
                safe = from;
            }
            pullBack(p, safe);
            s.move.lastLegit = safe;
            return true;
        }
        return false;
    }

    /** Setback: not a punishment, just cancels the move. */
    public static void pullBack(ServerPlayerEntity p, Vec3 to) {
        p.networkHandler.requestTeleport(to.x(), to.y(), to.z(), p.getYaw(), p.getPitch());
    }

    public static WorldView view(ServerWorld world) {
        return new WorldView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState st = world.getBlockState(pos);
                return !st.getCollisionShape(world, pos).isEmpty() && st.getFluidState().isEmpty();
            }

            @Override
            public boolean isPassable(int x, int y, int z) {
                BlockPos pos = new BlockPos(x, y, z);
                return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
            }

            @Override
            public boolean isDangerous(int x, int y, int z) {
                BlockState st = world.getBlockState(new BlockPos(x, y, z));
                return st.isOf(Blocks.LAVA) || st.isOf(Blocks.FIRE) || st.isOf(Blocks.SOUL_FIRE) || st.isOf(Blocks.MAGMA_BLOCK)
                        || st.isOf(Blocks.CACTUS) || st.isOf(Blocks.SWEET_BERRY_BUSH) || st.isOf(Blocks.WITHER_ROSE)
                        || st.isOf(Blocks.POWDER_SNOW) || st.isOf(Blocks.CAMPFIRE) || st.isOf(Blocks.SOUL_CAMPFIRE)
                        || st.isOf(Blocks.POINTED_DRIPSTONE);
            }

            @Override
            public int minY() {
                return world.getBottomY();
            }

            @Override
            public int maxY() {
                return world.getTopYInclusive();
            }
        };
    }

    private static boolean blockCollides(ServerWorld w, Entity e, Box box) {
        for (net.minecraft.util.shape.VoxelShape shape : w.getBlockCollisions(e, box)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean chunkLoaded(ServerWorld w, double x, double z) {
        return w.getChunkManager().isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }

    private static boolean blockIn(ServerWorld w, Box box, java.util.function.Predicate<BlockState> test) {
        for (BlockPos pos : BlockPos.iterate(
                BlockPos.ofFloored(box.minX, box.minY, box.minZ), BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {
            if (test.test(w.getBlockState(pos))) {
                return true;
            }
        }
        return false;
    }

    private static int amp(ServerPlayerEntity p, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect> e) {
        StatusEffectInstance i = p.getStatusEffect(e);
        return i == null ? -1 : i.getAmplifier();
    }

    /** Builds the prediction input from the server's view of the world. */
    static MoveInput input(ServerPlayerEntity p, PlayerSession s, ServerWorld w, Vec3 from, Vec3 to, boolean clientOnGround) {
        MoveInput in = new MoveInput();
        in.from = from;
        in.to = to;
        in.yaw = p.getYaw();
        in.pitch = p.getPitch();
        in.clientOnGround = clientOnGround;
        in.arrivalNanos = System.nanoTime();
        in.chunksLoaded = chunkLoaded(w, to.x(), to.z()) && chunkLoaded(w, from.x(), from.z());
        in.tps = Tps.tps();
        in.pingMs = p.networkHandler.getLatency();
        in.bedrock = s.bedrock;

        Box boxFrom = p.getBoundingBox();
        Box boxTo = boxFrom.offset(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
        Box feetTo = new Box(boxTo.minX + 0.001, boxTo.minY - 0.03, boxTo.minZ + 0.001, boxTo.maxX - 0.001, boxTo.minY, boxTo.maxZ - 0.001);
        Box feetFrom = new Box(boxFrom.minX + 0.001, boxFrom.minY - 0.03, boxFrom.minZ + 0.001, boxFrom.maxX - 0.001, boxFrom.minY, boxFrom.maxZ - 0.001);
        Box nearTo = new Box(boxTo.minX, boxTo.minY - 0.6, boxTo.minZ, boxTo.maxX, boxTo.minY, boxTo.maxZ);
        in.serverOnGround = !w.isSpaceEmpty(p, feetTo);
        in.serverWasOnGround = !w.isSpaceEmpty(p, feetFrom) || p.isOnGround();
        in.nearGround = !w.isSpaceEmpty(p, nearTo);
        in.groundBelow = !w.isSpaceEmpty(p, new Box(boxTo.minX, boxTo.minY - 2.0, boxTo.minZ, boxTo.maxX, boxTo.minY, boxTo.maxZ));
        // Only blocks count (a boat or another player bumping into you isn't phasing). Bedrock players' hitboxes
        // and some block shapes differ a little from Java, so they get more room.
        double inset = s.bedrock ? 0.2 : 0.08;
        in.movedIntoSolid = blockCollides(w, p, boxTo.contract(inset)) && !blockCollides(w, p, boxFrom.contract(inset));
        in.pushedByEntity = !w.getOtherEntities(p, boxTo.expand(0.3), e -> e.isPushable() && !e.isSpectator()).isEmpty();
        Box around = new Box(boxTo.minX - 0.06, boxTo.minY + 0.05, boxTo.minZ - 0.06, boxTo.maxX + 0.06, boxTo.maxY - 0.05, boxTo.maxZ + 0.06);
        in.horizontalCollision = !w.isSpaceEmpty(p, around);
        in.blockAbove = !w.isSpaceEmpty(p, new Box(boxTo.minX, boxTo.maxY, boxTo.minZ, boxTo.maxX, boxTo.maxY + 0.2, boxTo.maxZ));

        BlockPos below = BlockPos.ofFloored(from.x(), from.y() - 0.5000001, from.z());
        BlockState belowState = w.getBlockState(below);
        in.slipperiness = belowState.getBlock().getSlipperiness();
        if (in.slipperiness > 0.9f) {
            s.ticksSinceIce = 0;
        }
        BlockPos belowTo = BlockPos.ofFloored(to.x(), to.y() - 0.5000001, to.z());
        BlockState belowToState = w.getBlockState(belowTo);
        in.onSlime = belowState.isOf(Blocks.SLIME_BLOCK) || belowToState.isOf(Blocks.SLIME_BLOCK);
        if (in.onSlime) {
            s.ticksSinceSlime = 0;
        }
        in.onHoney = belowState.isOf(Blocks.HONEY_BLOCK);
        in.onSoulSand = belowState.isOf(Blocks.SOUL_SAND) || belowState.isOf(Blocks.SOUL_SOIL);
        in.onBed = belowToState.getBlock() instanceof BedBlock || belowState.getBlock() instanceof BedBlock;
        // One pass over the blocks the player touches instead of a separate scan per block type.
        boolean climb = false, water = false, lava = false;
        Box touch = boxTo.expand(0.05, 0.1, 0.05);
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(touch.minX, touch.minY, touch.minZ),
                BlockPos.ofFloored(touch.maxX, touch.maxY, touch.maxZ))) {
            BlockState st = w.getBlockState(pos);
            if (st.isAir()) {
                continue;
            }
            if (st.isOf(Blocks.COBWEB)) in.inCobweb = true;
            else if (st.isOf(Blocks.POWDER_SNOW)) in.inPowderSnow = true;
            else if (st.isOf(Blocks.SWEET_BERRY_BUSH)) in.inBerryBush = true;
            else if (st.isOf(Blocks.SCAFFOLDING)) in.inScaffolding = true;
            else if (st.isOf(Blocks.BUBBLE_COLUMN)) in.inBubbleColumn = true;
            else if (st.isOf(Blocks.HONEY_BLOCK)) in.touchingHoney = true;
            if (st.isIn(BlockTags.CLIMBABLE)) climb = true;
            if (!st.getFluidState().isEmpty()) {
                if (st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) water = true;
                else if (st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) lava = true;
            }
        }
        in.onClimbable = p.isClimbing() || climb;
        if (in.onClimbable) {
            s.ticksSinceClimbable = 0;
        }
        in.inWater = p.isTouchingWater() || water;
        in.inLava = p.isInLava() || lava;
        if (in.inWater || in.inLava) {
            s.ticksSinceLiquid = 0;
        }
        BlockState feetState = w.getBlockState(BlockPos.ofFloored(to.x(), to.y() - 0.1, to.z()));
        in.onLiquidSurfaceOnly = !feetState.getFluidState().isEmpty() && !in.nearGround && !in.inWater;
        // Pistons only matter for moves that could otherwise fail a check; skip the scan for small moves.
        in.pistonNearby = to.distanceSq(from) > 0.04
                && blockIn(w, boxTo.expand(1.5), st -> st.isOf(Blocks.MOVING_PISTON) || st.isOf(Blocks.PISTON_HEAD));

        in.sprinting = p.isSprinting();
        in.sneaking = p.isSneaking();
        in.swimming = p.isSwimming();
        in.crawling = p.isCrawling();
        in.gliding = p.isGliding();
        if (in.gliding) {
            s.ticksSinceGlide = 0;
        }
        in.usingItem = p.isUsingItem();
        in.usingItemTicks = p.isUsingItem() ? p.getItemUseTime() : 0;
        in.inVehicle = p.hasVehicle();
        if (in.inVehicle) {
            s.ticksSinceVehicle = 0;
        }
        in.flightAllowed = p.getAbilities().allowFlying;
        in.creativeOrSpectator = p.isCreative() || p.isSpectator();
        in.riptiding = p.isUsingRiptide();
        if (in.riptiding) {
            s.ticksSinceRiptide = 0;
        }
        in.sleeping = p.isSleeping();
        in.dead = p.isDead();

        in.movementSpeed = p.getAttributeValue(EntityAttributes.MOVEMENT_SPEED);
        in.jumpStrength = p.getAttributeValue(EntityAttributes.JUMP_STRENGTH);
        in.gravity = p.getAttributeValue(EntityAttributes.GRAVITY);
        in.stepHeight = p.getAttributeValue(EntityAttributes.STEP_HEIGHT);
        in.waterMovementEfficiency = p.getAttributeValue(EntityAttributes.WATER_MOVEMENT_EFFICIENCY);
        in.movementEfficiency = p.getAttributeValue(EntityAttributes.MOVEMENT_EFFICIENCY);
        in.sneakingSpeed = p.getAttributeValue(EntityAttributes.SNEAKING_SPEED);

        in.jumpBoost = amp(p, StatusEffects.JUMP_BOOST);
        in.levitation = amp(p, StatusEffects.LEVITATION);
        in.slowFalling = amp(p, StatusEffects.SLOW_FALLING) >= 0;
        in.dolphinsGrace = amp(p, StatusEffects.DOLPHINS_GRACE) >= 0;

        in.ticksSinceVelocity = s.ticksSinceVelocity;
        in.lastVelocity = s.lastVelocity;
        in.ticksSinceTeleport = s.ticksSinceTeleport;
        in.ticksSinceJoinOrRespawn = s.ticksSinceJoin;
        in.ticksSinceDimensionChange = s.ticksSinceDimension;
        in.ticksSinceGlide = s.ticksSinceGlide;
        in.ticksSinceFirework = s.ticksSinceFirework;
        in.ticksSinceVehicle = s.ticksSinceVehicle;
        in.ticksSinceRiptide = s.ticksSinceRiptide;
        in.ticksSinceClimbable = s.ticksSinceClimbable;
        in.ticksSinceLiquid = s.ticksSinceLiquid;
        in.ticksSinceSlime = s.ticksSinceSlime;
        in.ticksSinceIce = s.ticksSinceIce;
        in.ticksSinceGhostBlock = s.ticksSinceGhostBlock;
        return in;
    }

    // ---- Vehicles ----

    /** Checks a vehicle move after vanilla accepted it. Flags only; big violations dismount the player. */
    public static void afterVehicleMove(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (ac == null || !Ac.config().movement.enabled || p.isCreative() || p.isSpectator()) {
            return;
        }
        Entity v = p.getRootVehicle();
        if (v == p) {
            return;
        }
        PlayerSession s = Ac.session(p);
        s.ticksSinceVehicle = 0;
        Vec3 now = Mc.vec(v.getEntityPos());
        Vec3 last = VehicleTrack.last(p.getUuid(), v, now);
        if (last == null || s.ticksSinceTeleport < 40 || s.ticksSinceVelocity < 40 || Tps.tps() < Ac.config().general.lagTpsThreshold) {
            VehicleTrack.set(p.getUuid(), v, now);
            return;
        }
        double h = now.horizontalDistance(last);
        double dy = now.y() - last.y();
        String type = net.minecraft.registry.Registries.ENTITY_TYPE.getId(v.getType()).getPath();
        double maxH = switch (type) {
            case "boat", "chest_boat", "oak_boat", "spruce_boat", "birch_boat", "jungle_boat", "acacia_boat", "dark_oak_boat",
                    "mangrove_boat", "cherry_boat", "pale_oak_boat", "bamboo_raft", "oak_chest_boat", "spruce_chest_boat",
                    "birch_chest_boat", "jungle_chest_boat", "acacia_chest_boat", "dark_oak_chest_boat",
                    "mangrove_chest_boat", "cherry_chest_boat", "pale_oak_chest_boat", "bamboo_chest_raft" -> 4.3;
            case "camel" -> 2.0;
            case "minecart" -> 1.2;
            default -> 1.6;
        };
        double lenient = s.bedrock ? Ac.config().movement.bedrockLeniency : 1.0;
        boolean onGround = v.isOnGround() || v.isTouchingWater() || v.isInLava();
        if (!onGround && dy > 0.05) {
            s.move.vehicleAirUpTicks++;
        } else {
            s.move.vehicleAirUpTicks = 0;
        }
        int maxAirUp = type.contains("boat") || type.contains("raft") ? 4 : 14;
        // Flying mounts and carts climbing rails rise legitimately.
        boolean mayRise = type.equals("happy_ghast") || type.contains("minecart") || v.hasNoGravity();
        String problem = null;
        if (h > maxH * lenient) {
            problem = String.format(java.util.Locale.ROOT, "%s %.2f b/t", type, h);
        } else if (s.move.vehicleAirUpTicks > maxAirUp * lenient && !mayRise) {
            problem = type + " rising in the air";
        }
        if (problem != null) {
            ac.engine.flag(p.getUuid(), p.getGameProfile().name(), CheckType.VEHICLE, 1.0, problem, s.bedrock);
            if ((!mayRise && s.move.vehicleAirUpTicks > maxAirUp * 3) || h > maxH * 2.5) {
                p.stopRiding();
                pullBack(p, last);
                s.move.vehicleAirUpTicks = 0;
            }
        }
        VehicleTrack.set(p.getUuid(), v, now);
    }

    /** Remembers each rider's vehicle position. */
    static final class VehicleTrack {
        private static final java.util.Map<java.util.UUID, Object[]> LAST = new java.util.concurrent.ConcurrentHashMap<>();

        static Vec3 last(java.util.UUID player, Entity v, Vec3 fallback) {
            Object[] o = LAST.get(player);
            if (o == null || o[0] != v) {
                return null;
            }
            return (Vec3) o[1];
        }

        static void set(java.util.UUID player, Entity v, Vec3 pos) {
            LAST.put(player, new Object[]{v, pos});
        }

        static void forget(java.util.UUID player) {
            LAST.remove(player);
        }
    }

    public static void forget(java.util.UUID player) {
        VehicleTrack.forget(player);
    }

    /** Used by messages: remaining claim time. */
    static String remaining(Claim c) {
        return Durations.formatRemaining(c.expiresAt == Durations.PERMANENT ? Durations.PERMANENT
                : (c.paused ? System.currentTimeMillis() + c.pausedRemaining : c.expiresAt), System.currentTimeMillis());
    }

    static boolean can(ServerPlayerEntity p, Claim c, ClaimAction a) {
        return Claims.can(p, c, a);
    }

}
