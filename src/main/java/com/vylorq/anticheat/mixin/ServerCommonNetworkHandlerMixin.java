package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Combat;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches what the server sends each player: knockback/explosion velocity and teleports (from anything, including
 * other mods) feed the movement safeguards (section 7.2).
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    @Inject(method = "send", at = @At("HEAD"))
    private void ac$onSend(Packet<?> packet, PacketCallbacks callbacks, CallbackInfo ci) {
        if (!Ac.running() || !((Object) this instanceof ServerPlayNetworkHandler handler) || handler.player == null) {
            return;
        }
        if (packet instanceof EntityVelocityUpdateS2CPacket v && v.getEntityId() == handler.player.getId()) {
            Combat.onVelocity(handler.player, v.getVelocity().length());
        } else if (packet instanceof ExplosionS2CPacket e) {
            e.playerKnockback().ifPresent(k -> Combat.onVelocity(handler.player, k.length()));
        } else if (packet instanceof PlayerPositionLookS2CPacket) {
            Combat.onTeleportPacket(handler.player);
        }
    }
}
