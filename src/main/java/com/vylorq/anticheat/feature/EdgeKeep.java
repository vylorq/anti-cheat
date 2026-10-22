package com.vylorq.anticheat.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeverBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The World's Edge Citadel: one fortress out at the world border (it places itself there the first time the server
 * starts; /owner edge place moves it), the only home of Mjolnir, which only one player can ever claim.
 *
 * <p>Built from data/vigil/structure/edge/citadel.nbt; layout.json (made by scripts/secrets/edge.py with it) says where
 * the gates, levers, rooms and waves are. Like the Tempest Keep but harder all through: the entrance seals behind you;
 * four levers of eight open the next gate (a wrong guess brings lightning, vexes and a wither skeleton down on you); a
 * parkour run over lava; a dark maze with a lever at its heart; the Trial Hall, where three waves of elites must fall
 * before its gate opens; then the Edge Sovereign, who locks the arena until it dies. The vault holds Mjolnir (once,
 * ever) and new enchanted books for every group that wins through.</p>
 *
 * <p>Inside nobody but the owner can break or place blocks, pour water or lava, or throw ender pearls.</p>
 */
public final class EdgeKeep {
    private EdgeKeep() {
    }

    static final Identifier TEMPLATE = Identifier.of("vigil", "edge/citadel");
    static final Identifier LAYOUT = Identifier.of("vigil", "edge/layout.json");
    private static final String SNAP = "edge_citadel";
    /** Ticks with nobody inside before the keep resets for the next group. */
    private static final int EMPTY_RESET = 60 * 20;

    /** The saved state: where it stands, and Mjolnir's fate. */
    public static final class State {
        public String world;
        public int x;
        public int y;
        public int z;
        public boolean placed;
        /** Mjolnir was put in the vault (it only ever is once). */
        public boolean filled;
        /** Someone took it. */
        public boolean claimed;
        public String claimedBy;
        /** Gates that are open right now (by name). */
        public Set<String> open = new HashSet<>();
        public String boss;
        public boolean bossDone;
        /** It was placed at the border by itself once (so removing it doesn't bring it back). */
        public boolean autoPlaced;
        /** The Trial Hall's wave (0 before it starts, 4 once all three are beaten). */
        public int wave;
        /** How many of the entrance's offerings have been given (4 opens the inner door). */
        public int offered;
        /** Which version of the layout stands in the world (an older one is rebuilt in place at start). */
        public int version;
        /** Mobs killed in each room this run (rooms must be cleared, in order, before their way on opens). */
        public Map<String, Integer> kills = new java.util.HashMap<>();
        /** The Rift Gates: machines that take whoever feeds them an ender pearl to the citadel ("world|x|y|z"). */
        public List<String> machines = new ArrayList<>();
    }

    /** Raise when the citadel's layout changes, so a placed one is rebuilt with it. */
    static final int VERSION = 3;

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("edge-citadel.json");
    }

    public static synchronized State state() {
        if (state == null) {
            try {
                state = Files.exists(file()) ? ConfigManager.GSON.fromJson(Files.readString(file()), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the World's Edge Citadel's state", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.open == null) {
                state.open = new HashSet<>();
            }
            if (state.kills == null) {
                state.kills = new java.util.HashMap<>();
            }
            if (state.machines == null) {
                state.machines = new ArrayList<>();
            }
        }
        return state;
    }

    static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()));
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the World's Edge Citadel's state", e);
        }
    }

    // ---------------------------------------------------------------- the layout

    public record Gate(String name, int[] box, String block, boolean closedAtStart) {
    }

    public record Layout(int[] size, List<Gate> gates, List<int[]> levers, Set<Integer> code, Map<String, int[]> regions,
                  int[] mazeLever, int[] bossSpawn, int[] vaultChest) {
    }

    private static Layout layout;

    static Layout layout() {
        if (layout == null) {
            try {
                var res = Ac.server().getResourceManager().getResource(LAYOUT).orElseThrow();
                JsonObject o;
                try (var in = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                    o = JsonParser.parseReader(in).getAsJsonObject();
                }
                List<Gate> gates = new ArrayList<>();
                for (var g : o.getAsJsonArray("gates")) {
                    JsonObject go = g.getAsJsonObject();
                    gates.add(new Gate(go.get("name").getAsString(), ints(go.getAsJsonArray("box")), go.get("block").getAsString(),
                            go.get("closed").getAsBoolean()));
                }
                List<int[]> levers = new ArrayList<>();
                for (var l : o.getAsJsonArray("levers")) {
                    levers.add(ints(l.getAsJsonArray()));
                }
                Set<Integer> code = new HashSet<>();
                for (var c : o.getAsJsonArray("code")) {
                    code.add(c.getAsInt());
                }
                Map<String, int[]> regions = new LinkedHashMap<>();
                for (var e : o.getAsJsonObject("regions").entrySet()) {
                    regions.put(e.getKey(), ints(e.getValue().getAsJsonArray()));
                }
                layout = new Layout(ints(o.getAsJsonArray("size")), gates, levers, code, regions, ints(o.getAsJsonArray("maze_lever")),
                        ints(o.getAsJsonArray("boss_spawn")), ints(o.getAsJsonArray("vault_chest")));
            } catch (Exception e) {
                throw new IllegalStateException("The World's Edge Citadel's layout couldn't be read", e);
            }
        }
        return layout;
    }

    /** For the game tests. */
    public static Layout layoutForTest() {
        return layout();
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    private static ServerWorld world() {
        State s = state();
        if (!s.placed) {
            return null;
        }
        for (ServerWorld w : Ac.server().getWorlds()) {
            if (Mc.worldId(w).equals(s.world)) {
                return w;
            }
        }
        return null;
    }

    private static BlockPos at(int[] p) {
        State s = state();
        return new BlockPos(s.x + p[0], s.y + p[1], s.z + p[2]);
    }

    /** Whether a spot is inside a region of the keep (x0 y0 z0 x1 y1 z1 relative to the keep). */
    private static boolean in(String region, ServerWorld w, double x, double y, double z) {
        State s = state();
        if (!s.placed || w == null || !Mc.worldId(w).equals(s.world)) {
            return false;
        }
        int[] r = layout().regions().get(region);
        double rx = x - s.x;
        double ry = y - s.y;
        double rz = z - s.z;
        return rx >= r[0] && rx < r[3] + 1 && ry >= r[1] && ry < r[4] + 1 && rz >= r[2] && rz < r[5] + 1;
    }

    /** Whether a block is part of the keep (its whole footprint, roof included). */
    public static boolean protects(ServerWorld w, BlockPos pos) {
        State s = state();
        if (!s.placed || !Mc.worldId(w).equals(s.world)) {
            return false;
        }
        int[] size = layout().size();
        int rx = pos.getX() - s.x;
        int ry = pos.getY() - s.y;
        int rz = pos.getZ() - s.z;
        return rx >= 0 && rx < size[0] && ry >= 0 && ry < size[1] + 2 && rz >= 0 && rz < size[2];
    }

    private static List<ServerPlayerEntity> playersIn(String region, ServerWorld w) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (!p.isSpectator() && p.isAlive() && in(region, w, p.getX(), p.getY(), p.getZ())) {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- gates

    private static Gate gate(String name) {
        for (Gate g : layout().gates()) {
            if (g.name().equals(name)) {
                return g;
            }
        }
        throw new IllegalArgumentException(name);
    }

    static boolean isOpen(String name) {
        return state().open.contains(name);
    }

    private static void setGate(ServerWorld w, String name, boolean open) {
        if (open == isOpen(name)) {
            return;
        }
        Gate g = gate(name);
        BlockState fill = open ? net.minecraft.block.Blocks.AIR.getDefaultState()
                : Registries.BLOCK.get(Identifier.of(g.block())).getDefaultState();
        int[] b = g.box();
        for (int x = b[0]; x <= b[3]; x++) {
            for (int y = b[1]; y <= b[4]; y++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    w.setBlockState(at(new int[]{x, y, z}), fill);
                }
            }
        }
        if (open) {
            state().open.add(name);
        } else {
            state().open.remove(name);
        }
        BlockPos mid = at(new int[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2});
        w.playSound(null, mid, open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 2f, 0.5f);
        w.playSound(null, mid, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS, 1.5f, 0.6f);
        save();
    }

    // ---------------------------------------------------------------- placing and removing

    public static void place(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        if (s.placed) {
            Msg.send(p, "edge.already", s.world.replace("minecraft:", ""), s.x, s.y, s.z);
            return;
        }
        int[] size = layout().size();
        // The entrance faces the owner: the citadel runs east from two blocks in front of their feet.
        BlockPos origin = new BlockPos(p.getBlockX() + 2, p.getBlockY() - 3, p.getBlockZ() - size[2] / 2);
        String error = placeAt(p.getEntityWorld(), origin);
        if (error != null) {
            Msg.send(p, "edge.failed", error);
            return;
        }
        Staff.log(p, "edge-place", null, null, origin.toShortString());
        Msg.send(p, "edge.placed", origin.getX(), origin.getY(), origin.getZ());
    }

    /** Builds it with its corner at {@code origin} (the ground there is saved first). @return an error, or null */
    static String placeAt(ServerWorld w, BlockPos origin) {
        State s = state();
        var tpl = w.getStructureTemplateManager().getTemplate(TEMPLATE);
        if (tpl.isEmpty()) {
            return "the template is missing";
        }
        int[] size = layout().size();
        Area area = new Area(Mc.worldId(w), origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size[0] - 1, origin.getY() + size[1] + 1, origin.getZ() + size[2] - 1);
        try {
            // Rebuilding in place keeps the ground saved the first time, not the old citadel.
            if (!BlockSnapshots.exists(SNAP)) {
                BlockSnapshots.save(w, area, SNAP);
            }
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the ground under the Edge Citadel", e);
            return e.toString();
        }
        tpl.get().place(w, origin, origin, new StructurePlacementData(), w.getRandom(), 2);
        s.world = Mc.worldId(w);
        s.x = origin.getX();
        s.y = origin.getY();
        s.z = origin.getZ();
        s.placed = true;
        s.open.clear();
        for (Gate g : layout().gates()) {
            if (!g.closedAtStart()) {
                s.open.add(g.name());
            }
        }
        s.boss = null;
        s.bossDone = false;
        s.wave = 0;
        s.offered = 0;
        s.version = VERSION;
        save();
        return null;
    }

    /**
     * The first time the server starts with it: the citadel builds itself at the east edge of the overworld's border,
     * level with the ground there. Only ever once by itself (the owner can move it with /owner edge remove + place).
     */
    static void autoPlace(net.minecraft.server.MinecraftServer server) {
        State s = state();
        if (s.placed && s.version < VERSION) {
            // An older citadel stands: rebuild it where it is with the new layout.
            ServerWorld old = world();
            if (old != null) {
                killBoss(old);
                clearWave(old);
                try {
                    if (BlockSnapshots.exists(SNAP)) {
                        BlockSnapshots.restore(old, SNAP);
                        Files.deleteIfExists(BlockSnapshots.file(SNAP));
                    }
                } catch (Exception e) {
                    Ac.LOG.warn("Could not put the ground back under the old Edge Citadel", e);
                }
                String error = placeAt(old, new BlockPos(s.x, s.y, s.z));
                Ac.LOG.info("The World's Edge Citadel was rebuilt with its new layout{}", error == null ? "" : ": " + error);
            }
            return;
        }
        if (s.placed || s.autoPlaced) {
            return;
        }
        ServerWorld w = server.getOverworld();
        var border = w.getWorldBorder();
        int[] size = layout().size();
        int x = (int) Math.floor(Math.min(border.getBoundEast(), 29_999_000)) - size[0] - 48;
        int z = (int) Math.floor(border.getCenterZ()) - size[2] / 2;
        for (int cx = (x >> 4) - 1; cx <= ((x + size[0]) >> 4) + 1; cx++) {
            for (int cz = (z >> 4) - 1; cz <= ((z + size[2]) >> 4) + 1; cz++) {
                w.getChunk(cx, cz);
            }
        }
        int ground = w.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, x + size[0] / 2, z + size[2] / 2);
        int y = Math.max(w.getSeaLevel(), Math.min(ground, w.getTopYInclusive() - size[1] - 4)) - 3;
        String error = placeAt(w, new BlockPos(x, y, z));
        s.autoPlaced = true;
        save();
        if (error != null) {
            Ac.LOG.warn("The World's Edge Citadel couldn't build itself at the border: {}", error);
        } else {
            Ac.LOG.info("The World's Edge Citadel stands at the world border: {} {} {}", x, y, z);
        }
    }

    public static void remove(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = world();
        if (w == null) {
            Msg.send(p, "edge.none");
            return;
        }
        killBoss(w);
        try {
            BlockSnapshots.restore(w, SNAP);
            Files.deleteIfExists(BlockSnapshots.file(SNAP));
        } catch (Exception e) {
            Ac.LOG.warn("Could not put the ground back under the Edge Citadel", e);
            Msg.send(p, "edge.failed", e.toString());
            return;
        }
        // Its mobs go with it.
        int[] size = layout().size();
        State s = state();
        var box = new net.minecraft.util.math.Box(s.x, s.y, s.z, s.x + size[0], s.y + size[1] + 2, s.z + size[2]);
        for (MobEntity m : w.getEntitiesByClass(MobEntity.class, box, e -> e.getCommandTags().stream()
                .anyMatch(t -> t.startsWith(StructureMobs.TAG) || t.equals(Bosses.GUARD_TAG)))) {
            m.discard();
        }
        s.placed = false;
        s.open.clear();
        save();
        Staff.log(p, "edge-remove", null, null, "");
        Msg.send(p, "edge.removed");
    }

    /** Owner: closes everything again for the next group (Mjolnir's fate is kept). */
    public static void reset(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = world();
        if (w == null) {
            Msg.send(p, "edge.none");
            return;
        }
        resetRun(w);
        Msg.send(p, "edge.reset");
    }

    public static void info(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        if (!s.placed) {
            Msg.send(p, "edge.none");
            return;
        }
        Msg.send(p, "edge.info", s.world.replace("minecraft:", ""), s.x, s.y, s.z,
                s.claimed ? Msg.trFor(p, "edge.info.claimed", s.claimedBy) : s.filled ? Msg.trFor(p, "edge.info.waiting")
                        : Msg.trFor(p, "edge.info.sealed"));
    }

    // ---------------------------------------------------------------- the run

    private static long emptyTicks;

    private static void resetRun(ServerWorld w) {
        killBoss(w);
        State s = state();
        for (Gate g : layout().gates()) {
            setGate(w, g.name(), !g.closedAtStart());
        }
        for (int[] l : layout().levers()) {
            setLever(w, at(l), false);
        }
        setLever(w, at(layout().mazeLever()), false);
        s.bossDone = false;
        s.wave = 0;
        s.offered = 0;
        s.kills.clear();
        clearWave(w);
        save();
    }

    private static void killBoss(ServerWorld w) {
        State s = state();
        if (s.boss != null) {
            Entity e = w.getEntity(UUID.fromString(s.boss));
            if (e != null) {
                e.discard();
            }
            s.boss = null;
        }
    }

    private static boolean lever(ServerWorld w, BlockPos pos) {
        BlockState st = w.getBlockState(pos);
        return st.getBlock() instanceof LeverBlock && st.get(LeverBlock.POWERED);
    }

    private static void setLever(ServerWorld w, BlockPos pos, boolean on) {
        BlockState st = w.getBlockState(pos);
        if (st.getBlock() instanceof LeverBlock && st.get(LeverBlock.POWERED) != on) {
            w.setBlockState(pos, st.with(LeverBlock.POWERED, on), 3);
        }
    }

    public static void tick(long ticks) {
        if (ticks % 10 != 0) {
            return;
        }
        machines();
        ServerWorld w = world();
        if (w == null) {
            return;
        }
        State s = state();
        Layout lay = layout();
        if (!w.isChunkLoaded(s.x >> 4, s.z >> 4)) {
            return; // nobody near: nothing to do
        }
        List<ServerPlayerEntity> inside = playersIn("inside", w);
        if (inside.isEmpty()) {
            emptyTicks += 10;
            if (emptyTicks >= EMPTY_RESET && (!isOpen("seal") || isOpen("entry") || s.offered > 0 || isOpen("code") || isOpen("maze") || s.wave > 0 || s.bossDone || s.boss != null)) {
                resetRun(w);
            }
            return;
        }
        emptyTicks = 0;

        // The offering at the inner door.
        if (!isOpen("entry")) {
            offering(w);
        }

        // The entrance seals behind whoever goes in.
        if (isOpen("seal") && !playersIn("seal_trigger", w).isEmpty()) {
            setGate(w, "seal", false);
            for (ServerPlayerEntity p : inside) {
                Mc.title(p, "§d§lWorld's Edge", "§7" + Msg.trFor(p, "edge.sealed"), 10, 60, 20);
            }
        }

        // The lever code.
        if (!isOpen("code")) {
            Set<Integer> on = new HashSet<>();
            for (int i = 0; i < lay.levers().size(); i++) {
                if (lever(w, at(lay.levers().get(i)))) {
                    on.add(i);
                }
            }
            if (on.equals(lay.code()) && !cleared(w, "levers")) {
                // Right code, but the room isn't cleared yet: the levers wait.
            } else if (on.equals(lay.code())) {
                setGate(w, "code", true);
                for (ServerPlayerEntity p : playersIn("levers", w)) {
                    Msg.actionBar(p, "§d" + Msg.trFor(p, "edge.code-right"));
                }
            } else if (on.size() >= lay.code().size()) {
                wrongCode(w, lay);
            }
        }

        // The parkour room: its way down opens once it's cleared.
        if (isOpen("code") && !isOpen("parkour") && cleared(w, "parkour")) {
            setGate(w, "parkour", true);
            for (ServerPlayerEntity p : playersIn("parkour", w)) {
                Msg.actionBar(p, "§d" + Msg.trFor(p, "edge.room-cleared"));
            }
        }

        // The lever at the maze's heart (once the maze is cleared).
        if (!isOpen("maze") && s.wave == 0 && s.boss == null && !s.bossDone && lever(w, at(lay.mazeLever())) && cleared(w, "maze")) {
            setGate(w, "maze", true);
            for (ServerPlayerEntity p : playersIn("maze", w)) {
                Msg.actionBar(p, "§d" + Msg.trFor(p, "edge.maze-open"));
            }
        }

        // The Trial Hall: three waves of elites, and its gate (the way to the arena) opens when the last one falls.
        if ((isOpen("maze") || s.wave > 0) && !s.bossDone && s.boss == null && s.wave < 4) {
            trial(w);
        }

        // The arena and its lord.
        List<ServerPlayerEntity> fighters = playersIn("arena", w);
        MobEntity boss = s.boss == null ? null : w.getEntity(UUID.fromString(s.boss)) instanceof MobEntity m ? m : null;
        BlockPos lair = at(lay.bossSpawn());
        boolean killed = s.boss != null && wasKilled(s.boss);
        if (s.boss != null && boss == null && !killed && w.isChunkLoaded(lair.getX() >> 4, lair.getZ() >> 4)) {
            // Gone without dying (removed by the owner): he rises again next time someone steps in.
            s.boss = null;
            save();
        } else if (killed) {
            s.boss = null;
            s.bossDone = true;
            setGate(w, "trial", true);
            setGate(w, "vault", true);
            setGate(w, "exit", true);
            if (!s.filled) {
                BlockPos c = at(lay.vaultChest());
                Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(),
                        "loot replace block " + c.getX() + " " + c.getY() + " " + c.getZ() + " container.13 loot vigil:items/mjolnir");
                s.filled = true;
            }
            rewardBooks(w, at(lay.vaultChest()));
            for (ServerPlayerEntity p : inside) {
                Mc.title(p, "§d§lThe Edge Sovereign falls", "§7" + Msg.trFor(p, s.claimed ? "edge.vault-empty" : "edge.vault-open"), 10, 80, 20);
            }
            save();
        } else if (s.boss == null && !s.bossDone && isOpen("trial") && !fighters.isEmpty()) {
            Vec3d spot = Vec3d.ofBottomCenter(at(lay.bossSpawn()));
            MobEntity lord = Bosses.spawn(w, spot, Bosses.EDGE_SOVEREIGN);
            if (lord != null) {
                s.boss = lord.getUuidAsString();
                setGate(w, "trial", false);
                for (ServerPlayerEntity p : fighters) {
                    Mc.title(p, "§d§lThe Edge Sovereign", "§c" + Msg.trFor(p, "edge.boss-wakes"), 10, 70, 20);
                }
                save();
            }
        } else if (boss != null && fighters.isEmpty() && !isOpen("trial")) {
            // Everyone in the arena died: the gate opens again for the next try (it heals itself meanwhile).
            setGate(w, "trial", true);
        } else if (boss != null && !fighters.isEmpty() && isOpen("trial") && playersIn("trial", w).isEmpty()) {
            setGate(w, "trial", false);
        }

        // Mjolnir's fate: once it leaves the vault chest, it's claimed for good.
        if (s.filled && !s.claimed) {
            BlockPos c = at(lay.vaultChest());
            if (w.getBlockEntity(c) instanceof Inventory inv && !holdsMjolnir(inv)) {
                ServerPlayerEntity taker = null;
                double best = Double.MAX_VALUE;
                for (ServerPlayerEntity p : inside) {
                    double d = p.squaredDistanceTo(Vec3d.ofCenter(c));
                    if (d < best) {
                        best = d;
                        taker = p;
                    }
                }
                s.claimed = true;
                s.claimedBy = taker == null ? "?" : taker.getGameProfile().name();
                save();
                for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                    Mc.title(p, "§d§l⚒ " + s.claimedBy, "§7" + Msg.trFor(p, "edge.claimed"), 10, 100, 30);
                    Mc.sound(p, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.7f);
                }
                Ac.LOG.info("{} claimed Mjolnir", s.claimedBy);
            }
        }
    }

    // ---------------------------------------------------------------- clearing the rooms

    private static Map<String, Integer> quotas;

    private static int quota(String room) {
        if (quotas == null) {
            Map<String, Integer> q = new java.util.HashMap<>();
            try {
                var res = Ac.server().getResourceManager().getResource(LAYOUT).orElseThrow();
                try (var in = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(in).getAsJsonObject();
                    for (var e : o.getAsJsonObject("clear").entrySet()) {
                        q.put(e.getKey(), e.getValue().getAsInt());
                    }
                }
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the citadel's room quotas", e);
            }
            quotas = q;
        }
        return quotas.getOrDefault(room, 0);
    }

    private static final Map<String, Long> NAGGED = new java.util.HashMap<>();

    /** Whether enough of the room's mobs have died; if not, the players in it are told how many are left. */
    private static boolean cleared(ServerWorld w, String room) {
        int need = quota(room);
        int got = state().kills.getOrDefault(room, 0);
        if (got >= need) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - NAGGED.getOrDefault(room, 0L) > 4000) {
            NAGGED.put(room, now);
            for (ServerPlayerEntity p : playersIn(room, w)) {
                Msg.actionBar(p, "§c" + Msg.trFor(p, "edge.clear-room", got, need));
            }
        }
        return false;
    }

    /** A hostile mob died inside the citadel: it counts for the room it was in. */
    private static void mobDied(net.minecraft.entity.LivingEntity e) {
        if (!(e instanceof net.minecraft.entity.mob.Monster) || !(e.getEntityWorld() instanceof ServerWorld w)) {
            return;
        }
        for (String room : List.of("levers", "parkour", "maze")) {
            if (in(room, w, e.getX(), e.getY(), e.getZ())) {
                state().kills.merge(room, 1, Integer::sum);
                save();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- the Rift Gate (owner's machine)

    /** Owner: builds a Rift Gate in front of them. Anyone who drops an ender pearl in its hopper goes to the citadel. */
    public static void placeMachine(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (!state().placed) {
            Msg.send(p, "edge.none");
            return;
        }
        ServerWorld w = p.getEntityWorld();
        BlockPos c = p.getBlockPos().offset(p.getHorizontalFacing(), MR + 3);
        // Everything it will cover, as it was, so it can be undone exactly.
        Map<BlockPos, net.minecraft.block.BlockState> before = new LinkedHashMap<>();
        for (int dy = -1; dy <= MH; dy++) {
            for (int dx = -MR; dx <= MR; dx++) {
                for (int dz = -MR; dz <= MR; dz++) {
                    BlockPos q = c.add(dx, dy, dz);
                    before.put(q, w.getBlockState(q));
                }
            }
        }
        buildMachine(w, c);
        String key = Mc.worldId(w) + "|" + c.getX() + "|" + c.getY() + "|" + c.getZ();
        state().machines.add(key);
        save();
        LAST_MACHINES.computeIfAbsent(p.getUuid(), k -> new java.util.ArrayDeque<>()).push(new Built(key, w, before));
        Msg.send(p, "edge.machine-placed");
    }

    private record Built(String key, ServerWorld world, Map<BlockPos, net.minecraft.block.BlockState> before) {
    }

    /** Each owner's machines, newest first, with what stood there before (until the server restarts). */
    private static final Map<UUID, java.util.Deque<Built>> LAST_MACHINES = new java.util.HashMap<>();

    /** Owner: takes away the last Rift Gate they built and puts back exactly what stood there. */
    public static void undoMachine(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        var stack = LAST_MACHINES.get(p.getUuid());
        Built b = null;
        while (stack != null && !stack.isEmpty()) {
            Built top = stack.pop();
            if (state().machines.contains(top.key())) {
                b = top;
                break;
            }
        }
        if (b == null) {
            Msg.send(p, "edge.machine-nothing");
            return;
        }
        // Top down, so nothing that needs support breaks on the way.
        List<BlockPos> order = new ArrayList<>(b.before().keySet());
        order.sort((x, y) -> Integer.compare(y.getY(), x.getY()));
        for (BlockPos q : order) {
            if (b.world().getBlockEntity(q) instanceof Inventory inv) {
                inv.clear();
            }
            b.world().setBlockState(q, b.before().get(q), 2);
        }
        state().machines.remove(b.key());
        save();
        Msg.send(p, "edge.machine-undone");
    }

    private static net.minecraft.block.BlockState st(net.minecraft.block.Block b) {
        return b.getDefaultState();
    }

    /** How far the Rift Gate reaches from its hopper (it is 2R+1 blocks across), and how tall it stands. */
    static final int MR = 7;
    static final int MH = 16;

    /**
     * The Rift Gate: a 15x15 machine, 16 tall, round a hopper. Four great pylons of obsidian at the corners stacked with
     * lit redstone lamps, glass and crying obsidian; two rings of live redstone on the floor (dust fed by redstone
     * blocks, repeaters, comparators, observers); copper tesla coils topped with lightning rods; daylight sensors and
     * target blocks; a glass core rising from the hopper to a beacon and an amethyst crown high above. A clear walkway
     * leads in from each side so anyone can reach the hopper.
     */
    static void buildMachine(ServerWorld w, BlockPos c) {
        var B = new Object() {
            void at(int dx, int dy, int dz, net.minecraft.block.Block b) {
                w.setBlockState(c.add(dx, dy, dz), b.getDefaultState(), 3);
            }

            void at(int dx, int dy, int dz, net.minecraft.block.BlockState st) {
                w.setBlockState(c.add(dx, dy, dz), st, 3);
            }
        };
        // Clear the space, then the floor: a gold-and-crying-obsidian rim, stone, and a lodestone under the hopper.
        for (int dx = -MR; dx <= MR; dx++) {
            for (int dz = -MR; dz <= MR; dz++) {
                for (int dy = 0; dy <= MH; dy++) {
                    B.at(dx, dy, dz, net.minecraft.block.Blocks.AIR);
                }
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                boolean walk = dx == 0 || dz == 0;
                B.at(dx, -1, dz, ring == MR ? ((dx + dz) % 2 == 0 ? net.minecraft.block.Blocks.GILDED_BLACKSTONE : net.minecraft.block.Blocks.CRYING_OBSIDIAN)
                        : ring == 0 ? net.minecraft.block.Blocks.LODESTONE
                        : walk ? net.minecraft.block.Blocks.POLISHED_BLACKSTONE : net.minecraft.block.Blocks.POLISHED_BLACKSTONE_BRICKS);
            }
        }
        // Two rings of redstone on the floor (gaps where the walkways cross), each fed by redstone blocks.
        for (int r : new int[]{6, 3}) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == r && dx != 0 && dz != 0) {
                        B.at(dx, 0, dz, (Math.abs(dx) == r && Math.abs(dz) == r) ? net.minecraft.block.Blocks.REDSTONE_BLOCK
                                : net.minecraft.block.Blocks.REDSTONE_WIRE);
                    }
                }
            }
        }
        // Repeaters and comparators along the rings, pointing in toward the hopper.
        for (int r : new int[]{5, 2}) {
            for (var d : new net.minecraft.util.math.Direction[]{net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH,
                    net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
                int x = -d.getOffsetX() * r + (d.getOffsetZ() != 0 ? 2 : 0);
                int z = -d.getOffsetZ() * r + (d.getOffsetX() != 0 ? 2 : 0);
                B.at(x, 0, z, net.minecraft.block.Blocks.REPEATER.getDefaultState().with(net.minecraft.block.RepeaterBlock.FACING, d.getOpposite())
                        .with(net.minecraft.block.RepeaterBlock.DELAY, 1 + (r % 4)));
                B.at(-x, 0, -z, net.minecraft.block.Blocks.COMPARATOR.getDefaultState()
                        .with(net.minecraft.block.ComparatorBlock.FACING, d));
            }
        }
        // Four great pylons at the corners.
        for (int[] m : new int[][]{{MR - 1, MR - 1}, {-(MR - 1), MR - 1}, {MR - 1, -(MR - 1)}, {-(MR - 1), -(MR - 1)}}) {
            for (int dy = 0; dy <= 12; dy++) {
                net.minecraft.block.Block b = switch (dy % 4) {
                    case 0 -> net.minecraft.block.Blocks.OBSIDIAN;
                    case 1 -> net.minecraft.block.Blocks.REDSTONE_BLOCK;
                    case 2 -> net.minecraft.block.Blocks.REDSTONE_LAMP;
                    default -> net.minecraft.block.Blocks.PURPLE_STAINED_GLASS;
                };
                B.at(m[0], dy, m[1], b);
                // Buttresses on the two outer sides.
                if (dy <= 6) {
                    B.at(m[0] + Integer.signum(m[0]), dy, m[1], net.minecraft.block.Blocks.POLISHED_BLACKSTONE_BRICKS);
                    B.at(m[0], dy, m[1] + Integer.signum(m[1]), net.minecraft.block.Blocks.POLISHED_BLACKSTONE_BRICKS);
                }
            }
            B.at(m[0], 13, m[1], net.minecraft.block.Blocks.CRYING_OBSIDIAN);
            B.at(m[0], 14, m[1], net.minecraft.block.Blocks.END_ROD);
            B.at(m[0], 15, m[1], net.minecraft.block.Blocks.END_ROD);
        }
        // Tesla coils: copper columns with lightning rods, four on the diagonals between the rings.
        for (int[] m : new int[][]{{4, 4}, {-4, 4}, {4, -4}, {-4, -4}}) {
            for (int dy = 0; dy <= 6; dy++) {
                B.at(m[0], dy, m[1], dy % 2 == 0 ? net.minecraft.block.Blocks.WAXED_OXIDIZED_CUT_COPPER : net.minecraft.block.Blocks.WAXED_OXIDIZED_COPPER_GRATE);
            }
            B.at(m[0], 7, m[1], net.minecraft.block.Blocks.LIGHTNING_ROD);
        }
        // Observers and target blocks watching the core; daylight sensors on the side posts.
        for (int[] m : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) {
            B.at(m[0], 0, m[1], net.minecraft.block.Blocks.OBSERVER);
            B.at(m[0], 1, m[1], net.minecraft.block.Blocks.TARGET);
        }
        for (int[] m : new int[][]{{MR, 3}, {MR, -3}, {-MR, 3}, {-MR, -3}, {3, MR}, {-3, MR}, {3, -MR}, {-3, -MR}}) {
            B.at(m[0], 0, m[1], net.minecraft.block.Blocks.CHISELED_POLISHED_BLACKSTONE);
            B.at(m[0], 1, m[1], net.minecraft.block.Blocks.REDSTONE_LAMP);
            B.at(m[0], 2, m[1], net.minecraft.block.Blocks.REDSTONE_BLOCK);
            B.at(m[0], 3, m[1], net.minecraft.block.Blocks.DAYLIGHT_DETECTOR);
        }
        // The core: a glass column on four obsidian legs rising over the hopper, crowned with a beacon and amethyst.
        for (int[] m : new int[][]{{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) {
            for (int dy = 2; dy <= 8; dy++) {
                B.at(m[0], dy, m[1], dy == 8 ? net.minecraft.block.Blocks.CRYING_OBSIDIAN : net.minecraft.block.Blocks.OBSIDIAN);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                B.at(dx, 9, dz, Math.abs(dx) == 2 || Math.abs(dz) == 2 ? net.minecraft.block.Blocks.POLISHED_BLACKSTONE_BRICKS
                        : net.minecraft.block.Blocks.MAGENTA_STAINED_GLASS);
            }
        }
        for (int dy = 3; dy <= 8; dy++) {
            B.at(0, dy, 0, dy % 2 == 0 ? net.minecraft.block.Blocks.PURPLE_STAINED_GLASS : net.minecraft.block.Blocks.MAGENTA_STAINED_GLASS);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                B.at(dx, 10, dz, net.minecraft.block.Blocks.IRON_BLOCK);
            }
        }
        B.at(0, 11, 0, net.minecraft.block.Blocks.BEACON);
        B.at(0, 12, 0, net.minecraft.block.Blocks.CRYING_OBSIDIAN);
        B.at(0, 13, 0, net.minecraft.block.Blocks.AMETHYST_BLOCK);
        B.at(0, 14, 0, net.minecraft.block.Blocks.AMETHYST_CLUSTER);
        // The hopper last, on the lodestone, with room above it to reach in.
        B.at(0, 0, 0, net.minecraft.block.Blocks.HOPPER);
    }

    /** Owner: takes away the Rift Gate nearest them (within 8 blocks). */
    public static void removeMachine(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        String best = null;
        double bestD = 20 * 20;
        for (String m : state().machines) {
            String[] k = m.split("\\|");
            if (k[0].equals(Mc.worldId(w))) {
                double d = p.squaredDistanceTo(Integer.parseInt(k[1]) + 0.5, Integer.parseInt(k[2]), Integer.parseInt(k[3]) + 0.5);
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
        }
        if (best == null) {
            Msg.send(p, "edge.machine-none");
            return;
        }
        String[] k = best.split("\\|");
        BlockPos c = new BlockPos(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
        for (int dx = -MR; dx <= MR; dx++) {
            for (int dz = -MR; dz <= MR; dz++) {
                for (int dy = MH; dy >= 0; dy--) {
                    w.setBlockState(c.add(dx, dy, dz), net.minecraft.block.Blocks.AIR.getDefaultState());
                }
                w.setBlockState(c.add(dx, -1, dz), net.minecraft.block.Blocks.DIRT.getDefaultState());
            }
        }
        state().machines.remove(best);
        save();
        Msg.send(p, "edge.machine-removed");
    }

    /** Every half second: a pearl in a Rift Gate's hopper sends the nearest player (not in combat) to the citadel. */
    private static void machines() {
        State s = state();
        if (s.machines.isEmpty() || !s.placed) {
            return;
        }
        for (String m : new ArrayList<>(s.machines)) {
            String[] k = m.split("\\|");
            ServerWorld w = Mc.world(Ac.server(), k[0]);
            if (w == null) {
                continue;
            }
            BlockPos c = new BlockPos(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
            if (!w.isChunkLoaded(c.getX() >> 4, c.getZ() >> 4)) {
                continue;
            }
            w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, c.getX() + 0.5, c.getY() + 1.2, c.getZ() + 0.5, 6, 0.4, 0.4, 0.4, 0.2);
            if (!(w.getBlockEntity(c) instanceof Inventory inv)) {
                continue;
            }
            int slot = -1;
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isOf(Items.ENDER_PEARL)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                continue;
            }
            ServerPlayerEntity who = null;
            double best = 6 * 6;
            for (ServerPlayerEntity p : w.getPlayers()) {
                double d = p.squaredDistanceTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
                if (!p.isSpectator() && d < best) {
                    best = d;
                    who = p;
                }
            }
            if (who == null) {
                continue;
            }
            if (LobbyFeature.inCombat(who)) {
                Msg.actionBar(who, "§c" + Msg.trFor(who, "lobby.in-combat"));
                continue;
            }
            inv.getStack(slot).decrement(1);
            inv.markDirty();
            ServerWorld to = world();
            if (to == null) {
                continue;
            }
            // The machine charges for a moment: sparks up the coils, the crown flares, thunder, then away.
            ServerPlayerEntity rider = who;
            for (int tick = 0; tick < 30; tick += 3) {
                int step = tick;
                OwnerPowers.later(tick, () -> {
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.ELECTRIC_SPARK, c.getX() + 0.5, c.getY() + 1 + step * 0.1,
                            c.getZ() + 0.5, 20, 1.2, 0.8, 1.2, 0.2);
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.REVERSE_PORTAL, c.getX() + 0.5, c.getY() + 10.5, c.getZ() + 0.5,
                            40, 1.2, 1.2, 1.2, 0.05);
                    w.playSound(null, c, SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.BLOCKS, 1.2f, 0.6f + step * 0.04f);
                });
            }
            OwnerPowers.later(30, () -> {
                OwnerCombat.bolt(w, Vec3d.ofBottomCenter(c.up(15)), false, null);
                if (!rider.isRemoved() && rider.isAlive() && !LobbyFeature.inCombat(rider)) {
                    sendToCitadel(rider, to);
                }
            });
        }
    }

    private static void sendToCitadel(ServerPlayerEntity who, ServerWorld to) {
        State s = state();
        int[] size = layout().size();
        int tx = s.x - 4;
        int tz = s.z + size[2] / 2;
        to.getChunk(tx >> 4, tz >> 4);
        int ty = to.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, tx, tz);
        Mc.teleport(who, to, tx + 0.5, ty, tz + 0.5, -90f, 0f);
        who.fallDistance = 0;
        to.playSound(null, who.getBlockPos(), SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.BLOCKS, 0.6f, 1.4f);
        Mc.title(who, "§d§lWorld's Edge", "§7" + Msg.trFor(who, "edge.machine-arrived"), 10, 60, 20);
    }

    // ---------------------------------------------------------------- the offering

    private static List<int[]> hoppers;
    private static List<String> offering;

    private static void readOffering() {
        if (offering != null) {
            return;
        }
        try {
            var res = Ac.server().getResourceManager().getResource(LAYOUT).orElseThrow();
            JsonObject o;
            try (var in = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                o = JsonParser.parseReader(in).getAsJsonObject();
            }
            List<int[]> hs = new ArrayList<>();
            for (var e : o.getAsJsonArray("hoppers")) {
                hs.add(ints(e.getAsJsonArray()));
            }
            List<String> items = new ArrayList<>();
            for (var e : o.getAsJsonArray("offering")) {
                items.add(e.getAsString());
            }
            hoppers = hs;
            offering = items;
        } catch (Exception e) {
            throw new IllegalStateException("The citadel's offering couldn't be read", e);
        }
    }

    /** For the game tests: the items the entrance wants, in order. */
    public static List<String> offeringForTest() {
        readOffering();
        return offering;
    }

    /**
     * Each hopper wants its own item, given in order (I, II, III, IV). The right item in the right hopper is taken; the
     * wrong item, or any item out of turn, is thrown back out with lightning and the count starts again.
     */
    private static void offering(ServerWorld w) {
        State s = state();
        readOffering();
        for (int i = 0; i < hoppers.size(); i++) {
            if (!(w.getBlockEntity(at(hoppers.get(i))) instanceof Inventory inv) || inv.isEmpty()) {
                continue;
            }
            Identifier want = Identifier.of(offering.get(i));
            boolean right = i == s.offered;
            int slot = -1;
            for (int k = 0; k < inv.size(); k++) {
                ItemStack st = inv.getStack(k);
                if (!st.isEmpty() && Registries.ITEM.getId(st.getItem()).equals(want)) {
                    slot = k;
                    break;
                }
            }
            boolean accepted = right && slot >= 0;
            if (accepted) {
                inv.getStack(slot).decrement(1);
                s.offered++;
                save();
                BlockPos hp = at(hoppers.get(i));
                w.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, hp.getX() + 0.5, hp.getY() + 1.2, hp.getZ() + 0.5, 30, 0.3, 0.4, 0.3, 0.05);
                w.playSound(null, hp, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.BLOCKS, 1.5f, 0.6f + 0.15f * s.offered);
                for (ServerPlayerEntity p : playersIn("porch", w)) {
                    Msg.actionBar(p, "§d" + Msg.trFor(p, "edge.offered", s.offered, hoppers.size()));
                }
            }
            // Whatever is left in it comes back out: extras of an accepted gift quietly, anything else as a mistake.
            boolean wrong = false;
            for (int k = 0; k < inv.size(); k++) {
                ItemStack st = inv.getStack(k);
                if (!st.isEmpty()) {
                    wrong |= !accepted;
                    BlockPos hp = at(hoppers.get(i));
                    net.minecraft.util.ItemScatterer.spawn(w, hp.getX() - 1.0, hp.getY() + 0.5, hp.getZ(), st.copy());
                    inv.setStack(k, ItemStack.EMPTY);
                }
            }
            inv.markDirty();
            if (wrong) {
                s.offered = 0;
                save();
                for (ServerPlayerEntity p : playersIn("porch", w)) {
                    OwnerCombat.bolt(w, p.getEntityPos(), true, null);
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "edge.offer-wrong"));
                }
                return;
            }
        }
        if (s.offered >= hoppers.size()) {
            setGate(w, "entry", true);
            for (ServerPlayerEntity p : playersIn("porch", w)) {
                Mc.title(p, "§d§lThe offering is accepted", "§7" + Msg.trFor(p, "edge.offer-done"), 10, 60, 20);
            }
        }
    }

    // ---------------------------------------------------------------- the Trial Hall

    static final String WAVE_TAG = "vigil_edge_wave";

    private static List<int[]> waveSpots;
    private static List<List<Map.Entry<String, Integer>>> waves;

    private static void readWaves() {
        if (waves != null) {
            return;
        }
        try {
            var res = Ac.server().getResourceManager().getResource(LAYOUT).orElseThrow();
            JsonObject o;
            try (var in = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                o = JsonParser.parseReader(in).getAsJsonObject();
            }
            List<int[]> spots = new ArrayList<>();
            for (var e : o.getAsJsonArray("wave_spots")) {
                spots.add(ints(e.getAsJsonArray()));
            }
            List<List<Map.Entry<String, Integer>>> all = new ArrayList<>();
            for (var wave : o.getAsJsonArray("waves")) {
                List<Map.Entry<String, Integer>> one = new ArrayList<>();
                for (var mob : wave.getAsJsonArray()) {
                    JsonArray m = mob.getAsJsonArray();
                    one.add(Map.entry(m.get(0).getAsString(), m.get(1).getAsInt()));
                }
                all.add(one);
            }
            waveSpots = spots;
            waves = all;
        } catch (Exception e) {
            throw new IllegalStateException("The Trial Hall's waves couldn't be read", e);
        }
    }

    private static List<MobEntity> waveMobs(ServerWorld w) {
        State s = state();
        int[] size = layout().size();
        var box = new net.minecraft.util.math.Box(s.x, s.y, s.z, s.x + size[0], s.y + size[1] + 2, s.z + size[2]);
        return w.getEntitiesByClass(MobEntity.class, box, m -> m.isAlive() && m.getCommandTags().contains(WAVE_TAG));
    }

    private static void clearWave(ServerWorld w) {
        waveMobs(w).forEach(Entity::discard);
    }

    /** Starts the waves when someone steps in, sends the next one when a wave is dead, opens the gate after the third. */
    private static void trial(ServerWorld w) {
        State s = state();
        readWaves();
        List<ServerPlayerEntity> in = playersIn("trial", w);
        if (s.wave == 0) {
            if (in.isEmpty()) {
                return;
            }
            setGate(w, "maze", false);  // no way back
            spawnWave(w, 1, in);
            return;
        }
        if (!waveMobs(w).isEmpty()) {
            return;
        }
        if (s.wave < waves.size()) {
            spawnWave(w, s.wave + 1, in);
            return;
        }
        s.wave = 4;
        save();
        setGate(w, "trial", true);
        for (ServerPlayerEntity p : in) {
            Mc.title(p, "§d§lThe Trial is won", "§7" + Msg.trFor(p, "edge.trial-won"), 10, 60, 20);
        }
    }

    private static void spawnWave(ServerWorld w, int n, List<ServerPlayerEntity> in) {
        State s = state();
        s.wave = n;
        save();
        var r = w.getRandom();
        for (var e : waves.get(n - 1)) {
            var type = Registries.ENTITY_TYPE.get(Identifier.of(e.getKey()));
            for (int i = 0; i < e.getValue(); i++) {
                Entity made = type.create(w, SpawnReason.EVENT);
                if (!(made instanceof MobEntity m)) {
                    continue;
                }
                int[] spot = waveSpots.get(r.nextInt(waveSpots.size()));
                BlockPos at = at(spot);
                m.refreshPositionAndAngles(at.getX() + 0.5 + r.nextGaussian() * 0.6, at.getY(), at.getZ() + 0.5 + r.nextGaussian() * 0.6,
                        r.nextFloat() * 360, 0);
                m.addCommandTag(WAVE_TAG);
                m.addCommandTag(Bosses.GUARD_TAG);
                StructureMobs.guard(m);
                StructureMobs.elite(m);
                m.setPersistent();
                w.spawnEntity(m);
                if (!in.isEmpty()) {
                    m.setTarget(in.get(r.nextInt(in.size())));
                }
            }
        }
        for (ServerPlayerEntity p : in) {
            Mc.title(p, "§c§l" + Msg.trFor(p, "edge.wave", n, waves.size()), "", 5, 40, 10);
            Mc.sound(p, SoundEvents.EVENT_RAID_HORN.value(), 1f, 1f);
        }
    }

    /** Every group that beats the Edge Sovereign finds two of the new enchanted books (top level) in the vault. */
    private static void rewardBooks(ServerWorld w, BlockPos chest) {
        if (!(w.getBlockEntity(chest) instanceof Inventory inv)) {
            return;
        }
        List<String> pool = new ArrayList<>(List.of(CustomEnchants.VITALITY, CustomEnchants.FIRE_WARD, CustomEnchants.LEAPING,
                CustomEnchants.GILLS, CustomEnchants.REFLECTION, CustomEnchants.SECOND_WIND, CustomEnchants.MIGHT,
                CustomEnchants.THUNDERLORD, CustomEnchants.EXECUTIONER));
        java.util.Collections.shuffle(pool, new java.util.Random(w.getRandom().nextLong()));
        int[] slots = {11, 15};
        for (int k = 0; k < slots.length; k++) {
            String id = pool.get(k);
            if (ContentToggles.on("enchant:" + id) && inv.getStack(slots[k]).isEmpty()) {
                inv.setStack(slots[k], CustomEnchants.book(id, CustomEnchants.maxLevel(id)));
            }
        }
        inv.markDirty();
    }

    private static final Set<String> KILLED = new HashSet<>();

    /** Bosses calls this when one dies, so a kill isn't mistaken for the boss's chunk unloading. */
    static void bossDied(Entity e) {
        KILLED.add(e.getUuidAsString());
    }

    private static boolean wasKilled(String id) {
        return KILLED.remove(id);
    }

    private static boolean holdsMjolnir(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (SecretItems.MJOLNIR.equals(SecretItems.idOf(inv.getStack(i)))) {
                return true;
            }
        }
        return false;
    }

    private static long lastTrap;

    /** A wrong code: lightning on everyone in the room, shades, and the levers spring back. */
    private static void wrongCode(ServerWorld w, Layout lay) {
        for (int[] l : lay.levers()) {
            setLever(w, at(l), false);
        }
        long now = System.currentTimeMillis();
        if (now - lastTrap < 1500) {
            return;
        }
        lastTrap = now;
        for (ServerPlayerEntity p : playersIn("levers", w)) {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.refreshPositionAfterTeleport(p.getEntityPos());
                w.spawnEntity(bolt);
            }
            Msg.actionBar(p, "§c" + Msg.trFor(p, "edge.code-wrong"));
            var knight = EntityType.WITHER_SKELETON.create(w, SpawnReason.EVENT);
            if (knight != null) {
                knight.refreshPositionAndAngles(p.getX(), p.getY(), p.getZ() + 2, 0, 0);
                knight.addCommandTag(Bosses.GUARD_TAG);
                StructureMobs.guard(knight);
                w.spawnEntity(knight);
                knight.setTarget(p);
            }
            for (int i = 0; i < 2; i++) {
                var vex = EntityType.VEX.create(w, SpawnReason.EVENT);
                if (vex != null) {
                    vex.refreshPositionAndAngles(p.getX() + (i == 0 ? 2 : -2), p.getY() + 1.5, p.getZ(), 0, 0);
                    vex.addCommandTag(Bosses.GUARD_TAG);
                    StructureMobs.guard(vex);
                    w.spawnEntity(vex);
                    vex.setTarget(p);
                }
            }
        }
    }

    // ---------------------------------------------------------------- no cheating your way through

    private static boolean exempt(ServerPlayerEntity p) {
        return Perms.isOwner(p.getUuid());
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && state().placed) {
                mobDied(e);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                autoPlace(server);
            } catch (Exception e) {
                Ac.LOG.warn("The World's Edge Citadel couldn't build itself", e);
            }
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) ->
                !(Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !exempt(p) && protects(w, pos)));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !exempt(p)) {
                ItemStack held = p.getStackInHand(hand);
                if ((held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem) && protects(w, hit.getBlockPos())) {
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "edge.no-build"));
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !exempt(p)) {
                ItemStack held = p.getStackInHand(hand);
                boolean cheat = held.isOf(Items.ENDER_PEARL) || held.isOf(Items.CHORUS_FRUIT) || held.getItem() instanceof BucketItem
                        || held.isOf(Items.WIND_CHARGE);
                if (cheat && protects(w, p.getBlockPos())) {
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "edge.no-cheat"));
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
    }
}
