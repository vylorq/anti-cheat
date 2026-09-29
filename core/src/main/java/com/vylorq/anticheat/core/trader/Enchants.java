package com.vylorq.anticheat.core.trader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Vanilla 1.21 enchantment data needed for trader offers and illegal-item checks. */
public final class Enchants {
    private Enchants() {
    }

    public static final Map<String, Integer> MAX_LEVELS = new LinkedHashMap<>();
    public static final Set<String> TREASURE = Set.of("minecraft:mending", "minecraft:frost_walker",
            "minecraft:soul_speed", "minecraft:swift_sneak", "minecraft:wind_burst");
    public static final Set<String> CURSES = Set.of("minecraft:binding_curse", "minecraft:vanishing_curse");
    /** Most valuable enchantments. */
    public static final Set<String> TOP = Set.of("minecraft:protection", "minecraft:sharpness", "minecraft:efficiency",
            "minecraft:unbreaking", "minecraft:fortune", "minecraft:looting", "minecraft:power", "minecraft:feather_falling",
            "minecraft:density", "minecraft:breach");

    static {
        put("protection", 4); put("fire_protection", 4); put("feather_falling", 4); put("blast_protection", 4);
        put("projectile_protection", 4); put("respiration", 3); put("aqua_affinity", 1); put("thorns", 3);
        put("depth_strider", 3); put("frost_walker", 2); put("binding_curse", 1); put("soul_speed", 3);
        put("swift_sneak", 3); put("sharpness", 5); put("smite", 5); put("bane_of_arthropods", 5);
        put("knockback", 2); put("fire_aspect", 2); put("looting", 3); put("sweeping_edge", 3);
        put("efficiency", 5); put("silk_touch", 1); put("unbreaking", 3); put("fortune", 3); put("power", 5);
        put("punch", 2); put("flame", 1); put("infinity", 1); put("luck_of_the_sea", 3); put("lure", 3);
        put("loyalty", 3); put("impaling", 5); put("riptide", 3); put("channeling", 1); put("multishot", 1);
        put("quick_charge", 3); put("piercing", 4); put("mending", 1); put("vanishing_curse", 1);
        put("density", 5); put("breach", 4); put("wind_burst", 3);
    }

    private static void put(String id, int max) {
        MAX_LEVELS.put("minecraft:" + id, max);
    }

    public static int max(String id) {
        return MAX_LEVELS.getOrDefault(id, 1);
    }

    /** Enchantments that may appear on a random gear offer for this item. */
    public static List<String> applicable(String itemId) {
        String i = itemId.replace("minecraft:", "");
        List<String> out = new ArrayList<>();
        if (i.endsWith("_sword")) {
            add(out, "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "unbreaking");
        } else if (i.endsWith("_axe")) {
            add(out, "sharpness", "smite", "bane_of_arthropods", "efficiency", "unbreaking");
        } else if (i.equals("mace")) {
            add(out, "density", "breach", "smite", "bane_of_arthropods", "fire_aspect", "unbreaking");
        } else if (i.endsWith("_pickaxe") || i.endsWith("_shovel") || i.endsWith("_hoe")) {
            add(out, "efficiency", "unbreaking", "fortune");
        } else if (i.endsWith("_helmet")) {
            add(out, "protection", "fire_protection", "blast_protection", "projectile_protection", "unbreaking", "respiration", "aqua_affinity");
        } else if (i.endsWith("_chestplate") || i.endsWith("_leggings")) {
            add(out, "protection", "fire_protection", "blast_protection", "projectile_protection", "unbreaking", "thorns");
        } else if (i.endsWith("_boots")) {
            add(out, "protection", "fire_protection", "blast_protection", "projectile_protection", "unbreaking", "feather_falling", "depth_strider");
        } else if (i.equals("bow")) {
            add(out, "power", "punch", "flame", "unbreaking", "infinity");
        } else if (i.equals("crossbow")) {
            add(out, "quick_charge", "multishot", "piercing", "unbreaking");
        } else if (i.equals("fishing_rod")) {
            add(out, "luck_of_the_sea", "lure", "unbreaking");
        } else if (i.equals("trident")) {
            add(out, "loyalty", "impaling", "unbreaking");
        }
        return out;
    }

    /** Book-able enchantments (no curses). */
    public static List<String> bookable() {
        List<String> out = new ArrayList<>();
        for (String id : MAX_LEVELS.keySet()) {
            if (!CURSES.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static void add(List<String> out, String... ids) {
        for (String s : ids) {
            out.add("minecraft:" + s);
        }
    }

    /** Pairs that can't be on the same item. */
    public static boolean exclusive(String a, String b) {
        Set<Set<String>> groups = Set.of(
                Set.of("minecraft:sharpness", "minecraft:smite", "minecraft:bane_of_arthropods", "minecraft:density", "minecraft:breach"),
                Set.of("minecraft:protection", "minecraft:fire_protection", "minecraft:blast_protection", "minecraft:projectile_protection"),
                Set.of("minecraft:fortune", "minecraft:silk_touch"),
                Set.of("minecraft:multishot", "minecraft:piercing"),
                Set.of("minecraft:infinity", "minecraft:mending"),
                Set.of("minecraft:depth_strider", "minecraft:frost_walker"));
        for (Set<String> g : groups) {
            if (g.contains(a) && g.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /** Rarity of an enchanted book. */
    public static Rarity bookRarity(String id, int level) {
        int max = max(id);
        if (id.equals("minecraft:mending")) {
            return Rarity.LEGENDARY;
        }
        if (max > 1 && level >= max) {
            return Rarity.LEGENDARY;
        }
        if (max == 1 || (max >= 3 && level >= max - 1)) {
            return Rarity.RARE;
        }
        return Rarity.UNCOMMON;
    }

    /** Rarity bump from enchantments on gear: Protection IV or any max-level multi-level enchant = legendary. */
    public static Rarity gearRarity(Map<String, Integer> ench) {
        Rarity r = Rarity.COMMON;
        for (Map.Entry<String, Integer> e : ench.entrySet()) {
            int max = max(e.getKey());
            if (e.getKey().equals("minecraft:mending") || (max > 1 && e.getValue() >= max)) {
                return Rarity.LEGENDARY;
            }
            if (max >= 3 && e.getValue() >= max - 1) {
                r = r.max(Rarity.RARE);
            } else {
                r = r.max(Rarity.UNCOMMON);
            }
        }
        return r;
    }
}
