package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.Kit;
import com.vylorq.anticheat.util.ItemConv;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.potion.Potions;
import net.minecraft.registry.entry.RegistryEntry;

import java.util.List;

/** Built-in arena kits (section 22). Created on first start; admins can overwrite them with /arena kit save. */
public final class KitPresets {
    private KitPresets() {
    }

    private static ItemStack item(Item i, int count, String... enchants) {
        ItemStack s = new ItemStack(i, count);
        for (String e : enchants) {
            String[] parts = e.split(":");
            ItemConv.enchantment("minecraft:" + parts[0]).ifPresent(en -> s.addEnchantment(en, Integer.parseInt(parts[1])));
        }
        return s;
    }

    private static ItemStack potion(Item type, RegistryEntry<Potion> p, int count) {
        ItemStack s = new ItemStack(type, count);
        s.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(p));
        return s;
    }

    private static void armor(Kit k, Item helmet, Item chest, Item legs, Item boots, String... enchants) {
        k.items.put(39, ItemConv.encode(item(helmet, 1, enchants)));
        k.items.put(38, ItemConv.encode(item(chest, 1, enchants)));
        k.items.put(37, ItemConv.encode(item(legs, 1, enchants)));
        k.items.put(36, ItemConv.encode(item(boots, 1, enchants)));
    }

    private static void put(Kit k, int slot, ItemStack s) {
        k.items.put(slot, ItemConv.encode(s));
    }

    public static void ensure() {
        var arenas = Ac.get().arenas;
        for (String name : Kit.PRESETS) {
            if (arenas.kit(name) != null) {
                continue;
            }
            Kit k = new Kit();
            k.name = name;
            k.builtIn = true;
            switch (name) {
                case "sword" -> {
                    put(k, 0, item(Items.DIAMOND_SWORD, 1, "sharpness:1"));
                    put(k, 1, item(Items.COOKED_BEEF, 16));
                    armor(k, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
                }
                case "axe_shield" -> {
                    put(k, 0, item(Items.DIAMOND_AXE, 1));
                    put(k, 1, item(Items.IRON_SWORD, 1));
                    put(k, 2, item(Items.COOKED_BEEF, 16));
                    put(k, 40, item(Items.SHIELD, 1));
                    armor(k, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS);
                }
                case "mace" -> {
                    put(k, 0, item(Items.MACE, 1, "density:3"));
                    put(k, 1, item(Items.WIND_CHARGE, 16));
                    put(k, 2, item(Items.DIAMOND_SWORD, 1));
                    put(k, 3, item(Items.COOKED_BEEF, 16));
                    armor(k, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
                }
                case "archery" -> {
                    put(k, 0, item(Items.BOW, 1, "power:2"));
                    put(k, 1, item(Items.STONE_SWORD, 1));
                    put(k, 2, item(Items.COOKED_BEEF, 16));
                    put(k, 9, item(Items.ARROW, 64));
                    armor(k, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS, "protection:1");
                }
                case "netherite" -> {
                    put(k, 0, item(Items.NETHERITE_SWORD, 1, "sharpness:5"));
                    put(k, 1, item(Items.GOLDEN_APPLE, 2));
                    put(k, 2, item(Items.COOKED_BEEF, 16));
                    armor(k, Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS, "protection:4");
                }
                case "potion" -> {
                    put(k, 0, item(Items.DIAMOND_SWORD, 1, "sharpness:2"));
                    for (int i = 1; i <= 6; i++) {
                        put(k, i, potion(Items.SPLASH_POTION, Potions.STRONG_HEALING, 1));
                    }
                    put(k, 7, potion(Items.POTION, Potions.SWIFTNESS, 1));
                    put(k, 8, item(Items.COOKED_BEEF, 16));
                    for (int i = 9; i < 18; i++) {
                        put(k, i, potion(Items.SPLASH_POTION, Potions.STRONG_HEALING, 1));
                    }
                    armor(k, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS, "protection:2");
                }
                case "uhc" -> {
                    put(k, 0, item(Items.DIAMOND_SWORD, 1, "sharpness:2"));
                    put(k, 1, item(Items.BOW, 1, "power:1"));
                    put(k, 2, item(Items.GOLDEN_APPLE, 4));
                    put(k, 3, item(Items.WATER_BUCKET, 1));
                    put(k, 4, item(Items.LAVA_BUCKET, 1));
                    put(k, 5, item(Items.COBBLESTONE, 64));
                    put(k, 6, item(Items.COOKED_BEEF, 16));
                    put(k, 9, item(Items.ARROW, 32));
                    put(k, 40, item(Items.SHIELD, 1));
                    armor(k, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, "protection:1");
                    k.naturalRegen = false;
                }
                default -> {
                    continue;
                }
            }
            k.description = List.of("Built-in " + name + " kit");
            arenas.saveKit(k);
        }
        Ac.markDirty("arenas");
    }
}
