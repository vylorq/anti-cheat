package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
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

    /**
     * A staff alert (34.10): "[Staff] ⚠ text [Review] [Inspect] [Teleport]", built for each admin in their own
     * language, with the player's name clickable to inspect them.
     *
     * @param body    builds the alert text for one viewer (runs with that viewer's language)
     * @param player  the player the alert is about (name becomes clickable), or null
     * @param caseId  adds a [Review] button when set
     */
    public static void alert(java.util.function.Predicate<ServerPlayerEntity> who, java.util.function.Supplier<String> body,
                             String player, Long caseId, boolean sound) {
        String logged = null;
        for (ServerPlayerEntity p : online()) {
            if (!who.test(p)) {
                continue;
            }
            net.minecraft.text.MutableText t = com.vylorq.anticheat.ui.Viewer.with(p, () -> alertText(body.get(), player, caseId));
            p.sendMessage(t);
            if (sound) {
                com.vylorq.anticheat.ui.Sounds.play(p, com.vylorq.anticheat.ui.Sounds.Ui.ALERT);
            }
            if (logged == null) {
                logged = t.getString();
            }
        }
        Ac.LOG.info("[staff] {}", logged != null ? logged : com.vylorq.anticheat.ui.Viewer.with(null, body));
    }

    static net.minecraft.text.MutableText alertText(String body, String player, Long caseId) {
        net.minecraft.text.MutableText t = com.vylorq.anticheat.ui.Theme.c("[" + Msg.tr("alert.staff-tag") + "] ", com.vylorq.anticheat.ui.Theme.VIOLET)
                .append(com.vylorq.anticheat.ui.Theme.c(com.vylorq.anticheat.ui.Theme.Sym.WARN.sp(), com.vylorq.anticheat.ui.Theme.GOLD));
        String text = body.replaceAll("^(§.)+", "");
        if (player != null && text.contains(player)) {
            int i = text.indexOf(player);
            t.append(com.vylorq.anticheat.ui.Theme.c(text.substring(0, i), com.vylorq.anticheat.ui.Theme.GOLD));
            t.append(Msg.button("", "/inspect " + Msg.q(player), Msg.tr("alert.inspect-hover", player))
                    .append(com.vylorq.anticheat.ui.Theme.c(player, com.vylorq.anticheat.ui.Theme.WHITE))
                    .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/inspect " + Msg.q(player)))
                            .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal(Msg.tr("alert.inspect-hover", player))))));
            t.append(com.vylorq.anticheat.ui.Theme.c(text.substring(i + player.length()), com.vylorq.anticheat.ui.Theme.GOLD));
        } else {
            t.append(com.vylorq.anticheat.ui.Theme.c(text, com.vylorq.anticheat.ui.Theme.GOLD));
        }
        if (caseId != null) {
            t.append(Text.literal(" ")).append(button("alert.btn.review", "/review " + caseId, Msg.tr("alert.review-hover"), com.vylorq.anticheat.ui.Theme.RED));
        }
        if (player != null) {
            t.append(Text.literal(" ")).append(button("alert.btn.inspect", "/inspect " + Msg.q(player), Msg.tr("alert.inspect-hover", player),
                    com.vylorq.anticheat.ui.Theme.AQUA));
            t.append(Text.literal(" ")).append(button("alert.btn.teleport", "/inspect " + Msg.q(player) + " tp", Msg.tr("alert.tp-hover", player),
                    com.vylorq.anticheat.ui.Theme.GREEN));
        }
        return t;
    }

    private static net.minecraft.text.MutableText button(String key, String command, String hover, int color) {
        return com.vylorq.anticheat.ui.Theme.c("[" + Msg.tr(key) + "]", color)
                .styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand(command))
                        .withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal(hover))));
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
                actor == null ? "Console" : actor.getGameProfile().name(), action, target, targetName, detail);
    }

    public static void log(String actorName, UUID actor, String action, UUID target, String targetName, String detail) {
        Ac.get().logs.staff(System.currentTimeMillis(), actor, actorName, action, target, targetName, detail);
    }

    public static String name(ServerPlayerEntity p) {
        return p == null ? "Console" : p.getGameProfile().name();
    }
}
