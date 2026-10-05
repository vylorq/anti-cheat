package com.vylorq.anticheat.core.trader;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Stats;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trader stock, rotations and every economy guard (sections 23.2, 23.5, 23.7, 23.8).
 */
public final class TraderEconomy {
    /** Items traders never sell, whatever the config says. */
    public static final Set<String> ALWAYS_BLOCKED = Set.of(
            "minecraft:netherite_ingot", "minecraft:netherite_scrap", "minecraft:ancient_debris",
            "minecraft:netherite_block", "minecraft:netherite_upgrade_smithing_template",
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_portal_frame", "minecraft:end_gateway",
            "minecraft:bedrock", "minecraft:barrier", "minecraft:command_block", "minecraft:chain_command_block",
            "minecraft:repeating_command_block", "minecraft:command_block_minecart", "minecraft:structure_block",
            "minecraft:structure_void", "minecraft:jigsaw", "minecraft:spawner", "minecraft:trial_spawner",
            "minecraft:light", "minecraft:debug_stick", "minecraft:knowledge_book", "minecraft:vault");

    public static final class Settings {
        public int minOffers = 4;
        public int maxOffers = 8;
        public int rotationMinutes = 180;
        public int rotationJitterMinutes = 60;
        public double minMarkup = 1.2;
        public double maxMarkup = 1.5;
        public double demandIncrease = 0.05;
        public double demandDecayPerHour = 0.01;
        public int weeklyRareCap = 5;
        public int weeklyLegendaryCap = 1;
        public int perPlayerRarePerWeek = 3;
        public int perPlayerLegendaryPerWeek = 1;
        public int tradesPerMinute = 20;
        public List<String> neverSell = new ArrayList<>();
        /** Only sell items a player on the server already got naturally (off: traders always have stock). */
        public boolean onlyObtained = false;
        /** A rare offer shows up only once in this many tries; a legendary one once in legendaryOdds. */
        public int rareOdds = 1000;
        public int legendaryOdds = 3000;
        /** How many things one player may buy from traders a day (0 = no limit). */
        public int buysPerDay = 0;
        /** Market prices move every this many minutes, up or down by up to {@link #priceSwing}. */
        public int priceChangeMinutes = 50;
        public double priceSwing = 0.15;
        public double minPrice = 0.6;
        public double maxPrice = 1.8;
    }

    public static final class Data {
        /** Item ids players on the server have obtained naturally. */
        public Set<String> obtained = new LinkedHashSet<>();
        /** week -> key -> count. Keys: "t:<trader>:<RARITY>", "p:<uuid>:<RARITY>", "ip:<ip>:<RARITY>". */
        public Map<String, Map<String, Integer>> weekly = new LinkedHashMap<>();
        /** item signature -> [multiplier, lastUpdateMillis] */
        public Map<String, double[]> demand = new LinkedHashMap<>();
        /** item id -> [in, out] counts through traders */
        public Map<String, long[]> flow = new LinkedHashMap<>();
        /** day -> key -> count for sell-to caps */
        public Map<String, Map<String, Integer>> sold = new LinkedHashMap<>();
        /** item signature -> [market price factor, factor before the last change] */
        public Map<String, double[]> market = new LinkedHashMap<>();
        /** Which price period the market was last moved for. */
        public long marketPeriod = -1;
    }

    public enum Refusal { NONE, SOLD_OUT, TRADER_WEEKLY_CAP, PLAYER_WEEKLY_CAP, IP_WEEKLY_CAP, RATE_LIMIT, PLAYER_DAILY_CAP }

    private final Data data;
    private final Clock clock;
    private final Map<UUID, Deque<Long>> tradeTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> restockSnipes = new ConcurrentHashMap<>();

    public TraderEconomy(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    // ---- What may be sold ----

    public synchronized void markObtained(String itemId) {
        data.obtained.add(itemId);
    }

    /** Forgets every item players got, so traders start again from nothing. @return how many were forgotten */
    public synchronized int resetObtained() {
        int n = data.obtained.size();
        data.obtained.clear();
        return n;
    }

    /** Forgets one item, so traders stop selling it until a player gets it again. @return true if it was known */
    public synchronized boolean forgetObtained(String itemId) {
        return data.obtained.remove(itemId);
    }

    public synchronized boolean isObtained(String itemId) {
        return data.obtained.contains(itemId);
    }

    public static boolean isNetherite(String id) {
        return id.contains("netherite") || id.equals("minecraft:ancient_debris");
    }

    public boolean maySell(String id, Settings s, Collection<String> illegal) {
        if (ALWAYS_BLOCKED.contains(id) || isNetherite(id) || illegal.contains(id) || s.neverSell.contains(id)) {
            return false;
        }
        if (id.endsWith("_spawn_egg")) {
            return false;
        }
        return !s.onlyObtained || isObtained(id);
    }

    // ---- Market prices ----

    /**
     * Moves every price once per period (every {@code priceChangeMinutes}): each goes up or down by 3% to
     * {@code priceSwing}, staying between {@code minPrice} and {@code maxPrice}. New items get a starting price.
     *
     * @return true if anything changed
     */
    public synchronized boolean marketTick(Collection<Trader> traders, Settings s, SplittableRandom r) {
        boolean changed = false;
        for (Trader t : traders) {
            for (TraderOffer o : t.offers) {
                if (!data.market.containsKey(o.signature())) {
                    double f = 0.85 + r.nextDouble() * 0.3;
                    data.market.put(o.signature(), new double[]{f, f});
                    changed = true;
                }
            }
        }
        long period = clock.nowMillis() / (Math.max(1, s.priceChangeMinutes) * Durations.MINUTE);
        if (period == data.marketPeriod) {
            return changed;
        }
        boolean first = data.marketPeriod < 0;
        data.marketPeriod = period;
        if (first) {
            return true;
        }
        for (double[] m : data.market.values()) {
            double step = 0.03 + r.nextDouble() * Math.max(0, s.priceSwing - 0.03);
            boolean up = r.nextBoolean();
            if (m[0] * (1 + step) > s.maxPrice) {
                up = false;
            } else if (m[0] * (1 - step) < s.minPrice) {
                up = true;
            }
            m[1] = m[0];
            m[0] = Math.max(s.minPrice, Math.min(s.maxPrice, m[0] * (up ? 1 + step : 1 - step)));
        }
        return true;
    }

    /** Current market price factor (1 = normal). */
    public synchronized double market(String signature) {
        double[] m = data.market.get(signature);
        return m == null ? 1.0 : m[0];
    }

    /** How much the price moved at the last change, e.g. 0.08 for +8%. */
    public synchronized double trend(String signature) {
        double[] m = data.market.get(signature);
        return m == null || m[1] <= 0 ? 0 : m[0] / m[1] - 1;
    }

    /** Milliseconds until prices next change. */
    public long untilPriceChange(Settings s) {
        long len = Math.max(1, s.priceChangeMinutes) * Durations.MINUTE;
        return len - clock.nowMillis() % len;
    }

    // ---- Rotations ----

    /** Replaces the trader's offers with a new random set and schedules the next rotation. */
    public void rotate(Trader t, Settings s, Collection<String> illegal, SplittableRandom r) {
        long now = clock.nowMillis();
        if (t.pinned == null) {
            t.pinned = new LinkedHashMap<>();
        }
        if (t.blocked == null) {
            t.blocked = new LinkedHashSet<>();
        }
        List<Specialty.Entry> pool = new ArrayList<>();
        for (Specialty.Entry e : t.specialty.pool()) {
            if (maySell(e.id(), s, illegal) && !t.blocked.contains(e.id())) {
                pool.add(e);
            }
        }
        int want = s.minOffers + r.nextInt(Math.max(1, s.maxOffers - s.minOffers + 1));
        List<TraderOffer> offers = new ArrayList<>();
        Set<String> used = new HashSet<>();
        // The owner's own stock first: always there, whatever the rotation.
        for (Map.Entry<String, Integer> pin : t.pinned.entrySet()) {
            if (ALWAYS_BLOCKED.contains(pin.getKey())) {
                continue;
            }
            TraderOffer o = new TraderOffer();
            o.id = pin.getKey();
            o.rarity = rarityOf(pin.getKey());
            o.maxStock = o.stock = Math.max(1, pin.getValue());
            if (used.add(o.signature())) {
                offers.add(o);
            }
        }
        want = Math.max(want, offers.size());
        if (t.specialty == Specialty.LIBRARIAN && maySell("minecraft:enchanted_book", s, illegal)
                && !t.blocked.contains("minecraft:enchanted_book")) {
            int books = Math.max(1, want - 2);
            for (int i = 0; i < books * 20 && offers.size() < books; i++) {
                TraderOffer o = randomBook(r);
                if (luckyEnough(o.rarity, s, r) && used.add(o.signature())) {
                    offers.add(o);
                }
            }
        }
        for (int i = 0; i < want * 20 && offers.size() < want && !pool.isEmpty(); i++) {
            Specialty.Entry e = pool.get(r.nextInt(pool.size()));
            TraderOffer o = fromEntry(e, r);
            if (luckyEnough(o.rarity, s, r) && used.add(o.signature())) {
                offers.add(o);
            }
        }
        t.offers = offers;
        t.mood = s.minMarkup + r.nextDouble() * (s.maxMarkup - s.minMarkup);
        long jitter = s.rotationJitterMinutes <= 0 ? 0 : r.nextLong(s.rotationJitterMinutes * 2L + 1) - s.rotationJitterMinutes;
        t.nextRotation = now + (s.rotationMinutes + jitter) * Durations.MINUTE;
    }

    /** Rare and legendary offers only make it into stock once in rareOdds / legendaryOdds tries. */
    static boolean luckyEnough(Rarity rarity, Settings s, SplittableRandom r) {
        return switch (rarity) {
            case RARE -> r.nextInt(Math.max(1, s.rareOdds)) == 0;
            case LEGENDARY -> r.nextInt(Math.max(1, s.legendaryOdds)) == 0;
            default -> true;
        };
    }

    /** The rarity an item has in any trader's list (common if none lists it). */
    public static Rarity rarityOf(String id) {
        for (Specialty sp : Specialty.values()) {
            for (Specialty.Entry e : sp.pool()) {
                if (e.id().equals(id)) {
                    return e.rarity();
                }
            }
        }
        return Rarity.COMMON;
    }

    static TraderOffer randomBook(SplittableRandom r) {
        List<String> ids = Enchants.bookable();
        String id = ids.get(r.nextInt(ids.size()));
        int max = Enchants.max(id);
        int level = 1 + r.nextInt(max);
        TraderOffer o = new TraderOffer();
        o.id = "minecraft:enchanted_book";
        o.storedEnchantments.put(id, level);
        o.rarity = Enchants.bookRarity(id, level);
        o.maxStock = o.stock = o.rarity.rollStock(r);
        return o;
    }

    static TraderOffer fromEntry(Specialty.Entry e, SplittableRandom r) {
        TraderOffer o = new TraderOffer();
        o.id = e.id();
        o.unit = e.unit();
        o.potion = e.potion();
        o.rarity = e.rarity();
        if (e.enchanted()) {
            List<String> app = new ArrayList<>(Enchants.applicable(e.id()));
            int count = r.nextInt(3);
            for (int i = 0; i < count && !app.isEmpty(); i++) {
                String ench = app.remove(r.nextInt(app.size()));
                boolean clash = false;
                for (String have : o.enchantments.keySet()) {
                    if (Enchants.exclusive(have, ench)) {
                        clash = true;
                        break;
                    }
                }
                if (!clash) {
                    o.enchantments.put(ench, 1 + r.nextInt(Enchants.max(ench)));
                }
            }
            o.rarity = o.rarity.max(Enchants.gearRarity(o.enchantments));
        }
        o.maxStock = o.stock = o.rarity.rollStock(r);
        return o;
    }

    // ---- Purchases ----

    static String week(long millis) {
        var d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC);
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private int weeklyCount(String key) {
        return data.weekly.getOrDefault(week(clock.nowMillis()), Map.of()).getOrDefault(key, 0);
    }

    private void weeklyAdd(String key) {
        String w = week(clock.nowMillis());
        data.weekly.computeIfAbsent(w, k -> new LinkedHashMap<>()).merge(key, 1, Integer::sum);
        while (data.weekly.size() > 4) {
            data.weekly.remove(data.weekly.keySet().iterator().next());
        }
    }

    /** Checks every cap without changing anything. */
    public synchronized Refusal check(Trader t, TraderOffer o, UUID player, String ip, Settings s) {
        if (o.stock <= 0) {
            return Refusal.SOLD_OUT;
        }
        if (s.buysPerDay > 0 && buysToday(player) >= s.buysPerDay) {
            return Refusal.PLAYER_DAILY_CAP;
        }
        if (o.rarity == Rarity.RARE || o.rarity == Rarity.LEGENDARY) {
            int traderCap = o.rarity == Rarity.RARE ? s.weeklyRareCap : s.weeklyLegendaryCap;
            int playerCap = o.rarity == Rarity.RARE ? s.perPlayerRarePerWeek : s.perPlayerLegendaryPerWeek;
            if (weeklyCount("t:" + t.entity + ":" + o.rarity) >= traderCap) {
                return Refusal.TRADER_WEEKLY_CAP;
            }
            if (weeklyCount("p:" + player + ":" + o.rarity) >= playerCap) {
                return Refusal.PLAYER_WEEKLY_CAP;
            }
            // Same limit per IP so alts can't buy extra.
            if (ip != null && weeklyCount("ip:" + ip + ":" + o.rarity) >= playerCap) {
                return Refusal.IP_WEEKLY_CAP;
            }
        }
        return Refusal.NONE;
    }

    /**
     * Atomically claims one unit of stock (last-item lock: when two players buy the last one at once, only one
     * succeeds) and records caps, demand and flow.
     */
    public synchronized Refusal purchase(Trader t, TraderOffer o, UUID player, String ip, Settings s) {
        Refusal ref = check(t, o, player, ip, s);
        if (ref != Refusal.NONE) {
            return ref;
        }
        o.stock--;
        boughtToday(player, 1);
        if (o.rarity == Rarity.RARE || o.rarity == Rarity.LEGENDARY) {
            weeklyAdd("t:" + t.entity + ":" + o.rarity);
            weeklyAdd("p:" + player + ":" + o.rarity);
            if (ip != null) {
                weeklyAdd("ip:" + ip + ":" + o.rarity);
            }
        }
        double[] d = demandEntry(o.signature());
        d[0] += s.demandIncrease;
        flow(o.id, 0, o.unit);
        return Refusal.NONE;
    }

    /** Puts a unit back (the trade couldn't complete after claiming stock). */
    public synchronized void refund(TraderOffer o) {
        o.stock = Math.min(o.maxStock, o.stock + 1);
    }

    /** Puts a unit back and doesn't count it against the player's daily buys. */
    public synchronized void refund(TraderOffer o, UUID player) {
        refund(o);
        boughtToday(player, -1);
    }

    /** How many things this player bought from traders today. */
    public synchronized int buysToday(UUID player) {
        String day = com.vylorq.anticheat.core.stats.AcStats.day(clock.nowMillis());
        return data.sold.getOrDefault(day, Map.of()).getOrDefault("b:" + player, 0);
    }

    private void boughtToday(UUID player, int n) {
        String day = com.vylorq.anticheat.core.stats.AcStats.day(clock.nowMillis());
        Map<String, Integer> m = data.sold.computeIfAbsent(day, k -> new LinkedHashMap<>());
        while (data.sold.size() > 3) {
            data.sold.remove(data.sold.keySet().iterator().next());
        }
        m.merge("b:" + player, n, Integer::sum);
    }

    private double[] demandEntry(String sig) {
        double[] d = data.demand.computeIfAbsent(sig, k -> new double[]{1.0, clock.nowMillis()});
        return d;
    }

    /** Current price multiplier for an item: rises with purchases, slowly returns to 1. */
    public synchronized double demand(String signature, Settings s) {
        double[] d = data.demand.get(signature);
        if (d == null) {
            return 1.0;
        }
        long now = clock.nowMillis();
        double hours = (now - d[1]) / (double) Durations.HOUR;
        d[0] = Math.max(1.0, d[0] - hours * s.demandDecayPerHour);
        d[1] = now;
        return d[0];
    }

    public synchronized void flow(String id, long in, long out) {
        long[] f = data.flow.computeIfAbsent(id, k -> new long[2]);
        f[0] += in;
        f[1] += out;
    }

    /** Items flowing out much faster than in (possible imbalance for admins to look at). */
    public synchronized List<String> flowWarnings(double ratio, long minOut) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : data.flow.entrySet()) {
            long in = e.getValue()[0];
            long o = e.getValue()[1];
            if (o >= minOut && o > (in + 1) * ratio) {
                out.add(e.getKey() + ": out " + o + " / in " + in);
            }
        }
        return out;
    }

    // ---- Sell-to traders ----

    public synchronized boolean sellAllowed(UUID player, int amount, int perPlayer, int perServer) {
        String day = com.vylorq.anticheat.core.stats.AcStats.day(clock.nowMillis());
        Map<String, Integer> m = data.sold.computeIfAbsent(day, k -> new LinkedHashMap<>());
        while (data.sold.size() > 3) {
            data.sold.remove(data.sold.keySet().iterator().next());
        }
        if (m.getOrDefault("p:" + player, 0) + amount > perPlayer || m.getOrDefault("server", 0) + amount > perServer) {
            return false;
        }
        m.merge("p:" + player, amount, Integer::sum);
        m.merge("server", amount, Integer::sum);
        return true;
    }

    /**
     * No profit loops: warns when a sell-to rule pays more than the cheapest possible purchase of the same item.
     *
     * @param sellRules item id -> value paid out per unit by sell-to traders
     */
    public static List<String> profitLoops(Map<String, Double> sellRules, ItemValues values, double minMarkup) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : sellRules.entrySet()) {
            double buyCost = values.value(e.getKey()) * minMarkup;
            if (e.getValue() >= buyCost) {
                out.add(e.getKey() + " sells for " + Math.round(e.getValue()) + " but can be bought for " + Math.round(buyCost));
            }
        }
        return out;
    }

    // ---- Anti-cheat ----

    /** Rate limit. @return false when the player trades too fast. */
    public boolean rateOk(UUID player, int perMinute) {
        Deque<Long> d = tradeTimes.computeIfAbsent(player, k -> new ArrayDeque<>());
        long now = clock.nowMillis();
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < now - 60_000) {
                d.pollFirst();
            }
            if (d.size() >= perMinute) {
                return false;
            }
            d.addLast(now);
            return true;
        }
    }

    /**
     * Macro detection: trades impossibly fast or perfectly timed, or repeatedly within seconds of a restock.
     *
     * @return reason, or null
     */
    public String macroCheck(UUID player, long lastRotation) {
        Deque<Long> d = tradeTimes.get(player);
        long now = clock.nowMillis();
        if (lastRotation > 0 && now - lastRotation < 3000) {
            int n = restockSnipes.merge(player, 1, Integer::sum);
            if (n >= 3) {
                restockSnipes.put(player, 0);
                return "bought within 3s of a restock " + n + " times";
            }
        }
        if (d == null) {
            return null;
        }
        synchronized (d) {
            if (d.size() < 8) {
                return null;
            }
            List<Long> times = new ArrayList<>(d);
            double[] iv = new double[times.size() - 1];
            for (int i = 1; i < times.size(); i++) {
                iv[i - 1] = times.get(i) - times.get(i - 1);
            }
            double mean = Stats.mean(iv);
            if (mean < 150) {
                return String.format("trades every %.0fms", mean);
            }
            if (Stats.cv(iv) < 0.03) {
                return "perfectly timed trades";
            }
        }
        return null;
    }

    // ---- Mystery boxes ----

    /** Rolls a rarity for a mystery box: legendary 1 in legendaryOdds, rare 1 in rareOdds, 28% uncommon, else common. */
    public static Rarity rollMystery(Settings s, SplittableRandom r) {
        if (r.nextInt(Math.max(1, s.legendaryOdds)) == 0) {
            return Rarity.LEGENDARY;
        }
        if (r.nextInt(Math.max(1, s.rareOdds)) == 0) {
            return Rarity.RARE;
        }
        return r.nextInt(100) < 28 ? Rarity.UNCOMMON : Rarity.COMMON;
    }

    /** Random offer of the given rarity from any specialty (used for mystery boxes and request rewards). */
    public TraderOffer randomOfRarity(Rarity rarity, Settings s, Collection<String> illegal, SplittableRandom r) {
        List<TraderOffer> candidates = new ArrayList<>();
        for (Specialty sp : Specialty.values()) {
            for (Specialty.Entry e : sp.pool()) {
                if (maySell(e.id(), s, illegal)) {
                    TraderOffer o = fromEntry(e, r);
                    if (o.rarity == rarity) {
                        candidates.add(o);
                    }
                }
            }
        }
        if (rarity != Rarity.COMMON && maySell("minecraft:enchanted_book", s, illegal)) {
            for (int i = 0; i < 20; i++) {
                TraderOffer b = randomBook(r);
                if (b.rarity == rarity) {
                    candidates.add(b);
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        TraderOffer o = candidates.get(r.nextInt(candidates.size()));
        o.stock = o.maxStock = 1;
        return o;
    }

    /** Request traders: a daily "bring me X" request. */
    public void newRequest(Trader t, SplittableRandom r) {
        String[][] choices = {
                {"minecraft:iron_ingot", "64"}, {"minecraft:gold_ingot", "32"}, {"minecraft:wheat", "64"},
                {"minecraft:oak_log", "64"}, {"minecraft:cobblestone", "128"}, {"minecraft:redstone", "64"},
                {"minecraft:coal", "64"}, {"minecraft:string", "32"}, {"minecraft:leather", "32"},
                {"minecraft:bone", "48"}, {"minecraft:gunpowder", "32"}, {"minecraft:lapis_lazuli", "48"},
                {"minecraft:copper_ingot", "64"}, {"minecraft:quartz", "48"}, {"minecraft:slime_ball", "16"}};
        String[] c = choices[r.nextInt(choices.length)];
        t.requestItem = c[0];
        t.requestCount = Integer.parseInt(c[1]);
        t.requestDay = com.vylorq.anticheat.core.stats.AcStats.day(clock.nowMillis());
    }
}
