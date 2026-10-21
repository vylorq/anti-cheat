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
    }

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
            BlockSnapshots.save(w, area, SNAP);
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
        save();
        return null;
    }

    /**
     * The first time the server starts with it: the citadel builds itself at the east edge of the overworld's border,
     * level with the ground there. Only ever once by itself (the owner can move it with /owner edge remove + place).
     */
    static void autoPlace(net.minecraft.server.MinecraftServer server) {
        State s = state();
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
            if (emptyTicks >= EMPTY_RESET && (!isOpen("seal") || isOpen("code") || isOpen("maze") || s.wave > 0 || s.bossDone || s.boss != null)) {
                resetRun(w);
            }
            return;
        }
        emptyTicks = 0;

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
            if (on.equals(lay.code())) {
                setGate(w, "code", true);
                for (ServerPlayerEntity p : playersIn("levers", w)) {
                    Msg.actionBar(p, "§d" + Msg.trFor(p, "edge.code-right"));
                }
            } else if (on.size() >= lay.code().size()) {
                wrongCode(w, lay);
            }
        }

        // The lever at the maze's heart.
        if (!isOpen("maze") && s.wave == 0 && s.boss == null && !s.bossDone && lever(w, at(lay.mazeLever()))) {
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
