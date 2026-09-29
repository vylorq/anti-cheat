package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.util.BlockPos3;
import com.vylorq.anticheat.core.xray.OreAlerts;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anti-x-ray (section 9). Enclosed ores are replaced by stone in the chunk data sent to clients, so an x-ray
 * client sees nothing useful. Fake diamond veins (never real blocks) are added in hidden spots as a trap.
 * Blocks are revealed as soon as they get an open face.
 */
public final class Xray {
    private static Set<Block> hidden = Set.of();
    private static Set<Block> alertBlocks = Set.of();
    private static final Set<Block> STONE_LIKE = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.TUFF,
            Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.BASALT, Blocks.BLACKSTONE, Blocks.END_STONE,
            Blocks.CALCITE, Blocks.SMOOTH_BASALT);

    /** Section swaps for the chunk packet being built on this thread (used by the chunk mixins). */
    public static final ThreadLocal<Map<ChunkSection, ChunkSection>> REPLACEMENTS = new ThreadLocal<>();

    private Xray() {
    }

    public static void reloadLists() {
        AcConfig.Xray c = Ac.config().xray;
        hidden = blocks(c.hiddenBlocks);
        alertBlocks = blocks(c.alertBlocks);
    }

    private static Set<Block> blocks(List<String> ids) {
        Set<Block> out = new HashSet<>();
        for (String id : ids) {
            Identifier i = Identifier.tryParse(id);
            if (i != null && Registries.BLOCK.containsId(i)) {
                out.add(Registries.BLOCK.get(i));
            }
        }
        return out;
    }

    private static BlockState replacementFor(World w, int y) {
        String id = Mc.worldId(w);
        if (id.equals("minecraft:the_nether")) {
            return Blocks.NETHERRACK.getDefaultState();
        }
        if (id.equals("minecraft:the_end")) {
            return Blocks.END_STONE.getDefaultState();
        }
        return y < 0 ? Blocks.DEEPSLATE.getDefaultState() : Blocks.STONE.getDefaultState();
    }

    private static boolean overworld(World w) {
        return Mc.worldId(w).equals("minecraft:overworld");
    }

    /** Neighbour state without loading chunks (unloaded counts as solid). */
    private static BlockState neighbour(WorldChunk chunk, ServerWorld w, int x, int y, int z) {
        if (y < w.getBottomY() || y > w.getTopYInclusive()) {
            return Blocks.AIR.getDefaultState();
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx == chunk.getPos().x && cz == chunk.getPos().z) {
            return chunk.getBlockState(new BlockPos(x, y, z));
        }
        WorldChunk other = w.getChunkManager().getWorldChunk(cx, cz);
        if (other == null) {
            return Blocks.STONE.getDefaultState();
        }
        return other.getBlockState(new BlockPos(x, y, z));
    }

    private static boolean opaque(BlockState s) {
        return s.isOpaque() && s.getFluidState().isEmpty() && !s.isAir();
    }

    /** True when every face is covered by an opaque block. */
    public static boolean enclosed(WorldChunk chunk, ServerWorld w, int x, int y, int z) {
        for (Direction d : Direction.values()) {
            if (!opaque(neighbour(chunk, w, x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds modified copies of a chunk's sections for sending. Called while the chunk packet is being built
     * (server thread). Sections that need no change are left out of the map.
     */
    public static Map<ChunkSection, ChunkSection> modifiedSections(WorldChunk chunk) {
        Map<ChunkSection, ChunkSection> out = new IdentityHashMap<>();
        Ac ac = Ac.get();
        if (ac == null || !(chunk.getWorld() instanceof ServerWorld w)) {
            return out;
        }
        AcConfig.Xray cfg = Ac.config().xray;
        if (!cfg.oreHiding && !cfg.trapEnabled) {
            return out;
        }
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        List<BlockPos3> traps = cfg.trapEnabled && overworld(w)
                ? ac.xrayTrap.veinsFor(Mc.worldId(w), chunk.getPos().x, chunk.getPos().z, cfg.trapVeinsPerChunk, cfg.trapMinY, cfg.trapMaxY)
                : List.of();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) {
                continue;
            }
            int baseY = chunk.sectionIndexToCoord(i) << 4;
            boolean hasHidden = cfg.oreHiding && section.getBlockStateContainer().hasAny(st -> hidden.contains(st.getBlock()));
            boolean hasTrap = false;
            for (BlockPos3 t : traps) {
                if (t.y() >= baseY && t.y() < baseY + 16) {
                    hasTrap = true;
                    break;
                }
            }
            if (!hasHidden && !hasTrap) {
                continue;
            }
            PalettedContainer<BlockState> copy = section.getBlockStateContainer().copy();
            boolean changed = false;
            if (hasHidden) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = copy.get(x, y, z);
                            if (hidden.contains(st.getBlock()) && enclosed(chunk, w, baseX + x, baseY + y, baseZ + z)) {
                                copy.set(x, y, z, replacementFor(w, baseY + y));
                                changed = true;
                            }
                        }
                    }
                }
            }
            for (BlockPos3 t : traps) {
                if (t.y() < baseY || t.y() >= baseY + 16 || (t.x() >> 4) != chunk.getPos().x || (t.z() >> 4) != chunk.getPos().z) {
                    continue;
                }
                int lx = t.x() & 15;
                int ly = t.y() - baseY;
                int lz = t.z() & 15;
                BlockState real = section.getBlockState(lx, ly, lz);
                if ((real.isOf(Blocks.STONE) || real.isOf(Blocks.DEEPSLATE) || real.isOf(Blocks.TUFF))
                        && enclosed(chunk, w, t.x(), t.y(), t.z())) {
                    copy.set(lx, ly, lz, (t.y() < 0 ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DIAMOND_ORE).getDefaultState());
                    changed = true;
                }
            }
            if (changed) {
                out.put(section, new ChunkSection(copy, section.getBiomeContainer()));
            }
        }
        return out;
    }

    private static boolean isTrap(ServerWorld w, BlockPos p) {
        AcConfig.Xray cfg = Ac.config().xray;
        return cfg.trapEnabled && overworld(w)
                && Ac.get().xrayTrap.isTrap(Mc.worldId(w), Mc.pos(p), cfg.trapVeinsPerChunk, cfg.trapMinY, cfg.trapMaxY);
    }

    /** Before a block is broken: was it a hidden ore with no open face? (x-ray users dig straight to these) */
    public static boolean wasHiddenOre(ServerWorld w, BlockPos pos) {
        WorldChunk chunk = w.getWorldChunk(pos);
        return enclosed(chunk, w, pos.getX(), pos.getY(), pos.getZ());
    }

    /** After a block is broken: reveal neighbours, check traps, mining analysis and ore alerts. */
    public static void afterBreak(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState state, boolean wasEnclosed) {
        Ac ac = Ac.get();
        AcConfig.Xray cfg = Ac.config().xray;
        boolean trapHit = false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = w.getBlockState(n);
            boolean trap = isTrap(w, n);
            if (hidden.contains(ns.getBlock()) || trap) {
                w.getChunkManager().markForUpdate(n);
            }
            if (trap && (ns.isOf(Blocks.STONE) || ns.isOf(Blocks.DEEPSLATE) || ns.isOf(Blocks.TUFF))) {
                trapHit = true;
            }
        }
        if (p.isCreative() || p.isSpectator()) {
            return;
        }
        PlayerSession s = Ac.session(p);
        if (trapHit) {
            long now = System.currentTimeMillis();
            if (now - s.lastTrapHit > 10 * 60_000L) {
                s.trapHits = 0;
            }
            s.trapHits++;
            s.lastTrapHit = now;
            // A strip-miner can stumble onto one by chance; two in a short time is x-ray.
            PlayerSessionFlags.flag(p, CheckType.XRAY_TRAP, s.trapHits >= 2 ? 3.0 : 0.6,
                    "dug to a fake diamond vein at " + pos.toShortString() + " (" + s.trapHits + ")");
        }
        boolean alert = alertBlocks.contains(state.getBlock());
        double score = s.mining.onBreak(Mc.blockId(state.getBlock()), alert, !wasEnclosed, STONE_LIKE.contains(state.getBlock()),
                cfg.suspiciousRatio);
        if (score >= 0.5) {
            PlayerSessionFlags.flag(p, CheckType.XRAY, score * 2, "mining pattern " + String.format("%.2f", score));
        }
        if (alert && cfg.oreAlerts) {
            ac.oreAlerts.onMine(p.getUuid(), p.getGameProfile().getName(), state.getBlock().getName().getString(), System.currentTimeMillis());
        }
    }

    /** Explosions can expose ores too. */
    public static void afterExplosion(ServerWorld w, List<BlockPos> destroyed) {
        if (hidden.isEmpty()) {
            return;
        }
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : destroyed) {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.offset(d);
                if (seen.add(n) && hidden.contains(w.getBlockState(n).getBlock())) {
                    w.getChunkManager().markForUpdate(n);
                }
            }
        }
    }

    /** Every 5 seconds: grouped ore alerts. */
    public static void flushAlerts() {
        long window = Ac.config().xray.oreAlertGroupSeconds * 1000L;
        for (OreAlerts.Alert a : Ac.get().oreAlerts.flush(System.currentTimeMillis(), window)) {
            Staff.broadcast(Msg.prefixed(Msg.tr("ore.alert", a.name(), a.count(), a.ore(), Math.max(1, a.spanMillis() / 1000))));
        }
    }
}
