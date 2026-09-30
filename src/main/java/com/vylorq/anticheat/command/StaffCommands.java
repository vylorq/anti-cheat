package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.detect.ExemptList;
import com.vylorq.anticheat.core.detect.Watchlist;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.perm.PermissionPolicy;
import com.vylorq.anticheat.core.staff.Reports;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.feature.BlockLog;
import com.vylorq.anticheat.feature.Punish;
import com.vylorq.anticheat.feature.Redstone;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.gui.InspectMenu;
import com.vylorq.anticheat.gui.ReviewMenu;
import com.vylorq.anticheat.gui.SettingsMenu;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.OptionalLong;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/** Staff commands (sections 6, 12, 13, 14, 15). */
final class StaffCommands {
    private StaffCommands() {
    }

    private static ServerPlayerEntity self(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
        }
        return p;
    }

    static void register(CommandDispatcher<ServerCommandSource> d) {
        // /review [id]
        d.register(literal("review").requires(s -> Perms.visible(s, Perm.REVIEW))
                .executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null && Perms.check(ctx.getSource(), Perm.REVIEW)) {
                        ReviewMenu.queue(p, 0);
                    }
                    return 1;
                })
                .then(CommandManager.argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null && Perms.check(ctx.getSource(), Perm.REVIEW)) {
                        ReviewMenu.openCase(p, LongArgumentType.getLong(ctx, "id"));
                    }
                    return 1;
                })));

        // /inspect <player> [spectate|tp|inv|ender], /inspect leave
        d.register(literal("inspect").requires(s -> Perms.visible(s, Perm.INSPECT))
                .then(literal("leave").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null && !StaffTools.leaveSpectate(p)) {
                        Msg.err(ctx.getSource(), "spectate.not-spectating");
                    }
                    return 1;
                }))
                .then(Args.player("player")
                        .executes(ctx -> inspect(ctx, null, null))
                        .then(literal("spectate").executes(ctx -> inspect(ctx, "spectate", null)))
                        .then(literal("inv").executes(ctx -> inspect(ctx, "inv", null)))
                        .then(literal("ender").executes(ctx -> inspect(ctx, "ender", null)))
                        .then(literal("tp").executes(ctx -> inspect(ctx, "tp", null))
                                .then(literal("visible").executes(ctx -> inspect(ctx, "tp", false)))
                                .then(literal("invisible").executes(ctx -> inspect(ctx, "tp", true))))));

        // /whereis <player>
        d.register(literal("whereis").requires(s -> Perms.visible(s, Perm.INSPECT))
                .then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.INSPECT)) {
                        return 0;
                    }
                    ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (t == null) {
                        return 0;
                    }
                    String n = Msg.q(t.getGameProfile().name());
                    MutableText msg = Msg.prefixed(Msg.tr("whereis.result", t.getGameProfile().name(),
                            Mc.vec(t.getPos()).formatExact(), Mc.worldId(t.getWorld())));
                    msg.append(Text.literal(" ")).append(Msg.button("§b[TP]", "/inspect " + n + " tp", "Teleport (your default)"))
                            .append(Text.literal(" ")).append(Msg.button("§7[visible]", "/inspect " + n + " tp visible", "Teleport visibly"))
                            .append(Text.literal(" ")).append(Msg.button("§8[invisible]", "/inspect " + n + " tp invisible", "Teleport in vanish"));
                    ctx.getSource().sendFeedback(() -> msg, false);
                    Staff.log(ctx.getSource().getPlayer(), "whereis", t.getUuid(), t.getGameProfile().name(), "");
                    return 1;
                })));

        // /watch add|remove|list|history
        d.register(literal("watch").requires(s -> Perms.visible(s, Perm.WATCH))
                .then(literal("add").then(Args.player("player").executes(ctx -> watchAdd(ctx, ""))
                        .then(Args.rest("details").executes(ctx -> watchAdd(ctx, Args.str(ctx, "details"))))))
                .then(literal("remove").then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.WATCH)) return 0;
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    if (Ac.get().watchlist.remove(id, Staff.name(ctx.getSource().getPlayer()))) {
                        Ac.markDirty("watchlist");
                        Staff.log(ctx.getSource().getPlayer(), "watch-remove", id, Args.nameOf(id, "?"), "");
                        Msg.ok(ctx.getSource(), "watch.removed", Args.nameOf(id, "?"));
                    } else {
                        Msg.err(ctx.getSource(), "watch.not-watched");
                    }
                    return 1;
                })))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.WATCH)) return 0;
                    var list = Ac.get().watchlist.list();
                    Msg.ok(ctx.getSource(), "watch.list-header", list.size());
                    for (Watchlist.Entry e : list) {
                        ServerPlayerEntity o = Args.online(e.name);
                        MutableText t = Msg.text("§d• " + e.name + " §7" + e.reason + " §8(" + (e.expiresAt == Durations.PERMANENT ? "permanent"
                                : Durations.formatRemaining(e.expiresAt, System.currentTimeMillis())) + ", by " + e.addedBy + ")" + (o != null ? " §aonline" : ""));
                        t.append(Text.literal(" ")).append(Msg.button("§b[Inspect]", "/inspect " + Msg.q(e.name), "Inspect"));
                        ctx.getSource().sendFeedback(() -> t, false);
                    }
                    return 1;
                }))
                .then(literal("history").then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.WATCH)) return 0;
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    for (Watchlist.HistoryEvent h : Ac.get().watchlist.history(id)) {
                        ctx.getSource().sendFeedback(() -> Msg.text("§7" + f.format(new Date(h.at)) + " §f" + h.action + " §7by " + h.by + ": " + h.reason), false);
                    }
                    return 1;
                }))));

        // /exempt add|remove|list|setbacks (owner only)
        d.register(literal("exempt").requires(s -> Perms.visible(s, Perm.EXEMPT))
                .then(literal("add").then(Args.player("player").executes(ctx -> exempt(ctx, "add"))))
                .then(literal("remove").then(Args.player("player").executes(ctx -> exempt(ctx, "remove"))))
                .then(literal("setbacks").then(Args.player("player")
                        .then(literal("on").executes(ctx -> exempt(ctx, "setbacks-on")))
                        .then(literal("off").executes(ctx -> exempt(ctx, "setbacks-off")))))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.EXEMPT)) return 0;
                    for (ExemptList.Entry e : Ac.get().exempt.list()) {
                        ctx.getSource().sendFeedback(() -> Msg.text("§e• " + e.name + " §7(setbacks " + (e.setbacks ? "on" : "off") + ", by " + e.addedBy + ")"), false);
                    }
                    return 1;
                })));

        // /freeze <player>
        d.register(literal("freeze").requires(s -> Perms.visible(s, Perm.FREEZE))
                .then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.FREEZE)) return 0;
                    ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (t == null || !Punish.allowedOn(ctx.getSource().getPlayer(), t.getUuid())) return 0;
                    boolean on = !StaffTools.isFrozen(t);
                    StaffTools.setFrozen(ctx.getSource().getPlayer(), t, on);
                    Msg.ok(ctx.getSource(), on ? "freeze.done" : "freeze.undone", t.getGameProfile().name());
                    return 1;
                })));

        // Punishments
        d.register(literal("warn").requires(s -> Perms.visible(s, Perm.WARN))
                .then(Args.player("player").executes(ctx -> warn(ctx, null)).then(Args.rest("reason").executes(ctx -> warn(ctx, Args.str(ctx, "reason"))))));
        d.register(literal("mute").requires(s -> Perms.visible(s, Perm.MUTE))
                .then(Args.player("player").then(Args.word("time").executes(ctx -> mute(ctx, null))
                        .then(Args.rest("reason").executes(ctx -> mute(ctx, Args.str(ctx, "reason")))))));
        d.register(literal("unmute").requires(s -> Perms.visible(s, Perm.MUTE))
                .then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MUTE)) return 0;
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    Punish.unmute(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"));
                    Msg.ok(ctx.getSource(), "punish.unmuted", Args.nameOf(id, "?"));
                    return 1;
                })));
        d.register(literal("kick").requires(s -> Perms.visible(s, Perm.KICK))
                .then(Args.player("player").executes(ctx -> kick(ctx, null)).then(Args.rest("reason").executes(ctx -> kick(ctx, Args.str(ctx, "reason"))))));
        d.register(literal("tempban").requires(s -> Perms.visible(s, Perm.BAN))
                .then(Args.player("player").then(Args.word("time").executes(ctx -> ban(ctx, Args.str(ctx, "time"), null))
                        .then(Args.rest("reason").executes(ctx -> ban(ctx, Args.str(ctx, "time"), Args.str(ctx, "reason")))))));
        d.register(literal("ban").requires(s -> Perms.visible(s, Perm.BAN))
                .then(Args.player("player").executes(ctx -> ban(ctx, "permanent", null))
                        .then(Args.rest("reason").executes(ctx -> ban(ctx, "permanent", Args.str(ctx, "reason"))))));
        d.register(literal("unban").requires(s -> Perms.visible(s, Perm.BAN))
                .then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.BAN)) return 0;
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    Punish.unban(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"));
                    Msg.ok(ctx.getSource(), "punish.unbanned", Args.nameOf(id, "?"));
                    return 1;
                })));

        // /report <player> <reason>, /report list, /report close <id>
        d.register(literal("report")
                .then(literal("list").requires(s -> Perms.visible(s, Perm.REVIEW)).executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.REVIEW)) return 0;
                    SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm");
                    for (Reports.Report r : Ac.get().reports.open()) {
                        MutableText t = Msg.text("§e#" + r.id + " §7" + f.format(new Date(r.at)) + " §f" + r.reporterName + " §7→ §c" + r.suspectName + "§7: " + r.reason + " ");
                        if (r.suspectPos != null) {
                            t.append(Msg.button("§b[TP suspect]", "/ac tp " + r.suspectWorld + " " + (int) r.suspectPos.x() + " " + (int) r.suspectPos.y() + " " + (int) r.suspectPos.z(), "Where the suspect was"));
                        }
                        if (r.reporterPos != null) {
                            t.append(Text.literal(" ")).append(Msg.button("§3[TP reporter]", "/ac tp " + r.reporterWorld + " " + (int) r.reporterPos.x() + " " + (int) r.reporterPos.y() + " " + (int) r.reporterPos.z(), "Where the reporter was"));
                        }
                        t.append(Text.literal(" ")).append(Msg.button("§a[Close]", "/report close " + r.id, "Mark handled"));
                        ctx.getSource().sendFeedback(() -> t, false);
                    }
                    return 1;
                }))
                .then(literal("close").requires(s -> Perms.visible(s, Perm.REVIEW)).then(CommandManager.argument("id", LongArgumentType.longArg(1)).executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.REVIEW)) return 0;
                    long id = LongArgumentType.getLong(ctx, "id");
                    if (Ac.get().reports.close(id, Staff.name(ctx.getSource().getPlayer()))) {
                        Ac.markDirty("reports");
                        Staff.log(ctx.getSource().getPlayer(), "report-close", null, "#" + id, "");
                        Msg.ok(ctx.getSource(), "report.closed", id);
                    }
                    return 1;
                })))
                .then(Args.player("player").then(Args.rest("reason").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    UUID suspect = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (suspect == null) return 0;
                    Reports.Report r = new Reports.Report();
                    r.reporter = p.getUuid();
                    r.reporterName = p.getGameProfile().name();
                    r.suspect = suspect;
                    r.suspectName = Args.nameOf(suspect, "?");
                    r.reason = Args.str(ctx, "reason");
                    r.reporterWorld = Mc.worldId(p.getWorld());
                    r.reporterPos = Mc.vec(p.getPos());
                    ServerPlayerEntity s = Ac.server().getPlayerManager().getPlayer(suspect);
                    if (s != null) {
                        r.suspectWorld = Mc.worldId(s.getWorld());
                        r.suspectPos = Mc.vec(s.getPos());
                    }
                    if (Ac.get().reports.add(r, System.currentTimeMillis(), 60_000) == null) {
                        Msg.err(ctx.getSource(), "report.cooldown");
                        return 0;
                    }
                    Ac.markDirty("reports");
                    Msg.ok(ctx.getSource(), "report.sent");
                    Staff.broadcast(Msg.prefixed(Msg.tr("report.new", r.reporterName, r.suspectName, r.reason)).append(Text.literal(" "))
                            .append(Msg.button("§e[Reports]", "/report list", "Open reports")));
                    return 1;
                }))));

        // /vanish, /sc, /login, /maintenance
        d.register(literal("vanish").requires(s -> Perms.visible(s, Perm.VANISH)).executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p == null || !Perms.check(ctx.getSource(), Perm.VANISH)) return 0;
            boolean on = !StaffTools.isVanished(p.getUuid());
            StaffTools.setVanish(p, on);
            Staff.log(p, on ? "vanish-on" : "vanish-off", null, null, "");
            return 1;
        }));
        d.register(literal("sc").requires(s -> Perms.visible(s, Perm.STAFF_CHAT))
                .executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || !Perms.check(ctx.getSource(), Perm.STAFF_CHAT)) return 0;
                    boolean on = Ac.get().staff.toggleStaffChat(p.getUuid());
                    Ac.markDirty("staff");
                    Msg.ok(ctx.getSource(), on ? "staffchat.on" : "staffchat.off");
                    return 1;
                })
                .then(Args.rest("message").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.STAFF_CHAT)) return 0;
                    StaffTools.staffChat(ctx.getSource().getPlayer(), Args.str(ctx, "message"));
                    return 1;
                })));
        d.register(literal("login").then(Args.word("pin").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p == null) return 0;
            if (!Perms.isStaff(p)) {
                Msg.err(ctx.getSource(), "general.no-permission");
                return 0;
            }
            StaffTools.login(p, Args.str(ctx, "pin"));
            return 1;
        })));
        d.register(literal("maintenance").requires(s -> Perms.visible(s, Perm.MAINTENANCE))
                .then(literal("on").executes(ctx -> maintenance(ctx, true)))
                .then(literal("off").executes(ctx -> maintenance(ctx, false))));

        // /rollback <player> <time> [radius], /restore
        for (String cmd : new String[]{"rollback", "restore"}) {
            boolean restore = cmd.equals("restore");
            d.register(literal(cmd).requires(s -> Perms.visible(s, Perm.ROLLBACK))
                    .then(Args.player("player").then(Args.word("time").executes(ctx -> rollback(ctx, null, restore))
                            .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 500))
                                    .executes(ctx -> rollback(ctx, IntegerArgumentType.getInteger(ctx, "radius"), restore))))));
        }

        // /deaths <player>
        d.register(literal("deaths").requires(s -> Perms.visible(s, Perm.DEATHS))
                .then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || !Perms.check(ctx.getSource(), Perm.DEATHS)) return 0;
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    InspectMenu.deaths(p, id, 0);
                    return 1;
                })));

        // /lag
        d.register(literal("lag").requires(s -> Perms.visible(s, Perm.LAG)).executes(ctx -> {
            if (!Perms.check(ctx.getSource(), Perm.LAG)) return 0;
            Msg.ok(ctx.getSource(), "lag.header", String.format("%.1f", com.vylorq.anticheat.util.Tps.tps()),
                    String.format("%.1f", com.vylorq.anticheat.util.Tps.mspt()));
            for (String row : Redstone.lagReport(8)) {
                String[] p = row.split(" ");
                MutableText t = Msg.text("§7" + p[0].replace("minecraft:", "") + " §f" + p[1] + ", " + p[2] + " §7entities §f" + p[3]
                        + " §7block entities §f" + p[4] + " ");
                t.append(Msg.button("§b[TP]", "/ac tp " + p[0] + " " + p[1] + " 100 " + p[2], "Teleport"));
                ctx.getSource().sendFeedback(() -> t, false);
            }
            return 1;
        }));

        // /settings
        d.register(literal("settings").requires(s -> Perms.visible(s, Perm.SETTINGS)).executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null && Perms.check(ctx.getSource(), Perm.SETTINGS)) {
                SettingsMenu.open(p);
            }
            return 1;
        }));
    }

    private static int inspect(CommandContext<ServerCommandSource> ctx, String sub, Boolean invisible) {
        ServerPlayerEntity p = self(ctx);
        if (p == null || !Perms.check(ctx.getSource(), Perm.INSPECT)) {
            return 0;
        }
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (id == null) {
            return 0;
        }
        ServerPlayerEntity t = Ac.server().getPlayerManager().getPlayer(id);
        if (sub == null) {
            InspectMenu.open(p, id);
        } else if (sub.equals("inv")) {
            InspectMenu.inventory(p, id, false);
        } else if (sub.equals("ender")) {
            InspectMenu.ender(p, id, false);
        } else if (t == null) {
            Msg.err(ctx.getSource(), "general.not-online", Args.str(ctx, "player"));
        } else if (sub.equals("spectate")) {
            if (Perms.check(ctx.getSource(), Perm.SPECTATE)) {
                StaffTools.spectate(p, t);
            }
        } else if (sub.equals("tp") && Perms.check(ctx.getSource(), Perm.TELEPORT)) {
            boolean inv = invisible != null ? invisible
                    : Ac.get().staff.teleportInvisible(p.getUuid(), Ac.config().staff.teleportInvisibleByDefault);
            StaffTools.teleportTo(p, t.getServerWorld(), Mc.vec(t.getPos()), inv, t.getGameProfile().name());
        }
        return 1;
    }

    private static int watchAdd(CommandContext<ServerCommandSource> ctx, String details) {
        if (!Perms.check(ctx.getSource(), Perm.WATCH)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (id == null) return 0;
        String reason = details.trim();
        long time = Durations.PERMANENT;
        String[] parts = reason.split("\\s+");
        if (parts.length > 0 && Durations.isDuration(parts[parts.length - 1])) {
            time = Durations.parse(parts[parts.length - 1]).getAsLong();
            reason = reason.substring(0, reason.lastIndexOf(parts[parts.length - 1])).trim();
        }
        String name = Args.nameOf(id, Args.str(ctx, "player"));
        Ac.get().watchlist.add(id, name, reason, Staff.name(ctx.getSource().getPlayer()), time, false);
        Ac.markDirty("watchlist");
        Staff.log(ctx.getSource().getPlayer(), "watch-add", id, name, reason + " " + Durations.format(time));
        Msg.ok(ctx.getSource(), "watch.added", name, Durations.format(time));
        return 1;
    }

    private static int exempt(CommandContext<ServerCommandSource> ctx, String op) {
        ServerPlayerEntity actor = ctx.getSource().getPlayer();
        if (actor != null && !PermissionPolicy.canEditExempt(Perms.effectiveRole(actor))) {
            Perms.unauthorized(actor, "exempt list");
            return 0;
        }
        if (actor == null && !ctx.getSource().hasPermissionLevel(4)) {
            return 0;
        }
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (id == null) return 0;
        String name = Args.nameOf(id, "?");
        ExemptList ex = Ac.get().exempt;
        boolean ok = switch (op) {
            case "add" -> ex.add(id, name, Staff.name(actor), System.currentTimeMillis());
            case "remove" -> ex.remove(id);
            case "setbacks-on" -> ex.setSetbacks(id, true);
            default -> ex.setSetbacks(id, false);
        };
        Ac.markDirty("exempt");
        Staff.log(actor, "exempt-" + op, id, name, "");
        if (ok) {
            Msg.ok(ctx.getSource(), "exempt.done", op, name);
        } else {
            Msg.err(ctx.getSource(), "exempt.nothing");
        }
        return 1;
    }

    private static int warn(CommandContext<ServerCommandSource> ctx, String reason) {
        if (!Perms.check(ctx.getSource(), Perm.WARN)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (id == null) return 0;
        Punish.warn(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"), reason);
        Msg.ok(ctx.getSource(), "punish.warn-done", Args.nameOf(id, "?"));
        return 1;
    }

    private static int mute(CommandContext<ServerCommandSource> ctx, String reason) {
        if (!Perms.check(ctx.getSource(), Perm.MUTE)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        OptionalLong d = Args.duration(ctx.getSource(), Args.str(ctx, "time"));
        if (id == null || d.isEmpty()) return 0;
        Punish.mute(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"), d.getAsLong(), reason);
        Msg.ok(ctx.getSource(), "punish.mute-done", Args.nameOf(id, "?"), Durations.format(d.getAsLong()));
        return 1;
    }

    private static int kick(CommandContext<ServerCommandSource> ctx, String reason) {
        if (!Perms.check(ctx.getSource(), Perm.KICK)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (id == null) return 0;
        Punish.kick(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"), reason);
        Msg.ok(ctx.getSource(), "punish.kick-done", Args.nameOf(id, "?"));
        return 1;
    }

    private static int ban(CommandContext<ServerCommandSource> ctx, String time, String reason) {
        if (!Perms.check(ctx.getSource(), Perm.BAN)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        OptionalLong d = Args.duration(ctx.getSource(), time);
        if (id == null || d.isEmpty()) return 0;
        boolean cheating = reason != null && (reason.toLowerCase().contains("cheat") || reason.toLowerCase().contains("hack")
                || reason.equalsIgnoreCase("hacking") || reason.equalsIgnoreCase("xray"));
        Punish.ban(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"), d.getAsLong(), reason, cheating);
        Msg.ok(ctx.getSource(), "punish.ban-done", Args.nameOf(id, "?"), Durations.format(d.getAsLong()));
        return 1;
    }

    private static int maintenance(CommandContext<ServerCommandSource> ctx, boolean on) {
        if (!Perms.check(ctx.getSource(), Perm.MAINTENANCE)) return 0;
        Ac.get().staff.setMaintenance(on);
        Ac.markDirty("staff");
        Staff.log(ctx.getSource().getPlayer(), "maintenance", null, null, on ? "on" : "off");
        Msg.ok(ctx.getSource(), on ? "maintenance.on" : "maintenance.off");
        if (on) {
            for (ServerPlayerEntity p : new java.util.ArrayList<>(Ac.server().getPlayerManager().getPlayerList())) {
                if (!Perms.isStaff(p)) {
                    p.networkHandler.disconnect(Text.literal(Msg.tr("maintenance.kick")));
                }
            }
        }
        return 1;
    }

    private static int rollback(CommandContext<ServerCommandSource> ctx, Integer radius, boolean restore) {
        if (!Perms.check(ctx.getSource(), Perm.ROLLBACK)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        OptionalLong d = Args.duration(ctx.getSource(), Args.str(ctx, "time"));
        if (id == null || d.isEmpty() || d.getAsLong() == Durations.PERMANENT) {
            return 0;
        }
        Msg.ok(ctx.getSource(), "rollback.working");
        BlockLog.rollback(ctx.getSource().getPlayer(), id, Args.nameOf(id, "?"), d.getAsLong(), radius, restore);
        return 1;
    }

}
