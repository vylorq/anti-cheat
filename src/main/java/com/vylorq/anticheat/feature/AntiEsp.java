package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.perm.Perms;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-ESP. Wallhacks can only show what the game was told about, so:
 * <ul>
 *   <li>Players far away and fully hidden behind solid blocks aren't sent; they're sent again the moment they could
 *   be seen (checked several times a second, with a margin so nobody pops in late).</li>
 *   <li>Chests, barrels and shulker boxes are left out of map data and only appear within a short distance.</li>
 * </ul>
 */
public final class AntiEsp {
    private AntiEsp() {
    }

    /** Implemented by the entity tracker mixin: re-decide whether {@code viewer} should be sent this entity. */
    public interface Tracker {
        void ac$recheck(ServerPlayerEntity viewer);
    }

    private static final Map<Entity, Tracker> TRACKERS = Collections.synchronizedMap(new WeakHashMap<>());
    /** viewer -> players currently not sent to them. */
    private static final Map<UUID, Set<UUID>> HIDDEN = new ConcurrentHashMap<>();
    /** viewer -> target -> last tick the target could be seen. */
    private static final Map<UUID, Map<UUID, Long>> SEEN = new ConcurrentHashMap<>();
    /** viewer -> containers already shown to them (packed positions), per world. */
    private static final Map<UUID, Set<Long>> REVEALED = new ConcurrentHashMap<>();
    /** Containers left out of the chunk currently being sent (server thread only). */
    public static final ThreadLocal<Set<BlockPos>> HIDDEN_CONTAINERS = new ThreadLocal<>();

    public static void register(Entity e, Tracker t) {
        if (e instanceof ServerPlayerEntity) {
            TRACKERS.put(e, t);
        }
    }

    public static boolean hidden(ServerPlayerEntity viewer, Entity target) {
        Set<UUID> s = HIDDEN.get(viewer.getUuid());
        return s != null && s.contains(target.getUuid());
    }

    private static AcConfig.AntiEsp cfg() {
        return Ac.config().antiEsp;
    }

    private static boolean on() {
        return Features.on(Features.Feature.ANTI_ESP);
    }

    public static void tick(MinecraftServer server, long tick) {
        if (tick % 4 == 0) {
            players(server, tick);
        }
        if (tick % 10 == 5) {
            containers(server);
        }
    }

    public static void forget(UUID player) {
        HIDDEN.remove(player);
        SEEN.remove(player);
        REVEALED.remove(player);
        for (Set<UUID> s : HIDDEN.values()) {
            s.remove(player);
        }
    }

    // ---------------------------------------------------------------- players

    private static void players(MinecraftServer server, long tick) {
        boolean enabled = on() && cfg().players;
        for (ServerWorld w : server.getWorlds()) {
            var list = w.getPlayers();
            for (ServerPlayerEntity viewer : list) {
                Set<UUID> before = HIDDEN.getOrDefault(viewer.getUuid(), Set.of());
                Set<UUID> now = new HashSet<>();
                if (enabled) {
                    for (ServerPlayerEntity target : list) {
                        if (target != viewer && hide(w, viewer, target, tick)) {
                            now.add(target.getUuid());
                        }
                    }
                }
                if (now.equals(before)) {
                    continue;
                }
                HIDDEN.put(viewer.getUuid(), now);
                Set<UUID> changed = new HashSet<>(before);
                changed.addAll(now);
                for (UUID t : changed) {
                    if (before.contains(t) != now.contains(t)) {
                        ServerPlayerEntity target = server.getPlayerManager().getPlayer(t);
                        Tracker tr = target == null ? null : TRACKERS.get(target);
                        if (tr != null) {
                            tr.ac$recheck(viewer);
                        }
                    }
                }
            }
        }
    }

    private static boolean hide(ServerWorld w, ServerPlayerEntity viewer, ServerPlayerEntity target, long tick) {
        if (viewer.isSpectator() || target.isSpectator() || Perms.isActiveStaff(viewer)
                || viewer.hasVehicle() || target.hasVehicle() || target.hasPassengers()
                || target.isGlowing() || target.hasStatusEffect(StatusEffects.GLOWING)) {
            return false;
        }
        double show = cfg().playerShowDistance;
        if (viewer.squaredDistanceTo(target) <= show * show || viewer.squaredDistanceTo(target) > 160 * 160) {
            return false;
        }
        var teams = Ac.get().teams;
        if (teams != null && (teams.sameTeam(viewer.getUuid(), target.getUuid()) || teams.allied(viewer.getUuid(), target.getUuid()))) {
            return false;
        }
        Map<UUID, Long> seen = SEEN.computeIfAbsent(viewer.getUuid(), k -> new HashMap<>());
        Long last = seen.get(target.getUuid());
        if (canSee(w, viewer, target)) {
            seen.put(target.getUuid(), tick);
            return false;
        }
        // Stay shown for two seconds after they were last visible, so nobody flickers at a corner.
        return last == null || tick - last > 40;
    }

    /** Line of sight through anything that isn't a solid opaque block, also a few ticks ahead for both of them. */
    static boolean canSee(ServerWorld w, ServerPlayerEntity viewer, ServerPlayerEntity target) {
        Vec3d eye = viewer.getEyePos();
        Vec3d eyeAhead = eye.add(viewer.getVelocity().multiply(6));
        Vec3d ahead = target.getVelocity().multiply(6);
        var b = target.getBoundingBox();
        double cx = (b.minX + b.maxX) / 2;
        double cz = (b.minZ + b.maxZ) / 2;
        Vec3d[] points = {
                new Vec3d(cx, b.maxY - 0.1, cz), new Vec3d(cx, (b.minY + b.maxY) / 2, cz), new Vec3d(cx, b.minY + 0.1, cz),
                new Vec3d(b.minX - 0.3, (b.minY + b.maxY) / 2, cz), new Vec3d(b.maxX + 0.3, (b.minY + b.maxY) / 2, cz),
                new Vec3d(cx, (b.minY + b.maxY) / 2, b.minZ - 0.3), new Vec3d(cx, (b.minY + b.maxY) / 2, b.maxZ + 0.3),
        };
        for (Vec3d pt : points) {
            if (clear(w, eye, pt) || clear(w, eyeAhead, pt.add(ahead))) {
                return true;
            }
        }
        return false;
    }

    /** Walks the blocks between two points; only solid, opaque full blocks block the view. Never loads chunks. */
    public static boolean clear(ServerWorld w, Vec3d a, Vec3d b) {
        int x = MathHelper.floor(a.x);
        int y = MathHelper.floor(a.y);
        int z = MathHelper.floor(a.z);
        int ex = MathHelper.floor(b.x);
        int ey = MathHelper.floor(b.y);
        int ez = MathHelper.floor(b.z);
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        int sx = dx > 0 ? 1 : -1;
        int sy = dy > 0 ? 1 : -1;
        int sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.MAX_VALUE : ((sx > 0 ? (x + 1 - a.x) : (a.x - x)) * tdx);
        double ty = dy == 0 ? Double.MAX_VALUE : ((sy > 0 ? (y + 1 - a.y) : (a.y - y)) * tdy);
        double tz = dz == 0 ? Double.MAX_VALUE : ((sz > 0 ? (z + 1 - a.z) : (a.z - z)) * tdz);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        WorldChunk chunk = null;
        for (int i = 0; i < 400; i++) {
            if (tx < ty && tx < tz) {
                x += sx;
                tx += tdx;
            } else if (ty < tz) {
                y += sy;
                ty += tdy;
            } else {
                z += sz;
                tz += tdz;
            }
            if (x == ex && y == ey && z == ez) {
                return true;
            }
            if (tx > 1 && ty > 1 && tz > 1) {
                return true;
            }
            if (chunk == null || chunk.getPos().x != (x >> 4) || chunk.getPos().z != (z >> 4)) {
                chunk = w.getChunkManager().getWorldChunk(x >> 4, z >> 4);
                if (chunk == null) {
                    // Not loaded: don't hide because of it.
                    return true;
                }
            }
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (st.isOpaqueFullCube()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- containers

    public static boolean hiddenContainer(BlockState st) {
        return st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BarrelBlock || st.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * Called while a chunk packet is built: leaves containers out (as air for chests, as a neighbouring block for
     * full-block containers so walls don't show holes). Works on the same section copies as the anti-x-ray.
     */
    public static void hideContainers(WorldChunk chunk, Map<ChunkSection, ChunkSection> out) {
        HIDDEN_CONTAINERS.remove();
        if (!on() || !cfg().containers || chunk.getBlockEntities().isEmpty()) {
            return;
        }
        Set<BlockPos> hidden = new HashSet<>();
        ChunkSection[] sections = chunk.getSectionArray();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            BlockState st = be.getCachedState();
            if (!hiddenContainer(st)) {
                continue;
            }
            BlockPos pos = be.getPos();
            int idx = chunk.getSectionIndex(pos.getY());
            if (idx < 0 || idx >= sections.length || sections[idx] == null) {
                continue;
            }
            ChunkSection real = sections[idx];
            ChunkSection copy = out.get(real);
            if (copy == null) {
                PalettedContainer<BlockState> states = real.getBlockStateContainer().copy();
                copy = new ChunkSection(states, real.getBiomeContainer());
                out.put(real, copy);
            }
            int lx = pos.getX() & 15;
            int ly = pos.getY() & 15;
            int lz = pos.getZ() & 15;
            if (!hiddenContainer(copy.getBlockState(lx, ly, lz))) {
                // Already disguised by the anti-x-ray.
                continue;
            }
            copy.getBlockStateContainer().set(lx, ly, lz, disguise(chunk, pos, st));
            hidden.add(pos.toImmutable());
        }
        // ChunkSection keeps its block count; it isn't used when writing the packet, so no recount is needed.
        HIDDEN_CONTAINERS.set(hidden);
    }

    private static BlockState disguise(WorldChunk chunk, BlockPos pos, BlockState st) {
        if (st.getBlock() instanceof ChestBlock) {
            return Blocks.AIR.getDefaultState();
        }
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if ((n.getX() >> 4) != chunk.getPos().x || (n.getZ() >> 4) != chunk.getPos().z) {
                continue;
            }
            BlockState ns = chunk.getBlockState(n);
            if (ns.isOpaqueFullCube() && !ns.hasBlockEntity()) {
                return ns;
            }
        }
        return Blocks.AIR.getDefaultState();
    }

    /** A chunk was just sent to this player: its containers are hidden again until they come close. */
    public static void chunkSent(ServerPlayerEntity p, int cx, int cz) {
        Set<Long> s = REVEALED.get(p.getUuid());
        if (s != null) {
            s.removeIf(l -> (BlockPos.unpackLongX(l) >> 4) == cx && (BlockPos.unpackLongZ(l) >> 4) == cz);
        }
    }

    public static void worldChanged(ServerPlayerEntity p) {
        REVEALED.remove(p.getUuid());
    }

    private static void containers(MinecraftServer server) {
        if (!on() || !cfg().containers) {
            REVEALED.clear();
            return;
        }
        int dist = Math.max(8, cfg().containerShowDistance);
        int chunks = (dist >> 4) + 1;
        for (ServerWorld w : server.getWorlds()) {
            for (ServerPlayerEntity p : w.getPlayers()) {
                Set<Long> shown = REVEALED.computeIfAbsent(p.getUuid(), k -> ConcurrentHashMap.newKeySet());
                ChunkPos c = p.getChunkPos();
                for (int dx = -chunks; dx <= chunks; dx++) {
                    for (int dz = -chunks; dz <= chunks; dz++) {
                        WorldChunk chunk = w.getChunkManager().getWorldChunk(c.x + dx, c.z + dz);
                        if (chunk == null) {
                            continue;
                        }
                        for (BlockEntity be : chunk.getBlockEntities().values()) {
                            if (!hiddenContainer(be.getCachedState())) {
                                continue;
                            }
                            BlockPos pos = be.getPos();
                            if (shown.contains(pos.asLong()) || p.squaredDistanceTo(pos.toCenterPos()) > (double) dist * dist) {
                                continue;
                            }
                            shown.add(pos.asLong());
                            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(w, pos));
                            var update = BlockEntityUpdateS2CPacket.create(be);
                            if (update != null) {
                                p.networkHandler.sendPacket(update);
                            }
                        }
                    }
                }
            }
        }
    }
}
