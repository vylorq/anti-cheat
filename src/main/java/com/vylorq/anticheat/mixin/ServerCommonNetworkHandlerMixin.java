package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.feature.Combat;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches what the server sends each player: knockback/explosion velocity and teleports (from anything, including
 * other mods) feed the movement safeguards (section 7.2).
 */
@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {
    /** Answers to our pings (auto-totem timing). Recorded the moment they reach the network thread. */
    @Inject(method = "onPong", at = @At("HEAD"))
    private void ac$onPong(net.minecraft.network.packet.c2s.common.CommonPongC2SPacket packet, CallbackInfo ci) {
        long now = System.nanoTime();
        if (Ac.running() && (Object) this instanceof ServerPlayNetworkHandler handler && handler.player != null) {
            com.vylorq.anticheat.feature.AutoTotem.pong(handler.player, packet.getParameter(), now);
        }
    }

    /** Answers to the Vigil pack: a required pack that isn't loaded disconnects the player. */
    @Inject(method = "onResourcePackStatus", at = @At("HEAD"), require = 0)
    private void ac$onPackStatus(net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && (Object) this instanceof ServerPlayNetworkHandler handler && handler.player != null) {
            com.vylorq.anticheat.feature.OwnerPowers.packStatus(handler.player, packet.id(), packet.status().name());
        }
    }

    @Inject(method = "send", at = @At("HEAD"))
    private void ac$onSend(Packet<?> packet, io.netty.channel.ChannelFutureListener callbacks, CallbackInfo ci) {
        if (!Ac.running() || !((Object) this instanceof ServerPlayNetworkHandler handler) || handler.player == null) {
            return;
        }
        if (packet instanceof EntityVelocityUpdateS2CPacket v && v.getEntityId() == handler.player.getId()) {
            Combat.onVelocity(handler.player, v.getVelocity().length());
            Combat.knockback(handler.player, v.getVelocity());
        } else if (packet instanceof ExplosionS2CPacket e) {
            e.playerKnockback().ifPresent(k -> Combat.onVelocity(handler.player, k.length()));
        } else if (packet instanceof net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket chunk) {
            com.vylorq.anticheat.feature.AntiEsp.chunkSent(handler.player, chunk.getChunkX(), chunk.getChunkZ());
        } else if (packet instanceof net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket) {
            com.vylorq.anticheat.feature.AntiEsp.worldChanged(handler.player);
        } else if (packet instanceof net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket open) {
            com.vylorq.anticheat.feature.InventoryChecks.opened(handler.player, open.getSyncId());
        } else if (packet instanceof PlayerPositionLookS2CPacket) {
            Combat.onTeleportPacket(handler.player);
        }
    }

    /** Bedrock chat can't be clicked: chat buttons also show their command, so Bedrock players can type it. */
    /** Owner radar: other players glow on the owner's screen only. */
    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> ac$ownerRadar(Packet<?> packet) {
        if (Ac.running() && (Object) this instanceof ServerPlayNetworkHandler handler && handler.player != null
                && (packet instanceof net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket
                || packet instanceof net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket)) {
            return com.vylorq.anticheat.feature.ModelMobs.forViewer(handler.player,
                    com.vylorq.anticheat.feature.OwnerPowers.radarRewrite(handler.player, packet));
        }
        return packet;
    }

    @ModifyVariable(method = "send", at = @At("HEAD"), argsOnly = true)
    private Packet<?> ac$bedrockButtons(Packet<?> packet) {
        if (!Ac.running() || !(packet instanceof net.minecraft.network.packet.s2c.play.GameMessageS2CPacket msg) || msg.overlay()
                || !((Object) this instanceof ServerPlayNetworkHandler handler) || handler.player == null
                || !com.vylorq.anticheat.ui.Viewer.isBedrock(handler.player)) {
            return packet;
        }
        if (!com.vylorq.anticheat.util.BedrockText.hasButtons(msg.content())) {
            return packet;
        }
        return new net.minecraft.network.packet.s2c.play.GameMessageS2CPacket(com.vylorq.anticheat.util.BedrockText.withCommands(msg.content()), false);
    }
}
