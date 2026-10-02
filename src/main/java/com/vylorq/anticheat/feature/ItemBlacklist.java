package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Items the server doesn't allow (e.g. TNT): taken out of players' inventories automatically. Unlike the illegal
 * items check this isn't cheating, so nobody is flagged; the player is just told.
 */
public final class ItemBlacklist {
    private ItemBlacklist() {
    }

    static List<String> items() {
        return Ac.config().itemBlacklist.items;
    }

    /** Whether an item id ("minecraft:tnt") is on the list (entries may leave out "minecraft:"). */
    public static boolean listed(String id) {
        for (String e : items()) {
            String x = e.trim().toLowerCase(java.util.Locale.ROOT);
            if (x.equals(id) || ("minecraft:" + x).equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static boolean enabled() {
        return Ac.running() && Ac.config().itemBlacklist.enabled && !items().isEmpty();
    }

    /** Removes blacklisted items from the player (and their ender chest if set). @return items removed */
    public static int clean(ServerPlayerEntity p) {
        if (!enabled() || p.isCreative() && Ac.config().itemBlacklist.ignoreCreative
                || Ac.config().itemBlacklist.ignoreStaff && Perms.isActiveStaff(p) || BuilderMode.is(p)) {
            return 0;
        }
        Map<String, Integer> removed = new LinkedHashMap<>();
        clean(p.getInventory(), removed);
        if (Ac.config().itemBlacklist.includeEnderChest) {
            clean(p.getEnderChestInventory(), removed);
        }
        if (p.currentScreenHandler != null && !p.currentScreenHandler.getCursorStack().isEmpty()
                && listed(Registries.ITEM.getId(p.currentScreenHandler.getCursorStack().getItem()).toString())) {
            ItemStack cur = p.currentScreenHandler.getCursorStack();
            removed.merge(cur.getName().getString(), cur.getCount(), Integer::sum);
            p.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
        }
        int total = 0;
        for (Map.Entry<String, Integer> e : removed.entrySet()) {
            total += e.getValue();
            Msg.send(p, "blacklist.removed", e.getValue() + "x " + e.getKey());
        }
        if (total > 0) {
            Staff.log(null, "blacklist-remove", p.getUuid(), p.getGameProfile().name(), removed.toString());
        }
        return total;
    }

    static void clean(Inventory inv, Map<String, Integer> removed) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && listed(Registries.ITEM.getId(s.getItem()).toString())) {
                removed.merge(s.getName().getString(), s.getCount(), Integer::sum);
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
    }
}
