package com.vylorq.anticheat.feature;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

import java.util.HashMap;
import java.util.Map;

/**
 * The new overworld biomes (data/vigil/worldgen/biome, made by scripts/worldgen/biomes.py). Each takes over big
 * patches of one of the game's biomes in new chunks, so it sits where that biome would, with the same weather and
 * mobs, but its own colours and extras:
 * <ul>
 * <li><b>Autumn Forest</b> (in forests): orange and red leaves, golden grass, pumpkins everywhere;</li>
 * <li><b>Lavender Fields</b> (in plains): purple grass and meadows of flowers;</li>
 * <li><b>Frost Pines</b> (in taiga): blue-green pines and Frostshard in the rock;</li>
 * <li><b>Glowshroom Marsh</b> (in swamps): teal water, glowing lichen and giant mushrooms.</li>
 * </ul>
 * The Nether's new biome, Ember Wastes, is added the game's own way (Worldgen.java).
 */
public final class BiomeSwap {
    private BiomeSwap() {
    }

    /** The game's biome -> the mod's biome that takes over patches of it. */
    private static final Map<RegistryKey<Biome>, String> SWAPS = Map.of(
            BiomeKeys.FOREST, "autumn_forest", BiomeKeys.BIRCH_FOREST, "autumn_forest",
            BiomeKeys.PLAINS, "lavender_fields", BiomeKeys.SUNFLOWER_PLAINS, "lavender_fields",
            BiomeKeys.TAIGA, "frost_pines",
            BiomeKeys.SWAMP, "glowshroom_marsh");

    private static volatile Map<RegistryKey<Biome>, RegistryEntry<Biome>> entries = Map.of();

    /** As the server starts: the mod's biomes from its registry (until then nothing is swapped). */
    public static void load(net.minecraft.server.MinecraftServer server) {
        var reg = server.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        Map<RegistryKey<Biome>, RegistryEntry<Biome>> out = new HashMap<>();
        for (var e : SWAPS.entrySet()) {
            var key = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("vigil", e.getValue()));
            reg.getOptional(key).ifPresent(entry -> out.put(e.getKey(), entry));
        }
        entries = out;
    }

    /** A biome at biome coordinates x, z (4 blocks each): the mod's, in its patches, else the game's. */
    public static RegistryEntry<Biome> swap(RegistryEntry<Biome> original, int x, int z) {
        var map = entries;
        if (map.isEmpty() || original == null) {
            return original;
        }
        var key = original.getKey().orElse(null);
        if (key == null) {
            return original;
        }
        var to = map.get(key);
        if (to == null) {
            return original;
        }
        // Patches about 300 blocks across, roughly one in three of that biome.
        return noise(x / 72.0, z / 72.0) > 0.58 ? to : original;
    }

    /** Smooth value noise in 0..1 (two octaves), the same in every world. */
    static double noise(double x, double z) {
        return 0.7 * value(x, z) + 0.3 * value(x * 2.3 + 17.1, z * 2.3 - 9.4);
    }

    private static double value(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3 - 2 * fx);
        double sz = fz * fz * (3 - 2 * fz);
        double a = hash(x0, z0);
        double b = hash(x0 + 1, z0);
        double c = hash(x0, z0 + 1);
        double d = hash(x0 + 1, z0 + 1);
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz;
    }

    private static double hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        return (h >>> 11) * 0x1.0p-53;
    }
}
