package com.vylorq.anticheat.feature;

import com.mojang.authlib.GameProfile;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.joins.JoinGuard;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.platform.Floodgate;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Login checks, join and leave handling (sections 9, 12, 15, 25). */
public final class Joins {
    private Joins() {
    }

    public static String ip(SocketAddress a) {
        if (a instanceof InetSocketAddress i && i.getAddress() != null) {
            return i.getAddress().getHostAddress();
        }
        return String.valueOf(a);
    }

    /**
     * Before the player is let in. @return a disconnect reason, or null to allow.
     */
    public static Text checkLogin(SocketAddress address, net.minecraft.server.PlayerConfigEntry profile) {
        Ac ac = Ac.get();
        if (ac == null || profile == null || profile.id() == null) {
            return null;
        }
        UUID id = profile.id();
        boolean staffish = Perms.isOwner(id) || ac.server.getPlayerManager().isOperator(profile);
        Punishment ban = ac.punishments.active(id, Punishment.Type.BAN);
        if (ban == null) {
            ban = ac.punishments.active(id, Punishment.Type.DENY);
        }
        if (ban != null) {
            return Text.literal(ac.punishments.banScreen(ban));
        }
        if (ac.staff.maintenance() && !staffish) {
            return Text.literal(Msg.tr("maintenance.kick"));
        }
        if (!staffish) {
            var cfg = Ac.config().joins;
            JoinGuard.Verdict v = ac.joins.checkJoin(id, ip(address), System.currentTimeMillis(), cfg.maxNewAccountsPerMinute, cfg.subnetWaveSize);
            if (v != JoinGuard.Verdict.OK) {
                Staff.broadcast(Msg.prefixed(Msg.tr("joins.blocked", profile.name(), ip(address), v.name())));
                return Text.literal(Msg.tr("joins.try-later"));
            }
        }
        return null;
    }

    public static void onJoin(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        long now = System.currentTimeMillis();
        String name = p.getGameProfile().name();
        PlayerSession s = Ac.session(p);
        s.name = name;
        s.bedrock = Floodgate.isBedrock(p.getUuid());
        s.ip = p.getIp();
        s.ticksSinceJoin = 0;
        s.teleported();
        boolean newIp = ac.joins.recordJoin(p.getUuid(), name, s.ip);
        Ac.markDirty("joins");
        ac.misc.firstJoin.putIfAbsent(p.getUuid(), now);
        Ac.markDirty("misc");
        ac.logs.join(now, p.getUuid(), name, s.ip, "join" + (s.bedrock ? " (bedrock)" : ""));
        ac.warnings.startSession(p.getUuid());

        // Watched players: join alerts (and a new IP).
        if (ac.watchlist.isWatched(p.getUuid())) {
            boolean ipChanged = ac.watchlist.updateIp(p.getUuid(), s.ip);
            Ac.markDirty("watchlist");
            Staff.broadcast(Msg.prefixed(Msg.tr(ipChanged || newIp ? "watch.join-new-ip" : "watch.join", name)));
        }
        // Items held for them when the server stopped (trades, trader offers).
        List<String> held = ac.takeAllEscrow(p.getUuid());
        if (!held.isEmpty()) {
            List<ItemStack> items = new ArrayList<>();
            for (String e : held) {
                items.add(ItemConv.decode(e));
            }
            Trades.giveBack(p, items);
            Msg.send(p, "escrow.returned", items.size());
        }
        List<String> pending = ac.misc.pendingMessages.remove(p.getUuid());
        if (pending != null) {
            for (String m : pending) {
                p.sendMessage(Msg.prefixed(m));
            }
            Ac.markDirty("misc");
        }
        TempAdmins.onJoin(p);
        BuilderMode.onJoin(p);
        Teams.onJoin(p);
        Arenas.onJoin(p);
        Jail.onJoin(p);
        WaitingRoomFeature.onJoin(p);
        Claims.onJoin(p);
        StaffTools.onJoin(p);
        if (ac.misc.frozenLogout.remove(p.getUuid())) {
            Ac.markDirty("misc");
            Staff.broadcast(Msg.prefixed(Msg.tr("freeze.returned", name)));
        }
        Illegal.scanPlayer(p);
        s.lastDupeValue = Dupes.valueOf(p);
        if (Perms.isStaff(p)) {
            staffJoin(p);
        }
    }

    private static void staffJoin(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (Ac.config().staff.requirePin) {
            if (!ac.pins.hasPin(p.getUuid())) {
                Msg.send(p, "staff.pin.set-first");
            } else if (!ac.pins.isLoggedIn(p.getUuid())) {
                Msg.send(p, "staff.pin.login-first");
            }
        }
        notifyReviews(p);
    }

    /** "X players need review" with a clickable list (section 6.2). */
    public static void notifyReviews(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        List<ReviewCase> open = ac.reviews.open();
        int requests = ac.waitingRoom.pending().size();
        int reports = ac.reports.open().size();
        if (!open.isEmpty()) {
            MutableText t = Msg.prefixed(Msg.tr("review.login", open.size()));
            for (int i = 0; i < Math.min(8, open.size()); i++) {
                ReviewCase c = open.get(i);
                t.append(Text.literal(" "));
                t.append(Msg.button(DetectionListener.color(c.suspicion) + "[" + c.playerName + "]", "/review " + c.id,
                        "Suspicion " + c.suspicion + " - click to review"));
            }
            p.sendMessage(t);
        }
        if (requests > 0) {
            p.sendMessage(Msg.prefixed(Msg.tr("waiting.login", requests)).append(Text.literal(" "))
                    .append(Msg.button("§e[Open]", "/requests", "List join requests")));
        }
        if (reports > 0) {
            p.sendMessage(Msg.prefixed(Msg.tr("report.login", reports)).append(Text.literal(" "))
                    .append(Msg.button("§e[Open]", "/report list", "Open reports")));
        }
    }

    public static void onLeave(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (ac == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String name = p.getGameProfile().name();
        PlayerSession s = Ac.sessionOrNull(p.getUuid());
        TempAdmins.onLeave(p);
        BuilderMode.onLeave(p);
        Teams.forget(p.getUuid());
        BuilderTools.forget(p.getUuid());
        Trades.onDisconnect(p);
        Traders.onDisconnect(p);
        Arenas.onDisconnect(p);
        if (ac.staff.isFrozen(p.getUuid())) {
            ac.misc.frozenLogout.add(p.getUuid());
            Staff.broadcast(Msg.prefixed(Msg.tr("freeze.logout", name)));
            if (Ac.config().staff.freezeLogoutCreatesCase && ac.reviews.openCaseFor(p.getUuid()) == null) {
                ReviewCase c = ac.reviews.create(p.getUuid(), name, s != null && s.bedrock, ac.violations.suspicion(p.getUuid()));
                c.decisions.add(note("Logged out while frozen"));
                Ac.markDirty("reviews");
            }
        }
        if (ac.watchlist.isWatched(p.getUuid())) {
            Staff.broadcast(Msg.prefixed(Msg.tr("watch.leave", name)));
        }
        if (s != null) {
            ac.misc.playtime.merge(p.getUuid(), now - s.joinedAt, Long::sum);
        }
        ac.misc.lastLogout.put(p.getUuid(), Mc.location(p));
        Ac.markDirty("misc");
        ac.logs.join(now, p.getUuid(), name, s == null ? "" : s.ip, "leave");
        ac.claims.clearPresence(p.getUuid());
        ac.pins.logout(p.getUuid());
        ac.evidence.forget(p.getUuid());
        ac.dupeWatch.forget(p.getUuid());
        ac.chatFilter.forget(p.getUuid());
        Movement.forget(p.getUuid());
        ac.sessions.remove(p.getUuid());
    }

    private static ReviewCase.DecisionRecord note(String text) {
        ReviewCase.DecisionRecord r = new ReviewCase.DecisionRecord();
        r.decision = ReviewCase.Decision.WATCH;
        r.by = "system";
        r.at = System.currentTimeMillis();
        r.detail = text;
        return r;
    }

    /** Playtime so far, including the current session. */
    public static long playtime(UUID id) {
        long base = Ac.get().misc.playtime.getOrDefault(id, 0L);
        PlayerSession s = Ac.sessionOrNull(id);
        return base + (s == null ? 0 : System.currentTimeMillis() - s.joinedAt);
    }

    public static String formatPlaytime(UUID id) {
        return Durations.format(playtime(id));
    }
}
