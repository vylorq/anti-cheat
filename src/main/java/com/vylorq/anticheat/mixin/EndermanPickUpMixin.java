package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.entity.mob.EndermanEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Endermen don't take blocks from claims or the lobby. */
@Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PickUpBlockGoal")
public abstract class EndermanPickUpMixin {
    @Shadow
    @Final
    private EndermanEntity enderman;

    @Inject(method = "canStart", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$canStart(CallbackInfoReturnable<Boolean> cir) {
        if (WorldGuard.claimedOrLobby(enderman.getWorld(), enderman.getBlockPos())) {
            cir.setReturnValue(false);
        }
    }
}
