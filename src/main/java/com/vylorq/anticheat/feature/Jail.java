package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.jail.JailManager;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Jail (section 21). */
public final class Jail {
    private static final double RADIUS = 6.0;

    private Jail() {
    }

    public static boolean isJailed(ServerPlayerEntity p) {
        return Ac.get().jail.isJailed(p.getUuid());
    }

    /** Sends a jailed player to their cell. */
    public static void toCell(ServerPlayerEntity p) {
        JailManager.Record r = Ac.get().jail.get(p.getUuid());
        if (r == null) {
            return;
        }
        Location cell = Ac.get().jail.cell(r.cell);
        if (cell != null) {
            Mc.teleport(p, Ac.server(), cell);
        }
    }

    public static JailManager.Record jail(ServerPlayerEntity target, String reason, long duration, String by) {
        Trades.cancelFor(target, com.vylorq.anticheat.core.trade.SecureTrade.CancelReason.JAILED);
        JailManager.Record r = Ac.get().jail.jail(target.getUuid(), target.getGameProfile().name(), reason, by, duration,
                Mc.location(target), false);
        Ac.markDirty("jail");
        toCell(target);
        Mc.title(target, "§7" + Msg.trFor(target, "jail.title"), "§f" + reason, 10, 60, 20);
        if (Ac.config().jail.announce) {
            Ac.server().getPlayerManager().broadcast(Text.literal(Msg.tr("jail.announce", target.getGameProfile().name(), reason)), false);
        }
        Discord.send("jail", "Jailed: " + target.getGameProfile().name(), reason + " (" + Durations.format(duration) + ")", 0x7F8C8D);
        return r;
    }

    public static void release(UUID id, boolean announce) {
        JailManager.Record r = Ac.get().jail.release(id);
        Ac.markDirty("jail");
        if (r == null) {
            return;
        }
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
        if (p != null) {
            if (r.returnTo != null && Mc.teleport(p, Ac.server(), r.returnTo)) {
                // returned to where they were jailed
            } else {
                var spawn = Mc.worldSpawn(Ac.server());
                Mc.teleport(p, Ac.server().getOverworld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
            }
            com.vylorq.anticheat.ui.BossBars.hide(p, com.vylorq.anticheat.ui.BossBars.Kind.JAIL);
            Mc.title(p, "§a" + Msg.trFor(p, "jail.released-title"), "", 10, 50, 20);
            String msg = Ac.config().jail.releaseMessage;
            if (announce && msg != null && !msg.isBlank()) {
                p.sendMessage(Msg.prefixed(msg));
            }
        }
    }

    /** Keeps jailed players in their cell. @return true when the move was blocked. */
    public static boolean confine(ServerPlayerEntity p, Vec3 to) {
        JailManager.Record r = Ac.get().jail.get(p.getUuid());
        if (r == null) {
            return false;
        }
        Location cell = Ac.get().jail.cell(r.cell);
        if (cell == null) {
            return false;
        }
        if (!cell.world().equals(Mc.worldId(p.getEntityWorld())) || cell.vec().distance(to) > RADIUS) {
            Mc.teleport(p, Ac.server(), cell);
            return true;
        }
        return false;
    }

    /** Every second. */
    public static void tick() {
        Ac ac = Ac.get();
        Set<UUID> online = new HashSet<>();
        for (ServerPlayerEntity p : ac.server.getPlayerManager().getPlayerList()) {
            online.add(p.getUuid());
        }
        boolean onlineOnly = Ac.config().jail.onlineTimeOnly;
        for (JailManager.Record r : ac.jail.tick(online, 1000, onlineOnly)) {
            if (online.contains(r.player)) {
                release(r.player, true);
            }
        }
        for (JailManager.Record r : ac.jail.list()) {
            ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(r.player);
            if (p != null) {
                long left = ac.jail.remaining(r.player, onlineOnly);
                long total = r.expiresAt == Durations.PERMANENT || r.expiresAt <= r.jailedAt ? 0 : r.expiresAt - r.jailedAt;
                float progress = total > 0 ? Math.min(1f, (float) left / total) : 1f;
                String text = com.vylorq.anticheat.ui.Viewer.with(p, () -> Msg.tr("jail.bar", Durations.format(left)));
                if (!com.vylorq.anticheat.ui.BossBars.show(p, com.vylorq.anticheat.ui.BossBars.Kind.JAIL,
                        com.vylorq.anticheat.ui.Theme.c(text, 0xC8C8C8), progress, 3)) {
                    Msg.actionBar(p, Msg.trFor(p, "jail.time-left", Durations.format(left), r.reason));
                }
            }
        }
        Ac.markDirty("jail");
    }

    public static void onJoin(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        JailManager.Record r = ac.jail.get(p.getUuid());
        if (r == null) {
            return;
        }
        if (!Ac.config().jail.onlineTimeOnly && ac.jail.remaining(p.getUuid(), false) <= 0) {
            release(p.getUuid(), true);
            return;
        }
        toCell(p);
    }

    /** Commands jailed players may use. */
    public static boolean commandAllowed(String root) {
        return switch (root) {
            case "msg", "tell", "w", "r", "reply", "me", "sc", "login", "report", "rules", "help" -> true;
            default -> false;
        };
    }
}
