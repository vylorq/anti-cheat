package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.Gems;
import net.minecraft.item.FuelRegistry;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Topaz is furnace fuel: four times as long as coal. */
@Mixin(FuelRegistry.class)
public abstract class FuelRegistryMixin {
    @ModifyReturnValue(method = "isFuel", at = @At("RETURN"))
    private boolean ac$topazFuel(boolean original, @Local(argsOnly = true) ItemStack stack) {
        return original || Gems.isTopaz(stack);
    }

    @ModifyReturnValue(method = "getFuelTicks", at = @At("RETURN"))
    private int ac$topazTicks(int original, @Local(argsOnly = true) ItemStack stack) {
        return Gems.isTopaz(stack) ? 6400 : original;
    }
}
