package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.AdminPins;
import com.vylorq.anticheat.core.staff.StaffState;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.UUID;

/** Freeze, vanish, spectate, teleport, staff chat, admin PIN (sections 14, 15). */
public final class StaffTools {
    private StaffTools() {
    }

    // ---- Vanish ----

    public static boolean isVanished(UUID id) {
        return Ac.get() != null && Ac.get().staff.isVanished(id);
    }

    /** Who may see a vanished player: active staff. */
    public static boolean canSee(ServerPlayerEntity viewer, UUID vanished) {
        return viewer.getUuid().equals(vanished) || Perms.isActiveStaff(viewer);
    }

    public static void setVanish(ServerPlayerEntity p, boolean on) {
        Ac.get().staff.setVanished(p.getUuid(), on);
        Ac.markDirty("staff");
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            if (o == p || canSee(o, p.getUuid())) {
                continue;
            }
            if (on) {
                o.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(p.getUuid())));
            } else {
                o.networkHandler.sendPacket(PlayerListS2CPacket.entryFromPlayer(List.of(p)));
            }
        }
        Msg.actionBar(p, Msg.tr(on ? "vanish.on" : "vanish.off"));
    }

    /** Hides vanished staff from a player who just joined. */
    private static void hideVanishedFrom(ServerPlayerEntity joiner) {
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            if (o != joiner && isVanished(o.getUuid()) && !canSee(joiner, o.getUuid())) {
                joiner.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(o.getUuid())));
            }
        }
    }

    /** Opens a container without the lid animation or sound (vanished admins). @return true if handled. */
    public static boolean openSilently(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState state) {
        if (!isVanished(p.getUuid()) || !Perms.isActiveStaff(p)) {
            return false;
        }
        Inventory inv;
        if (state.getBlock() instanceof ChestBlock chest) {
            inv = ChestBlock.getInventory(chest, state, w, pos, true);
        } else {
            BlockEntity be = w.getBlockEntity(pos);
            inv = be instanceof Inventory i && (i.size() == 27 || i.size() == 54) ? i : null;
        }
        if (inv == null) {
            return false;
        }
        Inventory silent = new SilentInventory(inv);
        int rows = inv.size() / 9;
        ScreenHandlerType<?> type = rows == 6 ? ScreenHandlerType.GENERIC_9X6 : ScreenHandlerType.GENERIC_9X3;
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, pi, pl) ->
                new GenericContainerScreenHandler(type, syncId, pi, silent, rows), Text.literal(Msg.tr("vanish.silent-chest"))));
        Staff.log(p, "silent-open", null, null, Mc.worldId(w) + " " + pos.toShortString());
        return true;
    }

    /** Delegates everything except open/close notifications (which animate the lid). */
    static final class SilentInventory implements Inventory {
        private final Inventory d;

        SilentInventory(Inventory d) {
            this.d = d;
        }

        @Override
        public int size() {
            return d.size();
        }

        @Override
        public boolean isEmpty() {
            return d.isEmpty();
        }

        @Override
        public ItemStack getStack(int slot) {
            return d.getStack(slot);
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            return d.removeStack(slot, amount);
        }

        @Override
        public ItemStack removeStack(int slot) {
            return d.removeStack(slot);
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            d.setStack(slot, stack);
        }

        @Override
        public void markDirty() {
            d.markDirty();
        }

        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            return true;
        }

        @Override
        public void clear() {
            d.clear();
        }
    }

    // ---- Freeze ----

    public static void setFrozen(ServerPlayerEntity admin, ServerPlayerEntity target, boolean on) {
        Ac.get().staff.setFrozen(target.getUuid(), on);
        Ac.markDirty("staff");
        Staff.log(admin, on ? "freeze" : "unfreeze", target.getUuid(), target.getGameProfile().name(), "");
        if (on) {
            Trades.cancelFor(target, com.vylorq.anticheat.core.trade.SecureTrade.CancelReason.FROZEN);
            Mc.title(target, Msg.tr("freeze.title"), Msg.tr("freeze.subtitle"), 5, 100, 20);
            Msg.send(target, "freeze.message");
        } else {
            Msg.send(target, "freeze.released");
        }
    }

    public static boolean isFrozen(ServerPlayerEntity p) {
        return Ac.get().staff.isFrozen(p.getUuid());
    }

    /** Commands a frozen player may still use (messaging staff). */
    public static boolean frozenCommandAllowed(String root) {
        return switch (root) {
            case "msg", "tell", "w", "r", "reply", "login" -> true;
            default -> false;
        };
    }

    // ---- Spectate ----

    public static void spectate(ServerPlayerEntity admin, ServerPlayerEntity target) {
        if (admin == target) {
            return;
        }
        StaffState.SpectateReturn ret = new StaffState.SpectateReturn();
        ret.world = Mc.worldId(admin.getEntityWorld());
        ret.pos = Mc.vec(admin.getEntityPos());
        ret.yaw = admin.getYaw();
        ret.pitch = admin.getPitch();
        ret.gameMode = admin.interactionManager.getGameMode().asString();
        ret.target = target.getUuid();
        ret.wasVanished = isVanished(admin.getUuid());
        Ac.get().staff.startSpectate(admin.getUuid(), ret);
        Ac.saveNow("staff");
        if (!isVanished(admin.getUuid())) {
            setVanish(admin, true);
        }
        admin.changeGameMode(GameMode.SPECTATOR);
        Mc.teleport(admin, (ServerWorld) target.getEntityWorld(), target.getX(), target.getY(), target.getZ(), target.getYaw(), target.getPitch());
        admin.setCameraEntity(target);
        Staff.log(admin, "spectate", target.getUuid(), target.getGameProfile().name(), "");
        Msg.send(admin, "spectate.started", target.getGameProfile().name());
    }

    public static boolean leaveSpectate(ServerPlayerEntity admin) {
        StaffState.SpectateReturn ret = Ac.get().staff.endSpectate(admin.getUuid());
        if (ret == null) {
            return false;
        }
        Ac.saveNow("staff");
        admin.setCameraEntity(admin);
        admin.changeGameMode(GameMode.byId(ret.gameMode, GameMode.SURVIVAL));
        ServerWorld w = Mc.world(Ac.server(), ret.world);
        if (w != null) {
            Mc.teleport(admin, w, ret.pos.x(), ret.pos.y(), ret.pos.z(), ret.yaw, ret.pitch);
        }
        if (!ret.wasVanished) {
            setVanish(admin, false);
        }
        Staff.log(admin, "spectate-leave", null, null, "");
        Msg.send(admin, "spectate.left");
        return true;
    }

    /** Teleport, visible or invisible (arriving vanished). */
    public static void teleportTo(ServerPlayerEntity admin, ServerWorld w, Vec3 pos, boolean invisible, String what) {
        if (invisible && !isVanished(admin.getUuid())) {
            setVanish(admin, true);
        }
        Mc.teleport(admin, w, pos.x(), pos.y(), pos.z(), admin.getYaw(), admin.getPitch());
        Staff.log(admin, invisible ? "teleport-invisible" : "teleport", null, what, Mc.worldId(w) + " " + pos.formatExact());
    }

    // ---- Staff chat ----

    public static void staffChat(ServerPlayerEntity from, String message) {
        String name = from == null ? "Console" : from.getGameProfile().name();
        Text t = Text.literal(Msg.tr("staffchat.format", name, message));
        for (ServerPlayerEntity p : Staff.online()) {
            p.sendMessage(t);
        }
        Ac.LOG.info("[StaffChat] {}: {}", name, message);
        Ac.get().logs.chat(System.currentTimeMillis(), from == null ? null : from.getUuid(), name, "staff", message);
    }

    // ---- PIN ----

    public static void login(ServerPlayerEntity p, String pin) {
        Ac ac = Ac.get();
        AdminPins.Result r = ac.pins.login(p.getUuid(), pin);
        Ac.markDirty("pins");
        switch (r) {
            case OK -> {
                Msg.send(p, "staff.pin.ok");
                Staff.log(p, "pin-login", null, null, "");
                Joins.notifyReviews(p);
            }
            case WRONG -> Msg.send(p, "staff.pin.wrong", Ac.config().staff.maxPinAttempts - ac.pins.failedAttempts(p.getUuid()));
            case LOCKED -> {
                Msg.send(p, "staff.pin.locked", Ac.config().staff.pinLockMinutes);
                Staff.broadcastOwner(Msg.prefixed(Msg.tr("staff.pin.owner-alert", p.getGameProfile().name(), Mc.worldId(p.getWorld()))));
                Staff.log(p, "pin-locked", null, null, "too many wrong attempts");
            }
            case NO_PIN_SET -> Msg.send(p, "staff.pin.set-first");
            default -> Msg.send(p, "staff.pin.wrong", 0);
        }
    }

    public static void onJoin(ServerPlayerEntity p) {
        hideVanishedFrom(p);
        if (isVanished(p.getUuid())) {
            // Stay hidden after relogging.
            setVanish(p, true);
        }
        // Server restarted while spectating: restore their position and gamemode.
        if (Ac.get().staff.spectating(p.getUuid()) != null) {
            leaveSpectate(p);
        }
    }
}
