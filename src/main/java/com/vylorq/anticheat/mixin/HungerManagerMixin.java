package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Arenas;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Arena kits and rules without natural regeneration (UHC). Only natural regen is blocked; potions still heal. */
@Mixin(HungerManager.class)
public abstract class HungerManagerMixin {
    @WrapWithCondition(method = "update", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;heal(F)V"))
    private boolean ac$regenServer(ServerPlayerEntity player, float amount) {
        return !(Ac.running() && Arenas.noNaturalRegen(player));
    }

    @WrapWithCondition(method = "update", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;heal(F)V"))
    private boolean ac$regenPlayer(PlayerEntity player, float amount) {
        return !(Ac.running() && player instanceof ServerPlayerEntity sp && Arenas.noNaturalRegen(sp));
    }
}
