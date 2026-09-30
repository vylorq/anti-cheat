package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.perm.Role;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.staff.PunishmentManager;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * Punishments decided by admins (section 15). The system never calls these on its own.
 */
public final class Punish {
    private Punish() {
    }

    /** Admins can't punish admins or the owner (section 5). */
    public static boolean allowedOn(ServerPlayerEntity actor, UUID target) {
        Role a = actor == null ? Role.OWNER : Perms.effectiveRole(actor);
        Role t = Perms.roleOffline(target);
        if (!PermissionPolicy.canActOn(a, t)) {
            if (actor != null) {
                Msg.send(actor, "punish.not-allowed");
            }
            return false;
        }
        return true;
    }

    private static String reason(String r) {
        if (r == null || r.isBlank()) {
            return "No reason given";
        }
        String t = Ac.config().staff.reasonTemplates.get(r.toLowerCase());
        return t != null ? t : r;
    }

    public static void warn(ServerPlayerEntity actor, UUID target, String name, String why) {
        if (!allowedOn(actor, target)) {
            return;
        }
        Ac ac = Ac.get();
        String r = reason(why);
        ac.punishments.add(Punishment.Type.WARN, target, name, r, Staff.name(actor), 0);
        Ac.markDirty("punishments");
        Staff.log(actor, "warn", target, name, r);
        ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(target);
        if (p != null) {
            Msg.send(p, "punish.warned", r);
        } else {
            ac.pendingMessage(target, Msg.tr("punish.warned", r));
        }
        if (Ac.config().staff.ladderEnabled) {
            PunishmentManager.LadderStep step = ac.punishments.ladderStep(target, Ac.config().staff.ladder);
            if (step != null) {
                String auto = "Punishment ladder (" + ac.punishments.count(target, Punishment.Type.WARN) + " warnings)";
                switch (step.type()) {
                    case MUTE -> mute(actor, target, name, step.duration(), auto);
                    case BAN -> ban(actor, target, name, step.duration(), auto, false);
                    case KICK -> kick(actor, target, name, auto);
                    case JAIL -> {
                        if (p != null) {
                            Jail.jail(p, auto, step.duration(), Staff.name(actor));
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    public static void mute(ServerPlayerEntity actor, UUID target, String name, long duration, String why) {
        if (!allowedOn(actor, target)) {
            return;
        }
        Ac ac = Ac.get();
        String r = reason(why);
        ac.punishments.add(Punishment.Type.MUTE, target, name, r, Staff.name(actor), duration);
        Ac.markDirty("punishments");
        Staff.log(actor, "mute", target, name, Durations.format(duration) + " " + r);
        ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(target);
        if (p != null) {
            Msg.send(p, "punish.muted", Durations.format(duration), r);
        }
    }

    public static void unmute(ServerPlayerEntity actor, UUID target, String name) {
        int n = Ac.get().punishments.revoke(target, Punishment.Type.MUTE, Staff.name(actor));
        Ac.markDirty("punishments");
        Staff.log(actor, "unmute", target, name, n + " revoked");
    }

    public static void kick(ServerPlayerEntity actor, UUID target, String name, String why) {
        if (!allowedOn(actor, target)) {
            return;
        }
        Ac ac = Ac.get();
        String r = reason(why);
        ac.punishments.add(Punishment.Type.KICK, target, name, r, Staff.name(actor), 0);
        Ac.markDirty("punishments");
        Staff.log(actor, "kick", target, name, r);
        ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(target);
        if (p != null) {
            p.networkHandler.disconnect(Text.literal(Msg.tr("punish.kicked", r)));
        }
    }

    public static void ban(ServerPlayerEntity actor, UUID target, String name, long duration, String why, boolean cheating) {
        if (!allowedOn(actor, target)) {
            return;
        }
        Ac ac = Ac.get();
        String r = reason(why);
        Punishment ban = ac.punishments.add(Punishment.Type.BAN, target, name, r, Staff.name(actor), duration);
        ban.cheating = cheating;
        Ac.markDirty("punishments");
        Staff.log(actor, duration == Durations.PERMANENT ? "ban" : "tempban", target, name, Durations.format(duration) + " " + r);
        ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(target);
        if (cheating) {
            int caught = ac.stats.incrementCaught();
            Ac.markDirty("stats");
            if (Ac.config().fun.banEffects && p != null) {
                banEffect(p);
                ac.server.getPlayerManager().broadcast(Text.literal(Msg.tr("fun.caught", name)), false);
                if (Ac.config().fun.caughtCounter) {
                    ac.server.getPlayerManager().broadcast(Text.literal(Msg.tr("fun.caught-counter", caught)), false);
                }
            }
            // Evidence tied to a ban is kept.
            for (var c : ac.reviews.forPlayer(target)) {
                for (String clip : c.clipIds) {
                    try {
                        ac.clips.setPinned(clip, true);
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }
        }
        if (p != null) {
            p.networkHandler.disconnect(Text.literal(ac.punishments.banScreen(ban)));
        }
        Discord.send("ban", "Banned: " + name, r + " (" + Durations.format(duration) + ") by " + Staff.name(actor), 0xE74C3C);
    }

    public static void unban(ServerPlayerEntity actor, UUID target, String name) {
        Ac ac = Ac.get();
        int n = ac.punishments.revoke(target, Punishment.Type.BAN, Staff.name(actor))
                + ac.punishments.revoke(target, Punishment.Type.DENY, Staff.name(actor));
        Ac.markDirty("punishments");
        Staff.log(actor, "unban", target, name, n + " revoked");
    }

    /** Lightning with no damage or fire. */
    public static void banEffect(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        net.minecraft.util.math.Vec3d at = p.getEntityPos();
        Runnable strike = () -> {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.refreshPositionAfterTeleport(at.x, at.y, at.z);
                bolt.setCosmetic(true);
                w.spawnEntity(bolt);
            }
        };
        // The Watcher appears where the cheater stood just before the strike (33.5).
        if (!Watcher.banAppearance(w, at, p.getYaw(), strike)) {
            strike.run();
        }
    }
}
