package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire in a claim with fire spread off (or in the lobby) goes out instead of spreading. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$fireTick(CallbackInfo ci, @Local(argsOnly = true) ServerWorld world, @Local(argsOnly = true) BlockPos pos) {
        if (!WorldGuard.fireAllowed(world, pos)) {
            world.removeBlock(pos, false);
            ci.cancel();
        }
    }
}
