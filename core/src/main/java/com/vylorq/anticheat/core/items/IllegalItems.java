package com.vylorq.anticheat.core.items;

import com.vylorq.anticheat.core.item.ItemInfo;

import java.util.Collection;
import java.util.Map;

/**
 * Impossible item detection (section 9): overstacked items, enchantments above their max level or on items
 * that can't have them, and creative-only blocks.
 */
public final class IllegalItems {
    private IllegalItems() {
    }

    /**
     * @param maxLevels   enchantment id -> vanilla max level
     * @param banned      creative-only/illegal item ids
     * @return reason, or null when the item is fine
     */
    public static String check(ItemInfo item, Collection<String> banned, Map<String, Integer> maxLevels) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        if (banned.contains(item.id)) {
            return "illegal item " + item.id;
        }
        if (item.count > item.maxCount || item.count > 99) {
            return "overstacked " + item.count + "/" + item.maxCount;
        }
        if (item.maxDamage > 0 && (item.damage < 0 || item.damage > item.maxDamage)) {
            return "invalid durability";
        }
        String e = checkEnchants(item.enchantments, maxLevels);
        if (e != null) {
            return e;
        }
        e = checkEnchants(item.storedEnchantments, maxLevels);
        if (e != null) {
            return e;
        }
        if (item.contents != null) {
            for (ItemInfo inner : item.contents) {
                String r = check(inner, banned, maxLevels);
                if (r != null) {
                    return "inside container: " + r;
                }
            }
        }
        return null;
    }

    private static String checkEnchants(Map<String, Integer> ench, Map<String, Integer> maxLevels) {
        for (Map.Entry<String, Integer> en : ench.entrySet()) {
            Integer max = maxLevels.get(en.getKey());
            if (max != null && en.getValue() > max) {
                return "enchantment " + en.getKey() + " " + en.getValue() + " > " + max;
            }
            if (en.getValue() <= 0) {
                return "invalid enchantment level";
            }
        }
        return null;
    }
}
