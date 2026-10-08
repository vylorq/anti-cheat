package com.vylorq.anticheat.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Data id for what a painting shows (it changes for a moment, for one player). */
@Mixin(PaintingEntity.class)
public interface PaintingAccessor {
    @Accessor("VARIANT")
    static TrackedData<RegistryEntry<PaintingVariant>> ac$variant() {
        throw new AssertionError();
    }
}
