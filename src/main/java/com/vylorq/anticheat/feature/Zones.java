package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.config.ConfigManager;
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
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The owner's zones, drawn with the Claim Stick (two corners):
 * <ul>
 *   <li>build spots: in the protected lobby, players may place (and break) only the blocks the owner picked, only
 *   there;</li>
 *   <li>dark zones: a pitch-black box nobody can see into, and nobody but staff can get into (a barrier keeps them
 *   out). Its black walls fill only the empty spots on its outside, and go back to how they were when it's removed.</li>
 * </ul>
 */
public final class Zones {
    private Zones() {
    }

    public static final String BUILD = "build";
    public static final String DARK = "dark";
    /** A dark zone's walls are at most this big on any side (so making one can't stall the server). */
    static final int MAX_SIDE = 160;
    private static final BlockState WALL = Blocks.BLACK_CONCRETE.getDefaultState();

    public static final class Zone {
        public String name;
        public String kind;
        public String world;
        public int minX;
        public int minY;
        public int minZ;
        public int maxX;
        public int maxY;
        public int maxZ;
        /** Build spots: the blocks players may place and break there. */
        public List<String> blocks = new ArrayList<>();
        /** Dark zones: the spots its walls filled (packed BlockPos), put back to air when it's removed. */
        public List<Long> filled = new ArrayList<>();
        public String createdBy;

        boolean contains(String w, BlockPos p) {
            return world.equals(w) && p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY
                    && p.getZ() >= minZ && p.getZ() <= maxZ;
        }

        String size() {
            return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
        }
    }

    public static final class State {
        public Map<String, Zone> zones = new LinkedHashMap<>();
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("zones.json");
    }

    static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read zones.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.zones == null) {
                state.zones = new LinkedHashMap<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save zones.json", e);
        }
    }

    public static void reset() {
        state = null;
    }

    // ---------------------------------------------------------------- rules

    /** A build spot lets players place or break this block here, even in the protected lobby. */
    public static boolean buildAllowed(World w, BlockPos pos, Block block) {
        if (!Ac.running() || w.isClient() || block == null) {
            return false;
        }
        String world = Mc.worldId(w);
        String id = Registries.BLOCK.getId(block).toString();
        for (Zone z : state().zones.values()) {
            if (BUILD.equals(z.kind) && z.contains(world, pos) && z.blocks.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** The dark zone at this spot (its walls or anything inside), or null. */
    public static Zone darkAt(World w, BlockPos pos) {
        if (!Ac.running() || w.isClient()) {
            return null;
        }
        String world = Mc.worldId(w);
        for (Zone z : state().zones.values()) {
            if (DARK.equals(z.kind) && z.contains(world, pos)) {
                return z;
            }
        }
        return null;
    }

    /** Nobody but staff breaks, places or changes anything in a dark zone. @return true when this is refused */
    public static boolean darkRefuses(ServerPlayerEntity p, World w, BlockPos pos) {
        if (darkAt(w, pos) == null || com.vylorq.anticheat.perm.Perms.isActiveStaff(p)) {
            return false;
        }
        Msg.actionBar(p, "§8" + Msg.trFor(p, "zone.dark-protected"));
        return true;
    }

    // ---------------------------------------------------------------- owner commands

    /** The two corners picked with the Claim Stick, as {min, max}, or null (and they're told). */
    private static BlockPos[] corners(ServerPlayerEntity p) {
        PlayerSession s = Ac.session(p);
        if (s.corner1 == null || s.corner2 == null || !Mc.worldId(p.getEntityWorld()).equals(s.cornerWorld)) {
            Msg.send(p, "zone.need-corners");
            return null;
        }
        BlockPos a = s.corner1;
        BlockPos b = s.corner2;
        return new BlockPos[]{new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()))};
    }

    private static Zone make(ServerPlayerEntity p, String name, String kind, BlockPos[] c) {
        Zone z = new Zone();
        z.name = name.toLowerCase(Locale.ROOT);
        z.kind = kind;
        z.world = Mc.worldId(p.getEntityWorld());
        z.minX = c[0].getX();
        z.minY = c[0].getY();
        z.minZ = c[0].getZ();
        z.maxX = c[1].getX();
        z.maxY = c[1].getY();
        z.maxZ = c[1].getZ();
        z.createdBy = p.getGameProfile().name();
        return z;
    }

    /** The blocks named in a list ("oak_planks, torch glass"), or null with a message naming the first unknown one. */
    public static List<String> blockList(ServerPlayerEntity p, String text) {
        List<String> out = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            Identifier id = Identifier.tryParse(part.contains(":") ? part : "minecraft:" + part);
            if (id == null || !Registries.BLOCK.containsId(id) || Registries.BLOCK.get(id) == Blocks.AIR) {
                Msg.send(p, "zone.unknown-block", part);
                return null;
            }
            if (!out.contains(id.toString())) {
                out.add(id.toString());
            }
        }
        if (out.isEmpty()) {
            Msg.send(p, "zone.no-blocks");
            return null;
        }
        return out;
    }

    /** A build spot: players may place and break these blocks there. Making one with a used name replaces it. */
    public static void build(ServerPlayerEntity p, String name, String blocks) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        BlockPos[] c = corners(p);
        List<String> list = c == null ? null : blockList(p, blocks);
        if (list == null) {
            return;
        }
        Zone old = state().zones.get(name.toLowerCase(Locale.ROOT));
        if (old != null && DARK.equals(old.kind)) {
            Msg.send(p, "zone.name-used", name);
            return;
        }
        Zone z = make(p, name, BUILD, c);
        z.blocks = list;
        state().zones.put(z.name, z);
        save();
        Staff.log(p, "zone-build", null, z.name, z.size() + " " + String.join(",", list));
        Msg.send(p, "zone.build-made", z.name, z.size(), String.join(", ", list.stream().map(s -> s.replace("minecraft:", "")).toList()));
        if (!LobbyFeature.in(p.getEntityWorld(), c[0]) && !LobbyFeature.in(p.getEntityWorld(), c[1])) {
            Msg.send(p, "zone.build-not-lobby");
        }
    }

    /** A dark zone: black walls all round (filling only empty spots) and a barrier nobody but staff can cross. */
    public static void dark(ServerPlayerEntity p, String name) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        BlockPos[] c = corners(p);
        if (c == null) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (state().zones.containsKey(key)) {
            Msg.send(p, "zone.name-used", name);
            return;
        }
        int sx = c[1].getX() - c[0].getX() + 1;
        int sy = c[1].getY() - c[0].getY() + 1;
        int sz = c[1].getZ() - c[0].getZ() + 1;
        if (sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE) {
            Msg.send(p, "zone.too-big", MAX_SIDE);
            return;
        }
        if (sx < 3 || sy < 3 || sz < 3) {
            Msg.send(p, "zone.too-small");
            return;
        }
        ServerWorld w = p.getEntityWorld();
        Zone z = make(p, name, DARK, c);
        for (int x = z.minX; x <= z.maxX; x++) {
            for (int y = z.minY; y <= z.maxY; y++) {
                for (int zz = z.minZ; zz <= z.maxZ; zz++) {
                    boolean wall = x == z.minX || x == z.maxX || y == z.minY || y == z.maxY || zz == z.minZ || zz == z.maxZ;
                    if (!wall) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, zz);
                    BlockState st = w.getBlockState(pos);
                    if (st.isAir() || st.isReplaceable()) {
                        w.setBlockState(pos, WALL, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                        z.filled.add(pos.asLong());
                    }
                }
            }
        }
        state().zones.put(z.name, z);
        save();
        Barrier b = new Barrier();
        b.name = barrierName(z.name);
        b.world = z.world;
        b.shape = Barrier.Shape.CUBE;
        b.minX = z.minX;
        b.maxX = z.maxX;
        b.minY = z.minY;
        b.maxY = z.maxY;
        b.minZ = z.minZ;
        b.maxZ = z.maxZ;
        b.cy = z.minY;
        b.adminsPass = true;
        b.blockProjectiles = true;
        b.newPlayers = "outside";
        b.createdBy = z.createdBy;
        Ac.get().barriers.remove(b.name);
        Ac.get().barriers.add(b);
        Ac.get().barriers.resetSides(b);
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            b.sides.put(o.getUuid(), false);
        }
        Ac.markDirty("barriers");
        Ac.saveNow("barriers");
        Staff.log(p, "zone-dark", null, z.name, z.world + " " + z.minX + "," + z.minY + "," + z.minZ + " " + z.size());
        Msg.send(p, "zone.dark-made", z.name, z.size(), z.filled.size());
    }

    static String barrierName(String zone) {
        return "dark-" + zone;
    }

    /** Removes a zone; a dark zone's walls go back to empty (only where they're still its black walls). */
    public static void remove(ServerPlayerEntity p, String name) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Zone z = state().zones.remove(name.toLowerCase(Locale.ROOT));
        if (z == null) {
            Msg.send(p, "zone.none", name);
            return;
        }
        int back = 0;
        if (DARK.equals(z.kind)) {
            ServerWorld w = Mc.world(Ac.server(), z.world);
            if (w != null) {
                for (long l : z.filled) {
                    BlockPos pos = BlockPos.fromLong(l);
                    if (w.getBlockState(pos).isOf(Blocks.BLACK_CONCRETE)) {
                        w.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                        back++;
                    }
                }
            }
            Ac.get().barriers.remove(barrierName(z.name));
            Ac.markDirty("barriers");
            Ac.saveNow("barriers");
        }
        save();
        Staff.log(p, "zone-remove", null, z.name, z.kind);
        Msg.send(p, "zone.removed", z.name, back);
    }

    public static void list(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (state().zones.isEmpty()) {
            Msg.send(p, "zone.list-empty");
            return;
        }
        for (Zone z : state().zones.values()) {
            String what = BUILD.equals(z.kind)
                    ? String.join(", ", z.blocks.stream().map(s -> s.replace("minecraft:", "")).toList())
                    : Msg.trFor(p, "zone.dark-word");
            Msg.send(p, "zone.list-entry", z.name, z.world.replace("minecraft:", ""), z.minX + " " + z.minY + " " + z.minZ,
                    z.size(), what);
        }
    }

    /** For the game tests. */
    public static Zone get(String name) {
        return state().zones.get(name);
    }

    /** For the game tests: a zone without the stick, walls or barrier. */
    public static Zone putForTest(String name, String kind, ServerWorld w, BlockPos min, BlockPos max, List<String> blocks) {
        Zone z = new Zone();
        z.name = name;
        z.kind = kind;
        z.world = Mc.worldId(w);
        z.minX = min.getX();
        z.minY = min.getY();
        z.minZ = min.getZ();
        z.maxX = max.getX();
        z.maxY = max.getY();
        z.maxZ = max.getZ();
        z.blocks = new ArrayList<>(blocks);
        state().zones.put(name, z);
        return z;
    }

    public static void dropForTest(String name) {
        state().zones.remove(name);
    }
}
