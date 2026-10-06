package com.vylorq.anticheat.util;

import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Names inside the resource pack (models, textures, sounds) are coded, so opening the pack doesn't reveal what the
 * items or secrets are. scripts/owner-pack/build.py computes the same codes.
 */
public final class PackIds {
    private PackIds() {
    }

    public static String code(String name) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("vigil-pack:" + name).getBytes(StandardCharsets.UTF_8));
            return "x" + HexFormat.of().formatHex(h).substring(0, 10);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The custom model data string for a pack model. */
    public static String model(String name) {
        return "vigil:" + code(name);
    }

    /** The pack's own item definition for a model (the item_model component). */
    public static Identifier itemModel(String name) {
        return Identifier.of("vigil", code(name));
    }

    /**
     * Gives an item a pack model: custom_model_data (read by the base item's definition) and item_model (the item's
     * own definition, which also works on newer clients joining through ViaVersion).
     */
    public static void apply(net.minecraft.item.ItemStack s, String name) {
        s.set(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA,
                new net.minecraft.component.type.CustomModelDataComponent(java.util.List.of(), java.util.List.of(),
                        java.util.List.of(model(name)), java.util.List.of()));
        s.set(net.minecraft.component.DataComponentTypes.ITEM_MODEL, itemModel(name));
    }

    /**
     * Brings an older copy of a custom item up to date so it shows on every game version: very old copies name their
     * model in plain words ("vigil:meteor_staff") and get the coded name and item_model; newer ones that only lack
     * item_model get it. @return true if it changed
     */
    public static boolean upgrade(net.minecraft.item.ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        var cmd = s.get(net.minecraft.component.DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd == null || cmd.strings().isEmpty() || !cmd.strings().get(0).startsWith("vigil:")) {
            return false;
        }
        String name = cmd.strings().get(0).substring("vigil:".length());
        if (!name.matches("x[0-9a-f]{10}")) {
            if (!name.matches("[a-z0-9_]+")) {
                return false;
            }
            apply(s, name);
            return true;
        }
        Identifier want = Identifier.of("vigil", name);
        if (want.equals(s.get(net.minecraft.component.DataComponentTypes.ITEM_MODEL))) {
            return false;
        }
        s.set(net.minecraft.component.DataComponentTypes.ITEM_MODEL, want);
        return true;
    }

    /** A pack sound. */
    public static Identifier sound(String name) {
        return Identifier.of("vigil", code(name));
    }
}
