package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Redstone;
import net.minecraft.block.Blocks;
import net.minecraft.block.TntBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TNT cannons: limits primed TNT per chunk and (optionally) stops TNT duplicators (section 18). */
@Mixin(TntBlock.class)
public abstract class TntBlockMixin {
    @Inject(method = "primeTnt(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/LivingEntity;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void ac$prime(CallbackInfo ci, @Local(argsOnly = true) World world, @Local(argsOnly = true) BlockPos pos) {
        if (Ac.running() && world instanceof ServerWorld sw && !Redstone.mayPrimeTnt(sw, pos)) {
            ci.cancel();
        }
    }

    @Inject(method = "primeTnt(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/LivingEntity;)V",
            at = @At("TAIL"), require = 0)
    private static void ac$afterPrime(CallbackInfo ci, @Local(argsOnly = true) World world, @Local(argsOnly = true) BlockPos pos) {
        if (Ac.running() && Ac.config().redstone.blockTntDupers && world instanceof ServerWorld sw) {
            BlockPos p = pos.toImmutable();
            // A duplicator keeps the TNT block after priming it; remove it on the next tick.
            sw.getServer().execute(() -> {
                if (sw.getBlockState(p).isOf(Blocks.TNT)) {
                    sw.removeBlock(p, false);
                }
            });
        }
    }
}
