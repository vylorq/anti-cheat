package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.items.IllegalItems;
import com.vylorq.anticheat.core.trader.Enchants;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

/** Illegal item detection (section 9): impossible items are removed, logged and the owner flagged. */
public final class Illegal {
    private Illegal() {
    }

    public static String check(ItemStack s) {
        if (s.isEmpty()) {
            return null;
        }
        return IllegalItems.check(ItemConv.info(s), Ac.config().illegalItems.bannedItems, Enchants.MAX_LEVELS);
    }

    /** Logs, alerts and flags. The caller removes the item. */
    public static void handle(ServerPlayerEntity p, ItemStack s, String reason) {
        String desc = ItemConv.info(s).describe();
        Ac.get().logs.activity(System.currentTimeMillis(), p.getUuid(), "illegal-item", reason + ": " + desc);
        Staff.log("System", null, "illegal-item", p.getUuid(), p.getGameProfile().getName(), reason + ": " + desc);
        PlayerSessionFlags.flag(p, CheckType.ILLEGAL_ITEM, 1.0, reason);
        Staff.broadcast(Msg.prefixed(Msg.tr("illegal.alert", p.getGameProfile().getName(), desc, reason)));
    }

    /** Scans an inventory, removing illegal items. Creative players are skipped. @return number removed */
    public static int scan(ServerPlayerEntity p, Inventory inv) {
        var cfg = Ac.config().illegalItems;
        if (!cfg.enabled || p.isCreative() || p.isSpectator()) {
            return 0;
        }
        int removed = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            String r = check(s);
            if (r != null) {
                handle(p, s, r);
                if (cfg.removeItems) {
                    inv.setStack(i, ItemStack.EMPTY);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            inv.markDirty();
        }
        return removed;
    }

    public static void scanPlayer(ServerPlayerEntity p) {
        scan(p, p.getInventory());
        scan(p, p.getEnderChestInventory());
    }
}
