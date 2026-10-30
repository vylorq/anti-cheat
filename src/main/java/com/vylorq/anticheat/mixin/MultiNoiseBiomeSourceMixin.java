package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.vylorq.anticheat.feature.BiomeSwap;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** New biomes: patches of some of the game's biomes become the mod's (BiomeSwap). */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
    @ModifyReturnValue(method = "getBiome", at = @At("RETURN"), require = 0)
    private RegistryEntry<Biome> vigil$swap(RegistryEntry<Biome> original, int x, int y, int z) {
        return BiomeSwap.swap(original, x, z);
    }
}
