package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.BossBarArt;
import com.vylorq.anticheat.feature.Combat;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches what the server sends each player: knockback/explosion velocity and teleports (from anything, including
 * other mods) feed the movement safeguards (section 7.2). Also gives Dragon, Wither and Raid bars their art.
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    @Shadow
    @Final
    protected ClientConnection connection;

    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> ac$bossBarArt(Packet<?> packet) {
        if (!Ac.running() || !((Object) this instanceof ServerPlayNetworkHandler handler) || handler.player == null) {
            return packet;
        }
        return BossBarArt.rewrite(packet, handler.player, BossBarArt.hasPack(connection));
    }

    @Inject(method = "onResourcePackStatus", at = @At("HEAD"))
    private void ac$packStatus(ResourcePackStatusC2SPacket packet, CallbackInfo ci) {
        // ACCEPTED and DOWNLOADED are only steps on the way; the rest are final.
        String s = packet.status().name();
        if (s.equals("SUCCESSFULLY_LOADED")) {
            BossBarArt.packLoaded(connection, true);
        } else if (!s.equals("ACCEPTED") && !s.equals("DOWNLOADED")) {
            BossBarArt.packLoaded(connection, false);
        }
    }

    @Inject(method = "send", at = @At("HEAD"))
    private void ac$onSend(Packet<?> packet, io.netty.channel.ChannelFutureListener callbacks, CallbackInfo ci) {
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
