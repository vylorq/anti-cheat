package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Redstone;
import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.block.BlockState;
import net.minecraft.block.DispenserBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dispensers can't place lava, water or fire across a claim border; per-chunk dispenser spam is limited. */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    @Inject(method = "dispense", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$dispense(CallbackInfo ci, @Local(argsOnly = true) ServerWorld world, @Local(argsOnly = true) BlockState state,
                             @Local(argsOnly = true) BlockPos pos) {
        if (!Ac.running()) {
            return;
        }
        if (!Redstone.onDispense(world, pos)) {
            ci.cancel();
            return;
        }
        BlockPos target = pos.offset(state.get(DispenserBlock.FACING));
        if (!WorldGuard.crossAllowed(world, pos, target)) {
            ci.cancel();
        }
    }
}
