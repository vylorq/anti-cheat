package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.perm.Perms;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Online staff helpers: broadcasts to admins, staff action log. */
public final class Staff {
    private Staff() {
    }

    public static List<ServerPlayerEntity> online() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (Perms.isActiveStaff(p)) {
                out.add(p);
            }
        }
        return out;
    }

    public static void broadcast(Text t) {
        for (ServerPlayerEntity p : online()) {
            p.sendMessage(t);
        }
        Ac.LOG.info("[staff] {}", t.getString());
    }

    public static void broadcastOwner(Text t) {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (Perms.isOwner(p.getUuid())) {
                p.sendMessage(t);
            }
        }
        Ac.LOG.warn("[owner] {}", t.getString());
    }

    /** Staff action log (section 15): every admin action is recorded; the owner can view all of it. */
    public static void log(ServerPlayerEntity actor, String action, UUID target, String targetName, String detail) {
        Ac.get().logs.staff(System.currentTimeMillis(), actor == null ? null : actor.getUuid(),
                actor == null ? "Console" : actor.getGameProfile().getName(), action, target, targetName, detail);
    }

    public static void log(String actorName, UUID actor, String action, UUID target, String targetName, String detail) {
        Ac.get().logs.staff(System.currentTimeMillis(), actor, actorName, action, target, targetName, detail);
    }

    public static String name(ServerPlayerEntity p) {
        return p == null ? "Console" : p.getGameProfile().getName();
    }
}
