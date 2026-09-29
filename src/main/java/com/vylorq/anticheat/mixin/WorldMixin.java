package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Withers and other mobs can't break blocks in claims or the lobby. */
@Mixin(World.class)
public abstract class WorldMixin {
    @Inject(method = "breakBlock(Lnet/minecraft/util/math/BlockPos;ZLnet/minecraft/entity/Entity;I)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$breakBlock(BlockPos pos, boolean drop, Entity breaker, int depth, CallbackInfoReturnable<Boolean> cir) {
        if (!WorldGuard.mobMayBreak((World) (Object) this, pos, breaker)) {
            cir.setReturnValue(false);
        }
    }
}
