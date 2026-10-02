package com.vylorq.anticheat.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.market.Market;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.Markets;
import com.vylorq.anticheat.feature.Shops;
import com.vylorq.anticheat.feature.Staff;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.literal;

/** Cash (/balance, /pay, /baltop, /eco), the auction house, buy orders, /collect, bounties and player shops. */
public final class MarketCommands {
    private MarketCommands() {
    }

    private static ServerPlayerEntity self(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity p = ctx.getSource().getPlayer();
        if (p == null) {
            Msg.err(ctx.getSource(), "general.players-only");
            return null;
        }
        if (!Markets.enabled()) {
            Msg.err(ctx.getSource(), "market.disabled");
            return null;
        }
        return p;
    }

    private static int num(CommandContext<ServerCommandSource> ctx, String name) {
        return IntegerArgumentType.getInteger(ctx, name);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, Integer> amount(String name) {
        return CommandManager.argument(name, IntegerArgumentType.integer(1));
    }

    private static Market m() {
        return Ac.get().market;
    }

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        // ---- cash ----
        for (String name : new String[]{"balance", "bal"}) {
            d.register(literal(name).executes(ctx -> {
                ServerPlayerEntity p = self(ctx);
                if (p == null) return 0;
                Msg.ok(ctx.getSource(), "cash.balance", Markets.cash(m().balance(p.getUuid())));
                return 1;
            }).then(Args.player("player").executes(ctx -> {
                if (self(ctx) == null) return 0;
                UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
                if (id == null) return 0;
                Msg.ok(ctx.getSource(), "cash.balance-of", Args.nameOf(id, Args.str(ctx, "player")), Markets.cash(m().balance(id)));
                return 1;
            })));
        }
        d.register(literal("pay").then(Args.player("player").then(amount("amount").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p == null) return 0;
            UUID to = Args.known(ctx.getSource(), Args.str(ctx, "player"));
            if (to == null) return 0;
            int amount = num(ctx, "amount");
            Market.Result r = m().pay(p.getUuid(), to, amount);
            if (r != Market.Result.OK) {
                Msg.err(ctx.getSource(), r == Market.Result.NOT_ENOUGH ? "cash.not-enough" : "market.r." + r.name().toLowerCase(java.util.Locale.ROOT));
                return 0;
            }
            Ac.markDirty("market");
            String toName = Args.nameOf(to, Args.str(ctx, "player"));
            Msg.ok(ctx.getSource(), "cash.paid", Markets.cash(amount), toName);
            Markets.alert(to, "cash.received-title", "cash.received", Markets.cash(amount), p.getGameProfile().name());
            Ac.get().logs.trade(System.currentTimeMillis(), "pay", p.getUuid(), p.getGameProfile().name(), to, toName, String.valueOf(amount));
            return 1;
        }))));
        d.register(literal("baltop").executes(ctx -> {
            if (Ac.get() == null) return 0;
            Msg.ok(ctx.getSource(), "cash.top-head");
            int i = 0;
            for (Map.Entry<UUID, Long> e : m().richest(10)) {
                String line = "§6#" + (++i) + " §f" + Args.nameOf(e.getKey(), "?") + " §8» §e" + Markets.cash(e.getValue());
                ctx.getSource().sendFeedback(() -> Text.literal(line), false);
            }
            return 1;
        }));
        d.register(literal("eco").requires(s -> Perms.visible(s, Perm.SETTINGS))
                .then(literal("give").then(Args.player("player").then(amount("amount").executes(ctx -> eco(ctx, "give")))))
                .then(literal("take").then(Args.player("player").then(amount("amount").executes(ctx -> eco(ctx, "take")))))
                .then(literal("set").then(Args.player("player").then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> eco(ctx, "set"))))));

        // ---- auction house, orders, pickups ----
        d.register(literal("ah").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Markets.openAuctions(p);
            return 1;
        }).then(literal("sell").then(amount("price").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Markets.sellHeld(p, num(ctx, "price"));
            return 1;
        }))));
        d.register(literal("orders").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Markets.openOrders(p);
            return 1;
        }));
        d.register(literal("order").then(Args.word("item").suggests((c, b) -> {
            String typed = b.getRemaining().toLowerCase(java.util.Locale.ROOT);
            int n = 0;
            for (var id : Registries.ITEM.getIds()) {
                String s = id.getPath();
                if (s.startsWith(typed) && n++ < 50) {
                    b.suggest(s);
                }
            }
            return b.buildFuture();
        }).then(amount("amount").then(amount("price").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Markets.createOrder(p, Args.str(ctx, "item"), num(ctx, "amount"), num(ctx, "price"));
            return 1;
        })))));
        d.register(literal("collect").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Markets.collect(p);
            return 1;
        }));

        // ---- bounties ----
        d.register(literal("bounty").then(Args.player("player").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p == null) return 0;
            UUID target = Args.known(ctx.getSource(), Args.str(ctx, "player"));
            if (target == null) return 0;
            Markets.placeBounty(p, target, Args.nameOf(target, Args.str(ctx, "player")));
            return 1;
        })));
        d.register(literal("bounties").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Markets.listBounties(p);
            return 1;
        }));

        // ---- player shops ----
        d.register(literal("shop").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) Msg.send(p, "shop.help");
                    return 1;
                })
                .then(literal("sell").then(amount("price").then(Args.word("payment").suggests(MarketCommands::paySuggest)
                        .executes(ctx -> shop(ctx, true, null))
                        .then(amount("per_sale").executes(ctx -> shop(ctx, true, num(ctx, "per_sale")))))))
                .then(literal("buy").then(amount("price").then(Args.word("payment").suggests(MarketCommands::paySuggest)
                        .executes(ctx -> shop(ctx, false, null))
                        .then(amount("per_sale").executes(ctx -> shop(ctx, false, num(ctx, "per_sale")))))))
                .then(literal("remove").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) Shops.removeNearest(p);
                    return 1;
                }))
                .then(literal("list").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) Shops.list(p, true);
                    return 1;
                })));
        d.register(literal("booth").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.openMine(p);
                    return 1;
                })
                .then(literal("claim").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.claimNearest(p);
                    return 1;
                }))
                .then(literal("add").then(amount("price").then(Args.word("payment").suggests(MarketCommands::paySuggest).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.add(p, num(ctx, "price"), Args.str(ctx, "payment"));
                    return 1;
                }))))
                .then(literal("offers").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.offers(p);
                    return 1;
                }))
                .then(literal("accept").then(CommandManager.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.accept(p, com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"));
                    return 1;
                })))
                .then(literal("decline").then(CommandManager.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.decline(p, com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id"));
                    return 1;
                })))
                .then(literal("leave").executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p != null) com.vylorq.anticheat.feature.Booths.leave(p);
                    return 1;
                }))
                .then(literal("create").requires(s -> Perms.visible(s, Perm.TRADER_ADMIN)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || !Perms.check(ctx.getSource(), Perm.TRADER_ADMIN)) return 0;
                    com.vylorq.anticheat.feature.Booths.create(p);
                    return 1;
                }))
                .then(literal("delete").requires(s -> Perms.visible(s, Perm.TRADER_ADMIN)).executes(ctx -> {
                    ServerPlayerEntity p = self(ctx);
                    if (p == null || !Perms.check(ctx.getSource(), Perm.TRADER_ADMIN)) return 0;
                    com.vylorq.anticheat.feature.Booths.delete(p);
                    return 1;
                })));
        d.register(literal("booths").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) com.vylorq.anticheat.feature.Booths.list(p);
            return 1;
        }));
        d.register(literal("shops").executes(ctx -> {
            ServerPlayerEntity p = self(ctx);
            if (p != null) Shops.list(p, false);
            return 1;
        }));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> paySuggest(
            CommandContext<ServerCommandSource> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        b.suggest("cash");
        b.suggest("emeralds");
        b.suggest("diamond");
        b.suggest("iron_ingot");
        b.suggest("gold_ingot");
        return b.buildFuture();
    }

    private static int shop(CommandContext<ServerCommandSource> ctx, boolean selling, Integer perSale) {
        ServerPlayerEntity p = self(ctx);
        if (p == null) return 0;
        Shops.create(p, selling, num(ctx, "price"), Args.str(ctx, "payment"), perSale);
        return 1;
    }

    private static int eco(CommandContext<ServerCommandSource> ctx, String what) {
        if (!Perms.check(ctx.getSource(), Perm.SETTINGS)) return 0;
        UUID id = Args.known(ctx.getSource(), Args.str(ctx, "player"));
        if (id == null) return 0;
        int amount = num(ctx, "amount");
        switch (what) {
            case "give" -> m().addCash(id, amount);
            case "take" -> m().setCash(id, Math.max(0, m().balance(id) - amount));
            default -> m().setCash(id, amount);
        }
        Ac.markDirty("market");
        String name = Args.nameOf(id, Args.str(ctx, "player"));
        Msg.ok(ctx.getSource(), "cash.balance-of", name, Markets.cash(m().balance(id)));
        Staff.log(ctx.getSource().getPlayer(), "eco-" + what, id, name, String.valueOf(amount));
        return 1;
    }
}
