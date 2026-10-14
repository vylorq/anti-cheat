package com.vylorq.anticheat.feature;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;

/**
 * New sights in new chunks (data/vigil/worldgen):
 * <ul>
 * <li>crystal caves deep underground: big hollow geodes of amethyst with sea lanterns and froglights glowing in the
 * walls;</li>
 * <li>glowing mushroom groves in the dark forest: more giant mushrooms, and the ground between them carpeted with
 * glow lichen that lights up at night.</li>
 * </ul>
 */
public final class Worldgen {
    private Worldgen() {
    }

    private static RegistryKey<net.minecraft.world.gen.feature.PlacedFeature> placed(String id) {
        return RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("vigil", id));
    }

    public static void register() {
        BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Feature.LOCAL_MODIFICATIONS, placed("crystal_cave"));
        var grove = BiomeSelectors.includeByKey(BiomeKeys.DARK_FOREST);
        for (String id : java.util.List.of("glow_grove_red_mushroom", "glow_grove_brown_mushroom", "glow_grove_lichen")) {
            BiomeModifications.addFeature(grove, GenerationStep.Feature.VEGETAL_DECORATION, placed(id));
        }
    }
}
