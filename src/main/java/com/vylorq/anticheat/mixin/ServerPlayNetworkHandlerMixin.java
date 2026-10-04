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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
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

    /** True on the network thread (the first call of a packet handler, before it is passed to the server thread). */
    @org.spongepowered.asm.mixin.Unique
    private boolean ac$offThread() {
        var server = player == null ? null : player.getEntityWorld().getServer();
        return server != null && !server.isOnThread();
    }

    @Inject(method = "onPlayerMove", at = @At("HEAD"))
    private void ac$moveArrived(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && ac$offThread()) {
            var s = Ac.sessionOrNull(player.getUuid());
            if (s != null) {
                com.vylorq.anticheat.PlayerSession.arrived(s.moveArrivals);
            }
        }
    }

    @Inject(method = "onHandSwing", at = @At("HEAD"))
    private void ac$swingArrived(HandSwingC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && ac$offThread()) {
            var s = Ac.sessionOrNull(player.getUuid());
            if (s != null) {
                com.vylorq.anticheat.PlayerSession.arrived(s.swingArrivals);
            }
        }
    }

    // ---- Auto-totem: every packet that can move an item into the offhand ----

    @org.spongepowered.asm.mixin.Unique
    private void ac$invArrived() {
        if (Ac.running() && ac$offThread()) {
            var s = Ac.sessionOrNull(player.getUuid());
            if (s != null) {
                com.vylorq.anticheat.PlayerSession.arrived(s.invArrivals);
            }
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void ac$invBefore() {
        if (Ac.running()) {
            var s = Ac.sessionOrNull(player.getUuid());
            long arrived = s == null ? System.nanoTime() : com.vylorq.anticheat.PlayerSession.arrival(s.invArrivals);
            com.vylorq.anticheat.feature.AutoTotem.before(player, arrived);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private void ac$invAfter() {
        if (Ac.running() && !ac$offThread()) {
            com.vylorq.anticheat.feature.AutoTotem.after(player);
        }
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"))
    private void ac$clickArrived(ClickSlotC2SPacket packet, CallbackInfo ci) {
        ac$invArrived();
    }

    @Inject(method = "onClickSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$clickBefore(ClickSlotC2SPacket packet, CallbackInfo ci) {
        ac$invBefore();
        if (Ac.running()) {
            com.vylorq.anticheat.feature.InventoryChecks.beforeClick(player, packet);
        }
    }

    @Inject(method = "onClickSlot", at = @At("RETURN"))
    private void ac$clickAfter(ClickSlotC2SPacket packet, CallbackInfo ci) {
        ac$invAfter();
        if (Ac.running() && !ac$offThread()) {
            com.vylorq.anticheat.feature.InventoryChecks.afterClick(player);
        }
    }

    @Inject(method = "onPlayerAction", at = @At("HEAD"))
    private void ac$actionArrived(PlayerActionC2SPacket packet, CallbackInfo ci) {
        ac$invArrived();
    }

    @Inject(method = "onPlayerAction", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$actionBefore(PlayerActionC2SPacket packet, CallbackInfo ci) {
        ac$invBefore();
    }

    @Inject(method = "onPlayerAction", at = @At("RETURN"))
    private void ac$actionAfter(PlayerActionC2SPacket packet, CallbackInfo ci) {
        ac$invAfter();
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void ac$slotArrived(net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        ac$invArrived();
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$slotBefore(net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        ac$invBefore();
    }

    @Inject(method = "onUpdateSelectedSlot", at = @At("RETURN"))
    private void ac$slotAfter(net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        ac$invAfter();
    }

    /** A block the player tried to place didn't appear: their client briefly shows a ghost block. */
    @Inject(method = "onPlayerInteractBlock", at = @At("TAIL"))
    private void ac$placeRefused(net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (!Ac.running() || ac$offThread()) {
            return;
        }
        var hit = packet.getBlockHitResult();
        var w = player.getEntityWorld();
        var held = player.getStackInHand(packet.getHand());
        if (!(held.getItem() instanceof net.minecraft.item.BlockItem) && !held.isEmpty()) {
            return;
        }
        var pos = hit.getBlockPos();
        var place = w.getBlockState(pos).isReplaceable() ? pos : pos.offset(hit.getSide());
        if (w.getBlockState(place).isReplaceable()) {
            com.vylorq.anticheat.feature.Movement.ghostBlock(player);
        }
    }

    /** Every click on a block: is its geometry possible? */
    @Inject(method = "onPlayerInteractBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$blockClick(net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (Ac.running()) {
            com.vylorq.anticheat.feature.PacketChecks.blockClick(player, packet.getBlockHitResult());
        }
    }

    /** A block the player finished breaking is still there: the client thinks it's gone. */
    @Inject(method = "onPlayerAction", at = @At("TAIL"))
    private void ac$breakRefused(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (!Ac.running() || ac$offThread()) {
            return;
        }
        var a = packet.getAction();
        if (a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
            var s = Ac.sessionOrNull(player.getUuid());
            boolean stillThere = !player.getEntityWorld().getBlockState(packet.getPos()).isAir();
            // Refused by a protection (claims, lobby...) isn't fast breaking: those mark the ghost block themselves.
            boolean refusedByProtection = s != null && s.ticksSinceGhostBlock == 0;
            if (!refusedByProtection) {
                com.vylorq.anticheat.feature.PacketChecks.finishedBreaking(player, stillThere);
            }
        }
        if ((a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK || a == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK)
                && !player.getEntityWorld().getBlockState(packet.getPos()).isAir()
                && (a == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK || player.isCreative())) {
            com.vylorq.anticheat.feature.Movement.ghostBlock(player);
        }
    }

    @Inject(method = "onPlayerMove", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$onMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        var session = Ac.running() ? Ac.sessionOrNull(player.getUuid()) : null;
        long arrived = session == null ? System.nanoTime() : com.vylorq.anticheat.PlayerSession.arrival(session.moveArrivals);
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
        if (Movement.onMove(player, x, y, z, yaw, pitch, packet.isOnGround(), packet.changesPosition(), packet.changesLook(), arrived)) {
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
            var s = Ac.sessionOrNull(player.getUuid());
            long arrived = s == null ? System.nanoTime() : com.vylorq.anticheat.PlayerSession.arrival(s.swingArrivals);
            Combat.onSwing(player, System.currentTimeMillis() - (System.nanoTime() - arrived) / 1_000_000L);
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

    /** Builders can't drop anything (Q, Ctrl+Q). */
    @Inject(method = "onPlayerAction", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$builderDrop(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && (packet.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM
                || packet.getAction() == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS) && !player.getMainHandStack().isEmpty()) {
            var held = player.getMainHandStack();
            com.vylorq.anticheat.feature.PacketChecks.watchedItem(player, "dropped",
                    packet.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM ? held.copyWithCount(1) : held.copy());
        }
        if (Ac.running() && BuilderMode.is(player) && (packet.getAction() == PlayerActionC2SPacket.Action.DROP_ITEM
                || packet.getAction() == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS)) {
            ci.cancel();
            BuilderMode.denied(player);
            player.playerScreenHandler.syncState();
        }
    }

    /** ...or throw items out of their inventory (dropping outside the window, or Q on a slot). */
    @Inject(method = "onClickSlot", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$builderThrow(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && BuilderMode.is(player)
                && (packet.actionType() == SlotActionType.THROW || packet.slot() == ScreenHandler.EMPTY_SPACE_SLOT_INDEX)) {
            ci.cancel();
            BuilderMode.denied(player);
            player.currentScreenHandler.syncState();
        }
    }

    /** Builders only get building blocks from the creative menu, and can't throw items out of it. */
    @Inject(method = "onCreativeInventoryAction", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",
            shift = At.Shift.AFTER))
    private void ac$builderCreative(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (Ac.running() && BuilderMode.is(player)) {
            if (packet.slot() < 0 || !BuilderMode.allowed(packet.stack())) {
                ci.cancel();
                BuilderMode.denied(player, (packet.slot() < 0 ? "drop " : "take ") + com.vylorq.anticheat.util.Mc.itemId(packet.stack().getItem()));
                player.playerScreenHandler.syncState();
            } else if (!packet.stack().isEmpty()) {
                com.vylorq.anticheat.feature.BuilderLog.event(player, "TAKE", com.vylorq.anticheat.util.Mc.itemId(packet.stack().getItem())
                        + " x" + packet.stack().getCount() + " slot " + packet.slot());
            }
        }
    }

    /** Vanished admins leave silently (only staff are told). */
    @WrapOperation(method = {"onDisconnected", "cleanUp"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V"))
    private void ac$silentLeave(PlayerManager manager, Text message, boolean overlay, Operation<Void> original) {
        if (Ac.running() && (StaffTools.isVanished(player.getUuid()) || com.vylorq.anticheat.feature.OwnerPowers.silentJoin(player))) {
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
