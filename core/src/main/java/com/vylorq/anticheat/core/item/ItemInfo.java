package com.vylorq.anticheat.core.item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minecraft-independent description of an item stack. {@link #serialized} holds the full stack (encoded by the
 * Minecraft layer with the item codec) so it can be restored exactly.
 */
public final class ItemInfo {
    public String id;
    public int count;
    public int maxCount = 64;
    public int damage;
    public int maxDamage;
    /** enchantment id -> level */
    public Map<String, Integer> enchantments = new LinkedHashMap<>();
    /** Enchantments stored in an enchanted book. */
    public Map<String, Integer> storedEnchantments = new LinkedHashMap<>();
    public String customName;
    /** Items inside a shulker box / bundle. */
    public java.util.List<ItemInfo> contents;
    /** Opaque full encoding for exact restores. */
    public String serialized;
    /** Slot index it was in (inventory restore). -1 when unknown. */
    public int slot = -1;

    public ItemInfo() {
    }

    public ItemInfo(String id, int count) {
        this.id = id;
        this.count = count;
    }

    public boolean isEmpty() {
        return id == null || count <= 0 || id.equals("minecraft:air");
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(count).append("x ").append(id.replace("minecraft:", ""));
        if (!enchantments.isEmpty()) {
            sb.append(' ').append(enchantments);
        }
        if (!storedEnchantments.isEmpty()) {
            sb.append(' ').append(storedEnchantments);
        }
        return sb.toString();
    }
}
