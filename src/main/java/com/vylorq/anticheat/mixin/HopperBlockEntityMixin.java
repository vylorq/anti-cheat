package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.block.entity.Hopper;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hoppers and hopper minecarts can't pull from containers inside a claim they're not part of. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @ModifyReturnValue(method = "getInputInventory", at = @At("RETURN"), require = 0)
    private static Inventory ac$input(Inventory original, @Local(argsOnly = true) World world, @Local(argsOnly = true) Hopper hopper) {
        if (original == null) {
            return null;
        }
        BlockPos hopperPos = BlockPos.ofFloored(hopper.getHopperX(), hopper.getHopperY(), hopper.getHopperZ());
        BlockPos source = hopperPos.up();
        return WorldGuard.crossAllowed(world, hopperPos, source) ? original : null;
    }
}
