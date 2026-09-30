package com.vylorq.anticheat.perm;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.perm.Role;
import com.vylorq.anticheat.platform.PermissionsApi;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Role and permission checks (section 5). Every command and every menu click calls these on the server when the
 * action is received. Admin powers also require the admin PIN login when enabled.
 */
public final class Perms {
    private Perms() {
    }

    public static boolean isOwner(UUID id) {
        String owner = Ac.config().general.ownerUuid;
        return owner != null && !owner.isBlank() && owner.equalsIgnoreCase(id.toString());
    }

    /** Turns general.ownerName into ownerUuid the first time that player is seen. Bedrock's name prefix is optional. */
    private static void claimOwnerByName(ServerPlayerEntity p) {
        var g = Ac.config().general;
        if (g.ownerUuid != null && !g.ownerUuid.isBlank()) {
            return;
        }
        String want = g.ownerName == null ? "" : g.ownerName.trim();
        if (want.isEmpty()) {
            return;
        }
        String n = p.getGameProfile().getName();
        String bare = n.length() > 1 && !Character.isLetterOrDigit(n.charAt(0)) ? n.substring(1) : n;
        String wantBare = want.length() > 1 && !Character.isLetterOrDigit(want.charAt(0)) ? want.substring(1) : want;
        if (n.equalsIgnoreCase(want) || bare.equalsIgnoreCase(wantBare)) {
            g.ownerUuid = p.getUuid().toString();
            Ac.get().configManager.save();
            Ac.LOG.info("{} is now the owner (matched ownerName).", n);
        }
    }

    /** Raw role, ignoring the PIN. */
    public static Role role(ServerPlayerEntity p) {
        claimOwnerByName(p);
        if (isOwner(p.getUuid())) {
            return Role.OWNER;
        }
        if (PermissionsApi.check(p, "anticheat.admin", Ac.config().permissions.adminOpLevel)) {
            return Role.ADMIN;
        }
        return Role.PLAYER;
    }

    /** Role for an offline player (by op list). */
    public static Role roleOffline(UUID id) {
        if (isOwner(id)) {
            return Role.OWNER;
        }
        var server = Ac.server();
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
        if (online != null) {
            return role(online);
        }
        var ops = server.getPlayerManager().getOpList();
        for (String name : ops.getNames()) {
            var profile = server.getUserCache() == null ? null : server.getUserCache().findByName(name).orElse(null);
            if (profile != null && profile.getId().equals(id)) {
                return Role.ADMIN;
            }
        }
        return Role.PLAYER;
    }

    public static boolean isStaff(ServerPlayerEntity p) {
        return role(p).isStaff();
    }

    /** Staff who have logged in with their PIN (or PINs are off). */
    public static boolean isActiveStaff(ServerPlayerEntity p) {
        return isStaff(p) && pinOk(p);
    }

    public static boolean pinOk(ServerPlayerEntity p) {
        if (!Ac.config().staff.requirePin || com.vylorq.anticheat.feature.TempAdmins.isTemp(p.getUuid())) {
            return true;
        }
        return Ac.get().pins.isLoggedIn(p.getUuid());
    }

    /** Effective role: staff without a PIN login count as players. */
    public static Role effectiveRole(ServerPlayerEntity p) {
        Role r = role(p);
        return r.isStaff() && !pinOk(p) ? Role.PLAYER : r;
    }

    public static boolean has(ServerPlayerEntity p, Perm perm) {
        Role r = effectiveRole(p);
        if (perm.minimum() == Role.PLAYER) {
            return true;
        }
        if (r == Role.OWNER) {
            return true;
        }
        if (PermissionsApi.available() && r == Role.ADMIN) {
            // Fine-grained nodes: admins get everything except owner-only by default.
            return PermissionsApi.check(p, perm.node(), perm.minimum() == Role.OWNER ? 5 : Ac.config().permissions.adminOpLevel)
                    && PermissionPolicy.allowed(r, perm);
        }
        return PermissionPolicy.allowed(r, perm);
    }

    public static boolean has(ServerCommandSource src, Perm perm) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            // Console and command blocks with op level 4.
            return src.hasPermissionLevel(4) || (perm.minimum() != Role.OWNER && src.hasPermissionLevel(3));
        }
        return has(p, perm);
    }

    /** Whether the command should even show up in tab-complete (no PIN needed to see it). */
    public static boolean visible(ServerCommandSource src, Perm perm) {
        ServerPlayerEntity p = src.getPlayer();
        if (p == null) {
            return src.hasPermissionLevel(3);
        }
        Role r = role(p);
        return perm.minimum() == Role.PLAYER || PermissionPolicy.allowed(r, perm);
    }

    /**
     * Check for a received action (menu click, packet). A client sending something it isn't allowed to is flagged
     * (section 5) and told nothing specific.
     */
    public static boolean require(ServerPlayerEntity p, Perm perm) {
        if (has(p, perm)) {
            return true;
        }
        if (isStaff(p) && !pinOk(p)) {
            Msg.send(p, "staff.pin.required");
            return false;
        }
        unauthorized(p, perm.node());
        return false;
    }

    public static void unauthorized(ServerPlayerEntity p, String what) {
        if (Ac.config().permissions.flagUnauthorizedActions) {
            PlayerSessionFlags.flag(p, CheckType.UNAUTHORIZED, 1.0, what);
        }
        Msg.send(p, "general.no-permission");
    }

    /** Explains a failed command check. */
    public static boolean check(ServerCommandSource src, Perm perm) {
        if (has(src, perm)) {
            return true;
        }
        ServerPlayerEntity p = src.getPlayer();
        if (p != null && isStaff(p) && !pinOk(p)) {
            Msg.err(src, "staff.pin.required");
        } else {
            Msg.err(src, "general.no-permission");
        }
        return false;
    }
}
