package com.vylorq.anticheat.platform;

import com.vylorq.anticheat.Ac;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/** Optional fabric-permissions-api (LuckPerms etc.) support, falling back to op levels (section 5). */
public final class PermissionsApi {
    private static Method checkSource;
    private static Method checkEntity;
    private static boolean checked;

    private PermissionsApi() {
    }

    private static void init() {
        if (checked) {
            return;
        }
        checked = true;
        if (!FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) {
            return;
        }
        try {
            Class<?> c = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            checkSource = c.getMethod("check", net.minecraft.command.CommandSource.class, String.class, int.class);
            checkEntity = c.getMethod("check", Entity.class, String.class, int.class);
            Ac.LOG.info("fabric-permissions-api found: permission nodes are active.");
        } catch (Throwable t) {
            Ac.LOG.warn("fabric-permissions-api is installed but could not be used: {}", t.toString());
        }
    }

    public static boolean available() {
        init();
        return checkEntity != null;
    }

    /** Same node under the mod's old id (anticheat.*), so permissions given before the rename keep working. */
    private static String legacy(String node) {
        return node.startsWith("vigil.") ? "anticheat." + node.substring("vigil.".length()) : null;
    }

    /** Permission node check with an op-level fallback. */
    public static boolean check(ServerPlayerEntity e, String node, int fallbackLevel) {
        init();
        if (checkEntity != null) {
            try {
                if ((boolean) checkEntity.invoke(null, e, node, fallbackLevel)) {
                    return true;
                }
                String old = legacy(node);
                // Level 5 doesn't exist, so the old node only counts when it was granted explicitly.
                return old != null && (boolean) checkEntity.invoke(null, e, old, 5);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return com.vylorq.anticheat.util.Mc.hasLevel(e, fallbackLevel);
    }

    public static boolean check(ServerCommandSource s, String node, int fallbackLevel) {
        init();
        if (checkSource != null) {
            try {
                if ((boolean) checkSource.invoke(null, s, node, fallbackLevel)) {
                    return true;
                }
                String old = legacy(node);
                return old != null && (boolean) checkSource.invoke(null, s, old, 5);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return com.vylorq.anticheat.util.Mc.hasLevel(s, fallbackLevel);
    }
}
