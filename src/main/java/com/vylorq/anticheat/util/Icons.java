package com.vylorq.anticheat.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds menu icons with names and lore. */
public final class Icons {
    private Icons() {
    }

    public static ItemStack of(ItemConvertible item, String name, String... lore) {
        return of(new ItemStack(item), name, List.of(lore));
    }

    public static ItemStack of(ItemConvertible item, String name, List<String> lore) {
        return of(new ItemStack(item), name, lore);
    }

    public static ItemStack of(ItemStack stack, String name, List<String> lore) {
        ItemStack s = stack.copy();
        if (name != null) {
            s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§r" + name));
        }
        if (lore != null && !lore.isEmpty()) {
            List<Text> lines = new ArrayList<>();
            for (String l : lore) {
                for (String part : l.split("\n")) {
                    lines.add(Text.literal("§r§7" + part));
                }
            }
            s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        return s;
    }

    public static ItemStack withLore(ItemStack stack, List<String> extra) {
        ItemStack s = stack.copy();
        List<Text> lines = new ArrayList<>();
        LoreComponent old = s.get(DataComponentTypes.LORE);
        if (old != null) {
            lines.addAll(old.lines());
        }
        for (String l : extra) {
            lines.add(Text.literal("§r§7" + l));
        }
        s.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return s;
    }

    public static ItemStack head(UUID id, String name, String display, String... lore) {
        ItemStack s = new ItemStack(Items.PLAYER_HEAD);
        s.set(DataComponentTypes.PROFILE, new ProfileComponent(new GameProfile(id, name.length() > 16 ? name.substring(0, 16) : name)));
        return of(s, display, List.of(lore));
    }

    public static ItemStack filler() {
        return of(Items.GRAY_STAINED_GLASS_PANE, " ");
    }

    public static ItemStack glass(Item pane, String name, String... lore) {
        return of(pane, name, lore);
    }

    public static ItemStack toggle(boolean on, String name, String... lore) {
        List<String> l = new ArrayList<>(List.of(lore));
        l.add(on ? "§aON §7- click to turn off" : "§cOFF §7- click to turn on");
        return of(new ItemStack(on ? Items.LIME_DYE : Items.GRAY_DYE), (on ? "§a" : "§c") + name, l);
    }

    public static ItemStack glint(ItemStack s) {
        ItemStack c = s.copy();
        c.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return c;
    }
}
