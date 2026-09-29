package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.feature.Redstone;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.RedstoneTorchBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects redstone clocks that switch too fast for too long and stops them (section 18). */
@Mixin({AbstractRedstoneGateBlock.class, ObserverBlock.class, RedstoneTorchBlock.class})
public abstract class RedstoneClockMixin {
    @Inject(method = "scheduledTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$tick(CallbackInfo ci, @Local(argsOnly = true) ServerWorld world, @Local(argsOnly = true) BlockPos pos) {
        if (!Redstone.onToggle(world, pos)) {
            ci.cancel();
        }
    }
}
