package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.item.ItemStack;

/**
 * Structure chests hold back only the end-game items (netherite, totems, enchanted golden apples) until someone on the
 * server has got them by playing (the same list the traders use). Everything else is always there.
 */
public final class StructureLoot {
    private StructureLoot() {
    }

    public static void register() {
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            if (!Ac.running() || table.getKey().isEmpty()) {
                return;
            }
            var id = table.getKey().get().getValue();
            if ("vigil".equals(id.getNamespace()) && id.getPath().startsWith("chests/")) {
                drops.removeIf(s -> !allowed(s));
            }
        });
    }

    private static final java.util.Set<net.minecraft.item.Item> GATED = java.util.Set.of(
            net.minecraft.item.Items.NETHERITE_INGOT, net.minecraft.item.Items.NETHERITE_SCRAP,
            net.minecraft.item.Items.TOTEM_OF_UNDYING, net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE);

    /** Whether a chest may give this: anything but the end-game items, which wait until players have got them. */
    public static boolean allowed(ItemStack s) {
        if (s.isEmpty() || SecretItems.idOf(s) != null || !GATED.contains(s.getItem())) {
            return true;
        }
        return Ac.get().economy.isObtained(Mc.itemId(s.getItem()));
    }
}
