package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Joins;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.SocketAddress;

/** Ban/maintenance/bot checks at login, and silent joins for vanished staff. */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @ModifyReturnValue(method = "checkCanJoin", at = @At("RETURN"))
    private Text ac$checkCanJoin(Text original, @Local(argsOnly = true) SocketAddress address,
                                 @Local(argsOnly = true) net.minecraft.server.PlayerConfigEntry profile) {
        if (original != null || !Ac.running()) {
            return original;
        }
        return Joins.checkLogin(address, profile);
    }

    @WrapOperation(method = "onPlayerConnect", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void ac$silentJoin(PlayerManager manager, Text message, boolean overlay, Operation<Void> original,
                               @Local(argsOnly = true) ServerPlayerEntity player) {
        if (Ac.running() && StaffTools.isVanished(player.getUuid())) {
            for (ServerPlayerEntity p : Staff.online()) {
                if (p != player) {
                    p.sendMessage(Text.literal("§8[vanished] ").append(message));
                }
            }
            return;
        }
        original.call(manager, message, overlay);
    }
}
