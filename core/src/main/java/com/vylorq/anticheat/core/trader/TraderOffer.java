package com.vylorq.anticheat.core.trader;

import java.util.LinkedHashMap;
import java.util.Map;

/** One thing a trader sells right now. */
public final class TraderOffer {
    public String id;
    public int unit = 1;
    public Map<String, Integer> enchantments = new LinkedHashMap<>();
    public Map<String, Integer> storedEnchantments = new LinkedHashMap<>();
    public String potion;
    public Rarity rarity = Rarity.COMMON;
    public int stock;
    public int maxStock;

    /** Identity used for demand pricing and caps. */
    public String signature() {
        StringBuilder sb = new StringBuilder(id);
        if (potion != null) {
            sb.append('{').append(potion).append('}');
        }
        if (!enchantments.isEmpty()) {
            sb.append(enchantments);
        }
        if (!storedEnchantments.isEmpty()) {
            sb.append(storedEnchantments);
        }
        return sb.toString();
    }

    public boolean soldOut() {
        return stock <= 0;
    }
}
