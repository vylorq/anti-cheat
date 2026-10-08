package com.vylorq.anticheat.mixin;

import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Data id for what an item frame holds (The Boiled One's face flashes in it, for one player). */
@Mixin(ItemFrameEntity.class)
public interface ItemFrameAccessor {
    @Accessor("ITEM_STACK")
    static TrackedData<ItemStack> ac$item() {
        throw new AssertionError();
    }
}
