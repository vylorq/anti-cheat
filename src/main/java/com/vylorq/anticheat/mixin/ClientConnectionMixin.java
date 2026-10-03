package com.vylorq.anticheat.mixin;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.packets.PacketRate;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet flood protection: a player sending thousands of packets a second is cut off before the server chokes. */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Unique
    private final PacketRate ac$rate = new PacketRate();
    @Unique
    private boolean ac$flooded;

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void ac$count(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (ac$flooded) {
            ci.cancel();
            return;
        }
        if (!Ac.running()) {
            return;
        }
        ClientConnection self = (ClientConnection) (Object) this;
        if (!(self.getPacketListener() instanceof ServerPlayNetworkHandler handler) || handler.player == null) {
            return;
        }
        int limit = Ac.config().detection.maxPacketsPerSecond;
        if (ac$rate.add(System.currentTimeMillis(), limit)) {
            ac$flooded = true;
            ci.cancel();
            com.vylorq.anticheat.feature.PacketChecks.flood(handler.player, ac$rate.current(), self);
        }
    }
}
