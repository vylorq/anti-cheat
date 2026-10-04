package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.gui.MenuHandler;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chest stealer (taking items before the window could even be seen) and auto-armor (three pieces put on within a
 * tenth of a second). Java only: Bedrock's inventory goes through Geyser, which changes the timing.
 */
public final class InventoryChecks {
    private InventoryChecks() {
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(0x56480000);
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** A container window is being sent: start timing, with a ping right after it. */
    public static void opened(ServerPlayerEntity p, int syncId) {
        PlayerSession s = Ac.running() ? Ac.sessionOrNull(p.getUuid()) : null;
        var server = p.getEntityWorld().getServer();
        if (s == null || s.bedrock || server == null) {
            return;
        }
        s.containerSyncId = syncId;
        int id = NEXT_ID.incrementAndGet();
        s.containerPingId = id;
        // Sent after the window (this runs while the window packet is being sent).
        server.execute(() -> {
            s.container.onOpen(System.nanoTime(), id);
            p.networkHandler.sendPacket(new CommonPingS2CPacket(id));
        });
    }

    public static void pong(PlayerSession s, int id, long at) {
        s.container.onPong(at, id);
    }

    private static int armorCount(ServerPlayerEntity p) {
        int n = 0;
        for (EquipmentSlot e : ARMOR) {
            if (!p.getEquippedStack(e).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** Before a click is handled. */
    public static void beforeClick(ServerPlayerEntity p, ClickSlotC2SPacket packet) {
        PlayerSession s = Ac.running() ? Ac.sessionOrNull(p.getUuid()) : null;
        if (s == null) {
            return;
        }
        s.armorBefore = armorCount(p);
        s.clickTakeSlot = -1;
        var h = p.currentScreenHandler;
        boolean container = (h instanceof GenericContainerScreenHandler || h instanceof ShulkerBoxScreenHandler) && !(h instanceof MenuHandler);
        int slot = packet.slot();
        SlotActionType a = packet.actionType();
        if (container && h.syncId == s.containerSyncId && slot >= 0 && slot < h.slots.size() - 36
                && (a == SlotActionType.QUICK_MOVE || a == SlotActionType.PICKUP || a == SlotActionType.SWAP)
                && h.getSlot(slot).hasStack()) {
            s.clickTakeSlot = slot;
            s.clickTakeCount = h.getSlot(slot).getStack().getCount();
        }
    }

    /** After the click: was something taken out, or armor put on? */
    public static void afterClick(ServerPlayerEntity p) {
        PlayerSession s = Ac.running() ? Ac.sessionOrNull(p.getUuid()) : null;
        if (s == null || s.bedrock || p.isCreative() || p.isSpectator()) {
            return;
        }
        long at = s.invPacketArrival;
        var h = p.currentScreenHandler;
        if (s.clickTakeSlot >= 0 && s.clickTakeSlot < h.slots.size() && h.getSlot(s.clickTakeSlot).getStack().getCount() < s.clickTakeCount) {
            int fast = s.container.onFirstTake(at, p.networkHandler.getLatency());
            if (fast >= 2) {
                PlayerSessionFlags.flag(p, CheckType.INVENTORY, 1.5,
                        "took items out of a chest before the window could be seen (" + fast + " of the last 5 chests)");
            }
        }
        s.clickTakeSlot = -1;
        int now = armorCount(p);
        for (int i = s.armorBefore; i < now; i++) {
            if (s.armor.onEquip(at)) {
                PlayerSessionFlags.flag(p, CheckType.INVENTORY, 1.5, "put on 3 armor pieces within a tenth of a second (twice)");
            }
        }
    }
}
