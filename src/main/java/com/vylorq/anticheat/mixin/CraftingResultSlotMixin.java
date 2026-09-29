package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.CraftingResultSlot;
import net.minecraft.screen.slot.FurnaceOutputSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crafting and smelting count as obtaining an item naturally (trader stock, section 23.5). */
@Mixin({CraftingResultSlot.class, FurnaceOutputSlot.class})
public abstract class CraftingResultSlotMixin {
    @Inject(method = "onTakeItem", at = @At("HEAD"), require = 0)
    private void ac$take(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        if (Ac.running() && player instanceof ServerPlayerEntity p && !p.isCreative() && !stack.isEmpty()) {
            String id = Mc.itemId(stack.getItem());
            if (!Ac.get().economy.isObtained(id)) {
                Ac.get().economy.markObtained(id);
                Ac.markDirty("economy");
            }
        }
    }
}
