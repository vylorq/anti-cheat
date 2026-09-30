package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.core.waiting.WaitingRoom;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.platform.Floodgate;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;
import java.util.UUID;

/** New-player verification (section 25). */
public final class WaitingRoomFeature {
    private static final double RADIUS = 12.0;

    private WaitingRoomFeature() {
    }

    public static boolean waiting(ServerPlayerEntity p) {
        return Ac.get().waitingRoom.mustWait(p.getUuid(), Perms.isStaff(p), Ac.config().waitingRoom.enabled);
    }

    public static void onJoin(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        WaitingRoom wr = ac.waitingRoom;
        if (!wr.isSet() || !Ac.config().waitingRoom.enabled) {
            return;
        }
        if (Perms.isStaff(p)) {
            wr.accept(p.getUuid());
            return;
        }
        // Denial ban over: they may ask again.
        wr.resetAfterBan(p.getUuid());
        if (!waiting(p)) {
            return;
        }
        Mc.teleport(p, ac.server, wr.data().spawn);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, StatusEffectInstance.INFINITE, 0, false, false));
        WaitingRoom.Request r = wr.request(p.getUuid());
        if (r != null && r.status == WaitingRoom.Status.PENDING) {
            Mc.title(p, Msg.tr("waiting.title"), Msg.tr("waiting.pending"), 10, 100, 20);
        } else {
            Mc.title(p, Msg.tr("waiting.title"), Msg.tr("waiting.subtitle"), 10, 200, 20);
            Msg.send(p, "waiting.instructions");
        }
    }

    /** Seeds the accepted list with everyone known when the room is first set, so existing players never wait. */
    public static void seedExisting() {
        Ac ac = Ac.get();
        if (ac.misc.waitingRoomSeeded) {
            return;
        }
        for (UUID id : ac.joins.data().known) {
            ac.waitingRoom.accept(id);
        }
        for (ServerPlayerEntity p : ac.server.getPlayerManager().getPlayerList()) {
            ac.waitingRoom.accept(p.getUuid());
        }
        ac.misc.waitingRoomSeeded = true;
        Ac.markDirty("misc");
        Ac.markDirty("waiting");
    }

    /** @return true when the move was blocked. */
    public static boolean confine(ServerPlayerEntity p, Vec3 to) {
        if (!waiting(p)) {
            return false;
        }
        WaitingRoom wr = Ac.get().waitingRoom;
        Location spawn = wr.data().spawn;
        boolean inside = wr.data().area != null
                ? wr.data().area.contains(Mc.worldId(p.getWorld()), to.x(), to.y(), to.z())
                : spawn.world().equals(Mc.worldId(p.getWorld())) && spawn.vec().distance(to) <= RADIUS;
        if (!inside) {
            Mc.teleport(p, Ac.server(), spawn);
            return true;
        }
        return false;
    }

    /** /request join: three questions (chat for Java, a form for Bedrock). */
    public static void requestJoin(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (!waiting(p)) {
            Msg.send(p, "waiting.not-needed");
            return;
        }
        PlayerSession s = Ac.session(p);
        WaitingRoom.Request existing = ac.waitingRoom.request(p.getUuid());
        if (existing != null && existing.status == WaitingRoom.Status.PENDING) {
            Msg.send(p, "waiting.pending");
            return;
        }
        WaitingRoom.Request r = ac.waitingRoom.start(p.getUuid(), p.getGameProfile().name(), s.bedrock, s.ip);
        Ac.markDirty("waiting");
        UUID id = p.getUuid();
        if (s.bedrock && Floodgate.askText(id, Msg.tr("waiting.form-title"), List.of(WaitingRoom.QUESTIONS),
                answers -> Ac.server().execute(() -> {
                    ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(id);
                    if (online != null && answers != null) {
                        Ac.get().waitingRoom.answerAll(id, answers);
                        submitted(online);
                    }
                }))) {
            return;
        }
        askNext(p, r.step);
    }

    private static void askNext(ServerPlayerEntity p, int step) {
        Msg.send(p, "waiting.question", step + 1, WaitingRoom.QUESTIONS[step]);
        Ac.session(p).chatPrompt = answer -> {
            String next = Ac.get().waitingRoom.answer(p.getUuid(), answer);
            Ac.markDirty("waiting");
            WaitingRoom.Request r = Ac.get().waitingRoom.request(p.getUuid());
            if (next != null && r != null) {
                askNext(p, r.step);
            } else {
                submitted(p);
            }
        };
    }

    private static void submitted(ServerPlayerEntity p) {
        Ac.markDirty("waiting");
        Msg.send(p, "waiting.sent");
        WaitingRoom.Request r = Ac.get().waitingRoom.request(p.getUuid());
        if (r == null) {
            return;
        }
        for (ServerPlayerEntity admin : Staff.online()) {
            admin.sendMessage(requestText(r, admin));
        }
    }

    /** Chat card for a request, with buttons. */
    public static MutableText requestText(WaitingRoom.Request r, ServerPlayerEntity viewer) {
        Ac ac = Ac.get();
        String n = Msg.q(r.name);
        MutableText t = Msg.prefixed(Msg.tr("waiting.request-header", r.name, r.bedrock ? "Bedrock" : "Java"));
        for (int i = 0; i < WaitingRoom.QUESTIONS.length; i++) {
            t.append(Text.literal("\n§7" + WaitingRoom.QUESTIONS[i] + " §f" + (r.answers[i] == null ? "-" : r.answers[i])));
        }
        String inviter = r.answers[2] == null ? "" : r.answers[2].trim();
        UUID inviterId = inviter.isEmpty() ? null : ac.joins.findByName(inviter);
        t.append(Text.literal("\n§7" + Msg.tr("waiting.inviter-exists") + " " + (inviterId != null ? "§aYes" : "§cNo")));
        if (viewer != null && com.vylorq.anticheat.core.perm.PermissionPolicy.canSeePrivateInfo(Perms.effectiveRole(viewer),
                Ac.config().permissions.adminsSeePrivateInfo) && r.ip != null) {
            int banned = 0;
            for (UUID alt : ac.joins.accountsOn(r.ip)) {
                if (!alt.equals(r.player) && (ac.punishments.count(alt, Punishment.Type.BAN) > 0 || ac.punishments.count(alt, Punishment.Type.DENY) > 0)) {
                    banned++;
                }
            }
            if (banned > 0) {
                t.append(Text.literal("\n§c⚠ " + Msg.tr("waiting.alt-warning", banned)));
            }
        }
        t.append(Text.literal("\n"));
        t.append(Msg.button("§e[Teleport]", "/requests tp " + n, "Teleport to question them"));
        t.append(Text.literal(" "));
        t.append(Msg.button("§a[Accept]", "/requests accept " + n, "Let them play"));
        t.append(Text.literal(" "));
        t.append(Msg.button("§c[Deny]", "/requests deny " + n, "24h ban"));
        return t;
    }

    public static void accept(ServerPlayerEntity admin, UUID id) {
        Ac ac = Ac.get();
        WaitingRoom.Request r = ac.waitingRoom.decide(id, true, Staff.name(admin));
        if (r == null) {
            Msg.send(admin, "waiting.no-request");
            return;
        }
        Ac.markDirty("waiting");
        Staff.log(admin, "request-accept", id, r.name, "");
        ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(id);
        if (p != null) {
            p.removeStatusEffect(StatusEffects.DARKNESS);
            if (ac.lobby.isSet()) {
                Mc.teleport(p, ac.server, ac.lobby.data().spawn);
            } else {
                var spawn = ac.server.getOverworld().getSpawnPos();
                Mc.teleport(p, ac.server.getOverworld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
            }
            Mc.title(p, Msg.tr("waiting.accepted-title"), Msg.tr("waiting.accepted-subtitle"), 10, 60, 20);
        }
        Msg.send(admin, "waiting.accepted", r.name);
    }

    public static void deny(ServerPlayerEntity admin, UUID id) {
        Ac ac = Ac.get();
        WaitingRoom.Request r = ac.waitingRoom.decide(id, false, Staff.name(admin));
        if (r == null) {
            Msg.send(admin, "waiting.no-request");
            return;
        }
        var cfg = Ac.config().waitingRoom;
        long d = ac.waitingRoom.denyDuration(id, cfg.escalate, cfg.escalationSteps, cfg.denyBanMillis);
        Punishment ban = ac.punishments.add(Punishment.Type.DENY, id, r.name, "Join request denied", Staff.name(admin), d);
        Ac.markDirty("waiting");
        Ac.markDirty("punishments");
        Staff.log(admin, "request-deny", id, r.name, Durations.format(d));
        ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(id);
        if (p != null) {
            // Only shows time left, never the reason or that they were denied.
            p.networkHandler.disconnect(Text.literal(ac.punishments.banScreen(ban)));
        }
        Msg.send(admin, "waiting.denied", r.name, Durations.format(d));
    }

    /** Waiting players can't get hungry. */
    public static void tick(ServerPlayerEntity p) {
        if (waiting(p)) {
            p.getHungerManager().setFoodLevel(20);
            if (!p.hasStatusEffect(StatusEffects.DARKNESS)) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, StatusEffectInstance.INFINITE, 0, false, false));
            }
        }
    }

    /** Chat from a waiting player only reaches admins standing in the waiting room. */
    public static void chat(ServerPlayerEntity p, String message) {
        Location spawn = Ac.get().waitingRoom.data().spawn;
        Text t = Text.literal("§8[Waiting] §7" + p.getGameProfile().name() + ": §f" + message);
        p.sendMessage(t);
        for (ServerPlayerEntity admin : Staff.online()) {
            if (spawn != null && spawn.world().equals(Mc.worldId(admin.getWorld())) && spawn.vec().distance(Mc.vec(admin.getPos())) <= 32) {
                admin.sendMessage(t);
            }
        }
    }
}
