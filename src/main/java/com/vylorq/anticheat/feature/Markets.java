package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.items.IllegalItems;
import com.vylorq.anticheat.core.market.Market;
import com.vylorq.anticheat.core.trader.Enchants;
import com.vylorq.anticheat.core.trader.OfferEvaluator;
import com.vylorq.anticheat.core.trader.Trader;
import com.vylorq.anticheat.core.trader.TraderOffer;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.gui.Confirm;
import com.vylorq.anticheat.gui.Input;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Player market: auction house (/ah), buy orders (/orders), bounties (/bounty), the pickup box (/collect) and
 * the traders' daily deal. Money is the currency item (emeralds by default).
 */
public final class Markets {
    private static final SplittableRandom RANDOM = new SplittableRandom();

    private Markets() {
    }

    static AcConfig.MarketCfg cfg() {
        return Ac.config().market;
    }

    public static boolean enabled() {
        return Ac.running() && cfg().enabled;
    }

    public static Market market() {
        return Ac.get().market;
    }

    // ---------------------------------------------------------------- money

    /** Whether the market's money is cash (else it's an item). */
    public static boolean cashMode() {
        return "cash".equalsIgnoreCase(cfg().currency);
    }

    public static Item currency() {
        Identifier id = Identifier.tryParse(cfg().currency);
        Item i = id == null ? Items.AIR : Registries.ITEM.get(id);
        return i == Items.AIR ? Items.EMERALD : i;
    }

    public static String cash(long n) {
        return cfg().cashSymbol + String.format("%,d", n);
    }

    public static String money(int n) {
        return cashMode() ? cash(n) : n + " " + new ItemStack(currency()).getName().getString();
    }

    public static int countMoney(ServerPlayerEntity p) {
        if (cashMode()) {
            return (int) Math.min(Integer.MAX_VALUE, market().balance(p.getUuid()));
        }
        return countItem(p, currency());
    }

    /** Plain (no custom data) items of a kind in the inventory. */
    public static int countItem(ServerPlayerEntity p, Item c) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(c) && s.getComponentChanges().isEmpty()) {
                n += s.getCount();
            }
        }
        return n;
    }

    /** Takes the money if the player has it. @return false (and nothing taken) if not */
    public static boolean takeMoney(ServerPlayerEntity p, int amount) {
        if (amount <= 0) {
            return false;
        }
        if (cashMode()) {
            boolean ok = market().takeCash(p.getUuid(), amount);
            if (ok) {
                Ac.markDirty("market");
            }
            return ok;
        }
        return takeItem(p, currency(), amount);
    }

    /** Takes plain items of a kind. @return false (nothing taken) if there aren't enough */
    public static boolean takeItem(ServerPlayerEntity p, Item c, int amount) {
        if (countItem(p, c) < amount) {
            return false;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(c) && s.getComponentChanges().isEmpty()) {
                int take = Math.min(amount, s.getCount());
                s.decrement(take);
                amount -= take;
            }
        }
        inv.markDirty();
        return true;
    }

    public static void giveMoney(ServerPlayerEntity p, int amount) {
        if (cashMode()) {
            market().addCash(p.getUuid(), amount);
            Ac.markDirty("market");
            return;
        }
        giveItem(p, currency(), amount);
    }

    public static void giveItem(ServerPlayerEntity p, Item c, int amount) {
        List<ItemStack> out = new ArrayList<>();
        while (amount > 0) {
            int n = Math.min(amount, c.getMaxCount());
            out.add(new ItemStack(c, n));
            amount -= n;
        }
        Trades.giveBack(p, out);
    }

    private static ServerPlayerEntity online(UUID id) {
        return id == null ? null : Ac.server().getPlayerManager().getPlayer(id);
    }

    private static void tell(UUID id, String key, Object... args) {
        ServerPlayerEntity o = online(id);
        if (o != null) {
            Msg.send(o, key, args);
        }
    }

    /**
     * Shows something on a player's screen (title + chat + sound). If they're offline it waits for their next join.
     */
    public static void alert(UUID id, String titleKey, String lineKey, Object... args) {
        ServerPlayerEntity o = online(id);
        if (o == null) {
            Ac.get().pendingMessage(id, Msg.tr(lineKey, args));
            return;
        }
        Mc.title(o, Msg.trFor(o, titleKey), Msg.trFor(o, lineKey, args), 5, 70, 15);
        Mc.sound(o, SoundEvents.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
        o.sendMessage(Msg.prefixed(Msg.trFor(o, lineKey, args)));
    }

    /** Tells a player to collect what's waiting, with a button. */
    private static void tellCollect(UUID id) {
        ServerPlayerEntity o = online(id);
        if (o != null) {
            MutableText m = Msg.prefixed(Msg.trFor(o, "collect.waiting"));
            m.append(" ").append(Msg.button("§a[" + Msg.trFor(o, "collect.button") + "]", "/collect", ""));
            o.sendMessage(m);
        }
    }

    /** Items that can't be sold or put up as a reward (boxes hide what's inside; illegal items never). */
    private static String refuse(ServerPlayerEntity p, ItemStack s) {
        var info = ItemConv.info(s);
        if (OfferEvaluator.forbiddenPayment(info)) {
            return "market.no-containers";
        }
        String illegal = IllegalItems.check(info, Ac.config().illegalItems.bannedItems, Enchants.MAX_LEVELS);
        if (illegal != null) {
            Illegal.handle(p, s, illegal);
            return "market.illegal";
        }
        return null;
    }

    private static ItemStack takeHeld(ServerPlayerEntity p) {
        ItemStack held = p.getMainHandStack().copy();
        p.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        return held;
    }

    // ---------------------------------------------------------------- pickups

    public static void collect(ServerPlayerEntity p) {
        List<String> items = market().takeItems(p.getUuid());
        int coins = market().takeCoins(p.getUuid());
        Map<String, Integer> owed = market().takeOwedItems(p.getUuid());
        if (items.isEmpty() && coins == 0 && owed.isEmpty()) {
            Msg.send(p, "collect.none");
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (String e : items) {
            ItemStack s = ItemConv.decode(e);
            if (!s.isEmpty()) {
                stacks.add(s);
            }
        }
        for (Map.Entry<String, Integer> e : owed.entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            Item item = id == null ? Items.AIR : Registries.ITEM.get(id);
            int left = e.getValue();
            while (item != Items.AIR && left > 0) {
                int n = Math.min(left, item.getMaxCount());
                stacks.add(new ItemStack(item, n));
                left -= n;
            }
        }
        Trades.giveBack(p, stacks);
        if (coins > 0) {
            giveMoney(p, coins);
        }
        Ac.markDirty("market");
        Mc.sound(p, SoundEvents.ENTITY_ITEM_PICKUP, 1f, 1f);
        Msg.send(p, "collect.got", stacks.size(), money(coins));
        Ac.get().logs.trade(System.currentTimeMillis(), "collect", p.getUuid(), p.getGameProfile().name(), null, null,
                stacks.size() + " items, " + coins + " money");
    }

    public static void onJoin(ServerPlayerEntity p) {
        if (!enabled()) {
            return;
        }
        if (!market().hasWallet(p.getUuid())) {
            market().setCash(p.getUuid(), cfg().startingCash);
            Ac.markDirty("market");
        }
        if (market().hasPickups(p.getUuid())) {
            tellCollect(p.getUuid());
        }
        double bounty = market().bountyValue(p.getUuid());
        if (bounty > 0) {
            Msg.send(p, "bounty.on-you", Traders.fmt(bounty));
        }
        String deal = dealLine(p);
        if (deal != null) {
            p.sendMessage(Msg.prefixed(deal));
        }
    }

    // ---------------------------------------------------------------- auctions

    public static void sellHeld(ServerPlayerEntity p, int price) {
        if (p.getMainHandStack().isEmpty()) {
            Msg.send(p, "market.hold");
            return;
        }
        if (price < 1) {
            Msg.send(p, "market.bad-price");
            return;
        }
        if (market().auctionsBy(p.getUuid()) >= cfg().maxAuctionsPerPlayer) {
            Msg.send(p, "ah.too-many", cfg().maxAuctionsPerPlayer);
            return;
        }
        String why = refuse(p, p.getMainHandStack());
        if (why != null) {
            Msg.send(p, why);
            return;
        }
        ItemStack s = takeHeld(p);
        String name = s.getCount() + "x " + s.getName().getString();
        Market.Auction a = market().list(p.getUuid(), p.getGameProfile().name(), ItemConv.encode(s), name, price,
                cfg().auctionHours * Durations.HOUR);
        Ac.markDirty("market");
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            MutableText m = Msg.prefixed(Msg.trFor(o, "ah.listed", p.getGameProfile().name(), name, money(price)));
            m.append(" ").append(Msg.button("§e[" + Msg.trFor(o, "ah.open") + "]", "/ah", ""));
            o.sendMessage(m);
        }
        Ac.get().logs.trade(System.currentTimeMillis(), "auction-list", p.getUuid(), p.getGameProfile().name(), null, null,
                "#" + a.id + " " + name + " from " + price);
    }

    public static void bid(ServerPlayerEntity p, long id, int amount) {
        Market.Auction a = market().data().auctions.get(id);
        if (a == null) {
            Msg.send(p, "ah.gone");
            return;
        }
        if (amount < Market.minBid(a)) {
            Msg.send(p, "ah.too-low", money(Market.minBid(a)));
            return;
        }
        if (!takeMoney(p, amount)) {
            Msg.send(p, "market.no-money", money(amount));
            return;
        }
        Market.BidResult r = market().bid(id, p.getUuid(), p.getGameProfile().name(), amount);
        if (r.result() != Market.Result.OK) {
            giveMoney(p, amount);
            Msg.send(p, "market.r." + r.result().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        Ac.markDirty("market");
        Mc.sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Msg.send(p, "ah.bid-placed", money(amount), a.itemName);
        if (r.refundTo() != null) {
            tell(r.refundTo(), "ah.outbid", a.itemName, money(amount));
            tellCollect(r.refundTo());
        }
        tell(a.seller, "ah.new-bid", p.getGameProfile().name(), money(amount), a.itemName);
        Ac.get().logs.trade(System.currentTimeMillis(), "auction-bid", p.getUuid(), p.getGameProfile().name(), a.seller, a.sellerName,
                "#" + a.id + " " + a.itemName + " bid " + amount);
    }

    public static void openAuctions(ServerPlayerEntity p) {
        if (!enabled()) {
            Msg.send(p, "market.disabled");
            return;
        }
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "ah.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.GOLD_BLOCK).color(Theme.GOLD_LIGHT).name(Msg.tr("ah.title")).desc(Msg.tr("ah.desc"))
                    .line(Msg.tr("market.you-have", money(countMoney(p)))).build());
            List<Market.Auction> all = market().auctions();
            all.sort(Comparator.comparingLong(a -> a.endsAt));
            menu.list(all, a -> auctionIcon(p, a), a -> (pl, c) -> {
                if (a.seller.equals(pl.getUuid())) {
                    if (c.isShift()) {
                        Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "ah.cancel"), a.itemName, ItemConv.decode(a.item), () -> {
                            Market.Result r = market().cancelAuction(a.id, pl.getUuid(), false);
                            Ac.markDirty("market");
                            if (r == Market.Result.OK) {
                                collect(pl);
                            } else {
                                Msg.send(pl, "market.r." + r.name().toLowerCase(java.util.Locale.ROOT));
                            }
                        });
                    }
                    return;
                }
                if (c.isRight()) {
                    Input.text(pl, Msg.trFor(pl, "ah.your-bid"), String.valueOf(Market.minBid(a)), txt -> {
                        try {
                            bid(pl, a.id, Integer.parseInt(txt.trim()));
                        } catch (NumberFormatException | NullPointerException e) {
                            Msg.send(pl, "market.bad-price");
                        }
                        openAuctions(pl);
                    });
                } else {
                    bid(pl, a.id, Market.minBid(a));
                    menu.refresh();
                }
            }, a -> a.itemName + " " + a.sellerName, List.of(), Msg.tr("ah.empty"), Msg.tr("ah.empty-hint"));
            menu.set(46, Btn.of(Items.WRITABLE_BOOK).color(Theme.GREEN).name(Msg.tr("ah.sell")).desc(Msg.tr("ah.sell-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b[/ah sell <price>]", "/ah sell ", ""));
            });
            menu.set(48, collectButton(p), null, (pl, c) -> {
                collect(pl);
                menu.refresh();
            });
            menu.set(50, Btn.of(Items.HOPPER).color(Theme.GOLD_LIGHT).name(Msg.tr("orders.title")).desc(Msg.tr("orders.desc"))
                    .count(market().orders().size()).build(), null, (pl, c) -> openOrders(pl));
        });
        m.open(p);
    }

    private static ItemStack collectButton(ServerPlayerEntity p) {
        boolean has = market().hasPickups(p.getUuid());
        return Btn.of(has ? Items.CHEST_MINECART : Items.MINECART).color(has ? Theme.GREEN : Theme.SOFT).name(Msg.tr("collect.title"))
                .desc(Msg.tr(has ? "collect.waiting" : "collect.none")).glint(has).build();
    }

    private static ItemStack auctionIcon(ServerPlayerEntity viewer, Market.Auction a) {
        ItemStack s = ItemConv.decode(a.item);
        if (s.isEmpty()) {
            s = new ItemStack(Items.BARRIER);
        }
        Btn b = Btn.of(s).color(Theme.GOLD_LIGHT).name(a.itemName)
                .line(Msg.tr("ah.seller", a.sellerName))
                .line(a.bidder == null ? Msg.tr("ah.start", money(a.startPrice)) : Msg.tr("ah.top-bid", money(a.bid), a.bidderName))
                .line(Msg.tr("ah.worth", Traders.fmt(Traders.stackValue(s))))
                .line(Msg.tr("ah.ends", Durations.format(Math.max(0, a.endsAt - System.currentTimeMillis()))));
        if (a.seller.equals(viewer.getUuid())) {
            b.status(Theme.GOLD, Msg.tr("ah.yours"));
            if (a.bidder == null) {
                b.shift(Msg.tr("ah.cancel"));
            }
        } else {
            if (viewer.getUuid().equals(a.bidder)) {
                b.status(Theme.GREEN, Msg.tr("ah.winning"));
            }
            b.left(Msg.tr("ah.bid-min", money(Market.minBid(a)))).right(Msg.tr("ah.bid-custom"));
        }
        return b.amount(s.getCount()).build();
    }

    // ---------------------------------------------------------------- buy orders

    public static void createOrder(ServerPlayerEntity p, String itemId, int amount, int price) {
        String id = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Identifier ident = Identifier.tryParse(id);
        Item item = ident == null ? Items.AIR : Registries.ITEM.get(ident);
        if (item == Items.AIR) {
            Msg.send(p, "orders.bad-item", itemId);
            return;
        }
        if (amount < 1 || amount > 64 * 36) {
            Msg.send(p, "orders.bad-amount");
            return;
        }
        if (price < 1) {
            Msg.send(p, "market.bad-price");
            return;
        }
        if (market().ordersBy(p.getUuid()) >= cfg().maxOrdersPerPlayer) {
            Msg.send(p, "orders.too-many", cfg().maxOrdersPerPlayer);
            return;
        }
        if (!takeMoney(p, price)) {
            Msg.send(p, "market.no-money", money(price));
            return;
        }
        String realId = Registries.ITEM.getId(item).toString();
        market().order(p.getUuid(), p.getGameProfile().name(), realId, amount, price);
        Ac.markDirty("market");
        String name = new ItemStack(item).getName().getString();
        Msg.send(p, "orders.placed", amount + "x " + name, money(price));
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            if (o != p) {
                MutableText m = Msg.prefixed(Msg.trFor(o, "orders.new", p.getGameProfile().name(), amount + "x " + name, money(price)));
                m.append(" ").append(Msg.button("§e[" + Msg.trFor(o, "orders.open") + "]", "/orders", ""));
                o.sendMessage(m);
            }
        }
    }

    private static int countPlain(ServerPlayerEntity p, Item item) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item) && s.getComponentChanges().isEmpty()) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static void takePlain(ServerPlayerEntity p, Item item, int amount) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size() && amount > 0; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(item) && s.getComponentChanges().isEmpty()) {
                int take = Math.min(amount, s.getCount());
                s.decrement(take);
                amount -= take;
            }
        }
        inv.markDirty();
    }

    public static void fillOrder(ServerPlayerEntity p, Market.Order o) {
        Item item = Registries.ITEM.get(Identifier.of(o.itemId));
        int have = countPlain(p, item);
        if (have <= 0) {
            Msg.send(p, "orders.none-to-give", new ItemStack(item).getName().getString());
            return;
        }
        Market.FillResult f = market().fill(o.id, p.getUuid(), have);
        if (f.result() != Market.Result.OK) {
            Msg.send(p, "market.r." + f.result().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        takePlain(p, item, f.count());
        int left = f.count();
        while (left > 0) {
            int n = Math.min(left, item.getMaxCount());
            market().owe(o.owner, ItemConv.encode(new ItemStack(item, n)));
            left -= n;
        }
        if (f.pay() > 0) {
            giveMoney(p, f.pay());
        }
        Ac.markDirty("market");
        String name = new ItemStack(item).getName().getString();
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        Msg.send(p, "orders.filled", f.count() + "x " + name, money(f.pay()));
        tell(o.owner, f.complete() ? "orders.done" : "orders.part", p.getGameProfile().name(), f.count() + "x " + name);
        tellCollect(o.owner);
        Teams.xpForTrade(p);
        Ac.get().logs.trade(System.currentTimeMillis(), "order-fill", p.getUuid(), p.getGameProfile().name(), o.owner, o.ownerName,
                f.count() + "x " + o.itemId + " for " + f.pay());
    }

    public static void openOrders(ServerPlayerEntity p) {
        if (!enabled()) {
            Msg.send(p, "market.disabled");
            return;
        }
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "ah.title"), Msg.trFor(p, "orders.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.HOPPER).color(Theme.GOLD_LIGHT).name(Msg.tr("orders.title")).desc(Msg.tr("orders.desc"))
                    .line(Msg.tr("market.you-have", money(countMoney(p)))).build());
            List<Market.Order> all = market().orders();
            menu.list(all, o -> orderIcon(p, o), o -> (pl, c) -> {
                if (o.owner.equals(pl.getUuid())) {
                    if (c.isShift()) {
                        Market.Result r = market().cancelOrder(o.id, pl.getUuid(), false);
                        Ac.markDirty("market");
                        if (r == Market.Result.OK) {
                            collect(pl);
                        }
                        menu.refresh();
                    }
                    return;
                }
                fillOrder(pl, o);
                menu.refresh();
            }, o -> o.itemId + " " + o.ownerName, List.of(), Msg.tr("orders.empty"), Msg.tr("orders.empty-hint"));
            menu.set(46, Btn.of(Items.WRITABLE_BOOK).color(Theme.GREEN).name(Msg.tr("orders.create")).desc(Msg.tr("orders.create-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b[/order <item> <amount> <price>]", "/order ", ""));
            });
            menu.set(48, collectButton(p), null, (pl, c) -> {
                collect(pl);
                menu.refresh();
            });
            menu.set(50, Btn.of(Items.GOLD_BLOCK).color(Theme.GOLD_LIGHT).name(Msg.tr("ah.title")).build(), null, (pl, c) -> openAuctions(pl));
        });
        m.open(p);
    }

    private static ItemStack orderIcon(ServerPlayerEntity viewer, Market.Order o) {
        Item item = Registries.ITEM.get(Identifier.of(o.itemId));
        ItemStack s = new ItemStack(item);
        int left = o.wanted - o.filled;
        int perItem = Math.max(0, o.price);
        Btn b = Btn.of(s).color(Theme.GOLD_LIGHT).name(s.getName().getString())
                .line(Msg.tr("orders.by", o.ownerName))
                .line(Msg.tr("orders.wants", left, o.wanted))
                .line(Msg.tr("orders.pays", money(perItem), o.wanted))
                .line(Msg.tr("orders.you-have", countPlain(viewer, item)));
        if (o.owner.equals(viewer.getUuid())) {
            b.status(Theme.GOLD, Msg.tr("ah.yours")).shift(Msg.tr("orders.cancel"));
        } else {
            b.left(Msg.tr("orders.fill"));
        }
        return b.amount(Math.max(1, Math.min(64, left))).build();
    }

    // ---------------------------------------------------------------- bounties

    public static void placeBounty(ServerPlayerEntity p, UUID target, String targetName) {
        if (!cfg().bounties) {
            Msg.send(p, "market.disabled");
            return;
        }
        if (target.equals(p.getUuid())) {
            Msg.send(p, "bounty.self");
            return;
        }
        if (p.getMainHandStack().isEmpty()) {
            Msg.send(p, "market.hold");
            return;
        }
        String why = refuse(p, p.getMainHandStack());
        if (why != null) {
            Msg.send(p, why);
            return;
        }
        ItemStack s = takeHeld(p);
        String name = s.getCount() + "x " + s.getName().getString();
        double value = Traders.stackValue(s);
        market().addBounty(target, targetName, p.getUuid(), p.getGameProfile().name(), ItemConv.encode(s), name, value);
        Ac.markDirty("market");
        double total = market().bountyValue(target);
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            Msg.send(o, "bounty.placed", p.getGameProfile().name(), targetName, name, Traders.fmt(total));
        }
        Ac.get().logs.trade(System.currentTimeMillis(), "bounty", p.getUuid(), p.getGameProfile().name(), target, targetName, name);
    }

    public static void listBounties(ServerPlayerEntity p) {
        List<Map.Entry<UUID, Double>> rows = new ArrayList<>();
        for (UUID id : new ArrayList<>(market().data().bounties.keySet())) {
            rows.add(Map.entry(id, market().bountyValue(id)));
        }
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Msg.send(p, "bounty.head");
        if (rows.isEmpty()) {
            Msg.send(p, "bounty.none");
        }
        for (int i = 0; i < Math.min(10, rows.size()); i++) {
            UUID id = rows.get(i).getKey();
            List<Market.Bounty> list = market().bountiesOn(id);
            StringBuilder items = new StringBuilder();
            for (Market.Bounty b : list) {
                items.append(items.length() == 0 ? "" : ", ").append(b.itemName);
            }
            String name = market().data().bountyNames.getOrDefault(id, "?");
            p.sendMessage(net.minecraft.text.Text.literal("§6#" + (i + 1) + " §c" + name + " §8» §e" + Traders.fmt(rows.get(i).getValue())
                    + " ◆ §7(" + items + ")"));
        }
    }

    /** A player was killed by another: they get every bounty on the victim (never from an alt on the same IP). */
    public static void onKill(ServerPlayerEntity victim, ServerPlayerEntity killer) {
        if (!enabled() || killer == null || killer == victim) {
            return;
        }
        if (market().bountiesOn(victim.getUuid()).isEmpty()) {
            return;
        }
        String vip = Ac.session(victim).ip;
        if (vip != null && vip.equals(Ac.session(killer).ip)) {
            return;
        }
        double value = market().bountyValue(victim.getUuid());
        List<Market.Bounty> won = market().claimBounties(victim.getUuid(), killer.getUuid());
        Ac.markDirty("market");
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            Msg.send(o, "bounty.claimed", killer.getGameProfile().name(), victim.getGameProfile().name(), Traders.fmt(value));
        }
        Mc.sound(killer, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        tellCollect(killer.getUuid());
        Ac.get().logs.trade(System.currentTimeMillis(), "bounty-claim", killer.getUuid(), killer.getGameProfile().name(),
                victim.getUuid(), victim.getGameProfile().name(), won.size() + " rewards");
    }

    // ---------------------------------------------------------------- daily deal

    /** Discount factor for an offer (0.7 when it's today's deal). */
    public static double dealFactor(Trader t, TraderOffer o) {
        return enabled() && market().isDeal(t.entity.toString(), o.signature()) ? 1 - cfg().dailyDealDiscount : 1;
    }

    /** Today's deal as a chat line, or null. */
    public static String dealLine(ServerPlayerEntity p) {
        Market.Data d = market().data();
        if (d.dealTrader == null) {
            return null;
        }
        Trader t = Ac.get().traders.traders.get(UUID.fromString(d.dealTrader));
        if (t == null) {
            return null;
        }
        for (TraderOffer o : t.offers) {
            if (o.signature().equals(d.dealSignature) && !o.soldOut()) {
                return Msg.trFor(p, "deal.today", Traders.stackFor(o).getName().getString(), t.name,
                        (int) Math.round(cfg().dailyDealDiscount * 100));
            }
        }
        return null;
    }

    private static void pickDeal() {
        String day = com.vylorq.anticheat.core.stats.AcStats.day(System.currentTimeMillis());
        Market.Data d = market().data();
        if (day.equals(d.dealDay)) {
            return;
        }
        List<Object[]> pool = new ArrayList<>();
        for (Trader t : Ac.get().traders.traders.values()) {
            if (t.type != Trader.Type.SELLS) {
                continue;
            }
            for (TraderOffer o : t.offers) {
                if (!o.soldOut()) {
                    pool.add(new Object[]{t, o});
                }
            }
        }
        if (pool.isEmpty()) {
            return;
        }
        Object[] pick = pool.get(RANDOM.nextInt(pool.size()));
        Trader t = (Trader) pick[0];
        TraderOffer o = (TraderOffer) pick[1];
        market().setDeal(day, t.entity.toString(), o.signature());
        Ac.markDirty("market");
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            String line = dealLine(p);
            if (line != null) {
                p.sendMessage(Msg.prefixed(line));
            }
        }
    }

    // ---------------------------------------------------------------- timers

    private static int seconds;

    public static void tick() {
        if (!enabled()) {
            return;
        }
        if (cashMode() && !market().data().coins.isEmpty()) {
            // Cash owed (refunds, sales) goes straight into wallets.
            for (UUID id : new ArrayList<>(market().data().coins.keySet())) {
                market().addCash(id, market().takeCoins(id));
            }
            Ac.markDirty("market");
        }
        for (Market.Auction a : market().settle()) {
            Ac.markDirty("market");
            if (a.bidder != null) {
                alert(a.bidder, "ah.won-title", "ah.won", a.itemName, money(a.bid));
                tellCollect(a.bidder);
                alert(a.seller, "ah.sold-title", "ah.sold", a.itemName, money(a.bid), a.bidderName);
                Teams.xpForTrade(online(a.bidder));
                Teams.xpForTrade(online(a.seller));
                Ac.get().logs.trade(System.currentTimeMillis(), "auction-won", a.bidder, a.bidderName, a.seller, a.sellerName,
                        "#" + a.id + " " + a.itemName + " for " + a.bid);
            } else {
                tell(a.seller, "ah.unsold", a.itemName);
            }
            tellCollect(a.seller);
        }
        if (++seconds % 60 == 0) {
            pickDeal();
        }
    }
}
