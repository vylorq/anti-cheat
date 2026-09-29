package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.claims.ClaimAction;
import com.vylorq.anticheat.feature.Claims;
import com.vylorq.anticheat.feature.WorldGuard;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No crop trampling in the lobby or in claims (for non-members and mobs). */
@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {
    @Inject(method = "onLandedUpon", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac$trample(CallbackInfo ci, @Local(argsOnly = true) World world, @Local(argsOnly = true) BlockPos pos,
                            @Local(argsOnly = true) Entity entity) {
        if (!Ac.running() || world.isClient()) {
            return;
        }
        if (entity instanceof ServerPlayerEntity p) {
            if (com.vylorq.anticheat.feature.LobbyFeature.in(world, pos) || !Claims.can(p, Claims.at(world, pos), ClaimAction.CROP)) {
                ci.cancel();
            }
        } else if (WorldGuard.claimedOrLobby(world, pos)) {
            ci.cancel();
        }
    }
}
