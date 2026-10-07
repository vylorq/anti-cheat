package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.item.ItemStack;

/**
 * Structure chests only give what someone on the server has already got by playing (the same list the traders use):
 * if nobody has found netherite yet, no chest hands out netherite. Secret items keep their own (very rare) odds.
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

    /** Whether a chest may give this: a secret item, or something players have already got. */
    public static boolean allowed(ItemStack s) {
        if (s.isEmpty() || SecretItems.idOf(s) != null) {
            return true;
        }
        return Ac.get().economy.isObtained(Mc.itemId(s.getItem()));
    }
}
