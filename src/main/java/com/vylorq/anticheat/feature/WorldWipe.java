package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The owner's server wipe ("/owner wipe", then "/owner wipe confirm" within a minute): a fresh start for everyone.
 * <p>
 * Kept: the lobby, the jail, the waiting room, the arenas, the owner's builds, and every base (team land and claims).
 * Everything else in the overworld and the Nether generates again from scratch (the End is left alone, so its
 * dragon fight isn't broken). Every player starts over: no inventory, ender chest, XP, advancements or stats, and
 * they arrive at the lobby. Bases keep their builds but lose every chest and container (with what's in it), every
 * item on the ground, in frames and on armour stands; team vaults are emptied. The World's Edge Citadel, the Tempest
 * Keep and the bosses start over too. Money, teams, ranks and claims stay.
 * <p>
 * How: the plan is written, everyone is kicked, the world is saved and backed up (a zip in config/vigil/backups),
 * and the server stops (its service starts it again). As it starts, before any world loads, the region files are
 * cut down to the kept chunks; as each base chunk loads, its containers and items are taken away.
 */
public final class WorldWipe {
    private WorldWipe() {
    }

    /** What the next start wipes: world id -> chunk keys ("x,z") kept, and which of those are bases (cleaned). */
    public static final class Plan {
        public Map<String, Set<String>> keep = new HashMap<>();
        public Map<String, Set<String>> bases = new HashMap<>();
        public long at;
        public String by;
    }

    /** After a wipe: base chunks still to clean, and the players already sent to the lobby. */
    public static final class State {
        public long wipedAt;
        public Map<String, Set<String>> toClean = new HashMap<>();
        public Set<String> welcomed = new HashSet<>();
        public boolean vaultsCleared = true;
    }

    /** Only these worlds are wiped (the lobby can be in its own world, and the End keeps its dragon fight). */
    static final List<String> WIPED = List.of("minecraft:overworld", "minecraft:the_nether");

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("vigil");
    }

    private static Path planFile() {
        return dir().resolve("wipe-plan.json");
    }

    private static Path stateFile() {
        return dir().resolve("wipe-state.json");
    }

    private static State state;

    static State state() {
        if (state == null) {
            try {
                Path f = stateFile();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read wipe-state.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.toClean == null) {
                state.toClean = new HashMap<>();
            }
            if (state.welcomed == null) {
                state.welcomed = new HashSet<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(stateFile(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save wipe-state.json", e);
        }
    }

    // ---------------------------------------------------------------- the command

    private static final Map<UUID, Long> ASKED = new HashMap<>();

    /** /owner wipe: says what happens and waits for the confirm. */
    public static void ask(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ASKED.put(p.getUuid(), System.currentTimeMillis());
        Plan plan = plan();
        int kept = plan.keep.values().stream().mapToInt(Set::size).sum();
        int bases = plan.bases.values().stream().mapToInt(Set::size).sum();
        Msg.send(p, "wipe.warn", kept, bases);
    }

    /** /owner wipe confirm (within a minute of /owner wipe): writes the plan, backs up, stops the server. */
    public static void confirm(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Long asked = ASKED.remove(p.getUuid());
        if (asked == null || System.currentTimeMillis() - asked > 60_000) {
            Msg.send(p, "wipe.ask-first");
            return;
        }
        Plan plan = plan();
        plan.at = System.currentTimeMillis();
        plan.by = p.getGameProfile().name();
        try {
            Files.createDirectories(dir());
            Files.writeString(planFile(), ConfigManager.GSON.toJson(plan), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.error("Could not write the wipe plan", e);
            Msg.send(p, "wipe.failed", e.toString());
            return;
        }
        Staff.log(p, "server-wipe", null, null, plan.keep.values().stream().mapToInt(Set::size).sum() + " chunks kept");
        MinecraftServer server = Ac.server();
        for (ServerPlayerEntity o : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            o.networkHandler.disconnect(Text.literal(Msg.trFor(o, "wipe.kick")));
        }
        server.saveAll(true, true, true);
        try {
            Extras.backupWorld();
        } catch (Exception e) {
            // The wipe still happens next start; the cut region files are copied aside there as well.
            Ac.LOG.error("World backup before the wipe failed", e);
        }
        Ac.LOG.info("Server wipe planned by {}: stopping so it can be applied as the server starts again.", plan.by);
        server.stop(false);
    }

    /** The chunks kept: the lobby, jail, waiting room, arenas and builds (with a margin), and every base. */
    static Plan plan() {
        Plan plan = new Plan();
        Ac ac = Ac.get();
        var lobby = ac.lobby.data();
        if (lobby.area != null) {
            area(plan, lobby.area, 2);
        }
        if (lobby.spawn != null) {
            around(plan, lobby.spawn.world(), (int) Math.floor(lobby.spawn.x()), (int) Math.floor(lobby.spawn.z()), 3);
        }
        var wr = ac.waitingRoom.data();
        if (wr.area != null) {
            area(plan, wr.area, 1);
        }
        for (var a : ac.arenas.arenas()) {
            if (a.area != null) {
                area(plan, a.area, 1);
            }
        }
        for (var cell : ac.jail.data().cells.values()) {
            around(plan, cell.world(), (int) Math.floor(cell.x()), (int) Math.floor(cell.z()), 2);
        }
        for (var b : Builds.state().placed) {
            area(plan, new Area(b.world(), b.x(), b.y(), b.z(), b.x() + b.sx(), b.y() + b.sy(), b.z() + b.sz()), 1);
        }
        // Bases: every team's land and every claim (kept, and cleaned of containers and items).
        for (Team t : Teams.tm().list()) {
            for (String c : t.chunks) {
                String[] parts = c.split("\\|");
                if (parts.length == 3) {
                    try {
                        base(plan, parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    } catch (NumberFormatException ignored) {
                        // a broken entry
                    }
                }
            }
        }
        for (Claim c : ac.claims.all()) {
            if (c.status != Claim.Status.ACTIVE || c.world == null) {
                continue;
            }
            for (int cx = c.minX >> 4; cx <= c.maxX >> 4; cx++) {
                for (int cz = c.minZ >> 4; cz <= c.maxZ >> 4; cz++) {
                    base(plan, c.world, cx, cz);
                }
            }
        }
        return plan;
    }

    private static void base(Plan plan, String world, int cx, int cz) {
        plan.keep.computeIfAbsent(world, k -> new HashSet<>()).add(cx + "," + cz);
        plan.bases.computeIfAbsent(world, k -> new HashSet<>()).add(cx + "," + cz);
    }

    private static void area(Plan plan, Area a, int margin) {
        Set<String> set = plan.keep.computeIfAbsent(a.world, k -> new HashSet<>());
        for (int cx = (Math.min(a.minX, a.maxX) >> 4) - margin; cx <= (Math.max(a.minX, a.maxX) >> 4) + margin; cx++) {
            for (int cz = (Math.min(a.minZ, a.maxZ) >> 4) - margin; cz <= (Math.max(a.minZ, a.maxZ) >> 4) + margin; cz++) {
                set.add(cx + "," + cz);
            }
        }
    }

    private static void around(Plan plan, String world, int x, int z, int r) {
        if (world == null) {
            return;
        }
        Set<String> set = plan.keep.computeIfAbsent(world, k -> new HashSet<>());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                set.add(((x >> 4) + dx) + "," + ((z >> 4) + dz));
            }
        }
    }

    // ---------------------------------------------------------------- applying it (as the server starts)

    /** Before any world loads: cuts the region files down to the kept chunks and clears every player's data. */
    static void apply(MinecraftServer server) {
        Path f = planFile();
        if (!Files.exists(f)) {
            return;
        }
        Plan plan;
        try {
            plan = ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Plan.class);
        } catch (Exception e) {
            Ac.LOG.error("Could not read the wipe plan; the server was not wiped", e);
            return;
        }
        Path root = server.getSavePath(WorldSavePath.ROOT);
        long chunks = 0;
        for (String world : WIPED) {
            Set<String> keep = plan.keep.getOrDefault(world, Set.of());
            for (String folder : List.of("region", "entities", "poi")) {
                Path d = dimDir(root, world).resolve(folder);
                if (!Files.isDirectory(d)) {
                    continue;
                }
                try (Stream<Path> files = Files.list(d)) {
                    for (Path mca : files.filter(p -> p.getFileName().toString().endsWith(".mca")).toList()) {
                        chunks += cut(mca, keep);
                    }
                } catch (Exception e) {
                    Ac.LOG.error("Wipe: could not go through {}", d, e);
                }
            }
        }
        // Every player starts over.
        for (String sub : List.of("playerdata", "advancements", "stats")) {
            Path d = root.resolve(sub);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> files = Files.list(d)) {
                for (Path p : files.toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (Exception e) {
                Ac.LOG.error("Wipe: could not clear {}", d, e);
            }
        }
        // The citadel, the keep and the bosses start over (the citadel builds itself at the border again).
        for (String name : List.of("edge-citadel.json", "tempest-keep.json", "bosses.json", "snapshots/edge_citadel.nbt",
                "snapshots/tempest_keep.nbt")) {
            try {
                Files.deleteIfExists(dir().resolve(name));
            } catch (Exception e) {
                Ac.LOG.warn("Wipe: could not remove {}", name, e);
            }
        }
        State s = new State();
        s.wipedAt = plan.at;
        s.toClean = plan.bases;
        s.vaultsCleared = false;
        state = s;
        save();
        try {
            Files.delete(f);
        } catch (Exception e) {
            Ac.LOG.error("Wipe: could not remove the plan (it would be applied again next start!)", e);
        }
        Ac.LOG.info("Server wipe done: {} chunks will generate fresh; every player starts over.", chunks);
    }

    /** Takes every chunk not in {@code keep} out of a region file (deleting the file if none is kept). */
    private static long cut(Path mca, Set<String> keep) throws Exception {
        String[] parts = mca.getFileName().toString().split("\\.");
        if (parts.length != 4) {
            return 0;
        }
        int rx = Integer.parseInt(parts[1]);
        int rz = Integer.parseInt(parts[2]);
        List<Integer> drop = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            int cx = rx * 32 + (i & 31);
            int cz = rz * 32 + (i >> 5);
            if (!keep.contains(cx + "," + cz)) {
                drop.add(i);
            }
        }
        if (drop.size() == 1024) {
            Files.delete(mca);
            return 1024;
        }
        if (Files.size(mca) < 8192) {
            return 0;
        }
        try (RandomAccessFile raf = new RandomAccessFile(mca.toFile(), "rw")) {
            for (int i : drop) {
                raf.seek(4L * i);
                raf.writeInt(0);
                raf.seek(4096L + 4L * i);
                raf.writeInt(0);
            }
        }
        return drop.size();
    }

    private static Path dimDir(Path root, String world) {
        return switch (world) {
            case "minecraft:the_nether" -> root.resolve("DIM-1");
            case "minecraft:the_end" -> root.resolve("DIM1");
            case "minecraft:overworld" -> root;
            default -> {
                Identifier id = Identifier.tryParse(world);
                yield id == null ? root : root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
            }
        };
    }

    // ---------------------------------------------------------------- cleaning the bases, welcoming players

    /** Base chunks just cleaned: item entities loading in them are removed until this tick. */
    private static final Map<String, Long> ENTITY_WINDOW = new HashMap<>();

    private static String key(ServerWorld w, int cx, int cz) {
        return Mc.worldId(w) + "|" + cx + "," + cz;
    }

    /** A base chunk loads for the first time since the wipe: its containers go, with what's in them. */
    private static void chunkLoaded(ServerWorld w, WorldChunk chunk) {
        State s = state();
        Set<String> set = s.toClean.get(Mc.worldId(w));
        String k = chunk.getPos().x + "," + chunk.getPos().z;
        if (set == null || !set.remove(k)) {
            return;
        }
        ENTITY_WINDOW.put(key(w, chunk.getPos().x, chunk.getPos().z), w.getTime() + 200);
        save();
        OwnerPowers.later(1, () -> {
            List<BlockPos> containers = new ArrayList<>();
            for (var be : chunk.getBlockEntities().values()) {
                if (be instanceof Inventory) {
                    containers.add(be.getPos());
                }
            }
            for (BlockPos pos : containers) {
                if (w.getBlockEntity(pos) instanceof Inventory inv) {
                    inv.clear();
                }
                w.setBlockState(pos, Blocks.AIR.getDefaultState(), 2 | 16);
            }
        });
    }

    /** Items on the ground, in frames, on armour stands and in chest minecarts and boats in a cleaned base go. */
    private static void entityLoaded(Entity e, ServerWorld w) {
        if (ENTITY_WINDOW.isEmpty()) {
            return;
        }
        Long until = ENTITY_WINDOW.get(key(w, e.getChunkPos().x, e.getChunkPos().z));
        if (until == null) {
            return;
        }
        if (w.getTime() > until) {
            ENTITY_WINDOW.values().removeIf(t -> w.getTime() > t);
            return;
        }
        if (e instanceof ItemEntity || e instanceof ItemFrameEntity || e instanceof ArmorStandEntity || e instanceof Inventory) {
            if (e instanceof Inventory inv) {
                inv.clear();
            }
            OwnerPowers.later(1, e::discard);
        }
    }

    /** Everyone's first join after a wipe: off to the lobby, with a word on what happened. */
    public static void onJoin(ServerPlayerEntity p) {
        State s = state();
        if (s.wipedAt == 0 || !s.welcomed.add(p.getUuidAsString())) {
            return;
        }
        save();
        OwnerPowers.later(5, () -> {
            if (!p.isRemoved() && Ac.get().lobby.data().spawn != null) {
                LobbyFeature.teleport(p);
            }
            Mc.title(p, "§b§l" + Msg.trFor(p, "wipe.title"), "§7" + Msg.trFor(p, "wipe.subtitle"), 10, 80, 20);
        });
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(WorldWipe::apply);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            State s = state();
            if (!s.vaultsCleared) {
                // Team vaults are emptied too.
                for (Team t : Teams.tm().list()) {
                    Teams.forgetVault(t, null);
                }
                Ac.markDirty("teams");
                s.vaultsCleared = true;
                save();
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register((w, chunk) -> {
            if (!state().toClean.isEmpty()) {
                chunkLoaded(w, chunk);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(WorldWipe::entityLoaded);
    }
}
