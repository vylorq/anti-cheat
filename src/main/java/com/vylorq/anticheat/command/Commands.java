package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.evidence.EvidenceClip;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.core.perm.AdminPins;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.redstone.LagMachineDetector;
import com.vylorq.anticheat.feature.Discord;
import com.vylorq.anticheat.feature.Extras;
import com.vylorq.anticheat.feature.Jail;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.feature.Tools;
import com.vylorq.anticheat.feature.WaitingRoomFeature;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.gui.StatsMenu;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Registers every command once, with consistent names (section 3 / 29). The admin root is {@code /ac}.
 */
public final class Commands {
    private Commands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        StaffCommands.register(d);
        WorldCommands.register(d);
        registerAc(d);
        // Public "caught" counter (section 28).
        d.register(literal("caught").executes(ctx -> {
            if (!Ac.config().fun.caughtCounter) {
                Msg.err(ctx.getSource(), "general.no-permission");
                return 0;
            }
            Msg.ok(ctx.getSource(), "fun.caught-counter", Ac.get().stats.caught());
            return 1;
        }));
    }

    private static ServerPlayerEntity self(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
        }
        return p;
    }

    private static void registerAc(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("ac").requires(s -> Perms.visible(s, Perm.ALERTS))
                .executes(ctx -> help(ctx))
                .then(literal("help").executes(ctx -> help(ctx)))
                .then(literal("reload").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.RELOAD)) return 0;
                    String err = Ac.get().reload();
                    com.vylorq.anticheat.feature.Xray.reloadLists();
                    Staff.log(ctx.getSource().getPlayer(), "reload", null, null, err == null ? "ok" : err);
                    if (err == null) {
                        Msg.ok(ctx.getSource(), "ac.reloaded");
                    } else {
                        Msg.err(ctx.getSource(), "ac.reload-failed", err);
                    }
                    return 1;
                }))
                .then(literal("alerts").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || !Perms.check(ctx.getSource(), Perm.ALERTS)) return 0;
                    boolean on = com.vylorq.anticheat.feature.Alerts.toggle(p.getUuid());
                    Msg.ok(ctx.getSource(), on ? "ac.alerts-on" : "ac.alerts-off");
                    return 1;
                }))
                .then(literal("stats").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null && Perms.check(ctx.getSource(), Perm.STATS)) {
                        StatsMenu.open(p);
                    }
                    return 1;
                }))
                .then(literal("inspector").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null && Perms.check(ctx.getSource(), Perm.INSPECTOR_TOOL)) {
                        p.getInventory().insertStack(Tools.inspector());
                        Msg.ok(ctx.getSource(), "inspector.given");
                    }
                    return 1;
                }))
                .then(literal("log").executes(ctx -> staffLog(ctx, null))
                        .then(Args.player("player").executes(ctx -> staffLog(ctx, Args.str(ctx, "player")))))
                .then(literal("pin")
                        .then(literal("set").then(Args.word("pin").executes(ctx -> {
                            ServerPlayerEntity p = self(ctx);
                            if (p == null || !Perms.isStaff(p)) return 0;
                            // Changing an existing PIN requires being logged in with the old one.
                            if (Ac.get().pins.hasPin(p.getUuid()) && !Ac.get().pins.isLoggedIn(p.getUuid())) {
                                Msg.err(ctx.getSource(), "staff.pin.login-first");
                                return 0;
                            }
                            AdminPins.Result r = Ac.get().pins.setPin(p.getUuid(), Args.str(ctx, "pin"));
                            Ac.markDirty("pins");
                            if (r == AdminPins.Result.OK) {
                                Staff.log(p, "pin-set", null, null, "");
                                Msg.ok(ctx.getSource(), "staff.pin.set");
                            } else {
                                Msg.err(ctx.getSource(), "staff.pin.format");
                            }
                            return 1;
                        })))
                        .then(literal("clear").then(Args.player("player").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                            UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                            if (id == null) return 0;
                            Ac.get().pins.clearPin(id);
                            Ac.markDirty("pins");
                            Staff.log(ctx.getSource().getPlayer(), "pin-clear", id, Args.nameOf(id, "?"), "");
                            Msg.ok(ctx.getSource(), "staff.pin.cleared", Args.nameOf(id, "?"));
                            return 1;
                        }))))
                .then(literal("admin")
                        .then(literal("add").then(Args.player("player").executes(ctx -> admin(ctx, true))))
                        .then(literal("remove").then(Args.player("player").executes(ctx -> admin(ctx, false)))))
                .then(literal("tempadmin")
                        .then(literal("add").then(Args.player("player")
                                .executes(ctx -> tempAdmin(ctx, false))
                                .then(literal("keepbuilds").executes(ctx -> tempAdmin(ctx, true)))))
                        .then(literal("remove").then(Args.player("player").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                            ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                            if (t == null) return 0;
                            if (!com.vylorq.anticheat.feature.TempAdmins.end(ctx.getSource().getPlayer(), t)) {
                                Msg.err(ctx.getSource(), "tempadmin.not", t.getGameProfile().name());
                                return 0;
                            }
                            return 1;
                        }))))
                .then(literal("tp").then(Args.word("world")
                        .then(CommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                        .then(CommandManager.argument("z", DoubleArgumentType.doubleArg()).executes(ctx -> {
                                            ServerPlayerEntity p = self(ctx);
                                            if (p == null || !Perms.check(ctx.getSource(), Perm.TELEPORT)) return 0;
                                            ServerWorld w = Mc.world(Ac.server(), Args.str(ctx, "world"));
                                            if (w == null) {
                                                Msg.err(ctx.getSource(), "general.unknown-world");
                                                return 0;
                                            }
                                            boolean inv = Ac.get().staff.teleportInvisible(p.getUuid(), Ac.config().staff.teleportInvisibleByDefault);
                                            StaffTools.teleportTo(p, w, new com.vylorq.anticheat.core.util.Vec3(DoubleArgumentType.getDouble(ctx, "x"),
                                                    DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z")), inv, "location");
                                            return 1;
                                        }))))))
                .then(literal("export").then(Args.word("clip").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.REVIEW)) return 0;
                    EvidenceClip c = Ac.get().clips.load(Args.str(ctx, "clip"));
                    if (c == null) {
                        Msg.err(ctx.getSource(), "evidence.not-found");
                        return 0;
                    }
                    Discord.send("evidence", "Evidence " + c.id + " for " + c.playerName, c.summary(15), 0x3498DB);
                    Staff.log(ctx.getSource().getPlayer(), "evidence-export", c.player, c.playerName, c.id);
                    Msg.ok(ctx.getSource(), "evidence.exported", Ac.get().clips.dir().resolve(c.player + "_" + c.id + ".json").toString());
                    for (EvidenceEvent e : c.condensedTimeline()) {
                        if (e.type != EvidenceEvent.Type.MOVE) {
                            ctx.getSource().sendFeedback(() -> Msg.text("§7" + e.describe()), false);
                        }
                    }
                    return 1;
                })))
                .then(literal("farm")
                        .then(literal("add").then(Args.word("name").executes(ctx -> {
                            ServerPlayerEntity p = self(ctx);
                            if (p == null || !Perms.check(ctx.getSource(), Perm.REDSTONE_WHITELIST)) return 0;
                            var s = Ac.session(p);
                            if (s.corner1 == null || s.corner2 == null) {
                                Msg.err(ctx.getSource(), "claim.need-selection");
                                return 0;
                            }
                            LagMachineDetector.Whitelist wl = new LagMachineDetector.Whitelist();
                            wl.name = Args.str(ctx, "name");
                            wl.world = s.cornerWorld;
                            wl.minX = Math.min(s.corner1.getX(), s.corner2.getX());
                            wl.maxX = Math.max(s.corner1.getX(), s.corner2.getX());
                            wl.minY = Math.min(s.corner1.getY(), s.corner2.getY()) - 2;
                            wl.maxY = Math.max(s.corner1.getY(), s.corner2.getY()) + 2;
                            wl.minZ = Math.min(s.corner1.getZ(), s.corner2.getZ());
                            wl.maxZ = Math.max(s.corner1.getZ(), s.corner2.getZ());
                            Ac.get().redstone.data().whitelist.add(wl);
                            Ac.get().redstone.data().disabled.removeIf(k -> {
                                String[] parts = k.split("\\|");
                                return parts[0].equals(wl.world) && wl.contains(wl.world, new com.vylorq.anticheat.core.util.BlockPos3(
                                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3])));
                            });
                            Ac.markDirty("redstone");
                            Staff.log(p, "farm-whitelist", null, wl.name, wl.minX + "," + wl.minZ + " -> " + wl.maxX + "," + wl.maxZ);
                            Msg.ok(ctx.getSource(), "farm.added", wl.name);
                            return 1;
                        })))
                        .then(literal("remove").then(Args.word("name").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.REDSTONE_WHITELIST)) return 0;
                            Ac.get().redstone.data().whitelist.removeIf(w -> w.name.equalsIgnoreCase(Args.str(ctx, "name")));
                            Ac.markDirty("redstone");
                            Msg.ok(ctx.getSource(), "farm.removed", Args.str(ctx, "name"));
                            return 1;
                        })))
                        .then(literal("list").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.REDSTONE_WHITELIST)) return 0;
                            for (var w : Ac.get().redstone.data().whitelist) {
                                ctx.getSource().sendFeedback(() -> Msg.text("§a• " + w.name + " §7" + w.world + " " + w.minX + "," + w.minZ + " → " + w.maxX + "," + w.maxZ), false);
                            }
                            return 1;
                        })))
                .then(literal("event")
                        .then(literal("title").then(Args.rest("text").executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                            String[] parts = Args.str(ctx, "text").split("\\|", 2);
                            Extras.title(parts[0].replace('&', '§'), parts.length > 1 ? parts[1].replace('&', '§') : "");
                            Staff.log(ctx.getSource().getPlayer(), "event-title", null, null, Args.str(ctx, "text"));
                            return 1;
                        })))
                        .then(literal("countdown").then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(ctx -> countdown(ctx, ""))
                                .then(Args.rest("text").executes(ctx -> countdown(ctx, Args.str(ctx, "text"))))))
                        .then(literal("dropparty")
                                .then(literal("items").executes(ctx -> {
                                    ServerPlayerEntity p = self(ctx);
                                    if (p == null || !Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                                    dropItemsMenu(p);
                                    return 1;
                                }))
                                .then(literal("start").then(CommandManager.argument("seconds", IntegerArgumentType.integer(5, 600)).executes(ctx -> {
                                    ServerPlayerEntity p = self(ctx);
                                    if (p == null || !Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                                    if (Extras.dropItemCount() == 0) {
                                        Msg.err(ctx.getSource(), "event.no-items");
                                        return 0;
                                    }
                                    Extras.startDropParty(p, IntegerArgumentType.getInteger(ctx, "seconds"));
                                    Staff.log(p, "event-dropparty", null, null, Extras.dropItemCount() + " stacks");
                                    return 1;
                                })))))
                .then(literal("restart").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.RESTART)) return 0;
                    Staff.log(ctx.getSource().getPlayer(), "restart", null, null, "manual");
                    Extras.restartNow(Ac.config().restarts.backupBeforeRestart);
                    return 1;
                }))
                .then(literal("backup").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.RESTART)) return 0;
                    Msg.ok(ctx.getSource(), "backup.started");
                    var src = ctx.getSource();
                    Extras.backupAsync(names -> Msg.ok(src, "backup.done", names), e -> Msg.err(src, "backup.failed", e.getMessage()));
                    return 1;
                }))
                .then(literal("setowner").requires(s -> s.getPlayer() == null && s.hasPermissionLevel(4))
                        .then(Args.player("player").executes(ctx -> {
                            UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                            if (id == null) return 0;
                            Ac.config().general.ownerUuid = id.toString();
                            Ac.get().configManager.save();
                            Msg.ok(ctx.getSource(), "ac.owner-set", Args.nameOf(id, "?"));
                            return 1;
                        }))));
    }

    private static int help(CommandContext<ServerCommandSource> ctx) {
        for (String line : Msg.tr("ac.help").split("\n")) {
            ctx.getSource().sendFeedback(() -> Msg.text(line), false);
        }
        return 1;
    }

    private static int staffLog(CommandContext<ServerCommandSource> ctx, String player) {
        // Owners see everything; admins only their own actions.
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        boolean owner = p == null || Perms.has(p, Perm.STAFF_LOG);
        String filter = null;
        if (player != null) {
            UUID id = Args.known(ctx.getSource(), player);
            if (id == null) return 0;
            filter = id.toString();
        }
        if (!owner) {
            if (p == null || !Perms.check(ctx.getSource(), Perm.ALERTS)) return 0;
            filter = p.getUuid().toString();
        }
        String f = filter;
        Ac.get().logs.flush();
        try {
            SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm");
            for (var r : Ac.get().db.staffLog(20, f)) {
                ctx.getSource().sendFeedback(() -> Msg.text("§7" + df.format(new Date(r.time())) + " §e" + r.a() + " §f" + r.b()
                        + (r.c() == null || r.c().isEmpty() ? "" : " §7→ §f" + r.c()) + (r.d() == null || r.d().isEmpty() ? "" : " §8" + r.d())), false);
            }
        } catch (Exception e) {
            Msg.err(ctx.getSource(), "general.error");
        }
        return 1;
    }

    private static int tempAdmin(CommandContext<ServerCommandSource> ctx, boolean keepBuilds) {
        if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
        ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
        if (t == null) return 0;
        String name = t.getGameProfile().name();
        if (com.vylorq.anticheat.feature.TempAdmins.isTemp(t.getUuid())) {
            Msg.err(ctx.getSource(), "tempadmin.already", name);
            return 0;
        }
        com.vylorq.anticheat.feature.TempAdmins.grant(ctx.getSource().getPlayer(), t, keepBuilds);
        Msg.ok(ctx.getSource(), keepBuilds ? "tempadmin.added-keep" : "tempadmin.added", name);
        return 1;
    }

    private static int admin(CommandContext<ServerCommandSource> ctx, boolean add) {
        if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
        String name = Args.str(ctx, "player");
        var server = Ac.server();
        var profile = server.getUserCache() == null ? null : server.getUserCache().findByName(name).orElse(null);
        if (profile == null) {
            Msg.err(ctx.getSource(), "general.unknown-player", name);
            return 0;
        }
        if (add) {
            server.getPlayerManager().addToOperators(profile);
        } else {
            server.getPlayerManager().removeFromOperators(profile);
            Ac.get().pins.logout(profile.getId());
        }
        Staff.log(ctx.getSource().getPlayer(), add ? "admin-add" : "admin-remove", profile.getId(), profile.getName(), "");
        Msg.ok(ctx.getSource(), add ? "ac.admin-added" : "ac.admin-removed", profile.getName());
        return 1;
    }

    private static int countdown(CommandContext<ServerCommandSource> ctx, String text) {
        if (!Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
        Extras.countdown(IntegerArgumentType.getInteger(ctx, "seconds"), text.replace('&', '§'));
        Staff.log(ctx.getSource().getPlayer(), "event-countdown", null, null, text);
        return 1;
    }

    private static void dropItemsMenu(ServerPlayerEntity p) {
        Menu m = new Menu("§8Drop party items (close to save)", 6).perm(Perm.EVENTS);
        Set<Integer> all = new HashSet<>();
        for (int i = 0; i < 54; i++) {
            all.add(i);
        }
        SimpleInventory inv = new SimpleInventory(54);
        m.backedBy(inv).allowPlayerInventory(true).editable(all, (pl, slot) -> { });
        m.onClose(pl -> {
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty()) {
                    items.add(inv.getStack(i));
                }
            }
            Extras.setDropItems(items);
            Msg.send(pl, "event.items-saved", items.size());
            Staff.log(pl, "event-dropparty-items", null, null, items.size() + " stacks");
        });
        m.open(p);
    }

    // ---- Command filter (frozen / jailed / waiting room) and logging ----

    /**
     * Called before any player command runs. @return false to block it.
     */
    public static boolean allowCommand(ServerPlayerEntity p, String command) {
        Ac ac = Ac.get();
        if (ac == null) {
            return true;
        }
        String root = command.startsWith("/") ? command.substring(1) : command;
        int sp = root.indexOf(' ');
        root = (sp < 0 ? root : root.substring(0, sp)).toLowerCase();
        int colon = root.indexOf(':');
        if (colon >= 0) {
            root = root.substring(colon + 1);
        }
        long now = System.currentTimeMillis();
        ac.logs.chat(now, p.getUuid(), p.getGameProfile().name(), "command", "/" + command);
        ac.evidence.record(p.getUuid(), EvidenceEvent.Type.COMMAND, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), "/" + command);
        if (WaitingRoomFeature.waiting(p) && !(root.equals("request") || root.equals("login"))) {
            Msg.send(p, "waiting.only-request");
            return false;
        }
        if (StaffTools.isFrozen(p) && !StaffTools.frozenCommandAllowed(root)) {
            Msg.send(p, "freeze.no-commands");
            return false;
        }
        if (Jail.isJailed(p) && !Jail.commandAllowed(root)) {
            Msg.send(p, "jail.no-commands");
            return false;
        }
        return true;
    }

}
