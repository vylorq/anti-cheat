package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.arena.Arena;
import com.vylorq.anticheat.core.arena.ArenaManager;
import com.vylorq.anticheat.core.arena.Kit;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.jail.JailManager;
import com.vylorq.anticheat.core.lobby.Lobby;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.core.waiting.WaitingRoom;
import com.vylorq.anticheat.feature.Arenas;
import com.vylorq.anticheat.feature.Jail;
import com.vylorq.anticheat.feature.LobbyFeature;
import com.vylorq.anticheat.feature.Punish;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.feature.StaffTools;
import com.vylorq.anticheat.feature.Tools;
import com.vylorq.anticheat.feature.Trades;
import com.vylorq.anticheat.feature.Traders;
import com.vylorq.anticheat.feature.WaitingRoomFeature;
import com.vylorq.anticheat.gui.ArenaMenu;
import com.vylorq.anticheat.gui.ClaimMenu;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/** World and gameplay commands (sections 17-25). */
final class WorldCommands {
    private WorldCommands() {
    }

    private static ServerPlayerEntity self(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
        }
        return p;
    }

    private static ServerPlayerEntity staff(CommandContext<ServerCommandSource> ctx, Perm perm) {
        ServerPlayerEntity p = self(ctx);
        if (p == null || !Perms.check(ctx.getSource(), perm)) {
            return null;
        }
        return p;
    }

    /** The Claim Stick selection, or null with a hint. */
    private static Area selection(ServerPlayerEntity p, boolean fullHeight) {
        PlayerSession s = Ac.session(p);
        if (s.corner1 == null || s.corner2 == null || !Mc.worldId(p.getEntityWorld()).equals(s.cornerWorld)) {
            Msg.send(p, "claim.need-selection");
            return null;
        }
        ServerWorld w = p.getEntityWorld();
        int minY = fullHeight ? w.getBottomY() : Math.min(s.corner1.getY(), s.corner2.getY());
        int maxY = fullHeight ? w.getTopYInclusive() : Math.max(s.corner1.getY(), s.corner2.getY());
        return new Area(s.cornerWorld, s.corner1.getX(), minY, s.corner1.getZ(), s.corner2.getX(), maxY, s.corner2.getZ());
    }

    static void register(CommandDispatcher<ServerCommandSource> d) {
        registerClaims(d);
        registerBarriers(d);
        registerLobby(d);
        registerJail(d);
        registerWaiting(d);
        registerArenas(d);
        registerTraders(d);
        registerEnd(d);
        registerLockedBox(d);
        registerEvents(d);
        registerStats(d);
        registerBuilder(d);
        registerBuild(d);
    }

    // ---- Builder mode ----

    /** A current or past builder by name. */
    static java.util.UUID builderId(CommandContext<ServerCommandSource> ctx, String name) {
        for (var e : Ac.get().misc.builderNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) {
                return e.getKey();
            }
        }
        ServerPlayerEntity online = Ac.server().getPlayerManager().getPlayer(name);
        if (online != null && Ac.get().misc.builderNames.containsKey(online.getUuid())) {
            return online.getUuid();
        }
        Msg.err(ctx.getSource(), "builder.unknown", name);
        return null;
    }

    private static int ownerRun(CommandContext<ServerCommandSource> ctx, java.util.function.Consumer<java.util.UUID> then) {
        if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) {
            return 0;
        }
        java.util.UUID id = builderId(ctx, Args.str(ctx, "player"));
        if (id == null) {
            return 0;
        }
        then.accept(id);
        return 1;
    }

    private static void registerBuilder(CommandDispatcher<ServerCommandSource> d) {
        var add = Args.player("player").executes(ctx -> addBuilder(ctx, null, false, false))
                .then(literal("anywhere").executes(ctx -> addBuilder(ctx, null, true, false)))
                .then(literal("live").executes(ctx -> addBuilder(ctx, null, false, true)))
                .then(Args.word("time").executes(ctx -> addBuilder(ctx, Args.str(ctx, "time"), false, false))
                        .then(literal("anywhere").executes(ctx -> addBuilder(ctx, Args.str(ctx, "time"), true, false)))
                        .then(literal("live").executes(ctx -> addBuilder(ctx, Args.str(ctx, "time"), false, true))));
        d.register(literal("builder").requires(s -> Perms.visible(s, Perm.MANAGE_ADMINS))
                .executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.MANAGE_ADMINS);
                    if (p != null) {
                        com.vylorq.anticheat.gui.BuilderAdminMenu.open(p);
                    }
                    return 1;
                })
                .then(literal("add").then(add))
                .then(literal("remove").then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (t == null) return 0;
                    if (!com.vylorq.anticheat.feature.BuilderMode.end(ctx.getSource().getPlayer(), t)) {
                        Msg.err(ctx.getSource(), "builder.not", t.getGameProfile().name());
                        return 0;
                    }
                    Msg.ok(ctx.getSource(), "builder.removed", t.getGameProfile().name());
                    return 1;
                })))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    var all = Ac.get().misc.builders.values();
                    if (all.isEmpty()) {
                        Msg.ok(ctx.getSource(), "builder.none");
                    }
                    for (var b : all) {
                        String left = b.until > 0 ? Durations.format(Math.max(0, b.until - System.currentTimeMillis())) : "-";
                        Msg.ok(ctx.getSource(), "builder.entry", b.name, b.anywhere ? "anywhere" : b.draft ? "draft" : "lobby", left);
                    }
                    for (var e : com.vylorq.anticheat.feature.BuilderDrafts.all().values()) {
                        Msg.ok(ctx.getSource(), "draft.entry", e.name, e.submitted ? Msg.tr("draft.waiting") : Msg.tr("draft.in-progress"));
                    }
                    return 1;
                }))
                .then(literal("menu").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.MANAGE_ADMINS);
                    if (p != null) {
                        com.vylorq.anticheat.gui.BuilderAdminMenu.open(p);
                    }
                    return 1;
                }))
                .then(literal("log").then(Args.player("player")
                        .executes(ctx -> ownerRun(ctx, id -> com.vylorq.anticheat.gui.BuilderAdminMenu.sendSummary(ctx.getSource(), id, 0)))
                        .then(literal("recent").executes(ctx -> ownerRun(ctx, id ->
                                com.vylorq.anticheat.gui.BuilderAdminMenu.sendRecent(ctx.getSource(), id, 20))))
                        .then(Args.word("time").executes(ctx -> ownerRun(ctx, id -> {
                            OptionalLong dur = Args.duration(ctx.getSource(), Args.str(ctx, "time"));
                            if (dur.isPresent()) {
                                com.vylorq.anticheat.gui.BuilderAdminMenu.sendSummary(ctx.getSource(), id, System.currentTimeMillis() - dur.getAsLong());
                            }
                        })))))
                .then(literal("undo").then(Args.player("player").then(Args.word("time").executes(ctx -> ownerRun(ctx, id -> {
                    OptionalLong dur = Args.duration(ctx.getSource(), Args.str(ctx, "time"));
                    if (dur.isPresent()) {
                        int n = com.vylorq.anticheat.feature.BuilderLog.undoSince(ctx.getSource().getPlayer(), id,
                                System.currentTimeMillis() - dur.getAsLong());
                        Msg.ok(ctx.getSource(), "builder.undoing", n);
                    }
                })))))
                .then(literal("watch").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.MANAGE_ADMINS);
                    ServerPlayerEntity t = p == null ? null : Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (t == null) return 0;
                    com.vylorq.anticheat.gui.BuilderAdminMenu.watch(p, t);
                    return 1;
                })))
                .then(literal("review").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.MANAGE_ADMINS);
                    java.util.UUID id = p == null ? null : builderId(ctx, Args.str(ctx, "player"));
                    if (id == null) return 0;
                    if (!com.vylorq.anticheat.feature.BuilderDrafts.review(p, id)) {
                        Msg.err(ctx.getSource(), "draft.none-for", Args.str(ctx, "player"));
                    }
                    return 1;
                })))
                .then(literal("approve").then(Args.player("player").executes(ctx -> ownerRun(ctx, id -> {
                    if (com.vylorq.anticheat.feature.BuilderDrafts.approve(ctx.getSource().getPlayer(), id)) {
                        Msg.ok(ctx.getSource(), "draft.approving", Args.str(ctx, "player"));
                    } else {
                        Msg.err(ctx.getSource(), "draft.none-for", Args.str(ctx, "player"));
                    }
                }))))
                .then(literal("reject").then(Args.player("player").executes(ctx -> ownerRun(ctx, id -> {
                    if (com.vylorq.anticheat.feature.BuilderDrafts.reject(ctx.getSource().getPlayer(), id)) {
                        Msg.ok(ctx.getSource(), "draft.rejecting", Args.str(ctx, "player"));
                    } else {
                        Msg.err(ctx.getSource(), "draft.none-for", Args.str(ctx, "player"));
                    }
                }))))
                .then(literal("discard").then(Args.player("player").executes(ctx -> ownerRun(ctx, id -> {
                    com.vylorq.anticheat.feature.BuilderDrafts.discard(id);
                    Msg.ok(ctx.getSource(), "draft.discarded", Args.str(ctx, "player"));
                }))))
                .then(literal("backups").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    var list = com.vylorq.anticheat.feature.BuilderDrafts.backups();
                    Msg.ok(ctx.getSource(), list.isEmpty() ? "backup.none" : "backup.list", String.join(", ", list));
                    return 1;
                }))
                .then(literal("backup").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    String name = com.vylorq.anticheat.feature.BuilderDrafts.backup("manual");
                    if (name == null) {
                        Msg.err(ctx.getSource(), "backup.failed");
                        return 0;
                    }
                    Msg.ok(ctx.getSource(), "backup.saved", name);
                    return 1;
                }))
                .then(literal("restore").then(Args.word("name").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    int n = com.vylorq.anticheat.feature.BuilderDrafts.restore(ctx.getSource().getPlayer(), Args.str(ctx, "name"));
                    if (n < 0) {
                        Msg.err(ctx.getSource(), "backup.not-found", Args.str(ctx, "name"));
                        return 0;
                    }
                    Msg.ok(ctx.getSource(), "backup.restored", Args.str(ctx, "name"), n);
                    return 1;
                }))));
    }

    private static ServerPlayerEntity builderSelf(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = self(ctx);
        if (p != null && !com.vylorq.anticheat.feature.BuilderTools.canUse(p)) {
            Msg.err(ctx.getSource(), "general.no-permission");
            return null;
        }
        return p;
    }

    /** Runs with a block mix from the argument ("hotbar" = the blocks in the hotbar). */
    private static int withPattern(CommandContext<ServerCommandSource> ctx, String arg,
                                   java.util.function.BiConsumer<ServerPlayerEntity, com.vylorq.anticheat.feature.BuilderTools.Pattern> then) {
        ServerPlayerEntity p = builderSelf(ctx);
        if (p == null) {
            return 0;
        }
        String text = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, arg);
        var pat = text.equalsIgnoreCase("hotbar") ? com.vylorq.anticheat.feature.BuilderTools.hotbarMix(p)
                : com.vylorq.anticheat.feature.BuilderTools.pattern(text);
        if (pat == null) {
            Msg.err(ctx.getSource(), "build.unknown-block", text);
            return 0;
        }
        then.accept(p, pat);
        return 1;
    }

    private static int builderRun(CommandContext<ServerCommandSource> ctx, java.util.function.Consumer<ServerPlayerEntity> then) {
        ServerPlayerEntity p = builderSelf(ctx);
        if (p == null) {
            return 0;
        }
        then.accept(p);
        return 1;
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> blocks() {
        return CommandManager.argument("blocks", com.mojang.brigadier.arguments.StringArgumentType.greedyString());
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Integer> num(String name) {
        return CommandManager.argument(name, com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,
                com.vylorq.anticheat.feature.BuilderTools.MAX_RADIUS));
    }

    private static int n(CommandContext<ServerCommandSource> ctx, String name) {
        return com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, name);
    }

    /** /build: the builder tools (for builders and the owner). */
    private static void registerBuild(CommandDispatcher<ServerCommandSource> d) {
        var T = new Object() {
            com.vylorq.anticheat.feature.BuilderTools.BrushMode mode(String s) {
                try {
                    return com.vylorq.anticheat.feature.BuilderTools.BrushMode.valueOf(s.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        };
        d.register(literal("build").requires(s -> s.getPlayer() == null ? Mc.hasLevel(s, 3)
                        : com.vylorq.anticheat.feature.BuilderTools.canUse(s.getPlayer()))
                .executes(ctx -> builderRun(ctx, com.vylorq.anticheat.gui.BuilderMenu::open))
                .then(literal("menu").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.gui.BuilderMenu::open)))
                .then(literal("wand").executes(ctx -> builderRun(ctx, p -> {
                    p.getInventory().insertStack(Tools.builderWand());
                    p.getInventory().insertStack(Tools.builderMenu());
                })))
                .then(literal("pos1").executes(ctx -> builderRun(ctx, p ->
                        com.vylorq.anticheat.feature.BuilderTools.corner(p, (ServerWorld) p.getEntityWorld(), p.getBlockPos(), true))))
                .then(literal("pos2").executes(ctx -> builderRun(ctx, p ->
                        com.vylorq.anticheat.feature.BuilderTools.corner(p, (ServerWorld) p.getEntityWorld(), p.getBlockPos(), false))))
                .then(literal("clear").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderTools::clear)))
                .then(literal("set").then(blocks().executes(ctx -> withPattern(ctx, "blocks", com.vylorq.anticheat.feature.BuilderTools::set))))
                .then(literal("walls").then(blocks().executes(ctx -> withPattern(ctx, "blocks", com.vylorq.anticheat.feature.BuilderTools::walls))))
                .then(literal("hollow").then(blocks().executes(ctx -> withPattern(ctx, "blocks", com.vylorq.anticheat.feature.BuilderTools::hollow))))
                .then(literal("line").then(blocks().executes(ctx -> withPattern(ctx, "blocks", com.vylorq.anticheat.feature.BuilderTools::line))))
                .then(literal("replace").then(Args.word("from").then(blocks().executes(ctx -> {
                    var from = com.vylorq.anticheat.feature.BuilderTools.block(Args.str(ctx, "from"));
                    if (from == null) {
                        Msg.err(ctx.getSource(), "build.unknown-block", Args.str(ctx, "from"));
                        return 0;
                    }
                    return withPattern(ctx, "blocks", (p, pat) -> com.vylorq.anticheat.feature.BuilderTools.replace(p, from.getBlock(), pat));
                }))))
                .then(literal("sphere").then(num("radius").then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) ->
                        com.vylorq.anticheat.feature.BuilderTools.sphere(p, pat, n(ctx, "radius"), false))))))
                .then(literal("hsphere").then(num("radius").then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) ->
                        com.vylorq.anticheat.feature.BuilderTools.sphere(p, pat, n(ctx, "radius"), true))))))
                .then(literal("cyl").then(num("radius").then(num("height").then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) ->
                        com.vylorq.anticheat.feature.BuilderTools.cylinder(p, pat, n(ctx, "radius"), n(ctx, "height"), false)))))))
                .then(literal("hcyl").then(num("radius").then(num("height").then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) ->
                        com.vylorq.anticheat.feature.BuilderTools.cylinder(p, pat, n(ctx, "radius"), n(ctx, "height"), true)))))))
                .then(literal("pyramid").then(num("size").then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) ->
                        com.vylorq.anticheat.feature.BuilderTools.pyramid(p, pat, n(ctx, "size"), false))))))
                .then(literal("hpyramid").then(num("size").then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) ->
                        com.vylorq.anticheat.feature.BuilderTools.pyramid(p, pat, n(ctx, "size"), true))))))
                .then(literal("brush").then(Args.word("mode").then(num("radius")
                        .executes(ctx -> builderRun(ctx, p -> {
                            var m = T.mode(Args.str(ctx, "mode"));
                            if (m == null) {
                                Msg.err(ctx.getSource(), "build.bad-brush");
                            } else {
                                com.vylorq.anticheat.feature.BuilderTools.setBrush(p, m, null, n(ctx, "radius"));
                            }
                        }))
                        .then(blocks().executes(ctx -> withPattern(ctx, "blocks", (p, pat) -> {
                            var m = T.mode(Args.str(ctx, "mode"));
                            if (m == null) {
                                Msg.err(ctx.getSource(), "build.bad-brush");
                            } else {
                                com.vylorq.anticheat.feature.BuilderTools.setBrush(p, m, pat, n(ctx, "radius"));
                            }
                        }))))))
                .then(literal("copy").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderTools::copy)))
                .then(literal("paste").executes(ctx -> builderRun(ctx, p -> com.vylorq.anticheat.feature.BuilderTools.paste(p, true)))
                        .then(literal("noair").executes(ctx -> builderRun(ctx, p -> com.vylorq.anticheat.feature.BuilderTools.paste(p, false))))
                        .then(literal("now").executes(ctx -> builderRun(ctx, p -> com.vylorq.anticheat.feature.BuilderTools.pasteNow(p, true)))
                                .then(literal("noair").executes(ctx -> builderRun(ctx, p -> com.vylorq.anticheat.feature.BuilderTools.pasteNow(p, false))))))
                .then(literal("confirm").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderTools::confirmPaste)))
                .then(literal("cancel").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderTools::cancelPaste)))
                .then(literal("rotate").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderTools::rotate)))
                .then(literal("flip").then(literal("x").executes(ctx -> builderRun(ctx, p -> com.vylorq.anticheat.feature.BuilderTools.flip(p, true))))
                        .then(literal("z").executes(ctx -> builderRun(ctx, p -> com.vylorq.anticheat.feature.BuilderTools.flip(p, false)))))
                .then(literal("undo").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderTools::undo)))
                .then(literal("submit").executes(ctx -> builderRun(ctx, com.vylorq.anticheat.feature.BuilderDrafts::submit)))
                .then(literal("builds").executes(ctx -> builderRun(ctx, p -> {
                    var files = com.vylorq.anticheat.feature.BuildFiles.list();
                    Msg.ok(ctx.getSource(), files.isEmpty() ? "build.menu.no-builds" : "build.list", String.join(", ", files.keySet()));
                })))
                .then(literal("load").then(Args.word("name").executes(ctx -> builderRun(ctx, p ->
                        com.vylorq.anticheat.feature.BuilderTools.load(p, Args.str(ctx, "name"))))))
                .then(literal("save").then(Args.word("name").executes(ctx -> builderRun(ctx, p ->
                        com.vylorq.anticheat.feature.BuilderTools.save(p, Args.str(ctx, "name"))))))
                .then(literal("import").then(Args.word("name").then(CommandManager.argument("link",
                        com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(ctx -> builderRun(ctx, p ->
                        com.vylorq.anticheat.feature.BuilderTools.importUrl(p,
                                com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "link"), Args.str(ctx, "name"))))))));
    }

    private static int addBuilder(CommandContext<ServerCommandSource> ctx, String time, boolean anywhere, boolean live) {
        if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) {
            return 0;
        }
        ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
        if (t == null) {
            return 0;
        }
        long ms = 0;
        if (time != null) {
            OptionalLong dur = Args.duration(ctx.getSource(), time);
            if (dur.isEmpty()) {
                return 0;
            }
            ms = dur.getAsLong();
        }
        if (com.vylorq.anticheat.feature.BuilderMode.is(t) || com.vylorq.anticheat.feature.TempAdmins.isTemp(t.getUuid())) {
            Msg.err(ctx.getSource(), "builder.already", t.getGameProfile().name());
            return 0;
        }
        com.vylorq.anticheat.feature.BuilderMode.start(ctx.getSource().getPlayer(), t, ms, anywhere, live);
        var b = com.vylorq.anticheat.feature.BuilderMode.get(t.getUuid());
        String where = anywhere || Ac.get().lobby.data().area == null ? Msg.tr("builder.where-anywhere")
                : b != null && b.draft ? Msg.tr("builder.where-draft") : Msg.tr("builder.where-lobby");
        Msg.ok(ctx.getSource(), "builder.added", t.getGameProfile().name(), where,
                ms > 0 ? Durations.format(ms) : Msg.tr("builder.until-removed"));
        return 1;
    }

    // ---- World events ----

    private static void registerEvents(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("events")
                .executes(ctx -> {
                    var a = com.vylorq.anticheat.feature.WorldEvents.active();
                    if (a == null) {
                        Msg.ok(ctx.getSource(), "events.none-now");
                    } else {
                        Msg.ok(ctx.getSource(), "events.now", Msg.tr("events." + a.id()),
                                Durations.format(com.vylorq.anticheat.feature.WorldEvents.secondsLeft() * 1000L));
                    }
                    return 1;
                })
                .then(literal("list").executes(ctx -> {
                    for (var k : com.vylorq.anticheat.feature.WorldEvents.Kind.values()) {
                        Msg.ok(ctx.getSource(), "events.entry", k.id(), Msg.tr("events." + k.id()), Msg.tr(k.scary ? "events.scary" : "events.good"));
                    }
                    return 1;
                }))
                .then(literal("start").requires(s -> Perms.visible(s, Perm.EVENTS)).then(Args.word("event")
                        .suggests((c, b) -> {
                            for (var k : com.vylorq.anticheat.feature.WorldEvents.Kind.values()) {
                                b.suggest(k.id());
                            }
                            return b.buildFuture();
                        })
                        .executes(ctx -> {
                            if (!Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                            var k = com.vylorq.anticheat.feature.WorldEvents.Kind.byId(Args.str(ctx, "event"));
                            if (k == null) {
                                Msg.err(ctx.getSource(), "events.unknown", Args.str(ctx, "event"));
                                return 0;
                            }
                            String err = com.vylorq.anticheat.feature.WorldEvents.start(k, Staff.name(ctx.getSource().getPlayer()));
                            if (err != null) {
                                Msg.err(ctx.getSource(), err);
                                return 0;
                            }
                            Staff.log(ctx.getSource().getPlayer(), "event-start", null, k.id(), "");
                            return 1;
                        })))
                .then(literal("stop").requires(s -> Perms.visible(s, Perm.EVENTS)).executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.EVENTS)) return 0;
                    if (com.vylorq.anticheat.feature.WorldEvents.active() == null) {
                        Msg.err(ctx.getSource(), "events.none-now");
                        return 0;
                    }
                    com.vylorq.anticheat.feature.WorldEvents.stop("stopped");
                    Staff.log(ctx.getSource().getPlayer(), "event-stop", null, null, "");
                    return 1;
                })));
    }

    // ---- Player stats ----

    private static final List<String> STAT_KINDS = List.of("playtime", "kills", "pvp", "deaths", "mined", "walked");

    private static void sendStats(ServerCommandSource src, com.vylorq.anticheat.feature.PlayerStats.Row r) {
        src.sendFeedback(() -> Text.literal(Msg.tr("pstats.head", r.name())), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("pstats.line1", com.vylorq.anticheat.feature.PlayerStats.time(r.playTicks()),
                String.format("%,d", r.mined()), com.vylorq.anticheat.feature.PlayerStats.format("walked", r.walkedCm()))), false);
        src.sendFeedback(() -> Text.literal(Msg.tr("pstats.line2", String.format("%,d", r.mobKills()), String.format("%,d", r.playerKills()),
                String.format("%,d", r.deaths()))), false);
    }

    private static void registerStats(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("stats")
                .executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    sendStats(ctx.getSource(), com.vylorq.anticheat.feature.PlayerStats.of(p));
                    return 1;
                })
                .then(literal("top").executes(ctx -> statsTop(ctx, "playtime"))
                        .then(Args.word("what").suggests((c, b) -> {
                            STAT_KINDS.forEach(b::suggest);
                            return b.buildFuture();
                        }).executes(ctx -> statsTop(ctx, Args.str(ctx, "what").toLowerCase()))))
                .then(Args.player("player").executes(ctx -> {
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    var r = com.vylorq.anticheat.feature.PlayerStats.of(id);
                    if (r == null) {
                        Msg.err(ctx.getSource(), "pstats.none", Args.str(ctx, "player"));
                        return 0;
                    }
                    sendStats(ctx.getSource(), r);
                    return 1;
                })));
    }

    private static int statsTop(CommandContext<ServerCommandSource> ctx, String what) {
        if (!STAT_KINDS.contains(what)) {
            Msg.err(ctx.getSource(), "pstats.kinds", String.join(", ", STAT_KINDS));
            return 0;
        }
        var rows = com.vylorq.anticheat.feature.PlayerStats.top(what, 10);
        ctx.getSource().sendFeedback(() -> Text.literal(Msg.tr("pstats.top-head", Msg.tr("pstats.kind." + what))), false);
        int i = 1;
        for (var r : rows) {
            String line = "§e" + i++ + ". §f" + r.name() + " §7- §a" + com.vylorq.anticheat.feature.PlayerStats.format(what, r.get(what));
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    // ---- Locked Box ----

    private static void registerLockedBox(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("lockedbox").requires(s -> Perms.visible(s, Perm.MANAGE_ADMINS))
                .executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    Barrier b = com.vylorq.anticheat.feature.LockedBox.get();
                    if (b == null) {
                        Msg.ok(ctx.getSource(), "lockedbox.none");
                    } else {
                        Msg.ok(ctx.getSource(), "lockedbox.info", (int) b.minX + " " + (int) b.minY + " " + (int) b.minZ,
                                (int) b.maxX + " " + (int) b.maxY + " " + (int) b.maxZ);
                    }
                    return 1;
                })
                .then(literal("wand").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.MANAGE_ADMINS);
                    if (p != null) {
                        p.getInventory().insertStack(Tools.claimStick());
                        Msg.ok(ctx.getSource(), "lockedbox.wand");
                    }
                    return 1;
                }))
                .then(literal("create").executes(ctx -> lockBox(ctx, 0))
                        .then(CommandManager.argument("height", com.mojang.brigadier.arguments.IntegerArgumentType.integer(3, 384))
                                .executes(ctx -> lockBox(ctx, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "height")))))
                .then(literal("remove").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.MANAGE_ADMINS)) return 0;
                    if (!com.vylorq.anticheat.feature.LockedBox.remove(ctx.getSource().getPlayer())) {
                        Msg.err(ctx.getSource(), "lockedbox.none");
                        return 0;
                    }
                    Msg.ok(ctx.getSource(), "lockedbox.removed");
                    return 1;
                })));
    }

    /** Makes (or resizes) the Locked Box from the Claim Stick corners. */
    private static int lockBox(CommandContext<ServerCommandSource> ctx, int height) {
        ServerPlayerEntity p = staff(ctx, Perm.MANAGE_ADMINS);
        if (p == null) {
            return 0;
        }
        Area a = selection(p, false);
        if (a == null) {
            return 0;
        }
        if (height > 0) {
            a.maxY = a.minY + height - 1;
        } else if (a.maxY - a.minY < 5) {
            // Both corners on the ground: give it room to stand and jump.
            a.maxY = a.minY + 20;
        }
        ServerWorld w = Mc.world(Ac.server(), a.world);
        BlockPos spawn = com.vylorq.anticheat.feature.LockedBox.create(p, w != null ? w : (ServerWorld) p.getEntityWorld(), a);
        Msg.ok(ctx.getSource(), "lockedbox.created", (a.maxX - a.minX + 1) + "x" + (a.maxY - a.minY + 1) + "x" + (a.maxZ - a.minZ + 1),
                spawn.toShortString());
        return 1;
    }

    // ---- The End ----

    private static void registerEnd(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("end").requires(s -> Perms.visible(s, Perm.SETTINGS))
                .executes(ctx -> {
                    Msg.ok(ctx.getSource(), com.vylorq.anticheat.feature.EndLock.open() ? "end.status-open" : "end.status-closed");
                    return 1;
                })
                .then(literal("open").executes(ctx -> setEnd(ctx, true)))
                .then(literal("close").executes(ctx -> setEnd(ctx, false)))
                .then(literal("portal").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.SETTINGS);
                    if (p == null) {
                        return 0;
                    }
                    ServerWorld w = (ServerWorld) p.getEntityWorld();
                    BlockPos c = com.vylorq.anticheat.feature.EndLock.build(w, p.getBlockPos(), p.getHorizontalFacing());
                    Staff.log(p, "end-portal", null, Mc.worldId(w) + " " + c.toShortString(), "");
                    Msg.ok(ctx.getSource(), "end.portal-built", c.toShortString());
                    return 1;
                }))
                .then(literal("remove").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.SETTINGS);
                    if (p == null) {
                        return 0;
                    }
                    ServerWorld w = (ServerWorld) p.getEntityWorld();
                    if (!com.vylorq.anticheat.feature.EndLock.removeBuilt(w, p.getBlockPos())) {
                        Msg.err(ctx.getSource(), "end.no-portal-near");
                        return 0;
                    }
                    Staff.log(p, "end-portal-remove", null, Mc.worldId(w) + " " + p.getBlockPos().toShortString(), "");
                    Msg.ok(ctx.getSource(), "end.portal-removed");
                    return 1;
                })));
    }

    private static int setEnd(CommandContext<ServerCommandSource> ctx, boolean open) {
        if (!Perms.check(ctx.getSource(), Perm.SETTINGS)) {
            return 0;
        }
        Ac.config().general.endOpen = open;
        Ac.get().configManager.save();
        Staff.log(ctx.getSource().getPlayer(), open ? "end-open" : "end-close", null, "", "");
        Msg.ok(ctx.getSource(), open ? "end.opened" : "end.closed-now");
        return 1;
    }

    // ---- Claims ----

    private static void registerClaims(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("claim").requires(s -> Perms.visible(s, Perm.CLAIM))
                .then(literal("wand").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.CLAIM);
                    if (p != null) {
                        p.getInventory().insertStack(Tools.claimStick());
                        Msg.ok(ctx.getSource(), "claim.wand-given");
                    }
                    return 1;
                }))
                .then(literal("create").then(Args.player("name").then(Args.word("time").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.CLAIM);
                    if (p == null) return 0;
                    OptionalLong t = Args.duration(ctx.getSource(), Args.str(ctx, "time"));
                    Area a = selection(p, true);
                    if (t.isEmpty() || a == null) return 0;
                    var cfg = Ac.config().claims;
                    ClaimManager.CreateResult r = Ac.get().claims.create(Args.str(ctx, "name"), a.world, a.minX, a.minZ, a.maxX, a.maxZ,
                            p.getUuid(), Perms.isOwner(p.getUuid()), t.getAsLong(), cfg.minGap, cfg.maxClaimArea);
                    if (r == ClaimManager.CreateResult.OK) {
                        Ac.markDirty("claims");
                        Staff.log(p, "claim-create", null, Args.str(ctx, "name"), a.minX + "," + a.minZ + " -> " + a.maxX + "," + a.maxZ
                                + " " + Durations.format(t.getAsLong()));
                        Msg.ok(ctx.getSource(), "claim.created", Args.str(ctx, "name"), Durations.format(t.getAsLong()));
                    } else {
                        Msg.err(ctx.getSource(), "claim.create-" + r.name().toLowerCase().replace('_', '-'));
                    }
                    return 1;
                }))))
                .then(literal("menu").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.CLAIM);
                    if (p != null) {
                        ClaimMenu.list(p, 0);
                    }
                    return 1;
                }))
                .then(literal("who").then(Args.player("name").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.CLAIM)) return 0;
                    Claim c = Ac.get().claims.get(Args.str(ctx, "name"));
                    if (c == null) {
                        Msg.err(ctx.getSource(), "claim.not-found");
                        return 0;
                    }
                    Msg.ok(ctx.getSource(), "claim.who-header", c.name);
                    for (UUID id : Ac.get().claims.playersIn(c)) {
                        var r = c.roleOf(id, System.currentTimeMillis());
                        boolean watched = Ac.get().watchlist.isWatched(id);
                        ctx.getSource().sendFeedback(() -> Msg.text((watched ? "§d⚑ " : "§f• ") + Args.nameOf(id, "?") + " §7" + (r == null ? "no role" : r.name().toLowerCase())), false);
                    }
                    return 1;
                })))
                .then(literal("near").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.CLAIM);
                    if (p == null) return 0;
                    for (Claim c : Ac.get().claims.near(Mc.worldId(p.getEntityWorld()), p.getBlockX(), p.getBlockZ(), 200)) {
                        StringBuilder managers = new StringBuilder();
                        for (var e : c.members.values()) {
                            if (e.role == com.vylorq.anticheat.core.claims.ClaimRole.MANAGER) {
                                managers.append(e.name).append(' ');
                            }
                        }
                        long left = c.remaining(System.currentTimeMillis());
                        MutableText t = Msg.text("§a" + c.name + " §7" + c.minX + "," + c.minZ + " → " + c.maxX + "," + c.maxZ
                                + " §7managers: §f" + (managers.isEmpty() ? "-" : managers.toString().trim())
                                + " §7left: §f" + (left == Durations.PERMANENT ? "permanent" : Durations.format(left)));
                        ctx.getSource().sendFeedback(() -> t, false);
                    }
                    return 1;
                }))
                .then(literal("spawn").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.CLAIM);
                    if (p == null) return 0;
                    Area a = selection(p, true);
                    if (a == null) return 0;
                    ClaimManager.CreateResult r = Ac.get().claims.create("spawn", a.world, a.minX, a.minZ, a.maxX, a.maxZ, p.getUuid(),
                            Perms.isOwner(p.getUuid()), Durations.PERMANENT, 0, Integer.MAX_VALUE);
                    if (r == ClaimManager.CreateResult.OK) {
                        Claim c = Ac.get().claims.get("spawn");
                        c.spawnProtection = true;
                        c.settings.pvp = false;
                        c.settings.explosions = false;
                        Ac.markDirty("claims");
                        Staff.log(p, "claim-spawn", null, "spawn", "");
                        Msg.ok(ctx.getSource(), "claim.spawn-created");
                    } else {
                        Msg.err(ctx.getSource(), "claim.create-" + r.name().toLowerCase().replace('_', '-'));
                    }
                    return 1;
                })));
    }

    // ---- Barriers ----

    private static void registerBarriers(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("barrier").requires(s -> Perms.visible(s, Perm.BARRIER))
                .then(literal("create").then(Args.player("name")
                        .executes(ctx -> createBarrier(ctx, null, "box", null))
                        .then(CommandManager.argument("radius", DoubleArgumentType.doubleArg(1, 1000))
                                .executes(ctx -> createBarrier(ctx, DoubleArgumentType.getDouble(ctx, "radius"), "circle", null))
                                .then(Args.word("shape").executes(ctx -> createBarrier(ctx, DoubleArgumentType.getDouble(ctx, "radius"), Args.str(ctx, "shape"), null))
                                        .then(Args.word("time").executes(ctx -> createBarrier(ctx, DoubleArgumentType.getDouble(ctx, "radius"),
                                                Args.str(ctx, "shape"), Args.str(ctx, "time"))))))
                        .then(literal("for").then(Args.word("time").executes(ctx -> createBarrier(ctx, null, "box", Args.str(ctx, "time")))))))
                .then(literal("remove").then(Args.player("name").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.BARRIER)) return 0;
                    if (Ac.get().barriers.remove(Args.str(ctx, "name"))) {
                        Ac.markDirty("barriers");
                        Staff.log(ctx.getSource().getPlayer(), "barrier-remove", null, Args.str(ctx, "name"), "");
                        Msg.ok(ctx.getSource(), "barrier.removed", Args.str(ctx, "name"));
                    } else {
                        Msg.err(ctx.getSource(), "barrier.not-found");
                    }
                    return 1;
                })))
                .then(literal("adminpass").then(Args.player("name").then(Args.word("onoff").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.BARRIER)) return 0;
                    Barrier b = Ac.get().barriers.get(Args.str(ctx, "name"));
                    if (b == null) {
                        Msg.err(ctx.getSource(), "barrier.not-found");
                        return 0;
                    }
                    b.adminsPass = Args.str(ctx, "onoff").equalsIgnoreCase("on");
                    Ac.markDirty("barriers");
                    Msg.ok(ctx.getSource(), "barrier.adminpass", b.name, b.adminsPass ? "on" : "off");
                    return 1;
                }))))
                .then(literal("newplayers").then(Args.player("name").then(Args.word("side").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.BARRIER)) return 0;
                    Barrier b = Ac.get().barriers.get(Args.str(ctx, "name"));
                    String side = Args.str(ctx, "side").toLowerCase();
                    if (b == null || !(side.equals("inside") || side.equals("outside") || side.equals("auto"))) {
                        Msg.err(ctx.getSource(), b == null ? "barrier.not-found" : "barrier.bad-side");
                        return 0;
                    }
                    b.newPlayers = side;
                    Ac.get().barriers.resetSides(b);
                    Ac.markDirty("barriers");
                    Msg.ok(ctx.getSource(), "barrier.newplayers", b.name, side);
                    return 1;
                }))))
                .then(literal("reset").then(Args.player("name").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.BARRIER)) return 0;
                    Barrier b = Ac.get().barriers.get(Args.str(ctx, "name"));
                    if (b == null) {
                        Msg.err(ctx.getSource(), "barrier.not-found");
                        return 0;
                    }
                    Ac.get().barriers.resetSides(b);
                    Ac.markDirty("barriers");
                    Msg.ok(ctx.getSource(), "barrier.reset", b.name);
                    return 1;
                })))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.BARRIER)) return 0;
                    for (Barrier b : Ac.get().barriers.list()) {
                        Vec3 c = b.center();
                        MutableText t = Msg.text("§6" + b.name + " §7" + b.shape + " " + b.world.replace("minecraft:", "") + " "
                                + (b.expiresAt == Durations.PERMANENT ? "permanent" : Durations.formatRemaining(b.expiresAt, System.currentTimeMillis())) + " ");
                        t.append(Msg.button("§b[TP]", "/ac tp " + b.world + " " + (int) c.x() + " " + (int) Math.max(c.y(), 64) + " " + (int) c.z(), "Teleport"));
                        ctx.getSource().sendFeedback(() -> t, false);
                    }
                    return 1;
                })));
    }

    private static int createBarrier(CommandContext<ServerCommandSource> ctx, Double radius, String shape, String time) {
        ServerPlayerEntity p = staff(ctx, Perm.BARRIER);
        if (p == null) return 0;
        Barrier b = new Barrier();
        b.name = Args.str(ctx, "name");
        b.world = Mc.worldId(p.getEntityWorld());
        b.createdBy = p.getGameProfile().name();
        if (time != null) {
            OptionalLong t = Args.duration(ctx.getSource(), time);
            if (t.isEmpty()) return 0;
            b.expiresAt = Durations.expiryFrom(System.currentTimeMillis(), t.getAsLong());
        }
        if (radius == null) {
            Area a = selection(p, true);
            if (a == null) return 0;
            b.shape = Barrier.Shape.BOX;
            b.minX = a.minX;
            b.maxX = a.maxX;
            b.minZ = a.minZ;
            b.maxZ = a.maxZ;
            b.cy = p.getY();
        } else {
            b.shape = switch (shape.toLowerCase()) {
                case "sphere" -> Barrier.Shape.SPHERE;
                case "box", "square" -> Barrier.Shape.BOX;
                default -> Barrier.Shape.CYLINDER;
            };
            b.cx = p.getX();
            b.cy = p.getY();
            b.cz = p.getZ();
            b.radius = radius;
            if (b.shape == Barrier.Shape.BOX) {
                b.minX = Math.floor(p.getX() - radius);
                b.maxX = Math.floor(p.getX() + radius);
                b.minZ = Math.floor(p.getZ() - radius);
                b.maxZ = Math.floor(p.getZ() + radius);
            }
        }
        // Everyone online right now belongs to the side they're on.
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            b.sides.put(o.getUuid(), b.contains(Mc.worldId(o.getEntityWorld()), o.getX(), o.getY(), o.getZ()));
        }
        if (!Ac.get().barriers.add(b)) {
            Msg.err(ctx.getSource(), "barrier.exists");
            return 0;
        }
        Ac.markDirty("barriers");
        Staff.log(p, "barrier-create", null, b.name, b.shape + " " + b.world);
        Msg.ok(ctx.getSource(), "barrier.created", b.name, b.shape.name().toLowerCase());
        return 1;
    }

    // ---- Lobby ----

    private static void registerLobby(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("lobby")
                .executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    if (Ac.get().jail.isJailed(p.getUuid()) || WaitingRoomFeature.waiting(p) || Arenas.inMatch(p) || StaffTools.isFrozen(p)) {
                        Msg.err(ctx.getSource(), "lobby.blocked");
                        return 0;
                    }
                    LobbyFeature.teleport(p);
                    return 1;
                })
                .then(literal("set").requires(s -> Perms.visible(s, Perm.LOBBY_ADMIN)).executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.LOBBY_ADMIN);
                    if (p == null) return 0;
                    Area a = selection(p, true);
                    if (a == null) return 0;
                    Ac.get().lobby.data().area = a;
                    if (Ac.get().lobby.data().spawn == null) {
                        Ac.get().lobby.data().spawn = Mc.location(p);
                    }
                    Ac.markDirty("lobby");
                    Staff.log(p, "lobby-set", null, null, a.minX + "," + a.minZ + " -> " + a.maxX + "," + a.maxZ);
                    Msg.ok(ctx.getSource(), "lobby.set");
                    return 1;
                }))
                .then(literal("setspawn").requires(s -> Perms.visible(s, Perm.LOBBY_ADMIN)).executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.LOBBY_ADMIN);
                    if (p == null) return 0;
                    Ac.get().lobby.data().spawn = Mc.location(p);
                    Ac.markDirty("lobby");
                    Staff.log(p, "lobby-setspawn", null, null, Mc.vec(p.getEntityPos()).formatExact());
                    Msg.ok(ctx.getSource(), "lobby.spawn-set");
                    return 1;
                }))
                .then(literal("edit").requires(s -> Perms.visible(s, Perm.LOBBY_ADMIN)).executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.LOBBY_ADMIN);
                    if (p == null) return 0;
                    boolean on = Ac.get().lobby.toggleEdit(p.getUuid());
                    Ac.markDirty("lobby");
                    Staff.log(p, on ? "lobby-edit-on" : "lobby-edit-off", null, null, "");
                    Msg.ok(ctx.getSource(), on ? "lobby.edit-on" : "lobby.edit-off");
                    return 1;
                }))
                .then(literal("chest").requires(s -> Perms.visible(s, Perm.LOBBY_ADMIN)).then(Args.word("mode").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.LOBBY_ADMIN);
                    if (p == null) return 0;
                    Lobby.ChestMode mode = switch (Args.str(ctx, "mode").toLowerCase()) {
                        case "view", "view_only", "viewonly" -> Lobby.ChestMode.VIEW_ONLY;
                        case "take" -> Lobby.ChestMode.TAKE;
                        case "loot" -> Lobby.ChestMode.LOOT;
                        default -> null;
                    };
                    HitResult hit = p.raycast(6, 1f, false);
                    if (mode == null || !(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK
                            || !(p.getEntityWorld().getBlockEntity(bh.getBlockPos()) instanceof Inventory inv)) {
                        Msg.err(ctx.getSource(), "lobby.chest-usage");
                        return 0;
                    }
                    LobbyFeature.setChestMode(p.getEntityWorld(), bh.getBlockPos(), mode, inv);
                    Staff.log(p, "lobby-chest", null, null, mode + " at " + bh.getBlockPos().toShortString());
                    Msg.ok(ctx.getSource(), "lobby.chest-set", mode.name().toLowerCase());
                    return 1;
                }))));
    }

    // ---- Jail ----

    private static void registerJail(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("jail").requires(s -> Perms.visible(s, Perm.JAIL))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.JAIL)) return 0;
                    boolean onlineOnly = Ac.config().jail.onlineTimeOnly;
                    for (JailManager.Record r : Ac.get().jail.list()) {
                        ctx.getSource().sendFeedback(() -> Msg.text("§c• " + r.name + " §7" + r.reason + " §8(" +
                                Durations.format(Ac.get().jail.remaining(r.player, onlineOnly)) + " left, cell " + r.cell + ")"), false);
                    }
                    return 1;
                }))
                .then(literal("setcell").then(Args.word("cell").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.JAIL);
                    if (p == null) return 0;
                    Ac.get().jail.setCell(Args.str(ctx, "cell"), Mc.location(p));
                    Ac.markDirty("jail");
                    Staff.log(p, "jail-setcell", null, Args.str(ctx, "cell"), Mc.vec(p.getEntityPos()).formatExact());
                    Msg.ok(ctx.getSource(), "jail.cell-set", Args.str(ctx, "cell"));
                    return 1;
                })))
                .then(literal("delcell").then(Args.word("cell").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.JAIL)) return 0;
                    Ac.get().jail.removeCell(Args.str(ctx, "cell"));
                    Ac.markDirty("jail");
                    Msg.ok(ctx.getSource(), "jail.cell-removed", Args.str(ctx, "cell"));
                    return 1;
                })))
                .then(Args.player("player").then(Args.word("time").then(Args.rest("reason").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.JAIL)) return 0;
                    ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    OptionalLong d2 = Args.duration(ctx.getSource(), Args.str(ctx, "time"));
                    if (t == null || d2.isEmpty() || !Punish.allowedOn(ctx.getSource().getPlayer(), t.getUuid())) return 0;
                    if (Ac.get().jail.cells().isEmpty()) {
                        Msg.err(ctx.getSource(), "jail.no-cells");
                        return 0;
                    }
                    Jail.jail(t, Args.str(ctx, "reason"), d2.getAsLong(), Staff.name(ctx.getSource().getPlayer()));
                    Staff.log(ctx.getSource().getPlayer(), "jail", t.getUuid(), t.getGameProfile().name(),
                            Durations.format(d2.getAsLong()) + " " + Args.str(ctx, "reason"));
                    Ac.get().punishments.add(com.vylorq.anticheat.core.staff.Punishment.Type.JAIL, t.getUuid(), t.getGameProfile().name(),
                            Args.str(ctx, "reason"), Staff.name(ctx.getSource().getPlayer()), d2.getAsLong());
                    Ac.markDirty("punishments");
                    return 1;
                })))));
        d.register(literal("unjail").requires(s -> Perms.visible(s, Perm.JAIL))
                .then(Args.player("player").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.JAIL)) return 0;
                    UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id == null) return 0;
                    if (!Ac.get().jail.isJailed(id)) {
                        Msg.err(ctx.getSource(), "jail.not-jailed");
                        return 0;
                    }
                    Jail.release(id, true);
                    Ac.get().punishments.revoke(id, com.vylorq.anticheat.core.staff.Punishment.Type.JAIL, Staff.name(ctx.getSource().getPlayer()));
                    Ac.markDirty("punishments");
                    Staff.log(ctx.getSource().getPlayer(), "unjail", id, Args.nameOf(id, "?"), "");
                    Msg.ok(ctx.getSource(), "jail.released", Args.nameOf(id, "?"));
                    return 1;
                })));
    }

    // ---- Waiting room ----

    private static void registerWaiting(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("request").then(literal("join").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) {
                WaitingRoomFeature.requestJoin(p);
            }
            return 1;
        })));
        d.register(literal("requests").requires(s -> Perms.visible(s, Perm.WAITING_ROOM))
                .executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.WAITING_ROOM);
                    if (p == null) return 0;
                    var list = Ac.get().waitingRoom.pending();
                    if (list.isEmpty()) {
                        Msg.ok(ctx.getSource(), "waiting.none");
                    }
                    for (WaitingRoom.Request r : list) {
                        p.sendMessage(WaitingRoomFeature.requestText(r, p));
                    }
                    return 1;
                })
                .then(literal("accept").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.WAITING_ROOM);
                    UUID id = p == null ? null : Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id != null) {
                        WaitingRoomFeature.accept(p, id);
                    }
                    return 1;
                })))
                .then(literal("deny").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.WAITING_ROOM);
                    UUID id = p == null ? null : Args.known(ctx.getSource(), Args.str(ctx, "player"));
                    if (id != null) {
                        WaitingRoomFeature.deny(p, id);
                    }
                    return 1;
                })))
                .then(literal("tp").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.WAITING_ROOM);
                    if (p == null) return 0;
                    ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (t == null) return 0;
                    boolean inv = Ac.get().staff.teleportInvisible(p.getUuid(), Ac.config().staff.teleportInvisibleByDefault);
                    StaffTools.teleportTo(p, t.getEntityWorld(), Mc.vec(t.getEntityPos()), inv, t.getGameProfile().name());
                    return 1;
                }))));
        d.register(literal("waitingroom").requires(s -> Perms.visible(s, Perm.WAITING_ROOM))
                .then(literal("set").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.WAITING_ROOM);
                    if (p == null) return 0;
                    WaitingRoom wr = Ac.get().waitingRoom;
                    PlayerSession s = Ac.session(p);
                    if (s.corner1 != null && s.corner2 != null && Mc.worldId(p.getEntityWorld()).equals(s.cornerWorld)) {
                        wr.data().area = new Area(s.cornerWorld, s.corner1.getX(), Math.min(s.corner1.getY(), s.corner2.getY()),
                                s.corner1.getZ(), s.corner2.getX(), Math.max(s.corner1.getY(), s.corner2.getY()) + 3, s.corner2.getZ());
                    }
                    wr.data().spawn = Mc.location(p);
                    WaitingRoomFeature.seedExisting();
                    Ac.markDirty("waiting");
                    Staff.log(p, "waitingroom-set", null, null, Mc.vec(p.getEntityPos()).formatExact());
                    Msg.ok(ctx.getSource(), "waiting.set");
                    return 1;
                })));
    }

    // ---- Arenas ----

    private static void registerArenas(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("arena")
                .then(literal("create").requires(s -> Perms.visible(s, Perm.ARENA_ADMIN)).then(Args.word("name").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.ARENA_ADMIN);
                    if (p == null) return 0;
                    Area a = selection(p, false);
                    if (a == null) return 0;
                    Arena ar = new Arena();
                    ar.name = Args.str(ctx, "name");
                    ar.area = a;
                    if (!Ac.get().arenas.addArena(ar)) {
                        Msg.err(ctx.getSource(), "arena.exists");
                        return 0;
                    }
                    Ac.markDirty("arenas");
                    Staff.log(p, "arena-create", null, ar.name, "");
                    Msg.ok(ctx.getSource(), "arena.created", ar.name);
                    ArenaMenu.edit(p, ar);
                    return 1;
                })))
                .then(literal("menu").requires(s -> Perms.visible(s, Perm.ARENA_ADMIN)).executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.ARENA_ADMIN);
                    if (p != null) {
                        ArenaMenu.list(p);
                    }
                    return 1;
                }))
                .then(literal("join").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) {
                        ArenaMenu.join(p);
                    }
                    return 1;
                }).then(Args.word("mode").executes(ctx -> arenaJoin(ctx, "sword"))
                        .then(Args.word("kit").executes(ctx -> arenaJoin(ctx, Args.str(ctx, "kit"))))))
                .then(literal("leave").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Ac.get().arenas.leaveQueue(p.getUuid());
                    if (Arenas.isSpectating(p)) {
                        Arenas.stopSpectating(p);
                    }
                    Msg.ok(ctx.getSource(), "arena.left-queue");
                    return 1;
                }))
                .then(literal("spectate").then(Args.word("name").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null) return 0;
                    Arena a = Ac.get().arenas.arena(Args.str(ctx, "name"));
                    if (a == null) {
                        Msg.err(ctx.getSource(), "arena.not-found");
                        return 0;
                    }
                    Arenas.spectate(p, a);
                    return 1;
                })))
                .then(literal("kit").requires(s -> Perms.visible(s, Perm.ARENA_ADMIN)).then(literal("save").then(Args.word("name").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.ARENA_ADMIN);
                    if (p == null) return 0;
                    Kit k = new Kit();
                    k.name = Args.str(ctx, "name").toLowerCase();
                    var inv = p.getInventory();
                    for (int i = 0; i < inv.size(); i++) {
                        ItemStack s = inv.getStack(i);
                        if (!s.isEmpty()) {
                            k.items.put(i, ItemConv.encode(s));
                        }
                    }
                    for (var e : p.getStatusEffects()) {
                        e.getEffectType().getKey().ifPresent(key -> k.effects.put(key.getValue().toString(), e.getAmplifier()));
                    }
                    k.description = java.util.List.of("Custom kit by " + p.getGameProfile().name());
                    Kit old = Ac.get().arenas.kit(k.name);
                    if (old != null) {
                        k.naturalRegen = old.naturalRegen;
                    }
                    Ac.get().arenas.saveKit(k);
                    Ac.markDirty("arenas");
                    Staff.log(p, "kit-save", null, k.name, k.items.size() + " stacks");
                    Msg.ok(ctx.getSource(), "arena.kit-saved", k.name);
                    return 1;
                }))))
                .then(literal("stats").executes(ctx -> arenaStats(ctx, null))
                        .then(Args.player("player").executes(ctx -> arenaStats(ctx, Args.str(ctx, "player"))))));
        d.register(literal("duel")
                .then(literal("accept").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    ServerPlayerEntity from = p == null ? null : Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (from != null) {
                        Arenas.acceptDuel(p, from);
                    }
                    return 1;
                })))
                .then(literal("deny").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null && Ac.get().arenas.denyDuel(p.getUuid()) != null) {
                        Msg.ok(ctx.getSource(), "duel.denied");
                    }
                    return 1;
                })))
                .then(Args.player("player").executes(ctx -> duel(ctx, "sword"))
                        .then(Args.word("kit").executes(ctx -> duel(ctx, Args.str(ctx, "kit"))))));
    }

    private static int arenaJoin(CommandContext<ServerCommandSource> ctx, String kit) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) return 0;
        Arena.Mode mode = Arena.Mode.parse(Args.str(ctx, "mode"));
        if (mode == null) {
            Msg.err(ctx.getSource(), "arena.bad-mode");
            return 0;
        }
        Arenas.join(p, mode, kit.toLowerCase());
        return 1;
    }

    private static int duel(CommandContext<ServerCommandSource> ctx, String kit) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) return 0;
        ServerPlayerEntity t = Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
        if (t == null || t == p) return 0;
        Arenas.duel(p, t, kit.toLowerCase());
        return 1;
    }

    private static int arenaStats(CommandContext<ServerCommandSource> ctx, String name) {
        UUID id;
        if (name == null) {
            ServerPlayerEntity p = self(ctx);
            if (p == null) return 0;
            id = p.getUuid();
        } else {
            id = Args.known(ctx.getSource(), name);
            if (id == null) return 0;
        }
        ArenaManager.Stats s = Ac.get().arenas.stats(id);
        Msg.ok(ctx.getSource(), "arena.stats", Args.nameOf(id, "?"), s.wins, s.losses, s.kills);
        for (var e : s.perKit.entrySet()) {
            int[] v = e.getValue();
            ctx.getSource().sendFeedback(() -> Msg.text("§7  " + e.getKey() + ": §a" + v[0] + "W §c" + v[1] + "L §f" + v[2] + " kills"), false);
        }
        return 1;
    }

    // ---- Traders and trading ----

    private static void registerTraders(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("trader").requires(s -> Perms.visible(s, Perm.TRADER_ADMIN))
                .then(literal("stick").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.TRADER_ADMIN);
                    if (p != null) {
                        p.getInventory().insertStack(Tools.traderStick());
                        Msg.ok(ctx.getSource(), "trader.stick-given");
                    }
                    return 1;
                }))
                .then(literal("create").executes(ctx -> {
                    ServerPlayerEntity p = staff(ctx, Perm.TRADER_ADMIN);
                    if (p == null) return 0;
                    HitResult hit = p.raycast(6, 1f, false);
                    BlockPos on = hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : p.getBlockPos().down();
                    var t = Traders.create(p, p.getEntityWorld(), on, p.getYaw());
                    if (t != null) {
                        Traders.openEdit(p, t);
                    }
                    return 1;
                }))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.TRADER_ADMIN)) return 0;
                    int i = 1;
                    for (var t : Ac.get().traders.traders.values()) {
                        int n = i++;
                        MutableText line = Msg.text("§e" + n + ". §f" + t.name + " §7" + t.specialty + " " + t.type + " "
                                + t.location.world().replace("minecraft:", "") + " " + (int) t.location.x() + " " + (int) t.location.y() + " " + (int) t.location.z() + " ");
                        line.append(Msg.button("§b[TP]", "/ac tp " + t.location.world() + " " + (int) t.location.x() + " " + (int) t.location.y() + " " + (int) t.location.z(), "Teleport"));
                        line.append(Text.literal(" ")).append(Msg.button("§e[Edit]", "/trader edit " + n, "Edit"));
                        line.append(Text.literal(" ")).append(Msg.button("§c[Remove]", "/trader remove " + n, "Remove"));
                        ctx.getSource().sendFeedback(() -> line, false);
                    }
                    return 1;
                }))
                .then(literal("edit").then(Args.word("number").executes(ctx -> traderByIndex(ctx, false))))
                .then(literal("remove").then(Args.word("number").executes(ctx -> traderByIndex(ctx, true)))));
        d.register(literal("trade")
                .then(literal("accept").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    ServerPlayerEntity from = p == null ? null : Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (from != null) {
                        Trades.accept(p, from);
                    }
                    return 1;
                })))
                .then(literal("deny").then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    ServerPlayerEntity from = p == null ? null : Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (from != null) {
                        Trades.deny(p, from);
                    }
                    return 1;
                })))
                .then(Args.player("player").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    ServerPlayerEntity t = p == null ? null : Args.requireOnline(ctx.getSource(), Args.str(ctx, "player"));
                    if (t != null) {
                        Trades.request(p, t);
                    }
                    return 1;
                })));
    }

    private static int traderByIndex(CommandContext<ServerCommandSource> ctx, boolean remove) {
        ServerPlayerEntity p = staff(ctx, Perm.TRADER_ADMIN);
        if (p == null) return 0;
        int n;
        try {
            n = Integer.parseInt(Args.str(ctx, "number"));
        } catch (NumberFormatException e) {
            Msg.err(ctx.getSource(), "general.bad-number");
            return 0;
        }
        var list = new java.util.ArrayList<>(Ac.get().traders.traders.values());
        if (n < 1 || n > list.size()) {
            Msg.err(ctx.getSource(), "trader.not-found");
            return 0;
        }
        var t = list.get(n - 1);
        if (remove) {
            Traders.confirmRemove(p, t);
        } else {
            Traders.openEdit(p, t);
        }
        return 1;
    }
}
