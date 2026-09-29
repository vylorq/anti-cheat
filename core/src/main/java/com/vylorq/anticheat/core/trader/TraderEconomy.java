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
    }

    public enum Refusal { NONE, SOLD_OUT, TRADER_WEEKLY_CAP, PLAYER_WEEKLY_CAP, IP_WEEKLY_CAP, RATE_LIMIT }

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
        return isObtained(id);
    }

    // ---- Rotations ----

    /** Replaces the trader's offers with a new random set and schedules the next rotation. */
    public void rotate(Trader t, Settings s, Collection<String> illegal, SplittableRandom r) {
        long now = clock.nowMillis();
        List<Specialty.Entry> pool = new ArrayList<>();
        for (Specialty.Entry e : t.specialty.pool()) {
            if (maySell(e.id(), s, illegal)) {
                pool.add(e);
            }
        }
        int want = s.minOffers + r.nextInt(Math.max(1, s.maxOffers - s.minOffers + 1));
        List<TraderOffer> offers = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (t.specialty == Specialty.LIBRARIAN && maySell("minecraft:enchanted_book", s, illegal)) {
            int books = Math.max(1, want - 2);
            for (int i = 0; i < books * 3 && offers.size() < books; i++) {
                TraderOffer o = randomBook(r);
                if (used.add(o.signature())) {
                    offers.add(o);
                }
            }
        }
        for (int i = 0; i < want * 4 && offers.size() < want && !pool.isEmpty(); i++) {
            Specialty.Entry e = pool.get(r.nextInt(pool.size()));
            TraderOffer o = fromEntry(e, r);
            if (used.add(o.signature())) {
                offers.add(o);
            }
        }
        t.offers = offers;
        t.mood = s.minMarkup + r.nextDouble() * (s.maxMarkup - s.minMarkup);
        long jitter = s.rotationJitterMinutes <= 0 ? 0 : r.nextLong(s.rotationJitterMinutes * 2L + 1) - s.rotationJitterMinutes;
        t.nextRotation = now + (s.rotationMinutes + jitter) * Durations.MINUTE;
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

    /** Rolls a rarity for a mystery box: 60% common, 28% uncommon, 10% rare, 2% legendary. */
    public static Rarity rollMystery(SplittableRandom r) {
        int x = r.nextInt(100);
        if (x < 2) {
            return Rarity.LEGENDARY;
        }
        if (x < 12) {
            return Rarity.RARE;
        }
        if (x < 40) {
            return Rarity.UNCOMMON;
        }
        return Rarity.COMMON;
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
