package com.vylorq.anticheat.core.trader;

import java.util.ArrayList;
import java.util.List;

/** Trader specialties and what each can sell (section 23.2). */
public enum Specialty {
    LIBRARIAN, MASON, WEAPONSMITH, ARMORER, TOOLSMITH, FLETCHER, CLERIC, FARMER, FISHERMAN, SHEPHERD,
    LEATHERWORKER, CARTOGRAPHER;

    /** A possible offer: item, base rarity, units per sale, and whether it gets random enchantments. */
    public record Entry(String id, Rarity rarity, int unit, boolean enchanted, String potion) {
        static Entry of(String id, Rarity r, int unit) {
            return new Entry("minecraft:" + id, r, unit, false, null);
        }

        static Entry ench(String id, Rarity r) {
            return new Entry("minecraft:" + id, r, 1, true, null);
        }

        static Entry potion(String id, String potion, Rarity r, int unit) {
            return new Entry("minecraft:" + id, r, unit, false, "minecraft:" + potion);
        }
    }

    /** Villager profession id used for the trader's look. */
    public String profession() {
        return "minecraft:" + name().toLowerCase();
    }

    public List<Entry> pool() {
        List<Entry> l = new ArrayList<>();
        Rarity C = Rarity.COMMON;
        Rarity U = Rarity.UNCOMMON;
        Rarity R = Rarity.RARE;
        switch (this) {
            case LIBRARIAN -> {
                // Enchanted books are generated separately; these fill the rest.
                l.add(Entry.of("book", C, 4));
                l.add(Entry.of("bookshelf", C, 2));
                l.add(Entry.of("lantern", C, 2));
                l.add(Entry.of("name_tag", U, 1));
                l.add(Entry.of("clock", U, 1));
            }
            case MASON -> {
                for (String s : List.of("stone_bricks", "polished_andesite", "polished_diorite", "polished_granite",
                        "bricks", "deepslate_bricks", "mud_bricks", "terracotta", "smooth_stone", "chiseled_stone_bricks",
                        "dripstone_block", "polished_blackstone_bricks", "tuff_bricks", "white_glazed_terracotta")) {
                    l.add(Entry.of(s, C, 16));
                }
                l.add(Entry.of("quartz_block", U, 8));
                l.add(Entry.of("quartz_pillar", U, 8));
            }
            case WEAPONSMITH -> {
                l.add(Entry.ench("iron_sword", U));
                l.add(Entry.ench("iron_axe", U));
                l.add(Entry.ench("diamond_sword", R));
                l.add(Entry.ench("diamond_axe", R));
                l.add(Entry.ench("mace", R));
                l.add(Entry.of("bell", U, 1));
            }
            case ARMORER -> {
                for (String p : List.of("helmet", "chestplate", "leggings", "boots")) {
                    l.add(Entry.ench("iron_" + p, U));
                    l.add(Entry.ench("chainmail_" + p, U));
                    l.add(Entry.ench("diamond_" + p, R));
                }
                l.add(Entry.of("shield", U, 1));
            }
            case TOOLSMITH -> {
                for (String p : List.of("pickaxe", "shovel", "hoe")) {
                    l.add(Entry.ench("iron_" + p, U));
                    l.add(Entry.ench("diamond_" + p, R));
                }
            }
            case FLETCHER -> {
                l.add(Entry.of("arrow", C, 16));
                l.add(Entry.of("spectral_arrow", C, 8));
                for (String p : List.of("swiftness", "healing", "poison", "slowness", "weakness", "harming", "fire_resistance")) {
                    l.add(Entry.potion("tipped_arrow", p, U, 8));
                }
                l.add(Entry.ench("bow", U));
                l.add(Entry.ench("crossbow", U));
                l.add(Entry.of("flint", C, 8));
            }
            case CLERIC -> {
                for (String p : List.of("healing", "swiftness", "fire_resistance", "night_vision", "water_breathing",
                        "strength", "regeneration", "leaping", "slow_falling", "invisibility")) {
                    l.add(Entry.potion("potion", p, U, 1));
                }
                l.add(Entry.potion("splash_potion", "healing", U, 1));
                l.add(Entry.of("blaze_powder", U, 4));
                l.add(Entry.of("nether_wart", C, 8));
                l.add(Entry.of("glowstone_dust", C, 16));
                l.add(Entry.of("redstone", C, 16));
                l.add(Entry.of("glass_bottle", C, 8));
                l.add(Entry.of("experience_bottle", U, 4));
                l.add(Entry.of("rabbit_foot", U, 1));
                l.add(Entry.of("ender_pearl", U, 1));
            }
            case FARMER -> {
                for (String s : List.of("bread", "cooked_beef", "baked_potato", "apple", "pumpkin_pie", "carrot",
                        "potato", "wheat_seeds", "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "oak_sapling",
                        "birch_sapling", "spruce_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling",
                        "cherry_sapling", "cookie")) {
                    l.add(Entry.of(s, C, 8));
                }
                l.add(Entry.of("golden_carrot", U, 4));
                l.add(Entry.of("cake", U, 1));
            }
            case FISHERMAN -> {
                l.add(Entry.ench("fishing_rod", U));
                for (String s : List.of("cod", "salmon", "cooked_cod", "cooked_salmon", "tropical_fish", "pufferfish")) {
                    l.add(Entry.of(s, C, 8));
                }
                l.add(Entry.of("cod_bucket", U, 1));
                l.add(Entry.of("campfire", C, 1));
            }
            case SHEPHERD -> {
                for (String c : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")) {
                    l.add(Entry.of(c + "_wool", C, 16));
                    l.add(Entry.of(c + "_dye", C, 8));
                }
                l.add(Entry.of("white_banner", C, 1));
                l.add(Entry.of("shears", U, 1));
                l.add(Entry.of("painting", C, 2));
            }
            case LEATHERWORKER -> {
                for (String p : List.of("helmet", "chestplate", "leggings", "boots")) {
                    l.add(Entry.ench("leather_" + p, U));
                }
                l.add(Entry.of("leather", C, 8));
                l.add(Entry.of("saddle", R, 1));
                l.add(Entry.of("leather_horse_armor", U, 1));
                l.add(Entry.of("iron_horse_armor", U, 1));
                l.add(Entry.of("golden_horse_armor", R, 1));
                l.add(Entry.of("diamond_horse_armor", R, 1));
            }
            case CARTOGRAPHER -> {
                l.add(Entry.of("map", C, 1));
                l.add(Entry.of("paper", C, 16));
                l.add(Entry.of("compass", U, 1));
                l.add(Entry.of("spyglass", U, 1));
                l.add(Entry.of("item_frame", C, 4));
                l.add(Entry.of("recovery_compass", R, 1));
                l.add(Entry.of("globe_banner_pattern", U, 1));
            }
        }
        return l;
    }

    public static Specialty parse(String s) {
        for (Specialty sp : values()) {
            if (sp.name().equalsIgnoreCase(s)) {
                return sp;
            }
        }
        return null;
    }
}
