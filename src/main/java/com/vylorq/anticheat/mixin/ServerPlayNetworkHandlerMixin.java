package com.vylorq.anticheat.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.feature.BuilderMode;
import com.vylorq.anticheat.feature.Combat;
import com.vylorq.anticheat.feature.Movement;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Movement, swings, inventory clicks and vehicle moves (sections 7, 8, 11). */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Shadow
    public ServerPlayerEntity player;

    @Shadow
    private Vec3d requestedTeleportPos;

    @Inject(method = "onPlayerMove", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$onMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (!Ac.running() || this.requestedTeleportPos != null) {
            // Vanilla ignores moves until the client confirms a teleport; so do we.
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        float yaw = packet.getYaw(player.getYaw());
        float pitch = packet.getPitch(player.getPitch());
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return;
        }
        if (Movement.onMove(player, x, y, z, yaw, pitch, packet.isOnGround(), packet.changesPosition(), packet.changesLook())) {
            ci.cancel();
        }
    }

    @Inject(method = "onVehicleMove", at = @At("TAIL"))
    private void ac$afterVehicleMove(VehicleMoveC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && player.getEntityWorld().getServer() != null && player.getEntityWorld().getServer().isOnThread()) {
            Movement.afterVehicleMove(player);
        }
    }

    @Inject(method = "onHandSwing", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$onSwing(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (Ac.running()) {
            Combat.onSwing(player);
        }
    }

    @Inject(method = "onClickSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (Ac.running()) {
            Ac.get().evidence.record(player.getUuid(), EvidenceEvent.Type.INVENTORY, player.getX(), player.getY(), player.getZ(),
                    player.getYaw(), player.getPitch(), "inventory click slot " + packet.slot() + " " + packet.actionType());
        }
    }

    /** Builders only get building blocks from the creative menu, and can't throw items out of it. */
    @Inject(method = "onCreativeInventoryAction", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$builderCreative(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && BuilderMode.is(player) && (packet.slot() < 0 || !BuilderMode.allowed(packet.stack()))) {
            ci.cancel();
            BuilderMode.denied(player);
            player.playerScreenHandler.syncState();
        }
    }

    /** Vanished admins leave silently (only staff are told). */
    @WrapOperation(method = {"onDisconnected", "cleanUp"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void ac$silentLeave(PlayerManager manager, Text message, boolean overlay, Operation<Void> original) {
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
