package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The achievement book (/achievements): every boss beaten, every structure found, every artifact held, the biomes
 * walked through, skill milestones and a few feats. Kept per player in achievements.json.
 */
public final class Achievements {
    private Achievements() {
    }

    /** Feats: id -> name (shown in the book once earned, greyed out until then). */
    public static final Map<String, String> FEATS = new LinkedHashMap<>();

    static {
        FEATS.put("feat:guardian", "Guardian Slayer: beat a room Guardian");
        FEATS.put("feat:greater", "Twice as Strong: make a Greater artifact");
        FEATS.put("feat:set", "Perfect Pair: wear an artifact set");
        FEATS.put("feat:socket", "Gemsmith: set a gem into a weapon");
        FEATS.put("feat:vault", "Key Holder: open a locked vault");
        FEATS.put("feat:all_new_biomes", "Wanderer: see all five new biomes");
        FEATS.put("feat:season_top3", "Champion: finish an arena season in the top three");
    }

    public static final java.util.List<String> NEW_BIOMES = java.util.List.of("vigil:autumn_forest", "vigil:lavender_fields",
            "vigil:frost_pines", "vigil:glowshroom_marsh", "vigil:ember_wastes");

    private static Map<String, Set<String>> data;
    private static boolean dirty;

    private static Path file() {
        return Ac.get().dir.resolve("achievements.json");
    }

    private static Map<String, Set<String>> data() {
        if (data == null) {
            try {
                Path f = file();
                Map<String, Set<String>> d = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                        new com.google.gson.reflect.TypeToken<Map<String, TreeSet<String>>>() { }.getType()) : null;
                data = d == null ? new HashMap<>() : d;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read achievements", e);
                data = new HashMap<>();
            }
        }
        return data;
    }

    public static void save() {
        if (!dirty || data == null) {
            return;
        }
        try {
            Files.writeString(file(), ConfigManager.GSON.toJson(data), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            Ac.LOG.warn("Could not save achievements", e);
        }
    }

    public static Set<String> of(ServerPlayerEntity p) {
        return data().getOrDefault(p.getUuidAsString(), Set.of());
    }

    public static boolean has(ServerPlayerEntity p, String id) {
        return of(p).contains(id);
    }

    /** Records it; the first time, the player hears about it. Biomes are recorded quietly. */
    public static void unlock(ServerPlayerEntity p, String id) {
        if (!data().computeIfAbsent(p.getUuidAsString(), k -> new TreeSet<>()).add(id)) {
            return;
        }
        dirty = true;
        if (id.startsWith("biome:")) {
            if (NEW_BIOMES.stream().allMatch(b -> has(p, "biome:" + b))) {
                unlock(p, "feat:all_new_biomes");
            }
            return;
        }
        p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.1f);
        Msg.send(p, "ach.unlocked", label(id));
    }

    /** How an entry reads in the book and in the message. */
    public static String label(String id) {
        String[] parts = id.split(":", 2);
        String rest = parts.length > 1 ? parts[1] : id;
        return switch (parts[0]) {
            case "boss" -> "Beat " + Bosses.nameOf(rest);
            case "structure" -> "Found the " + pretty(rest);
            case "artifact" -> {
                var d = Artifacts.DEFS.get(rest);
                yield "Found the " + (d == null ? rest : d.name());
            }
            case "skill" -> {
                String[] s = rest.split(":");
                yield pretty(s[0]) + " level " + (s.length > 1 ? s[1] : "?");
            }
            case "feat" -> FEATS.getOrDefault(id, rest);
            default -> pretty(rest);
        };
    }

    public static String pretty(String id) {
        StringBuilder b = new StringBuilder();
        for (String w : id.replace("vigil:", "").replace("minecraft:", "").split("_")) {
            if (!w.isEmpty()) {
                b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return b.toString();
    }

    /** Everyone close by when a boss falls gets the credit. */
    public static void bossDefeated(ServerWorld w, String kind, Vec3d at) {
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) < 64 * 64) {
                unlock(p, "boss:" + kind);
                Skills.add(p, "combat", 150);
            }
        }
    }

    public static void tick(long ticks) {
        if (ticks % 100 == 0) {
            for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                p.getEntityWorld().getBiome(p.getBlockPos()).getKey().ifPresent(k -> unlock(p, "biome:" + k.getValue()));
                if (Artifacts.set(Artifacts.worn(p)) != null) {
                    unlock(p, "feat:set");
                }
            }
        }
        if (ticks % 1200 == 0) {
            save();
        }
    }

    public static void register() {
        // Opening any chest of a boss structure counts as finding it.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && world.getBlockEntity(hit.getBlockPos()) instanceof LootableContainerBlockEntity c
                    && c.getLootTable() != null && c.getLootTable().getValue().getNamespace().equals("vigil")) {
                String path = c.getLootTable().getValue().getPath();
                if (path.startsWith("chests/")) {
                    String name = path.substring("chests/".length()).replaceAll("_(room|keyvault)$", "");
                    if (Bosses.structures().contains(name)) {
                        unlock(p, "structure:" + name);
                    }
                    if (path.endsWith("_keyvault")) {
                        unlock(p, "feat:vault");
                    }
                }
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
    }
}
