package com.vylorq.anticheat.core.market;

import com.vylorq.anticheat.core.util.Clock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The player market: auctions, buy orders, bounties, the pickup box (things owed to players, collected with
 * /collect), trader price history, trader reputation and the daily deal. Money is a count of the currency item
 * (emeralds by default); the server moves the real items, this keeps the books.
 */
public final class Market {
    public static final class Auction {
        public long id;
        public UUID seller;
        public String sellerName;
        /** The item for sale, encoded. */
        public String item;
        public String itemName;
        public int startPrice;
        /** Highest bid so far (0 = none). */
        public int bid;
        public UUID bidder;
        public String bidderName;
        public long endsAt;
    }

    public static final class Order {
        public long id;
        public UUID owner;
        public String ownerName;
        public String itemId;
        public int wanted;
        public int filled;
        /** Price for the whole order (already paid in by the owner). */
        public int price;
        public long created;
    }

    public static final class Bounty {
        public UUID placer;
        public String placerName;
        /** The reward, encoded. */
        public String item;
        public String itemName;
        public double value;
        public long created;
    }

    /** A player shop in the lobby: sells an item for a price, or buys it. */
    public static final class Shop {
        public long id;
        public UUID owner;
        public String ownerName;
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        /** true = the shop sells to players; false = it buys from them. */
        public boolean selling;
        /** One sale's worth of the item, encoded (its count is the amount per sale). */
        public String item;
        public String itemName;
        public int bundle;
        /** "cash" or an item id the price is paid in. */
        public String pay;
        public int price;
        /** Selling shops: items in stock. */
        public int stock;
        /** Buying shops paid in items: payment items held to pay sellers. */
        public int funds;
        /** The display entity. */
        public UUID entity;
        public long created;
        public long sales;
    }

    /** A booth in the lobby: admins place them, a player claims one and lists items in it. */
    public static final class Booth {
        public long id;
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        /** null while free. */
        public UUID owner;
        public String ownerName;
        public long claimed;
        public java.util.List<Listing> listings = new ArrayList<>();
        public UUID entity;
        public long sales;
    }

    /** One item for sale in a booth. */
    public static final class Listing {
        public long id;
        /** The whole stack for sale, encoded. */
        public String item;
        public String itemName;
        public int price;
        /** "cash" or an item id. */
        public String pay;
        public long listed;
    }

    /** A visitor's offer for a listing; the money is already held. */
    public static final class BoothOffer {
        public long id;
        public long booth;
        public long listing;
        public UUID buyer;
        public String buyerName;
        public int amount;
        public String pay;
        public String itemName;
        public long created;
    }

    /** Something the server itself sells and/or buys back at fixed prices (cash). */
    public static final class ServerItem {
        public long id;
        /** One sale's worth, encoded (its count is the amount per sale). */
        public String item;
        public String itemName;
        /** Price to buy from the server (0 = not for sale). */
        public int buy;
        /** What the server pays when players sell it (0 = doesn't buy). */
        public int sell;
    }

    public static final class Data {
        public long nextId = 1;
        public Map<Long, ServerItem> serverShop = new LinkedHashMap<>();
        public Map<Long, Booth> booths = new LinkedHashMap<>();
        public Map<Long, BoothOffer> boothOffers = new LinkedHashMap<>();
        /** Cash balances. */
        public Map<UUID, Long> cash = new LinkedHashMap<>();
        /** Plain items owed (item id -> count), e.g. shop earnings. */
        public Map<UUID, Map<String, Integer>> owedItems = new LinkedHashMap<>();
        public Map<Long, Shop> shops = new LinkedHashMap<>();
        public Map<Long, Auction> auctions = new LinkedHashMap<>();
        public Map<Long, Order> orders = new LinkedHashMap<>();
        /** Items owed to players (encoded stacks). */
        public Map<UUID, List<String>> pickups = new LinkedHashMap<>();
        /** Currency owed to players. */
        public Map<UUID, Integer> coins = new LinkedHashMap<>();
        /** Bounties by target. */
        public Map<UUID, List<Bounty>> bounties = new LinkedHashMap<>();
        public Map<UUID, String> bountyNames = new LinkedHashMap<>();
        /** Trader item signature -> [time, price factor] points, oldest first. */
        public Map<String, List<double[]>> history = new LinkedHashMap<>();
        /** "player|trader" -> deals made. */
        public Map<String, Integer> reputation = new LinkedHashMap<>();
        public String dealDay = "";
        public String dealTrader;
        public String dealSignature;
    }

    public enum Result { OK, NO_SUCH, ENDED, OWN, TOO_LOW, HAS_BIDS, NOT_YOURS, TOO_MANY, BAD_AMOUNT, NOT_ENOUGH, SOLD_OUT, TAKEN, FULL }

    /** What a booth sale or accepted offer did. */
    public record Sale(Result result, Booth booth, Listing listing, List<BoothOffer> refunded) {
    }

    public record BidResult(Result result, UUID refundTo, int refund) {
    }

    public record FillResult(Result result, int count, int pay, boolean complete) {
    }

    public static final int HISTORY_POINTS = 30;

    private final Data data;
    private final Clock clock;

    public Market(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    // ---------------------------------------------------------------- pickups

    public synchronized void owe(UUID player, String encodedItem) {
        data.pickups.computeIfAbsent(player, k -> new ArrayList<>()).add(encodedItem);
    }

    public synchronized void oweCoins(UUID player, int amount) {
        if (amount > 0) {
            data.coins.merge(player, amount, Integer::sum);
        }
    }

    /** Takes everything owed to the player. */
    public synchronized List<String> takeItems(UUID player) {
        List<String> l = data.pickups.remove(player);
        return l == null ? List.of() : l;
    }

    public synchronized int takeCoins(UUID player) {
        Integer c = data.coins.remove(player);
        return c == null ? 0 : c;
    }

    public synchronized boolean hasPickups(UUID player) {
        return data.pickups.containsKey(player) || data.coins.containsKey(player) || data.owedItems.containsKey(player);
    }

    public synchronized void oweItem(UUID player, String itemId, int count) {
        if (count > 0) {
            data.owedItems.computeIfAbsent(player, k -> new LinkedHashMap<>()).merge(itemId, count, Integer::sum);
        }
    }

    public synchronized Map<String, Integer> takeOwedItems(UUID player) {
        Map<String, Integer> m = data.owedItems.remove(player);
        return m == null ? Map.of() : m;
    }

    // ---------------------------------------------------------------- cash

    public synchronized boolean hasWallet(UUID player) {
        return data.cash.containsKey(player);
    }

    public synchronized long balance(UUID player) {
        return data.cash.getOrDefault(player, 0L);
    }

    public synchronized void addCash(UUID player, long amount) {
        data.cash.merge(player, Math.max(0, amount), Long::sum);
    }

    public synchronized void setCash(UUID player, long amount) {
        data.cash.put(player, Math.max(0, amount));
    }

    /** @return false (nothing taken) if they don't have enough */
    public synchronized boolean takeCash(UUID player, long amount) {
        long have = balance(player);
        if (amount < 0 || have < amount) {
            return false;
        }
        data.cash.put(player, have - amount);
        return true;
    }

    public synchronized Result pay(UUID from, UUID to, long amount) {
        if (from.equals(to)) {
            return Result.OWN;
        }
        if (amount <= 0) {
            return Result.BAD_AMOUNT;
        }
        if (!takeCash(from, amount)) {
            return Result.NOT_ENOUGH;
        }
        addCash(to, amount);
        return Result.OK;
    }

    /** Richest players first. */
    public synchronized List<Map.Entry<UUID, Long>> richest(int n) {
        List<Map.Entry<UUID, Long>> l = new ArrayList<>(data.cash.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    // ---------------------------------------------------------------- shops

    public synchronized List<Shop> shops() {
        return new ArrayList<>(data.shops.values());
    }

    public synchronized long shopsBy(UUID owner) {
        return data.shops.values().stream().filter(x -> x.owner.equals(owner)).count();
    }

    public synchronized Shop shopByEntity(UUID entity) {
        for (Shop x : data.shops.values()) {
            if (entity.equals(x.entity)) {
                return x;
            }
        }
        return null;
    }

    public synchronized Shop addShop(Shop x) {
        x.id = data.nextId++;
        x.created = clock.nowMillis();
        data.shops.put(x.id, x);
        return x;
    }

    public synchronized Shop removeShop(long id) {
        return data.shops.remove(id);
    }

    /**
     * A customer buys one bundle from a selling shop (they already paid). The owner is paid: cash straight into
     * their wallet, items into their pickups.
     */
    public synchronized Result shopSale(Shop x) {
        if (!data.shops.containsKey(x.id)) {
            return Result.NO_SUCH;
        }
        if (!x.selling || x.stock < x.bundle) {
            return Result.SOLD_OUT;
        }
        x.stock -= x.bundle;
        x.sales++;
        if ("cash".equals(x.pay)) {
            addCash(x.owner, x.price);
        } else {
            oweItem(x.owner, x.pay, x.price);
        }
        return Result.OK;
    }

    /**
     * A customer sells one bundle to a buying shop (they already handed the items in; the owner gets them in their
     * pickups). The shop pays from the owner's wallet (cash) or its funds (items). On OK the server pays the customer.
     */
    public synchronized Result shopPurchase(Shop x, String plainItemId) {
        if (!data.shops.containsKey(x.id)) {
            return Result.NO_SUCH;
        }
        if (x.selling) {
            return Result.BAD_AMOUNT;
        }
        if ("cash".equals(x.pay)) {
            if (!takeCash(x.owner, x.price)) {
                return Result.NOT_ENOUGH;
            }
        } else {
            if (x.funds < x.price) {
                return Result.NOT_ENOUGH;
            }
            x.funds -= x.price;
        }
        oweItem(x.owner, plainItemId, x.bundle);
        x.sales++;
        return Result.OK;
    }

    // ---------------------------------------------------------------- auctions

    public synchronized List<Auction> auctions() {
        return new ArrayList<>(data.auctions.values());
    }

    public synchronized long auctionsBy(UUID seller) {
        return data.auctions.values().stream().filter(a -> a.seller.equals(seller)).count();
    }

    public synchronized Auction list(UUID seller, String sellerName, String item, String itemName, int startPrice, long durationMs) {
        Auction a = new Auction();
        a.id = data.nextId++;
        a.seller = seller;
        a.sellerName = sellerName;
        a.item = item;
        a.itemName = itemName;
        a.startPrice = Math.max(1, startPrice);
        a.endsAt = clock.nowMillis() + durationMs;
        data.auctions.put(a.id, a);
        return a;
    }

    /** The least the next bid can be: the start price, or 5% (at least 1) over the current bid. */
    public static int minBid(Auction a) {
        return a.bid == 0 ? a.startPrice : a.bid + Math.max(1, a.bid * 5 / 100);
    }

    /** The bidder has already paid {@code amount}; the previous bidder (if any) is refunded. */
    public synchronized BidResult bid(long id, UUID bidder, String name, int amount) {
        Auction a = data.auctions.get(id);
        if (a == null) {
            return new BidResult(Result.NO_SUCH, null, 0);
        }
        if (clock.nowMillis() >= a.endsAt) {
            return new BidResult(Result.ENDED, null, 0);
        }
        if (a.seller.equals(bidder)) {
            return new BidResult(Result.OWN, null, 0);
        }
        if (amount < minBid(a)) {
            return new BidResult(Result.TOO_LOW, null, 0);
        }
        UUID prev = a.bidder;
        int refund = a.bid;
        a.bid = amount;
        a.bidder = bidder;
        a.bidderName = name;
        // A bid in the last minute adds a minute, so nobody can snipe.
        if (a.endsAt - clock.nowMillis() < 60_000) {
            a.endsAt = clock.nowMillis() + 60_000;
        }
        if (prev != null) {
            oweCoins(prev, refund);
        }
        return new BidResult(Result.OK, prev, prev == null ? 0 : refund);
    }

    public synchronized Result cancelAuction(long id, UUID by, boolean admin) {
        Auction a = data.auctions.get(id);
        if (a == null) {
            return Result.NO_SUCH;
        }
        if (!admin && !a.seller.equals(by)) {
            return Result.NOT_YOURS;
        }
        if (!admin && a.bidder != null) {
            return Result.HAS_BIDS;
        }
        data.auctions.remove(id);
        owe(a.seller, a.item);
        if (a.bidder != null) {
            oweCoins(a.bidder, a.bid);
        }
        return Result.OK;
    }

    /** Ends every auction whose time is up: item to the winner and money to the seller, or the item back. */
    public synchronized List<Auction> settle() {
        long now = clock.nowMillis();
        List<Auction> ended = new ArrayList<>();
        for (Auction a : new ArrayList<>(data.auctions.values())) {
            if (now < a.endsAt) {
                continue;
            }
            data.auctions.remove(a.id);
            if (a.bidder != null) {
                owe(a.bidder, a.item);
                oweCoins(a.seller, a.bid);
            } else {
                owe(a.seller, a.item);
            }
            ended.add(a);
        }
        return ended;
    }

    // ---------------------------------------------------------------- buy orders

    public synchronized List<Order> orders() {
        return new ArrayList<>(data.orders.values());
    }

    public synchronized long ordersBy(UUID owner) {
        return data.orders.values().stream().filter(o -> o.owner.equals(owner)).count();
    }

    /** The owner has already paid {@code price}. */
    public synchronized Order order(UUID owner, String ownerName, String itemId, int wanted, int price) {
        Order o = new Order();
        o.id = data.nextId++;
        o.owner = owner;
        o.ownerName = ownerName;
        o.itemId = itemId;
        o.wanted = wanted;
        o.price = price;
        o.created = clock.nowMillis();
        data.orders.put(o.id, o);
        return o;
    }

    static int paidFor(Order o, int n) {
        return (int) ((long) o.price * n / o.wanted);
    }

    /**
     * Someone hands in up to {@code count} items; the owner gets them (the server adds them to the owner's pickups)
     * and the filler is paid their share.
     */
    public synchronized FillResult fill(long id, UUID filler, int count) {
        Order o = data.orders.get(id);
        if (o == null) {
            return new FillResult(Result.NO_SUCH, 0, 0, false);
        }
        if (o.owner.equals(filler)) {
            return new FillResult(Result.OWN, 0, 0, false);
        }
        int n = Math.min(count, o.wanted - o.filled);
        if (n <= 0) {
            return new FillResult(Result.BAD_AMOUNT, 0, 0, false);
        }
        int pay = paidFor(o, o.filled + n) - paidFor(o, o.filled);
        o.filled += n;
        boolean done = o.filled >= o.wanted;
        if (done) {
            data.orders.remove(id);
        }
        return new FillResult(Result.OK, n, pay, done);
    }

    /** Cancels an order; the money not yet paid out goes back to the owner. */
    public synchronized Result cancelOrder(long id, UUID by, boolean admin) {
        Order o = data.orders.get(id);
        if (o == null) {
            return Result.NO_SUCH;
        }
        if (!admin && !o.owner.equals(by)) {
            return Result.NOT_YOURS;
        }
        data.orders.remove(id);
        oweCoins(o.owner, o.price - paidFor(o, o.filled));
        return Result.OK;
    }

    // ---------------------------------------------------------------- bounties

    public synchronized void addBounty(UUID target, String targetName, UUID placer, String placerName, String item, String itemName, double value) {
        Bounty b = new Bounty();
        b.placer = placer;
        b.placerName = placerName;
        b.item = item;
        b.itemName = itemName;
        b.value = value;
        b.created = clock.nowMillis();
        data.bounties.computeIfAbsent(target, k -> new ArrayList<>()).add(b);
        data.bountyNames.put(target, targetName);
    }

    public synchronized List<Bounty> bountiesOn(UUID target) {
        return new ArrayList<>(data.bounties.getOrDefault(target, List.of()));
    }

    public synchronized double bountyValue(UUID target) {
        double v = 0;
        for (Bounty b : data.bounties.getOrDefault(target, List.of())) {
            v += b.value;
        }
        return v;
    }

    /** The target was killed: every reward on them goes to the killer's pickups. */
    public synchronized List<Bounty> claimBounties(UUID target, UUID killer) {
        List<Bounty> l = data.bounties.remove(target);
        data.bountyNames.remove(target);
        if (l == null) {
            return List.of();
        }
        for (Bounty b : l) {
            owe(killer, b.item);
        }
        return l;
    }

    // ---------------------------------------------------------------- trader prices

    public synchronized void recordPrice(String signature, double factor) {
        List<double[]> h = data.history.computeIfAbsent(signature, k -> new ArrayList<>());
        h.add(new double[]{clock.nowMillis(), factor});
        while (h.size() > HISTORY_POINTS) {
            h.remove(0);
        }
    }

    public synchronized List<double[]> history(String signature) {
        return new ArrayList<>(data.history.getOrDefault(signature, List.of()));
    }

    /** Forgets history of items no trader sells any more (keeps the file small). */
    public synchronized void keepHistory(java.util.Collection<String> signatures) {
        data.history.keySet().retainAll(signatures);
    }

    public synchronized void addDeal(UUID player, String trader) {
        data.reputation.merge(player + "|" + trader, 1, Integer::sum);
    }

    public synchronized int deals(UUID player, String trader) {
        return data.reputation.getOrDefault(player + "|" + trader, 0);
    }

    /** 1% off for every 5 deals with the same trader, up to 10%. */
    public static double discount(int deals) {
        return Math.min(0.10, (deals / 5) * 0.01);
    }

    public synchronized void setDeal(String day, String trader, String signature) {
        data.dealDay = day;
        data.dealTrader = trader;
        data.dealSignature = signature;
    }

    public synchronized boolean isDeal(String trader, String signature) {
        return trader != null && trader.equals(data.dealTrader) && signature.equals(data.dealSignature);
    }

    // ---------------------------------------------------------------- server shop

    public synchronized List<ServerItem> serverShop() {
        return new ArrayList<>(data.serverShop.values());
    }

    public synchronized ServerItem addServerItem(String item, String itemName, int buy, int sell) {
        ServerItem x = new ServerItem();
        x.id = data.nextId++;
        x.item = item;
        x.itemName = itemName;
        x.buy = Math.max(0, buy);
        x.sell = Math.max(0, sell);
        data.serverShop.put(x.id, x);
        return x;
    }

    public synchronized ServerItem removeServerItem(long id) {
        return data.serverShop.remove(id);
    }

    // ---------------------------------------------------------------- booths

    /** Pays someone in cash (wallet) or in items (pickups). */
    public synchronized void credit(UUID player, String pay, int amount) {
        if ("cash".equals(pay)) {
            addCash(player, amount);
        } else {
            oweItem(player, pay, amount);
        }
    }

    public synchronized List<Booth> booths() {
        return new ArrayList<>(data.booths.values());
    }

    public synchronized Booth booth(long id) {
        return data.booths.get(id);
    }

    public synchronized Booth boothOf(UUID owner) {
        for (Booth b : data.booths.values()) {
            if (owner.equals(b.owner)) {
                return b;
            }
        }
        return null;
    }

    public synchronized Booth boothByEntity(UUID entity) {
        for (Booth b : data.booths.values()) {
            if (entity.equals(b.entity)) {
                return b;
            }
        }
        return null;
    }

    public synchronized Booth addBooth(String world, double x, double y, double z, float yaw) {
        Booth b = new Booth();
        b.id = data.nextId++;
        b.world = world;
        b.x = x;
        b.y = y;
        b.z = z;
        b.yaw = yaw;
        data.booths.put(b.id, b);
        return b;
    }

    public synchronized Result claimBooth(long id, UUID player, String name) {
        Booth b = data.booths.get(id);
        if (b == null) {
            return Result.NO_SUCH;
        }
        if (b.owner != null) {
            return b.owner.equals(player) ? Result.OWN : Result.TAKEN;
        }
        if (boothOf(player) != null) {
            return Result.TOO_MANY;
        }
        b.owner = player;
        b.ownerName = name;
        b.claimed = clock.nowMillis();
        b.sales = 0;
        return Result.OK;
    }

    /**
     * Frees a booth: every listing goes back to the owner's pickups and every open offer is refunded.
     *
     * @return the offers refunded (so the buyers can be told)
     */
    public synchronized List<BoothOffer> freeBooth(long id) {
        Booth b = data.booths.get(id);
        if (b == null || b.owner == null) {
            return List.of();
        }
        for (Listing l : b.listings) {
            owe(b.owner, l.item);
        }
        b.listings.clear();
        List<BoothOffer> refunded = refundOffers(o -> o.booth == id);
        b.owner = null;
        b.ownerName = null;
        return refunded;
    }

    /** Removes a booth spot (admin); frees it first. */
    public synchronized List<BoothOffer> deleteBooth(long id) {
        List<BoothOffer> r = freeBooth(id);
        data.booths.remove(id);
        return r;
    }

    public synchronized Result list(long boothId, UUID owner, String item, String itemName, int price, String pay, int max) {
        Booth b = data.booths.get(boothId);
        if (b == null) {
            return Result.NO_SUCH;
        }
        if (!owner.equals(b.owner)) {
            return Result.NOT_YOURS;
        }
        if (b.listings.size() >= max) {
            return Result.FULL;
        }
        if (price < 1) {
            return Result.BAD_AMOUNT;
        }
        Listing l = new Listing();
        l.id = data.nextId++;
        l.item = item;
        l.itemName = itemName;
        l.price = price;
        l.pay = pay;
        l.listed = clock.nowMillis();
        b.listings.add(l);
        return Result.OK;
    }

    public synchronized Listing listing(Booth b, long listingId) {
        for (Listing l : b.listings) {
            if (l.id == listingId) {
                return l;
            }
        }
        return null;
    }

    /** The owner takes a listing back (to their pickups); its offers are refunded. */
    public synchronized List<BoothOffer> unlist(long boothId, long listingId, UUID owner) {
        Booth b = data.booths.get(boothId);
        if (b == null || !owner.equals(b.owner)) {
            return List.of();
        }
        Listing l = listing(b, listingId);
        if (l == null) {
            return List.of();
        }
        b.listings.remove(l);
        owe(owner, l.item);
        return refundOffers(o -> o.listing == listingId);
    }

    public synchronized Result setPrice(long boothId, long listingId, UUID owner, int price) {
        Booth b = data.booths.get(boothId);
        if (b == null || !owner.equals(b.owner)) {
            return Result.NOT_YOURS;
        }
        Listing l = listing(b, listingId);
        if (l == null) {
            return Result.NO_SUCH;
        }
        if (price < 1) {
            return Result.BAD_AMOUNT;
        }
        l.price = price;
        return Result.OK;
    }

    private List<BoothOffer> refundOffers(java.util.function.Predicate<BoothOffer> which) {
        List<BoothOffer> out = new ArrayList<>();
        for (BoothOffer o : new ArrayList<>(data.boothOffers.values())) {
            if (which.test(o)) {
                data.boothOffers.remove(o.id);
                credit(o.buyer, o.pay, o.amount);
                out.add(o);
            }
        }
        return out;
    }

    /**
     * A visitor buys a listing at its price (they already paid). The seller is paid, other offers on it are
     * refunded; the server gives the item to the buyer.
     */
    public synchronized Sale buy(long boothId, long listingId, UUID buyer) {
        Booth b = data.booths.get(boothId);
        if (b == null || b.owner == null) {
            return new Sale(Result.NO_SUCH, b, null, List.of());
        }
        if (b.owner.equals(buyer)) {
            return new Sale(Result.OWN, b, null, List.of());
        }
        Listing l = listing(b, listingId);
        if (l == null) {
            return new Sale(Result.SOLD_OUT, b, null, List.of());
        }
        b.listings.remove(l);
        b.sales++;
        credit(b.owner, l.pay, l.price);
        return new Sale(Result.OK, b, l, refundOffers(o -> o.listing == listingId));
    }

    public synchronized List<BoothOffer> offersFor(long boothId) {
        List<BoothOffer> out = new ArrayList<>();
        for (BoothOffer o : data.boothOffers.values()) {
            if (o.booth == boothId) {
                out.add(o);
            }
        }
        return out;
    }

    public synchronized BoothOffer offer(long id) {
        return data.boothOffers.get(id);
    }

    /**
     * A visitor offers {@code amount} (already paid) for a listing. Their earlier offer on the same listing is
     * refunded and replaced.
     *
     * @return the new offer, or null if the listing is gone or it's their own booth
     */
    public synchronized BoothOffer makeOffer(long boothId, long listingId, UUID buyer, String buyerName, int amount) {
        Booth b = data.booths.get(boothId);
        if (b == null || b.owner == null || b.owner.equals(buyer) || amount < 1) {
            return null;
        }
        Listing l = listing(b, listingId);
        if (l == null) {
            return null;
        }
        refundOffers(o -> o.listing == listingId && o.buyer.equals(buyer));
        BoothOffer o = new BoothOffer();
        o.id = data.nextId++;
        o.booth = boothId;
        o.listing = listingId;
        o.buyer = buyer;
        o.buyerName = buyerName;
        o.amount = amount;
        o.pay = l.pay;
        o.itemName = l.itemName;
        o.created = clock.nowMillis();
        data.boothOffers.put(o.id, o);
        return o;
    }

    /** The owner accepts: they're paid, the buyer gets the item in their pickups, other offers are refunded. */
    public synchronized Sale accept(long offerId, UUID owner) {
        BoothOffer o = data.boothOffers.get(offerId);
        if (o == null) {
            return new Sale(Result.NO_SUCH, null, null, List.of());
        }
        Booth b = data.booths.get(o.booth);
        if (b == null || !owner.equals(b.owner)) {
            return new Sale(Result.NOT_YOURS, b, null, List.of());
        }
        Listing l = listing(b, o.listing);
        if (l == null) {
            refundOffers(x -> x.id == offerId);
            return new Sale(Result.SOLD_OUT, b, null, List.of());
        }
        data.boothOffers.remove(offerId);
        b.listings.remove(l);
        b.sales++;
        credit(owner, o.pay, o.amount);
        owe(o.buyer, l.item);
        return new Sale(Result.OK, b, l, refundOffers(x -> x.listing == l.id));
    }

    /** The owner says no: the buyer gets their money back. */
    public synchronized BoothOffer decline(long offerId, UUID owner) {
        BoothOffer o = data.boothOffers.get(offerId);
        if (o == null) {
            return null;
        }
        Booth b = data.booths.get(o.booth);
        if (b == null || !owner.equals(b.owner)) {
            return null;
        }
        refundOffers(x -> x.id == offerId);
        return o;
    }

    /** The buyer takes their offer back. */
    public synchronized BoothOffer withdrawOffer(long offerId, UUID buyer) {
        BoothOffer o = data.boothOffers.get(offerId);
        if (o == null || !o.buyer.equals(buyer)) {
            return null;
        }
        refundOffers(x -> x.id == offerId);
        return o;
    }

    /** Offers nobody answered in time are refunded. */
    public synchronized List<BoothOffer> expireOffers(long maxAgeMs) {
        long now = clock.nowMillis();
        return refundOffers(o -> now - o.created >= maxAgeMs);
    }
}
