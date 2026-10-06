package com.vylorq.anticheat.feature;

import com.google.gson.reflect.TypeToken;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Owner tool to look at the secret structures: places one where the owner stands (saving the area first) and takes
 * it away again in an instant, blocks, mobs and all, as if it had never been there.
 */
public final class TestStructures {
    private TestStructures() {
    }

    public static final List<String> NAMES = List.of("sunken_vault", "buried_vault", "sky_citadel", "nether_forge",
            "desert_tomb", "frozen_bastion", "overgrown_labyrinth", "watchers_hollow");

    /** How far the rooms can reach from the middle (the structures' max distance plus a room). */
    private static final int REACH = 112;

    /** One placed structure: its number, kind, world and the saved area (x1 y1 z1 x2 y2 z2). */
    record Placed(int id, String structure, String world, int[] area) {
    }

    private static List<Placed> placed;

    private static Path file() {
        return BlockSnapshots.dir().resolve("test-structures.json");
    }

    private static synchronized List<Placed> placed() {
        if (placed == null) {
            placed = new ArrayList<>();
            try {
                if (Files.exists(file())) {
                    List<Placed> l = ConfigManager.GSON.fromJson(Files.readString(file()), new TypeToken<List<Placed>>() { }.getType());
                    if (l != null) {
                        placed.addAll(l);
                    }
                }
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the placed test structures", e);
            }
        }
        return placed;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(placed()));
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the placed test structures", e);
        }
    }

    /** The heights a structure can take up when placed at x, z (most follow the ground, three have a fixed height). */
    private static int[] heights(ServerWorld w, String structure, int x, int z) {
        return switch (structure) {
            case "sky_citadel" -> new int[]{200, 245};
            case "buried_vault" -> new int[]{-50, -20};
            case "nether_forge" -> new int[]{38, 70};
            default -> {
                int ground = w.getTopY(structure.equals("sunken_vault") ? Heightmap.Type.OCEAN_FLOOR : Heightmap.Type.WORLD_SURFACE, x, z);
                yield new int[]{ground - 22, ground + 22};
            }
        };
    }

    public static void place(ServerPlayerEntity p, String structure) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        int x = p.getBlockX();
        int z = p.getBlockZ();
        int[] ys = heights(w, structure, x, z);
        Area area = new Area(Mc.worldId(w), x - REACH, ys[0], z - REACH, x + REACH, ys[1], z + REACH);
        int id = placed().stream().mapToInt(Placed::id).max().orElse(0) + 1;
        String snap = "test_structure_" + id;
        try {
            BlockSnapshots.save(w, area, snap);
            var src = Ac.server().getCommandSource().withWorld(w).withSilent();
            int ok = Ac.server().getCommandManager().getDispatcher()
                    .execute("place structure vigil:" + structure + " " + x + " " + p.getBlockY() + " " + z, src);
            if (ok <= 0) {
                Files.deleteIfExists(BlockSnapshots.file(snap));
                Msg.send(p, "teststructure.failed", structure);
                return;
            }
        } catch (Exception e) {
            Ac.LOG.warn("Placing test structure {} failed", structure, e);
            try {
                Files.deleteIfExists(BlockSnapshots.file(snap));
            } catch (Exception ignored) {
                // nothing to clean up
            }
            Msg.send(p, "teststructure.failed", structure);
            return;
        }
        placed().add(new Placed(id, structure, Mc.worldId(w),
                new int[]{area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ}));
        save();
        Msg.send(p, "teststructure.placed", structure.replace('_', ' '), id, ys[0], ys[1]);
    }

    /** Removes one placed structure (the newest when id is null). */
    public static void remove(ServerPlayerEntity p, Integer id) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Placed target = null;
        for (Placed x : placed()) {
            if (id == null ? target == null || x.id() > target.id() : x.id() == id) {
                target = x;
            }
        }
        if (target == null) {
            Msg.send(p, "teststructure.none");
            return;
        }
        ServerWorld w = null;
        for (ServerWorld sw : Ac.server().getWorlds()) {
            if (Mc.worldId(sw).equals(target.world())) {
                w = sw;
            }
        }
        if (w == null) {
            Msg.send(p, "teststructure.none");
            return;
        }
        int[] a = target.area();
        String snap = "test_structure_" + target.id();
        try {
            // Its mobs first (guards, spawner mobs, the boss), then the blocks, then the game's record of the structure.
            var box = new net.minecraft.util.math.Box(a[0], a[1] - 10, a[2], a[3] + 1, a[4] + 30, a[5] + 1);
            for (MobEntity m : w.getEntitiesByClass(MobEntity.class, box, e -> (e instanceof Monster && !e.hasCustomName())
                    || e.getCommandTags().contains(Bosses.GUARD_TAG) || e.getCommandTags().stream().anyMatch(t -> t.startsWith(ModelMobs.TAG)))) {
                m.discard();
            }
            BlockSnapshots.restore(w, snap);
            forget(w, target.structure(), a);
            Files.deleteIfExists(BlockSnapshots.file(snap));
        } catch (Exception e) {
            Ac.LOG.warn("Removing test structure {} failed", target.id(), e);
            Msg.send(p, "teststructure.failed", target.structure());
            return;
        }
        placed().remove(target);
        save();
        Msg.send(p, "teststructure.removed", target.structure().replace('_', ' '), target.id());
    }

    /** Takes the structure out of the chunks' records, so its boss can't rise there and /locate doesn't find it. */
    private static void forget(ServerWorld w, String structure, int[] a) {
        var s = w.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE).get(Identifier.of("vigil", structure));
        if (s == null) {
            return;
        }
        for (int cx = a[0] >> 4; cx <= a[3] >> 4; cx++) {
            for (int cz = a[2] >> 4; cz <= a[5] >> 4; cz++) {
                var chunk = w.getChunk(cx, cz);
                var starts = new HashMap<>(chunk.getStructureStarts());
                var refs = new HashMap<>(chunk.getStructureReferences());
                boolean changed = starts.remove(s) != null;
                changed |= refs.remove(s) != null;
                if (changed) {
                    chunk.setStructureStarts(starts);
                    chunk.setStructureReferences(refs);
                    chunk.markNeedsSaving();
                }
            }
        }
    }

    public static void list(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (placed().isEmpty()) {
            Msg.send(p, "teststructure.none");
            return;
        }
        for (Placed x : placed()) {
            Msg.send(p, "teststructure.entry", x.id(), x.structure().replace('_', ' '), (x.area()[0] + x.area()[3]) / 2,
                    (x.area()[2] + x.area()[5]) / 2);
        }
    }
}
