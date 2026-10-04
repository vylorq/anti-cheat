package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.StaffTools;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanished admins are never sent to players who can't see them; anti-ESP keeps hidden players back too. */
@Mixin(targets = "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker")
public abstract class EntityTrackerMixin implements com.vylorq.anticheat.feature.AntiEsp.Tracker {
    @Shadow
    @Final
    Entity entity;

    @Shadow
    public abstract void stopTracking(ServerPlayerEntity player);

    @Shadow
    public abstract void updateTrackedStatus(ServerPlayerEntity player);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ac$register(CallbackInfo ci) {
        com.vylorq.anticheat.feature.AntiEsp.register(entity, this);
    }

    @Override
    public void ac$recheck(ServerPlayerEntity viewer) {
        updateTrackedStatus(viewer);
    }

    @Inject(method = "updateTrackedStatus(Lnet/minecraft/server/network/ServerPlayerEntity;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$track(ServerPlayerEntity viewer, CallbackInfo ci) {
        if (Ac.running() && entity instanceof ServerPlayerEntity target && target != viewer
                && ((StaffTools.isVanished(target.getUuid()) && !StaffTools.canSee(viewer, target.getUuid()))
                || com.vylorq.anticheat.feature.AntiEsp.hidden(viewer, target))) {
            stopTracking(viewer);
            ci.cancel();
        }
    }
}
