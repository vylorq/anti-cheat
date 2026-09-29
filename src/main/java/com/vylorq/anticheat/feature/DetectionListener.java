package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.DetectionEngine;
import com.vylorq.anticheat.core.detect.ViolationTracker;
import com.vylorq.anticheat.core.evidence.EvidenceClip;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.UUID;

/** Carries out what the detection engine decides: logs, admin alerts, the generic warning, review notices. */
public final class DetectionListener implements DetectionEngine.Listener {
    public static String color(int suspicion) {
        return switch (ViolationTracker.level(suspicion)) {
            case GREEN -> "§a";
            case YELLOW -> "§e";
            case RED -> "§c";
        };
    }

    @Override
    public void onFlag(DetectionEngine.Flag f) {
        Ac.get().logs.flag(f.time(), f.player(), f.name(), f.check().id(), f.points(), f.suspicion(), f.detail());
        Ac.markDirty("stats");
    }

    @Override
    public void onAlert(DetectionEngine.Flag f, boolean instant) {
        String name = f.name();
        MutableText t = Msg.prefixed(Msg.tr("alert.flag", color(f.suspicion()) + name, f.check().displayName(),
                f.suspicion(), f.detail() == null ? "" : f.detail()) + (f.watched() ? " §d[W]" : ""));
        t.append(Text.literal(" "));
        t.append(Msg.button("§b[Inspect]", "/inspect " + Msg.q(name), "Open /inspect"));
        t.append(Text.literal(" "));
        t.append(Msg.button("§e[Spectate]", "/inspect " + Msg.q(name) + " spectate", "Spectate unseen"));
        for (ServerPlayerEntity p : Staff.online()) {
            if (Alerts.enabled(p.getUuid())) {
                p.sendMessage(t);
            }
        }
    }

    @Override
    public void onWarn(UUID player) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(player);
        if (p != null) {
            p.sendMessage(Text.literal(Msg.tr("warning.suspicious")));
            Ac.get().logs.activity(System.currentTimeMillis(), player, "warning", "generic suspicious-activity warning");
        }
        Ac.markDirty("reviews");
    }

    @Override
    public void onCaseOpened(ReviewCase c) {
        Ac.markDirty("reviews");
        MutableText t = Msg.prefixed(Msg.tr("review.opened", c.playerName, c.suspicion));
        t.append(Text.literal(" ")).append(Msg.button("§b[Review]", "/review " + c.id, "Open this case"));
        Staff.broadcast(t);
        Discord.send("review", "Review case #" + c.id + ": " + c.playerName,
                "Suspicion " + c.suspicion + "\nTop checks: " + String.join(", ", c.topChecks(3))
                        + (c.bedrock ? "\nBedrock player" : ""), 0xE67E22);
    }

    @Override
    public void onCaseUpdated(ReviewCase c) {
        Ac.markDirty("reviews");
    }

    @Override
    public void onAutoWatch(UUID player, String name, String reason) {
        Ac.markDirty("watchlist");
        Staff.log("System", null, "watch-auto", player, name, reason);
        Staff.broadcast(Msg.prefixed(Msg.tr("watch.auto", name, reason)));
    }

    @Override
    public void onClip(EvidenceClip clip) {
        try {
            Ac.get().clips.save(clip);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save evidence clip: {}", e.getMessage());
        }
    }
}
