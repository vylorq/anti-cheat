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

    public static void register(CommandDispatcher<ServerCommandSource> d) {
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
