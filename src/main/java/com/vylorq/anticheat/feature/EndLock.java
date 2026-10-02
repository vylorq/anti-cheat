package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The End stays closed until the owner opens it ({@code general.endOpen}, in /settings or the config):
 * <ul>
 * <li>the portal rooms of every stronghold disappear (frames and portal), and come back exactly as they were
 * when the End is opened;</li>
 * <li>eyes of ender can't be thrown or put into frames, and no End portal takes anyone there;</li>
 * <li>portal rooms built with {@code /vigil end portal} always work.</li>
 * </ul>
 */
public final class EndLock {
    private EndLock() {
    }

    /** A hidden block of a stronghold portal room. */
    public static final class Hidden {
        public int x;
        public int y;
        public int z;
        public boolean portal;
        public String facing = "north";
        public boolean eye;
    }

    /** A portal room built with /vigil end portal. */
    public static final class Built {
        public String world;
        public int minX;
        public int minY;
        public int minZ;
        public int maxX;
        public int maxY;
        public int maxZ;
        /** Snapshot of what was there before it was built (so removing it puts the ground back). */
        public String before;

        boolean contains(String w, BlockPos p) {
            return world.equals(w) && p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY
                    && p.getZ() >= minZ && p.getZ() <= maxZ;
        }
    }

    public static final class Data {
        /** "world|chunk" to the blocks hidden there. */
        public Map<String, List<Hidden>> hidden = new HashMap<>();
        public List<Built> built = new ArrayList<>();
    }

    /** Chunks already looked at since the End last opened or closed. */
    private static final Set<String> CHECKED = new HashSet<>();
    private static Boolean lastOpen;

    private static Data data() {
        return Ac.get().end;
    }

    public static boolean open() {
        return Ac.config().general.endOpen || !Features.on(Features.Feature.END_LOCK);
    }

    /** Whether the End can be reached from here: it's open, or this is a portal room built with /vigil end portal. */
    public static boolean allowedAt(ServerWorld w, BlockPos pos) {
        if (open()) {
            return true;
        }
        String id = Mc.worldId(w);
        for (Built b : data().built) {
            if (b.contains(id, pos)) {
                return true;
            }
        }
        return false;
    }

    private static String key(ServerWorld w, ChunkPos c) {
        return Mc.worldId(w) + "|" + c.toLong();
    }

    private static Structure stronghold(MinecraftServer server) {
        return server.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE)
                .getEntry(Identifier.of("minecraft", "stronghold")).map(RegistryEntry::value).orElse(null);
    }

    /** Every second: hides portal rooms around players while closed, and brings them back once open. */
    public static void tick(MinecraftServer server) {
        boolean open = open();
        if (lastOpen == null || lastOpen != open) {
            lastOpen = open;
            CHECKED.clear();
        }
        if (open) {
            restoreLoaded(server);
            return;
        }
        Structure sh = stronghold(server);
        if (sh == null) {
            return;
        }
        int r = server.getPlayerManager().getViewDistance() + 1;
        if (CHECKED.size() > 200_000) {
            CHECKED.clear();
        }
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            ServerWorld w = (ServerWorld) p.getEntityWorld();
            ChunkPos at = p.getChunkPos();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    ChunkPos c = new ChunkPos(at.x + dx, at.z + dz);
                    String k = key(w, c);
                    if (CHECKED.contains(k)) {
                        continue;
                    }
                    WorldChunk chunk = w.getChunkManager().getWorldChunk(c.x, c.z);
                    if (chunk == null) {
                        continue;
                    }
                    CHECKED.add(k);
                    if (chunk.getStructureStart(sh) != null || !chunk.getStructureReferences(sh).isEmpty()) {
                        hide(w, c.getStartX(), w.getBottomY(), c.getStartZ(), c.getEndX(), w.getTopYInclusive(), c.getEndZ());
                    }
                }
            }
        }
    }

    private static boolean isEndBlock(BlockState s) {
        return s.isOf(Blocks.END_PORTAL_FRAME) || s.isOf(Blocks.END_PORTAL);
    }

    /** Hides the End portal frames and portal blocks in a box (skipping built portal rooms). @return how many */
    public static int hide(ServerWorld w, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        String id = Mc.worldId(w);
        int n = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                WorldChunk chunk = w.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }
                var sections = chunk.getSectionArray();
                for (int i = 0; i < sections.length; i++) {
                    var section = sections[i];
                    if (section.isEmpty() || !section.getBlockStateContainer().hasAny(EndLock::isEndBlock)) {
                        continue;
                    }
                    int baseY = chunk.sectionIndexToCoord(i) << 4;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState s = section.getBlockState(x, y, z);
                                if (!isEndBlock(s)) {
                                    continue;
                                }
                                pos.set((cx << 4) + x, baseY + y, (cz << 4) + z);
                                if (pos.getX() < minX || pos.getX() > maxX || pos.getY() < minY || pos.getY() > maxY
                                        || pos.getZ() < minZ || pos.getZ() > maxZ || allowedAt(w, pos)) {
                                    continue;
                                }
                                Hidden h = new Hidden();
                                h.x = pos.getX();
                                h.y = pos.getY();
                                h.z = pos.getZ();
                                h.portal = s.isOf(Blocks.END_PORTAL);
                                if (!h.portal) {
                                    h.facing = s.get(EndPortalFrameBlock.FACING).asString();
                                    h.eye = s.get(EndPortalFrameBlock.EYE);
                                }
                                data().hidden.computeIfAbsent(key(w, new ChunkPos(pos)), k -> new ArrayList<>()).add(h);
                                w.setBlockState(pos.toImmutable(), h.portal ? Blocks.AIR.getDefaultState()
                                        : Blocks.STONE_BRICKS.getDefaultState(), Block.NOTIFY_LISTENERS);
                                n++;
                            }
                        }
                    }
                }
            }
        }
        if (n > 0) {
            Ac.markDirty("end");
        }
        return n;
    }

    private static void restoreLoaded(MinecraftServer server) {
        if (data().hidden.isEmpty()) {
            return;
        }
        for (ServerWorld w : server.getWorlds()) {
            String prefix = Mc.worldId(w) + "|";
            for (Iterator<Map.Entry<String, List<Hidden>>> it = data().hidden.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!e.getKey().startsWith(prefix)) {
                    continue;
                }
                ChunkPos c = new ChunkPos(Long.parseLong(e.getKey().substring(prefix.length())));
                if (w.getChunkManager().getWorldChunk(c.x, c.z) == null) {
                    continue;
                }
                put(w, e.getValue());
                it.remove();
                Ac.markDirty("end");
            }
        }
    }

    /** Puts hidden blocks in a box back now (used by tests; normally they come back when the End opens). */
    public static int restore(ServerWorld w, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int n = 0;
        for (var list : data().hidden.values()) {
            for (Iterator<Hidden> it = list.iterator(); it.hasNext(); ) {
                Hidden h = it.next();
                if (h.x >= minX && h.x <= maxX && h.y >= minY && h.y <= maxY && h.z >= minZ && h.z <= maxZ) {
                    put(w, List.of(h));
                    it.remove();
                    n++;
                }
            }
        }
        data().hidden.values().removeIf(List::isEmpty);
        Ac.markDirty("end");
        return n;
    }

    private static void put(ServerWorld w, List<Hidden> blocks) {
        for (Hidden h : blocks) {
            BlockState s = Blocks.END_PORTAL.getDefaultState();
            if (!h.portal) {
                Direction facing = Direction.NORTH;
                for (Direction d : Direction.values()) {
                    if (d.asString().equals(h.facing)) {
                        facing = d;
                    }
                }
                s = Blocks.END_PORTAL_FRAME.getDefaultState().with(EndPortalFrameBlock.FACING, facing)
                        .with(EndPortalFrameBlock.EYE, h.eye);
            }
            w.setBlockState(new BlockPos(h.x, h.y, h.z), s, Block.NOTIFY_LISTENERS);
        }
    }

    /**
     * Builds a working End portal room a few blocks in front of a player (it works even while the End is closed).
     *
     * @return the centre of the portal
     */
    public static BlockPos build(ServerWorld w, BlockPos feet, Direction facing) {
        BlockPos c = feet.offset(facing, 6);
        int r = 4;
        Built b = new Built();
        b.world = Mc.worldId(w);
        b.minX = c.getX() - r;
        b.maxX = c.getX() + r;
        b.minY = c.getY() - 1;
        b.maxY = c.getY() + 4;
        b.minZ = c.getZ() - r;
        b.maxZ = c.getZ() + r;
        b.before = "endportal-" + java.util.UUID.randomUUID();
        try {
            com.vylorq.anticheat.util.BlockSnapshots.save(w, new com.vylorq.anticheat.core.util.Area(b.world, b.minX, b.minY, b.minZ,
                    b.maxX, b.maxY, b.maxZ), b.before);
        } catch (Exception e) {
            b.before = null;
        }
        data().built.add(b);
        Ac.markDirty("end");
        var rnd = w.getRandom();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                BlockPos floor = c.add(dx, -1, dz);
                int roll = rnd.nextInt(10);
                w.setBlockState(floor, (roll == 0 ? Blocks.CRACKED_STONE_BRICKS : roll == 1 ? Blocks.MOSSY_STONE_BRICKS
                        : Blocks.STONE_BRICKS).getDefaultState());
                boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
                for (int dy = 0; dy <= 3; dy++) {
                    BlockState s = Blocks.AIR.getDefaultState();
                    if (corner) {
                        s = dy < 3 ? Blocks.STONE_BRICK_WALL.getDefaultState() : Blocks.SOUL_LANTERN.getDefaultState();
                    }
                    w.setBlockState(c.add(dx, dy, dz), s);
                }
            }
        }
        for (int i = -1; i <= 1; i++) {
            frame(w, c.add(i, 0, -2), Direction.SOUTH);
            frame(w, c.add(i, 0, 2), Direction.NORTH);
            frame(w, c.add(-2, 0, i), Direction.EAST);
            frame(w, c.add(2, 0, i), Direction.WEST);
            for (int j = -1; j <= 1; j++) {
                w.setBlockState(c.add(i, 0, j), Blocks.END_PORTAL.getDefaultState());
            }
        }
        return c;
    }

    private static void frame(ServerWorld w, BlockPos pos, Direction facing) {
        w.setBlockState(pos, Blocks.END_PORTAL_FRAME.getDefaultState().with(EndPortalFrameBlock.FACING, facing)
                .with(EndPortalFrameBlock.EYE, true));
    }

    /**
     * Removes the portal room built with /vigil end portal that the player is in or next to, putting back what was
     * there before. @return false if there's none nearby
     */
    public static boolean removeBuilt(ServerWorld w, BlockPos near) {
        String id = Mc.worldId(w);
        for (Iterator<Built> it = data().built.iterator(); it.hasNext(); ) {
            Built b = it.next();
            if (!b.world.equals(id) || near.getX() < b.minX - 8 || near.getX() > b.maxX + 8 || near.getZ() < b.minZ - 8
                    || near.getZ() > b.maxZ + 8 || near.getY() < b.minY - 8 || near.getY() > b.maxY + 8) {
                continue;
            }
            it.remove();
            Ac.markDirty("end");
            boolean restored = false;
            if (b.before != null) {
                try {
                    restored = com.vylorq.anticheat.util.BlockSnapshots.restore(w, b.before) >= 0;
                    java.nio.file.Files.deleteIfExists(com.vylorq.anticheat.util.BlockSnapshots.file(b.before));
                } catch (Exception e) {
                    restored = false;
                }
            }
            if (!restored) {
                // Built before snapshots were kept: clear the room but leave the floor.
                for (BlockPos p : BlockPos.iterate(b.minX, b.minY + 1, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
                }
            }
            // Portal blocks can never stay behind, whatever was saved.
            for (BlockPos p : BlockPos.iterate(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
                if (isEndBlock(w.getBlockState(p))) {
                    w.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
                }
            }
            return true;
        }
        return false;
    }
}
