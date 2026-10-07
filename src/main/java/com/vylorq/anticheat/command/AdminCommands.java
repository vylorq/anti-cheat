package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.ServerShop;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.gui.InventoryTools;
import com.vylorq.anticheat.gui.Notes;
import com.vylorq.anticheat.gui.Snapshots;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/** Player notes, inventory snapshots, the item blacklist and the server shop. */
public final class AdminCommands {
    private AdminCommands() {
    }

    private interface WithTarget {
        int run(ServerPlayerEntity admin, UUID target);
    }

    private static int target(CommandContext<ServerCommandSource> ctx, Perm perm, WithTarget then) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
            return 0;
        }
        if (!Perms.check(ctx.getSource(), perm)) {
            return 0;
        }
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        return id == null ? 0 : then.run(p, id);
    }

    private static int owner(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
                             java.util.function.Consumer<ServerPlayerEntity> action) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            return 0;
        }
        action.accept(p);
        return 1;
    }

    private static int replay(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx,
                              java.util.function.Consumer<ServerPlayerEntity> action) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null || !Perms.check(ctx.getSource(), Perm.REVIEW)) {
            return 0;
        }
        action.accept(p);
        return 1;
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        // ---- feature switches ----
        d.register(literal("features").requires(s -> Perms.visible(s, Perm.SETTINGS)).executes(ctx -> {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p != null) {
                com.vylorq.anticheat.gui.FeaturesMenu.open(p);
            } else {
                StringBuilder b = new StringBuilder();
                for (var f : com.vylorq.anticheat.feature.Features.Feature.values()) {
                    b.append(f.id).append(com.vylorq.anticheat.feature.Features.on(f) ? "=on " : "=off ");
                }
                ctx.getSource().sendFeedback(() -> Text.literal(b.toString().trim()), false);
            }
            return 1;
        }));
        d.register(literal("feature").requires(s -> Perms.visible(s, Perm.SETTINGS))
                .then(Args.word("feature").suggests((c, b) -> {
                    for (var f : com.vylorq.anticheat.feature.Features.Feature.values()) {
                        b.suggest(f.id);
                    }
                    return b.buildFuture();
                }).then(literal("on").executes(ctx -> feature(ctx, true)))
                        .then(literal("off").executes(ctx -> feature(ctx, false)))));

        // ---- owner powers (only the owner sees these commands) ----
        java.util.function.Predicate<ServerCommandSource> ownerOnly = s -> s.getPlayer() != null
                && com.vylorq.anticheat.perm.Perms.isOwner(s.getPlayer().getUuid());
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> owner = literal("owner").requires(ownerOnly)
                .executes(ctx -> owner(ctx, com.vylorq.anticheat.gui.OwnerMenu::open));
        for (var pw : com.vylorq.anticheat.feature.OwnerPowers.Power.values()) {
            String name = pw.name().toLowerCase(java.util.Locale.ROOT).replace("_", "");
            owner.then(literal(name).executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.toggle(p, pw))));
        }
        owner.then(literal("speed").then(CommandManager.argument("level", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 3))
                .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.setSpeed(p,
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "level"))))));
        owner.then(literal("tools").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerTools.all()
                .forEach(t -> com.vylorq.anticheat.feature.OwnerTools.give(p, t)))));
        owner.then(literal("freezeradius").then(CommandManager.argument("blocks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 100))
                .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.setFreezeRadius(p,
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "blocks"))))));
        // /owner goto <x> <z> [y]: straight there, standing on safe ground (any distance, inside the world border)
        var gx = CommandManager.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000));
        var gz = CommandManager.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000));
        owner.then(literal("goto").then(gx.then(gz
                .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.goTo(p,
                        IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"), null)))
                .then(CommandManager.argument("y", IntegerArgumentType.integer(-64, 320))
                        .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.goTo(p,
                                IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z"),
                                IntegerArgumentType.getInteger(ctx, "y"))))))));
        owner.then(literal("combat").executes(ctx -> owner(ctx, com.vylorq.anticheat.gui.OwnerMenu::combat)));
        owner.then(literal("weapons").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerCombat.weapons()
                .forEach(t -> com.vylorq.anticheat.feature.OwnerTools.give(p, t)))));
        owner.then(literal("berserk").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.OwnerCombat::berserk)));
        owner.then(literal("mobwipe").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.OwnerCombat::mobWipe))
                .then(CommandManager.argument("blocks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(4, 128))
                        .executes(ctx -> owner(ctx, p -> {
                            com.vylorq.anticheat.feature.OwnerPowers.setWipeRadius(p, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "blocks"));
                            com.vylorq.anticheat.feature.OwnerCombat.mobWipe(p);
                        }))));
        owner.then(literal("orbital").executes(ctx -> owner(ctx, com.vylorq.anticheat.gui.OwnerMenu::orbital))
                .then(literal("fire").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.OrbitalStrike::fire)))
                .then(literal("cancel").executes(ctx -> owner(ctx, p -> {
                    if (com.vylorq.anticheat.feature.OwnerPowers.require(p)) {
                        com.vylorq.anticheat.feature.OrbitalStrike.cancelAll(p);
                    }
                })))
                .then(literal("undo").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OrbitalStrike.undo(p, false)))
                        .then(literal("all").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OrbitalStrike.undo(p, true))))));
        var secret = literal("secret");
        for (String id : com.vylorq.anticheat.feature.SecretItems.ALL) {
            secret.then(literal(id).executes(ctx -> owner(ctx, p -> {
                if (com.vylorq.anticheat.feature.OwnerPowers.require(p)) {
                    com.vylorq.anticheat.feature.SecretItems.give(p, id);
                }
            })));
        }
        owner.then(secret);
        var cooldown = literal("cooldown").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.SecretItems::listCooldowns));
        for (String id : com.vylorq.anticheat.feature.SecretItems.ALL) {
            cooldown.then(literal(id)
                    .then(literal("default").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.SecretItems.setCooldown(p, id, null))))
                    .then(CommandManager.argument("seconds", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 86400))
                            .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.SecretItems.setCooldown(p, id,
                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "seconds"))))));
        }
        owner.then(cooldown);
        var boss = literal("boss");
        var bossSpawn = literal("spawn");
        for (String id : com.vylorq.anticheat.feature.Bosses.kinds()) {
            bossSpawn.then(literal(id).executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.Bosses.ownerSpawn(p, id))));
        }
        boss.then(bossSpawn);
        boss.then(literal("rotate").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.Bosses::ownerRotate)));
        boss.then(literal("removeall").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.Bosses::ownerKillAll)));
        boss.then(literal("cursedkey").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.Bosses::ownerKey)));
        owner.then(boss);
        var structure = literal("structure");
        var structurePlace = literal("place");
        for (String n : com.vylorq.anticheat.feature.TestStructures.NAMES) {
            structurePlace.then(literal(n).executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.TestStructures.place(p, n))));
        }
        structure.then(structurePlace);
        structure.then(literal("remove").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.TestStructures.remove(p, null)))
                .then(CommandManager.argument("number", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.TestStructures.remove(p,
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "number"))))));
        structure.then(literal("list").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.TestStructures::list)));
        owner.then(structure);
        var build = literal("build");
        var buildPlace = literal("place");
        var buildRemove = literal("remove");
        for (String n : com.vylorq.anticheat.feature.Builds.NAMES) {
            buildPlace.then(literal(n).executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.Builds.place(p, n))));
            buildRemove.then(literal(n).executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.Builds.remove(p, n))));
        }
        owner.then(build.then(buildPlace).then(buildRemove)
                .then(literal("list").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.Builds::list))));
        owner.then(literal("tempest")
                .then(literal("place").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.TempestKeep::place)))
                .then(literal("remove").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.TempestKeep::remove)))
                .then(literal("reset").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.TempestKeep::reset)))
                .then(literal("info").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.TempestKeep::info))));
        var boiledSend = literal("send");
        for (String how : new String[]{"watch", "scare", "peek", "behind", "breakin", "glimpse", "footsteps", "knock", "door",
                "whisper", "torches"}) {
            boiledSend.then(literal(how).then(Args.player("player").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerSend(p,
                    Args.online(Args.str(ctx, "player")), how)))));
        }
        owner.then(literal("boiledone")
                .then(literal("on").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerToggle(p, true))))
                .then(literal("off").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerToggle(p, false))))
                .then(literal("info").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.BoiledOne::ownerInfo)))
                .then(literal("locate").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.BoiledOne::ownerLocate)))
                .then(literal("removeall").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.BoiledOne::ownerRemoveAll)))
                .then(literal("minutes").then(CommandManager.argument("minutes", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 10000))
                        .executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerMinutes(p,
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "minutes"))))))
                .then(literal("breakins")
                        .then(literal("always").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerBreakIns(p, true))))
                        .then(literal("rare").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerBreakIns(p, false)))))
                .then(literal("hunt").then(Args.player("player").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerHunt(p,
                        Args.online(Args.str(ctx, "player")), true)))))
                .then(literal("unhunt").then(Args.player("player").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerHunt(p,
                        Args.online(Args.str(ctx, "player")), false)))))
                .then(literal("hunted").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.BoiledOne::ownerHunted)))
                .then(literal("bases").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.BoiledOne::ownerBases)))
                .then(literal("lantern").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.BoiledOne::ownerLantern)))
                .then(literal("event")
                        .then(literal("start").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerEvent(p, true))))
                        .then(literal("stop").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.BoiledOne.ownerEvent(p, false)))))
                .then(boiledSend));
        owner.then(literal("smite").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.OwnerCombat::smite)));
        owner.then(literal("items").executes(ctx -> owner(ctx, com.vylorq.anticheat.gui.OwnerMenu::items)));
        owner.then(literal("pack").executes(ctx -> owner(ctx, com.vylorq.anticheat.feature.OwnerPowers::sendPack)));
        owner.then(literal("join").then(literal("normal").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.setJoinStyle(p, "normal"))))
                .then(literal("grand").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.setJoinStyle(p, "grand"))))
                .then(literal("silent").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerPowers.setJoinStyle(p, "silent")))));
        owner.then(literal("repair").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerTools.repair(p, false)))
                .then(literal("all").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerTools.repair(p, true)))));
        d.register(owner);
        d.register(literal("fly").requires(ownerOnly).executes(ctx -> owner(ctx,
                p -> com.vylorq.anticheat.feature.OwnerPowers.toggle(p, com.vylorq.anticheat.feature.OwnerPowers.Power.FLY))));
        d.register(literal("god").requires(ownerOnly).executes(ctx -> owner(ctx,
                p -> com.vylorq.anticheat.feature.OwnerPowers.toggle(p, com.vylorq.anticheat.feature.OwnerPowers.Power.GOD))));
        d.register(literal("repair").requires(ownerOnly).executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerTools.repair(p, false)))
                .then(literal("all").executes(ctx -> owner(ctx, p -> com.vylorq.anticheat.feature.OwnerTools.repair(p, true)))));

        // ---- evidence replay ----
        d.register(literal("replay").requires(s -> Perms.visible(s, Perm.REVIEW))
                .then(literal("pause").executes(ctx -> replay(ctx, com.vylorq.anticheat.feature.Replay::pause)))
                .then(literal("restart").executes(ctx -> replay(ctx, com.vylorq.anticheat.feature.Replay::restart)))
                .then(literal("stop").executes(ctx -> replay(ctx, p -> com.vylorq.anticheat.feature.Replay.stop(p, true))))
                .then(literal("speed").then(CommandManager.argument("speed", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0.1, 4))
                        .executes(ctx -> replay(ctx, p -> com.vylorq.anticheat.feature.Replay.speed(p,
                                com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "speed")))))));

        // ---- notes ----
        d.register(literal("note").requires(s -> Perms.visible(s, Perm.INSPECT))
                .then(Args.player("player").then(CommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> target(ctx, Perm.INSPECT, (p, id) -> {
                            Notes.add(p, id, StringArgumentType.getString(ctx, "text"));
                            Msg.ok(ctx.getSource(), "notes.added", Args.nameOf(id, "?"));
                            return 1;
                        })))));
        d.register(literal("notes").requires(s -> Perms.visible(s, Perm.INSPECT))
                .then(Args.player("player").executes(ctx -> target(ctx, Perm.INSPECT, (p, id) -> {
                    Notes.open(p, id);
                    return 1;
                }))));

        // ---- snapshots ----
        d.register(literal("snapshot").requires(s -> Perms.visible(s, Perm.INSPECT_EDIT))
                .then(Args.player("player").executes(ctx -> snapshot(ctx, ""))
                        .then(CommandManager.argument("note", StringArgumentType.greedyString())
                                .executes(ctx -> snapshot(ctx, StringArgumentType.getString(ctx, "note"))))));
        d.register(literal("snapshots").requires(s -> Perms.visible(s, Perm.INSPECT_EDIT))
                .then(Args.player("player").executes(ctx -> target(ctx, Perm.INSPECT_EDIT, (p, id) -> {
                    Snapshots.open(p, id);
                    return 1;
                }))));

        // ---- item blacklist ----
        d.register(literal("blacklist").requires(s -> Perms.visible(s, Perm.SETTINGS))
                .then(literal("list").executes(ctx -> {
                    if (!Perms.check(ctx.getSource(), Perm.SETTINGS)) return 0;
                    var items = Ac.config().itemBlacklist.items;
                    Msg.ok(ctx.getSource(), "blacklist.list", items.isEmpty() ? "-" : String.join(", ", items));
                    return 1;
                }))
                .then(literal("add").executes(ctx -> blacklist(ctx, null, true))
                        .then(Args.word("item").suggests((c, b) -> {
                            String typed = b.getRemaining().toLowerCase(java.util.Locale.ROOT);
                            int n = 0;
                            for (var id : Registries.ITEM.getIds()) {
                                if (id.getPath().startsWith(typed) && n++ < 50) {
                                    b.suggest(id.getPath());
                                }
                            }
                            return b.buildFuture();
                        }).executes(ctx -> blacklist(ctx, Args.str(ctx, "item"), true))))
                .then(literal("remove").then(Args.word("item").suggests((c, b) -> {
                    Ac.config().itemBlacklist.items.forEach(b::suggest);
                    return b.buildFuture();
                }).executes(ctx -> blacklist(ctx, Args.str(ctx, "item"), false)))));

        // ---- server shop ----
        for (String name : new String[]{"servershop", "sshop"}) {
            d.register(literal(name).executes(ctx -> {
                ServerPlayerEntity p = ctx.getSource().getPlayer();
                if (p != null) ServerShop.open(p);
                return 1;
            }).then(literal("add").requires(s -> Perms.visible(s, Perm.SETTINGS))
                    .then(CommandManager.argument("buy", IntegerArgumentType.integer(0))
                            .then(CommandManager.argument("sell", IntegerArgumentType.integer(0)).executes(ctx -> {
                                ServerPlayerEntity p = ctx.getSource().getPlayer();
                                if (p != null) {
                                    ServerShop.add(p, IntegerArgumentType.getInteger(ctx, "buy"), IntegerArgumentType.getInteger(ctx, "sell"));
                                }
                                return 1;
                            })))));
        }
    }

    private static int feature(CommandContext<ServerCommandSource> ctx, boolean on) {
        if (!Perms.check(ctx.getSource(), Perm.SETTINGS)) return 0;
        var f = com.vylorq.anticheat.feature.Features.byId(Args.str(ctx, "feature"));
        if (f == null) {
            Msg.err(ctx.getSource(), "features.unknown", Args.str(ctx, "feature"));
            return 0;
        }
        com.vylorq.anticheat.feature.Features.set(f, on);
        Staff.log(ctx.getSource().getPlayer(), on ? "feature-on" : "feature-off", null, f.id, "");
        Msg.ok(ctx.getSource(), on ? "features.turned-on" : "features.turned-off", Msg.tr("feature." + f.id));
        return 1;
    }

    private static int snapshot(CommandContext<ServerCommandSource> ctx, String note) {
        return target(ctx, Perm.INSPECT_EDIT, (p, id) -> {
            InventoryTools.Invs i = InventoryTools.get(id);
            if (i == null) {
                Msg.err(ctx.getSource(), "inspect.no-data");
                return 0;
            }
            Snapshots.take(id, i, p.getGameProfile().name(), note, false);
            Staff.log(p, "snapshot", id, Args.nameOf(id, "?"), note);
            Msg.ok(ctx.getSource(), "snap.taken", Args.nameOf(id, "?"));
            return 1;
        });
    }

    private static int blacklist(CommandContext<ServerCommandSource> ctx, String item, boolean add) {
        if (!Perms.check(ctx.getSource(), Perm.SETTINGS)) return 0;
        String id = item;
        if (id == null) {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null || p.getMainHandStack().isEmpty()) {
                Msg.err(ctx.getSource(), "market.hold");
                return 0;
            }
            id = Registries.ITEM.getId(p.getMainHandStack().getItem()).toString();
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        var list = Ac.config().itemBlacklist.items;
        String fid = id;
        if (add) {
            if (!list.contains(fid)) {
                list.add(fid);
            }
        } else {
            list.removeIf(e -> e.equals(fid) || ("minecraft:" + e).equals(fid));
        }
        Ac.get().configManager.save();
        Staff.log(ctx.getSource().getPlayer(), add ? "blacklist-add" : "blacklist-remove", null, fid, "");
        Msg.ok(ctx.getSource(), add ? "blacklist.added" : "blacklist.removed-entry", fid);
        return 1;
    }
}
