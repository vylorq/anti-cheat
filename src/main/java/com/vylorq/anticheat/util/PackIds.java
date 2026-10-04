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

    /** A pack sound. */
    public static Identifier sound(String name) {
        return Identifier.of("vigil", code(name));
    }
}
