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

    /** Permission node check with an op-level fallback. */
    public static boolean check(ServerPlayerEntity e, String node, int fallbackLevel) {
        init();
        if (checkEntity != null) {
            try {
                return (boolean) checkEntity.invoke(null, e, node, fallbackLevel);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return e.hasPermissionLevel(fallbackLevel);
    }

    public static boolean check(ServerCommandSource s, String node, int fallbackLevel) {
        init();
        if (checkSource != null) {
            try {
                return (boolean) checkSource.invoke(null, s, node, fallbackLevel);
            } catch (Throwable ignored) {
                // fall through
            }
        }
        return s.hasPermissionLevel(fallbackLevel);
    }
}
