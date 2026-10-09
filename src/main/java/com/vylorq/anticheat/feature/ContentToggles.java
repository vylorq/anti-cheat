package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.ItemConv;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * The owner turns the mod's findable things on or off: secret items, armour sets, gems, Ruby tools, custom
 * enchantments, The Boiled One's items, and the mace's Heavy Core. Off means nobody can get it any more: chests and
 * bosses leave it out, ores give nothing, it can't be crafted. Whatever players already have stays theirs.
 */
public final class ContentToggles {
    private ContentToggles() {
    }

    public static final class State {
        public Set<String> off = new HashSet<>();
    }

    private static State state;
    /** The owner taking something from a menu: always allowed. */
    private static boolean bypass;

    private static Path file() {
        return Ac.get().dir.resolve("content_toggles.json");
    }

    private static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read content_toggles.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.off == null) {
                state.off = new HashSet<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save content_toggles.json", e);
        }
    }

    public static boolean on(String key) {
        return !state().off.contains(key);
    }

    public static void set(String key, boolean on) {
        if (on) {
            state().off.remove(key);
        } else {
            state().off.add(key);
        }
        save();
    }

    /** What this item counts as ("secret:voidblade", "armor:inferno", "gem:ruby"...), or null for anything else. */
    public static String key(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String secret = SecretItems.idOf(s);
        if (secret != null) {
            return "secret:" + secret;
        }
        var set = ArmorSets.setOf(s);
        if (set != null) {
            return "armor:" + set.id();
        }
        String make = ItemConv.tag(s, Gems.MAKE);
        if (make != null) {
            String[] p = make.split(":");
            return switch (p[0]) {
                case "armor" -> "armor:" + p[1];
                case "ruby_tool" -> "ruby_tools";
                case "gem" -> "gem:" + p[1];
                case "dawn_lantern" -> "boiled:lantern";
                case "boiled" -> "boiled:armor";
                default -> null;
            };
        }
        String gem = ItemConv.tag(s, Gems.GEM);
        if (gem == null) {
            gem = ItemConv.tag(s, Gems.RAW);
        }
        if (gem != null) {
            return "gem:" + gem;
        }
        if (ItemConv.tag(s, Gems.MAKE + "_done") != null) {
            return "ruby_tools";
        }
        if (ItemConv.tag(s, "vigil_boiled_armor") != null) {
            return "boiled:armor";
        }
        if (BoiledOmens.isLantern(s)) {
            return "boiled:lantern";
        }
        if (BoiledHaunts.pageOf(s) > 0) {
            return "boiled:pages";
        }
        if (BoiledDread.isCursed(s)) {
            return "boiled:cursed_bone";
        }
        if (s.isOf(Items.HEAVY_CORE)) {
            return "vanilla:mace";
        }
        var stored = s.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (stored != null) {
            for (var e : stored.getEnchantments()) {
                var k = e.getKey();
                if (k.isPresent() && "vigil".equals(k.get().getValue().getNamespace())) {
                    return "enchant:" + k.get().getValue().getPath();
                }
            }
        }
        return null;
    }

    /** Whether players may get this (always, for anything that isn't the mod's). */
    public static boolean allowed(ItemStack s) {
        if (bypass) {
            return true;
        }
        String k = key(s);
        return k == null || on(k);
    }

    /** Gives it to them if it's turned on. */
    public static void give(ServerPlayerEntity p, ItemStack s) {
        if (!s.isEmpty() && allowed(s)) {
            p.getInventory().offerOrDrop(s);
        }
    }

    /** Runs something (the owner taking an item from a menu) with everything allowed. */
    public static void asOwner(Runnable r) {
        bypass = true;
        try {
            r.run();
        } finally {
            bypass = false;
        }
    }

    /** Registered last, so it sees every chest, boss weapon and ore after the rest of the mod added to them. */
    public static void register() {
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            if (Ac.running()) {
                drops.removeIf(s -> !allowed(s));
            }
        });
    }
}
