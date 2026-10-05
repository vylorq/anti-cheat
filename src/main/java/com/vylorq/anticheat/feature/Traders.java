package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.gui.Confirm;
import com.vylorq.anticheat.gui.Input;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.core.util.Durations;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.detect.CheckType;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.items.IllegalItems;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.core.market.Market;
import com.vylorq.anticheat.core.trader.Enchants;
import com.vylorq.anticheat.core.trader.ItemValues;
import com.vylorq.anticheat.core.trader.OfferEvaluator;
import com.vylorq.anticheat.core.trader.Rarity;
import com.vylorq.anticheat.core.trader.Specialty;
import com.vylorq.anticheat.core.trader.Trader;
import com.vylorq.anticheat.core.trader.TraderEconomy;
import com.vylorq.anticheat.core.trader.TraderOffer;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.gui.Prompts;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.perm.PlayerSessionFlags;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/** Traders (section 23). */
public final class Traders {
    public static final String TAG = "ac_trader";
    private static final SplittableRandom RANDOM = new SplittableRandom();
    /** Your offer: the right side of the window (the item for sale is on the left). */
    private static final int[] PAYMENT = {13, 14, 15, 16, 22, 23, 24, 25, 31, 32, 33, 34, 40, 41, 42, 43};
    /** Rotation time per trader (for restock-sniping detection). */
    private static final Map<UUID, Long> LAST_ROTATION = new HashMap<>();
    private static final List<String> LOOKS = List.of("plains", "desert", "jungle", "savanna", "snow", "swamp", "taiga");

    private Traders() {
    }

    public static boolean isTrader(Entity e) {
        return e instanceof VillagerEntity && e.getCommandTags().contains(TAG);
    }

    private static Map<UUID, Trader> all() {
        return Ac.get().traders.traders;
    }

    public static Trader of(Entity e) {
        return all().get(e.getUuid());
    }

    public static ItemValues values() {
        Ac ac = Ac.get();
        if (ac.itemValues == null) {
            ac.itemValues = ItemValues.loadDefaults(Ac.config().traders.baseValues);
        }
        return ac.itemValues;
    }

    public static TraderEconomy.Settings settings() {
        var c = Ac.config().traders;
        TraderEconomy.Settings s = new TraderEconomy.Settings();
        s.minOffers = c.minOffers;
        s.maxOffers = c.maxOffers;
        s.rotationMinutes = c.rotationMinutes;
        s.rotationJitterMinutes = c.rotationJitterMinutes;
        s.minMarkup = c.minMarkup;
        s.maxMarkup = c.maxMarkup;
        s.demandIncrease = c.demandIncrease;
        s.demandDecayPerHour = c.demandDecayPerHour;
        s.weeklyRareCap = c.weeklyRareCap;
        s.weeklyLegendaryCap = c.weeklyLegendaryCap;
        s.perPlayerRarePerWeek = c.perPlayerRarePerWeek;
        s.perPlayerLegendaryPerWeek = c.perPlayerLegendaryPerWeek;
        s.tradesPerMinute = c.tradesPerMinute;
        s.neverSell = c.neverSell;
        s.onlyObtained = c.onlyObtainedItems;
        s.rareOdds = c.rareOdds;
        s.legendaryOdds = c.legendaryOdds;
        s.buysPerDay = c.buysPerDay;
        s.priceChangeMinutes = c.priceChangeMinutes;
        s.priceSwing = c.priceSwing;
        s.minPrice = c.minPrice;
        s.maxPrice = c.maxPrice;
        return s;
    }

    // ---- Placing and editing ----

    public static Trader create(ServerPlayerEntity admin, ServerWorld w, BlockPos on, float yaw) {
        VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
        if (v == null) {
            return null;
        }
        double x = on.getX() + 0.5;
        double y = on.getY() + 1;
        double z = on.getZ() + 0.5;
        float facing = yaw + 180f;
        v.refreshPositionAndAngles(x, y, z, facing, 0);
        v.setHeadYaw(facing);
        v.setBodyYaw(facing);
        v.addCommandTag(TAG);
        applyFrozen(v);
        Trader t = new Trader();
        t.entity = v.getUuid();
        t.name = "Trader";
        t.specialty = Specialty.LIBRARIAN;
        t.location = new Location(Mc.worldId(w), x, y, z, facing, 0);
        t.createdAt = System.currentTimeMillis();
        t.createdBy = admin.getGameProfile().name();
        applyLook(v, t);
        w.spawnEntity(v);
        all().put(t.entity, t);
        rotate(t);
        Ac.markDirty("traders");
        Staff.log(admin, "trader-create", null, t.name, t.location.world() + " " + on.toShortString());
        return t;
    }

    /** Completely frozen: no AI, no damage, no pushing, never despawns. */
    public static void applyFrozen(VillagerEntity v) {
        v.setAiDisabled(true);
        v.setInvulnerable(true);
        v.setPersistent();
        v.setNoGravity(true);
        v.setSilent(false);
        v.setVelocity(0, 0, 0);
    }

    public static void applyLook(VillagerEntity v, Trader t) {
        VillagerType type = Registries.VILLAGER_TYPE.get(Identifier.of(t.look));
        VillagerProfession prof = Registries.VILLAGER_PROFESSION.get(Identifier.of(t.specialty.profession()));
        v.setVillagerData(v.getVillagerData().withType(Registries.VILLAGER_TYPE.getEntry(type)).withProfession(Registries.VILLAGER_PROFESSION.getEntry(prof)).withLevel(5));
        v.setCustomName(Text.literal("§e" + t.name));
        v.setCustomNameVisible(true);
    }

    public static VillagerEntity entity(Trader t) {
        ServerWorld w = Mc.world(Ac.server(), t.location.world());
        if (w == null) {
            return null;
        }
        Entity e = w.getEntity(t.entity);
        return e instanceof VillagerEntity v ? v : null;
    }

    public static void remove(ServerPlayerEntity admin, Trader t) {
        VillagerEntity v = entity(t);
        if (v != null) {
            v.discard();
        }
        all().remove(t.entity);
        Ac.markDirty("traders");
        Staff.log(admin, "trader-remove", null, t.name, t.location.world());
    }

    public static void move(ServerPlayerEntity admin, Trader t, ServerWorld w, BlockPos on) {
        VillagerEntity v = entity(t);
        float yaw = admin.getYaw() + 180f;
        t.location = new Location(Mc.worldId(w), on.getX() + 0.5, on.getY() + 1, on.getZ() + 0.5, yaw, 0);
        if (v != null) {
            if (v.getEntityWorld() != w) {
                v.discard();
                all().remove(t.entity);
                VillagerEntity nv = EntityType.VILLAGER.create(w, SpawnReason.COMMAND);
                if (nv == null) {
                    return;
                }
                nv.addCommandTag(TAG);
                applyFrozen(nv);
                t.entity = nv.getUuid();
                all().put(t.entity, t);
                v = nv;
                applyLook(v, t);
                v.refreshPositionAndAngles(t.location.x(), t.location.y(), t.location.z(), yaw, 0);
                w.spawnEntity(v);
            } else {
                v.refreshPositionAndAngles(t.location.x(), t.location.y(), t.location.z(), yaw, 0);
            }
            v.setHeadYaw(yaw);
            v.setBodyYaw(yaw);
        }
        Ac.markDirty("traders");
        Staff.log(admin, "trader-move", null, t.name, t.location.world() + " " + on.toShortString());
    }

    public static void rotate(Trader t) {
        Ac ac = Ac.get();
        switch (t.type) {
            case SELLS -> ac.economy.rotate(t, settings(), Ac.config().illegalItems.bannedItems, RANDOM);
            case REQUESTS -> {
                ac.economy.newRequest(t, RANDOM);
                t.nextRotation = System.currentTimeMillis() + 24L * 3600_000L;
            }
            case MYSTERY -> {
                TraderOffer box = new TraderOffer();
                box.id = "minecraft:chest";
                box.rarity = Rarity.UNCOMMON;
                box.stock = box.maxStock = 8;
                t.offers = new ArrayList<>(List.of(box));
                t.mood = settings().minMarkup + RANDOM.nextDouble() * (settings().maxMarkup - settings().minMarkup);
                t.nextRotation = System.currentTimeMillis() + settings().rotationMinutes * 60_000L;
            }
            case BUYS -> t.nextRotation = Long.MAX_VALUE;
        }
        LAST_ROTATION.put(t.entity, System.currentTimeMillis());
        Ac.markDirty("traders");
        Ac.markDirty("economy");
    }

    /**
     * Resets what traders may sell back to nothing: they forget every item players got and only start selling
     * an item again once a player gets it naturally (turns "only obtained items" on). Stock is redone at once.
     */
    public static void resetObtained(ServerPlayerEntity admin) {
        int n = Ac.get().economy.resetObtained();
        Ac.config().traders.onlyObtainedItems = true;
        Ac.get().configManager.save();
        restockAll();
        Staff.log(admin, "trader-reset", null, "", n + " items");
        Msg.send(admin, "trader.reset-done", n);
    }

    /** Forgets one item, so traders stop selling it until a player gets it again. */
    public static void forgetObtained(ServerPlayerEntity admin, String itemId) {
        String id = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        if (!Ac.get().economy.forgetObtained(id)) {
            Msg.send(admin, "trader.forget-unknown", id.replace("minecraft:", ""));
            return;
        }
        Ac.config().traders.onlyObtainedItems = true;
        Ac.get().configManager.save();
        restockAll();
        Staff.log(admin, "trader-forget", null, "", id);
        Msg.send(admin, "trader.forget-done", id.replace("minecraft:", ""));
    }

    private static void restockAll() {
        for (Trader t : all().values()) {
            if (t.type == Trader.Type.SELLS) {
                rotate(t);
            }
        }
        Ac.markDirty("economy");
    }

    /** An item id from what an admin typed ("oak_log", "minecraft:oak_log" or "hand"), or null if it isn't an item. */
    public static String itemId(ServerPlayerEntity admin, String typed) {
        if ("hand".equals(typed)) {
            ItemStack held = admin.getMainHandStack();
            return held.isEmpty() ? null : Mc.itemId(held.getItem());
        }
        Identifier id = Identifier.tryParse(typed.contains(":") ? typed : "minecraft:" + typed);
        return id != null && Registries.ITEM.containsId(id) ? id.toString() : null;
    }

    /** Owner's own stock: this item is always on the trader, this many at a time. */
    public static void stockAdd(ServerPlayerEntity admin, Trader t, String id, int count) {
        t.blocked.remove(id);
        t.pinned.put(id, count);
        rotate(t);
        Staff.log(admin, "trader-stock-add", null, t.name, id + " x" + count);
        Msg.send(admin, "trader.stock-added", id.replace("minecraft:", ""), count, t.name);
    }

    /** Takes an item off the trader for good (until added back). */
    public static void stockRemove(ServerPlayerEntity admin, Trader t, String id) {
        t.pinned.remove(id);
        t.blocked.add(id);
        rotate(t);
        Staff.log(admin, "trader-stock-remove", null, t.name, id);
        Msg.send(admin, "trader.stock-removed", id.replace("minecraft:", ""), t.name);
    }

    /** Back to the trader's normal random stock. */
    public static void stockClear(ServerPlayerEntity admin, Trader t) {
        t.pinned.clear();
        t.blocked.clear();
        rotate(t);
        Staff.log(admin, "trader-stock-clear", null, t.name, "");
        Msg.send(admin, "trader.stock-cleared", t.name);
    }

    public static void stockList(ServerPlayerEntity admin, Trader t) {
        Msg.send(admin, "trader.stock-header", t.name);
        for (TraderOffer o : t.offers) {
            Msg.send(admin, "trader.stock-line", o.rarity.color + o.id.replace("minecraft:", ""), o.stock, o.maxStock,
                    t.pinned.containsKey(o.id) ? Msg.trFor(admin, "trader.stock-pinned") : "");
        }
        for (String b : t.blocked) {
            Msg.send(admin, "trader.stock-blocked", b.replace("minecraft:", ""));
        }
    }

    /** Every second: keep traders frozen in place. Every minute: rotations. */
    public static void tick(boolean minute) {
        long now = System.currentTimeMillis();
        for (Trader t : new ArrayList<>(all().values())) {
            ServerWorld w = Mc.world(Ac.server(), t.location.world());
            if (w == null || !w.isChunkLoaded(BlockPos.ofFloored(t.location.x(), t.location.y(), t.location.z()))) {
                continue;
            }
            VillagerEntity v = entity(t);
            if (v == null) {
                continue;
            }
            if (v.squaredDistanceTo(t.location.x(), t.location.y(), t.location.z()) > 0.0001 || v.getYaw() != t.location.yaw()) {
                v.refreshPositionAndAngles(t.location.x(), t.location.y(), t.location.z(), t.location.yaw(), 0);
                v.setHeadYaw(t.location.yaw());
                v.setBodyYaw(t.location.yaw());
                v.setVelocity(0, 0, 0);
            }
            if (!v.isAiDisabled() || !v.isInvulnerable()) {
                applyFrozen(v);
            }
            if (v.isOnFire()) {
                v.extinguish();
            }
            if (minute && (now >= t.nextRotation || (t.type == Trader.Type.SELLS && t.offers.isEmpty()))) {
                rotate(t);
            }
        }
        TraderEconomy econ = Ac.get().economy;
        long period = econ.data().marketPeriod;
        if (minute && econ.marketTick(all().values(), settings(), RANDOM)) {
            Ac.markDirty("economy");
            boolean moved = period != econ.data().marketPeriod;
            Set<String> sigs = new HashSet<>();
            for (Trader t : all().values()) {
                for (TraderOffer o : t.offers) {
                    sigs.add(o.signature());
                    if (moved || Ac.get().market.history(o.signature()).isEmpty()) {
                        Ac.get().market.recordPrice(o.signature(), econ.market(o.signature()));
                    }
                }
            }
            Ac.get().market.keepHistory(sigs);
            Ac.markDirty("market");
        }
    }

    // ---- Prices ----

    /** The price of an offer right now (what the trader wants in value). */
    public static double price(Trader t, TraderOffer o) {
        Ac ac = Ac.get();
        return OfferEvaluator.price(o, values(), ac.economy.demand(o.signature(), settings()), t.mood, ac.economy.market(o.signature()));
    }

    /** The price for one player: today's deal and their reputation with this trader lower it. */
    public static double price(Trader t, TraderOffer o, UUID player) {
        double v = price(t, o) * Markets.dealFactor(t, o);
        if (player != null) {
            v *= 1 - Market.discount(Ac.get().market.deals(player, t.entity.toString()));
        }
        return v;
    }

    /** How a trader is paid: "items", "emeralds" or "cash". */
    public static String payment(Trader t) {
        String p = t.payment == null || t.payment.equals("default") ? Ac.config().traders.defaultPayment : t.payment;
        return switch (p) {
            case "cash", "emeralds" -> p;
            default -> "items";
        };
    }

    public static int cashPrice(double value) {
        return (int) Math.max(1, Math.ceil(value * Ac.config().market.cashPerValue));
    }

    public static int emeraldPrice(double value) {
        double em = values().value("minecraft:emerald");
        return (int) Math.max(1, Math.ceil(value / Math.max(0.01, em)));
    }

    /** The price as players pay it at this trader: "$120", "6 Emerald" or "120 ◆" (in items). */
    public static String priceText(Trader t, double value) {
        return switch (payment(t)) {
            case "cash" -> Markets.cash(cashPrice(value));
            case "emeralds" -> emeraldPrice(value) + " " + new ItemStack(Items.EMERALD).getName().getString();
            default -> fmt(value) + " ◆";
        };
    }

    /** "123" or "4.5": values below 10 keep one decimal. */
    public static String fmt(double v) {
        return v >= 10 ? String.format("%,d", Math.round(v)) : String.format("%.1f", v);
    }

    /** "▲ +8%" / "▼ -5%" in green or red. */
    public static String trendText(String signature) {
        double tr = Ac.get().economy.trend(signature);
        int pct = (int) Math.round(tr * 100);
        if (pct == 0) {
            return "§7= 0%";
        }
        return pct > 0 ? "§c▲ +" + pct + "%" : "§a▼ " + pct + "%";
    }

    public static String untilPriceChange() {
        return Durations.format(Ac.get().economy.untilPriceChange(settings()));
    }

    /** Value of one stack (all of it), for showing players what their items are worth. */
    public static double stackValue(ItemStack s) {
        if (s.isEmpty()) {
            return 0;
        }
        return OfferEvaluator.paymentValue(List.of(ItemConv.info(s)), values(), Ac.config().traders.diminishingFactor);
    }

    // ---- Items ----

    public static ItemStack stackFor(TraderOffer o) {
        Item item = Registries.ITEM.get(Identifier.of(o.id));
        ItemStack s = new ItemStack(item, Math.max(1, o.unit));
        for (Map.Entry<String, Integer> e : o.enchantments.entrySet()) {
            ItemConv.enchantment(e.getKey()).ifPresent(en -> s.addEnchantment(en, e.getValue()));
        }
        if (!o.storedEnchantments.isEmpty()) {
            ItemEnchantmentsComponent.Builder b = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
            for (Map.Entry<String, Integer> e : o.storedEnchantments.entrySet()) {
                ItemConv.enchantment(e.getKey()).ifPresent(en -> b.add(en, e.getValue()));
            }
            s.set(DataComponentTypes.STORED_ENCHANTMENTS, b.build());
        }
        if (o.potion != null) {
            Registries.POTION.getEntry(Identifier.of(o.potion))
                    .ifPresent(p -> s.set(DataComponentTypes.POTION_CONTENTS, new PotionContentsComponent(p)));
        }
        return s;
    }

    static int rarityColor(Rarity r) {
        return switch (r) {
            case COMMON -> Theme.WHITE;
            case UNCOMMON -> Theme.GREEN;
            case RARE -> 0x5B8CFF;
            case LEGENDARY -> Theme.GOLD;
        };
    }

    private static ItemStack offerIcon(Trader t, TraderOffer o, boolean clickable, UUID viewer) {
        ItemStack s = stackFor(o);
        if (o.soldOut()) {
            return Btn.of(Items.GRAY_STAINED_GLASS_PANE).color(Theme.SOFT).name(s.getName().getString())
                    .status(Theme.RED, Theme.Sym.CROSS.sp() + Msg.tr("tr.sold-out")).desc(Msg.tr("tr.restock")).build();
        }
        Btn b = Btn.of(s).color(rarityColor(o.rarity)).name(s.getName().getString())
                .status(rarityColor(o.rarity), Theme.Sym.DOT.sp() + Msg.tr("tr.rarity." + o.rarity.name().toLowerCase(java.util.Locale.ROOT)))
                .line(Msg.tr("tr.stock", o.stock + "/" + o.maxStock));
        priceLines(b, t, o, viewer);
        if (clickable) {
            b.left(Msg.tr(payment(t).equals("items") ? "tr.action.offer" : "tr.action.buy"));
        }
        return b.amount(s.getCount()).build();
    }

    private static void priceLines(Btn b, Trader t, TraderOffer o, UUID viewer) {
        b.line(Msg.tr("tr.price", priceText(t, price(t, o, viewer)), trendText(o.signature())));
        if (Markets.dealFactor(t, o) < 1) {
            b.line(Msg.tr("tr.deal-tag", (int) Math.round(Ac.config().market.dailyDealDiscount * 100)));
        }
        if (viewer != null) {
            double off = Market.discount(Ac.get().market.deals(viewer, t.entity.toString()));
            if (off > 0) {
                b.line(Msg.tr("tr.rep-tag", (int) Math.round(off * 100)));
            }
        }
        b.line(Msg.tr("tr.pay-with", Msg.tr("tr.pay." + payment(t))));
        b.line(Msg.tr("tr.price-changes", untilPriceChange()));
    }

    private static ItemStack mysteryIcon(Trader t, TraderOffer o, boolean clickable, UUID viewer) {
        Btn b = Btn.of(Items.CHEST).color(Theme.VIOLET).name(Msg.tr("tr.mystery")).desc(Msg.tr("tr.mystery-desc"))
                .line(Msg.tr("tr.stock", o.stock + "/" + o.maxStock));
        priceLines(b, t, o, viewer);
        if (clickable) {
            b.left(Msg.tr(payment(t).equals("items") ? "tr.action.offer" : "tr.action.buy"));
        }
        return b.build();
    }

    // ---- Player interaction ----

    public static void open(ServerPlayerEntity p, Trader t) {
        if (!Features.on(Features.Feature.TRADERS)) {
            Msg.send(p, "features.is-off", Msg.trFor(p, "feature.traders"));
            return;
        }
        if (Ac.get().jail.isJailed(p.getUuid()) || WaitingRoomFeature.waiting(p) || Arenas.inMatch(p)) {
            Msg.send(p, "trader.blocked");
            return;
        }
        switch (t.type) {
            case SELLS, MYSTERY -> openOffers(p, t);
            case BUYS -> openSellTo(p, t);
            case REQUESTS -> openRequest(p, t);
        }
    }

    private static void openOffers(ServerPlayerEntity p, Trader t) {
        Menu m = Menu.std(Theme.Category.PLAYER, t.name);
        m.renderer(menu -> {
            menu.info(Btn.of(Items.EMERALD).color(Theme.GOLD_LIGHT).name(t.name).desc(Msg.tr("tr.offers-desc"))
                    .line(Msg.tr("tr.next-rotation", Durations.format(Math.max(0, t.nextRotation - System.currentTimeMillis()))))
                    .line(Msg.tr("tr.price-changes", untilPriceChange())).build());
            menu.list(t.offers, o -> icon(t, o, true, p.getUuid()),
                    o -> (pl, c) -> {
                        if (o.soldOut()) {
                            Msg.send(pl, "trader.sold-out");
                            return;
                        }
                        if (payment(t).equals("items")) {
                            openNegotiation(pl, t, o);
                        } else {
                            openBuy(pl, t, o);
                        }
                    },
                    null, List.of(), Msg.tr("tr.no-offers"), Msg.tr("tr.restock"));
        });
        m.open(p);
    }

    private static ItemStack icon(Trader t, TraderOffer o, boolean clickable, UUID viewer) {
        return t.type == Trader.Type.MYSTERY ? mysteryIcon(t, o, clickable, viewer) : offerIcon(t, o, clickable, viewer);
    }

    /** Cash or emerald traders: a simple "buy for this much" window. */
    private static void openBuy(ServerPlayerEntity p, Trader t, TraderOffer o) {
        Menu m = Menu.std(Theme.Category.PLAYER, 3, t.name, Msg.trFor(p, "tr.buy-title"));
        m.parent(null);
        m.renderer(menu -> {
            double v = price(t, o, p.getUuid());
            boolean cash = payment(t).equals("cash");
            int cost = cash ? cashPrice(v) : emeraldPrice(v);
            long have = cash ? Ac.get().market.balance(p.getUuid()) : Markets.countItem(p, Items.EMERALD);
            boolean afford = have >= cost;
            menu.set(11, icon(t, o, false, p.getUuid()), null, null);
            menu.set(13, Btn.of(cash ? Items.GOLD_INGOT : Items.EMERALD).color(Theme.GOLD_LIGHT)
                    .name(Msg.tr("tr.you-have", cash ? Markets.cash(have) : have + " " + new ItemStack(Items.EMERALD).getName().getString())).build(), null, null);
            menu.set(15, Btn.of(afford ? Items.LIME_CONCRETE : Items.RED_CONCRETE).color(afford ? Theme.GREEN : Theme.RED)
                    .name(Msg.tr("tr.buy-for", priceText(t, v))).desc(Msg.tr(afford ? "tr.buy-desc" : "tr.cant-afford"))
                    .left(Msg.tr("tr.action.buy")).glint(afford).build(), null, (pl, c) -> {
                String verdict = buyDirect(pl, t, o);
                Msg.actionBar(pl, verdict);
                pl.sendMessage(Text.literal(verdict));
                if (o.soldOut()) {
                    openOffers(pl, t);
                } else {
                    menu.refresh();
                }
            });
            menu.set(18, Menu.back(), null, (pl, c) -> openOffers(pl, t));
        });
        m.open(p);
    }

    private static String buyDirect(ServerPlayerEntity p, Trader t, TraderOffer o) {
        if (!Ac.get().economy.rateOk(p.getUuid(), settings().tradesPerMinute)) {
            // Buying quickly isn't cheating: the limit just slows it down.
            return "§c" + Msg.tr("tr.v.slow");
        }
        double v = price(t, o, p.getUuid());
        boolean cash = payment(t).equals("cash");
        int cost = cash ? cashPrice(v) : emeraldPrice(v);
        long have = cash ? Ac.get().market.balance(p.getUuid()) : Markets.countItem(p, Items.EMERALD);
        if (have < cost) {
            Mc.sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f, 1f);
            return "§c" + Msg.tr("tr.cant-afford");
        }
        String paid = cash ? Markets.cash(cost) : cost + "x emerald";
        return deliver(p, t, o, paid, () -> {
            if (cash) {
                boolean ok = Ac.get().market.takeCash(p.getUuid(), cost);
                Ac.markDirty("market");
                return ok;
            }
            boolean ok = Markets.takeItem(p, Items.EMERALD, cost);
            if (ok) {
                Ac.get().economy.flow("minecraft:emerald", cost, 0);
            }
            return ok;
        });
    }

    private static final Set<UUID> NEGOTIATING = new HashSet<>();

    private static void openNegotiation(ServerPlayerEntity p, Trader t, TraderOffer o) {
        Menu m = new Menu("", 6);
        m.titleText(Theme.title(Theme.Category.PLAYER, t.name, Msg.trFor(p, "tr.your-offer")));
        Set<Integer> pay = new HashSet<>();
        for (int i : PAYMENT) {
            pay.add(i);
        }
        m.allowPlayerInventory(true);
        m.editable(pay, (pl, slot) -> {
            saveEscrow(pl, m);
            m.refresh();
        });
        String[] verdict = {null};
        m.renderer(menu -> {
            ItemStack glass = Btn.pane(Theme.Category.PLAYER.glass);
            for (int i = 0; i < 54; i++) {
                if (!pay.contains(i)) {
                    menu.icon(i, glass);
                }
            }
            for (int r = 1; r <= 4; r++) {
                menu.icon(r * 9 + 3, Btn.pane(Items.BLACK_STAINED_GLASS_PANE));
            }
            menu.icon(20, icon(t, o, false, p.getUuid()));
            double priceNow = price(t, o, p.getUuid());
            double offered = offerSummary(menu, payment(m), priceNow);
            menu.icon(4, Btn.of(Items.OAK_SIGN).color(Theme.GOLD_LIGHT).name(Msg.tr("tr.how")).desc(Msg.tr("tr.how-desc")).build());
            Btn button;
            if (verdict[0] == null) {
                button = Btn.of(Items.EMERALD).color(Theme.GREEN).name(Msg.tr("tr.offer")).desc(Msg.tr("tr.offer-desc"))
                        .glint(offered >= priceNow && offered > 0);
            } else {
                char code = verdict[0].length() > 1 && verdict[0].charAt(0) == '§' ? verdict[0].charAt(1) : '7';
                net.minecraft.item.Item icon = code == 'a' ? Items.LIME_CONCRETE : code == 'e' ? Items.YELLOW_CONCRETE : code == 'c' ? Items.RED_CONCRETE : Items.EMERALD;
                int color = code == 'a' ? Theme.GREEN : code == 'e' ? Theme.GOLD : code == 'c' ? Theme.RED : Theme.SOFT;
                button = Btn.of(icon).color(color).name(verdict[0].replaceAll("§.", "")).desc(Msg.tr("tr.offer-again"));
            }
            menu.set(49, button.left(Msg.tr("tr.action.offer")).build(), (pl, c) -> {
                verdict[0] = offer(pl, t, o, m);
                if (verdict[0] != null) {
                    menu.refresh();
                    menu.retitle(pl, Theme.title(Theme.Category.PLAYER, t.name, verdict[0].replaceAll("§.", "")));
                }
            });
            menu.set(45, Menu.back(), (pl, c) -> {
                returnPayment(pl, m);
                openOffers(pl, t);
            });
            menu.set(53, Menu.close(), (pl, c) -> pl.closeHandledScreen());
        });
        m.onClose(pl -> {
            returnPayment(pl, m);
            NEGOTIATING.remove(pl.getUuid());
        });
        NEGOTIATING.add(p.getUuid());
        m.open(p);
    }

    /** Bottom of the offer window: what every item in the offer is worth, the total, and how close it is. */
    private static double offerSummary(Menu menu, List<ItemStack> stacks, double price) {
        List<ItemInfo> infos = new ArrayList<>();
        Map<String, String> names = new HashMap<>();
        for (ItemStack st : stacks) {
            ItemInfo i = ItemConv.info(st);
            infos.add(i);
            names.putIfAbsent(i.id, st.getName().getString());
        }
        double factor = Ac.config().traders.diminishingFactor;
        List<OfferEvaluator.Line> lines = OfferEvaluator.breakdown(infos, values(), factor);
        double total = 0;
        for (OfferEvaluator.Line l : lines) {
            total += l.value();
        }
        boolean enough = total >= price && total > 0;
        int pct = price <= 0 ? 0 : (int) Math.min(100, Math.round(total / price * 100));
        Btn b = Btn.of(enough ? Items.LIME_DYE : total > 0 ? Items.YELLOW_DYE : Items.GRAY_DYE)
                .color(enough ? Theme.GREEN : total > 0 ? Theme.GOLD : Theme.SOFT)
                .name(Msg.tr("tr.offer-value", fmt(total), fmt(price)))
                .line(bar(pct) + " §f" + pct + "%");
        if (lines.isEmpty()) {
            b.line(Msg.tr("tr.offer-empty"));
        }
        int shown = 0;
        for (OfferEvaluator.Line l : lines) {
            if (shown++ >= 12) {
                b.line("§7…");
                break;
            }
            b.line("§7" + l.count() + "x §f" + names.getOrDefault(l.id(), l.id()) + " §8» §e" + fmt(l.value()));
        }
        if (enough && total > price * 1.25) {
            b.line(Msg.tr("tr.overpaying", fmt(total - price)));
        } else if (!enough && total > 0) {
            b.line(Msg.tr("tr.still-need", fmt(price - total)));
        }
        menu.icon(47, b.build());
        menu.icon(51, Btn.of(Items.GOLD_NUGGET).color(Theme.GOLD_LIGHT).name(Msg.tr("tr.price-now", fmt(price)))
                .line(Msg.tr("tr.price-changes", untilPriceChange())).line(Msg.tr("tr.value-hint")).build());
        return total;
    }

    /** /market: everything traders sell right now, with price, stock and the last price move. */
    public static void showMarket(ServerPlayerEntity p) {
        p.sendMessage(Text.literal("§8§m        §r §6§l" + Msg.trFor(p, "tr.market") + " §8§m        "));
        int n = 0;
        for (Trader t : all().values()) {
            if (t.type != Trader.Type.SELLS && t.type != Trader.Type.MYSTERY) {
                continue;
            }
            for (TraderOffer o : t.offers) {
                if (n++ >= 40) {
                    break;
                }
                String item = t.type == Trader.Type.MYSTERY ? Msg.trFor(p, "tr.mystery") : stackFor(o).getName().getString();
                p.sendMessage(Text.literal(o.rarity.color + item + (o.unit > 1 ? " §7x" + o.unit : "") + " §8» §e" + priceText(t, price(t, o, p.getUuid()))
                        + " " + trendText(o.signature()) + " §7(" + (o.soldOut() ? Msg.trFor(p, "tr.sold-out") : o.stock + "/" + o.maxStock)
                        + ") §8@ §f" + t.name));
            }
        }
        if (n == 0) {
            Msg.send(p, "tr.market-empty");
        }
        p.sendMessage(Text.literal(Msg.trFor(p, "tr.price-changes", untilPriceChange())));
    }

    static String bar(int pct) {
        int filled = Math.max(0, Math.min(10, pct / 10));
        return "§a" + "■".repeat(filled) + "§8" + "■".repeat(10 - filled);
    }

    private static List<ItemStack> payment(Menu m) {
        List<ItemStack> out = new ArrayList<>();
        for (int i : PAYMENT) {
            ItemStack s = m.inventory().getStack(i);
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static void saveEscrow(ServerPlayerEntity p, Menu m) {
        List<String> enc = new ArrayList<>();
        for (ItemStack s : payment(m)) {
            enc.add(ItemConv.encode(s));
        }
        Ac.get().setEscrow(p.getUuid(), "trader", enc);
    }

    private static void clearPayment(Menu m) {
        for (int i : PAYMENT) {
            m.inventory().setStack(i, ItemStack.EMPTY);
        }
    }

    /** Payment goes back to the player whenever the window closes without a deal (also on disconnect). */
    private static void returnPayment(ServerPlayerEntity p, Menu m) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : payment(m)) {
            items.add(s.copy());
        }
        clearPayment(m);
        Trades.giveBack(p, items);
        Ac.get().setEscrow(p.getUuid(), "trader", List.of());
    }

    /** @return the verdict line shown in the menu, or null when the menu was closed. */
    private static String offer(ServerPlayerEntity p, Trader t, TraderOffer o, Menu m) {
        Ac ac = Ac.get();
        TraderEconomy.Settings st = settings();
        List<ItemStack> payStacks = payment(m);
        if (payStacks.isEmpty()) {
            return "§7" + Msg.tr("tr.v.empty");
        }
        if (!ac.economy.rateOk(p.getUuid(), st.tradesPerMinute)) {
            // Buying quickly isn't cheating: the limit just slows it down.
            return "§c" + Msg.tr("tr.v.slow");
        }
        List<ItemInfo> infos = new ArrayList<>();
        String illegal = null;
        for (ItemStack s : payStacks) {
            ItemInfo i = ItemConv.info(s);
            infos.add(i);
            String r = IllegalItems.check(i, Ac.config().illegalItems.bannedItems, Enchants.MAX_LEVELS);
            if (r != null) {
                illegal = r;
                Illegal.handle(p, s, r);
            }
        }
        if (illegal != null) {
            saveEscrow(p, m);
            return "§c" + Msg.tr("tr.v.refuses");
        }
        double demand = ac.economy.demand(o.signature(), st) * ac.economy.market(o.signature()) * Markets.dealFactor(t, o)
                * (1 - Market.discount(ac.market.deals(p.getUuid(), t.entity.toString())));
        OfferEvaluator.Result res = OfferEvaluator.evaluate(o, infos, values(), demand, t.mood,
                Ac.config().traders.diminishingFactor, Ac.config().traders.closeFraction, null);
        switch (res.verdict()) {
            case REJECTED -> {
                Mc.sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f, 1f);
                return "§c" + (res.reason() == null ? Msg.tr("tr.v.not-accepted") : res.reason());
            }
            case WAY_TOO_LOW -> {
                Mc.sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f, 1f);
                return "§c" + Msg.tr("tr.v.too-low");
            }
            case GETTING_CLOSER -> {
                Mc.sound(p, SoundEvents.ENTITY_VILLAGER_AMBIENT, 1f, 1f);
                return "§e" + Msg.tr("tr.v.closer");
            }
            default -> {
                // fall through to the deal
            }
        }
        return deliver(p, t, o, Trades.describe(payStacks), () -> {
            for (ItemInfo i : infos) {
                ac.economy.flow(i.id, i.count, 0);
            }
            clearPayment(m);
            ac.setEscrow(p.getUuid(), "trader", List.of());
            return true;
        });
    }

    /**
     * The deal itself: checks room and caps, claims the stock, takes the payment ({@code pay}), then gives the goods.
     *
     * @return the verdict line
     */
    private static String deliver(ServerPlayerEntity p, Trader t, TraderOffer o, String paid, java.util.function.BooleanSupplier pay) {
        Ac ac = Ac.get();
        TraderEconomy.Settings st = settings();
        Rarity mysteryRarity = null;
        ItemStack goods;
        if (t.type == Trader.Type.MYSTERY) {
            mysteryRarity = TraderEconomy.rollMystery(settings(), RANDOM);
            TraderOffer prize = ac.economy.randomOfRarity(mysteryRarity, st, Ac.config().illegalItems.bannedItems, RANDOM);
            if (prize == null) {
                mysteryRarity = Rarity.COMMON;
                prize = ac.economy.randomOfRarity(Rarity.COMMON, st, Ac.config().illegalItems.bannedItems, RANDOM);
            }
            if (prize == null) {
                return "§c" + Msg.tr("tr.v.no-box");
            }
            goods = stackFor(prize);
        } else {
            goods = stackFor(o);
        }
        if (!hasRoom(p, goods)) {
            return "§c" + Msg.tr("tr.v.room");
        }
        String macro = ac.economy.macroCheck(p.getUuid(), LAST_ROTATION.getOrDefault(t.entity, 0L));
        if (macro != null) {
            PlayerSessionFlags.flag(p, CheckType.TRADE_MACRO, 1.0, macro);
        }
        String ip = Ac.session(p).ip;
        TraderEconomy.Refusal ref = ac.economy.purchase(t, o, p.getUuid(), ip, st);
        if (ref != TraderEconomy.Refusal.NONE) {
            Mc.sound(p, SoundEvents.ENTITY_VILLAGER_NO, 1f, 1f);
            return "§c" + Msg.tr("trader.refusal." + ref.name().toLowerCase());
        }
        // Deal: the payment is destroyed (item sink) and the goods are given, in this one step.
        if (!pay.getAsBoolean()) {
            ac.economy.refund(o, p.getUuid());
            return "§c" + Msg.tr("tr.cant-afford");
        }
        p.getInventory().insertStack(goods.copy());
        ac.market.addDeal(p.getUuid(), t.entity.toString());
        Ac.markDirty("market");
        Teams.xpForTrade(p);
        if (mysteryRarity != null) {
            mysteryAnimation(p, mysteryRarity, goods);
        }
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        ac.logs.trade(System.currentTimeMillis(), "trader", p.getUuid(), p.getGameProfile().name(), null, t.name,
                "bought " + ItemConv.info(goods).describe() + " for " + paid);
        ac.dupeWatch.legitGain(p.getUuid(), Dupes.value(List.of(goods)), System.currentTimeMillis());
        Ac.markDirty("traders");
        Ac.markDirty("economy");
        return "§a" + Msg.tr("tr.v.deal");
    }

    private static boolean hasRoom(ServerPlayerEntity p, ItemStack s) {
        var inv = p.getInventory();
        if (inv.getEmptySlot() >= 0) {
            return true;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack in = inv.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(in, s) && in.getCount() + s.getCount() <= in.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    /** A little "opening" animation: rising clicks, then the reveal. */
    private static void mysteryAnimation(ServerPlayerEntity p, Rarity r, ItemStack s) {
        Mc.title(p, r.color + "§l" + r.name(), "§f" + s.getName().getString(), 5, 50, 15);
        for (int i = 0; i < 4; i++) {
            Mc.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 0.8f, 0.8f + i * 0.3f);
        }
        Mc.sound(p, r.ordinal() >= Rarity.RARE.ordinal() ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    // ---- Sell-to and request traders ----

    private static void openSellTo(ServerPlayerEntity p, Trader t) {
        if (!payment(t).equals("items")) {
            openSellForMoney(p, t);
            return;
        }
        Menu m = Menu.std(Theme.Category.PLAYER, 3, t.name, Msg.trFor(p, "tr.buying"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.EMERALD).color(Theme.GOLD_LIGHT).name(t.name).desc(Msg.tr("tr.buying-desc")).build());
            int slot = 10;
            for (Map.Entry<String, String[]> e : Ac.get().traders.sellRules.entrySet()) {
                if (slot > 16) {
                    break;
                }
                String item = e.getKey();
                int amount = Integer.parseInt(e.getValue()[0]);
                String reward = e.getValue()[1];
                int rewardCount = Integer.parseInt(e.getValue()[2]);
                ItemStack icon = new ItemStack(Registries.ITEM.get(Identifier.of(item)), Math.min(64, amount));
                menu.set(slot++, Btn.of(icon).color(Theme.WHITE).name(icon.getName().getString())
                        .line(Msg.tr("tr.sell-line", amount, rewardCount, reward.replace("minecraft:", "")))
                        .left(Msg.tr("tr.action.sell")).build(), (pl, c) -> sell(pl, t, item, amount, reward, rewardCount));
            }
        });
        m.open(p);
    }

    /** Cash or emerald traders that buy: put anything in, see what you'd get, sell. */
    private static void openSellForMoney(ServerPlayerEntity p, Trader t) {
        boolean cash = payment(t).equals("cash");
        Menu m = new Menu("", 6);
        m.titleText(Theme.title(Theme.Category.PLAYER, t.name, Msg.trFor(p, "tr.buying")));
        Set<Integer> pay = new HashSet<>();
        for (int i : PAYMENT) {
            pay.add(i);
        }
        m.allowPlayerInventory(true);
        m.editable(pay, (pl, slot) -> {
            saveEscrow(pl, m);
            m.refresh();
        });
        m.renderer(menu -> {
            ItemStack glass = Btn.pane(Theme.Category.PLAYER.glass);
            for (int i = 0; i < 54; i++) {
                if (!pay.contains(i)) {
                    menu.icon(i, glass);
                }
            }
            for (int r = 1; r <= 4; r++) {
                menu.icon(r * 9 + 3, Btn.pane(Items.BLACK_STAINED_GLASS_PANE));
            }
            menu.icon(20, Btn.of(cash ? Items.GOLD_INGOT : Items.EMERALD).color(Theme.GOLD_LIGHT).name(t.name)
                    .desc(Msg.tr("tr.sell-money-desc", (int) Math.round(Ac.config().market.sellRate * 100), Msg.tr("tr.pay." + payment(t)))).build());
            List<ItemStack> items = payment(m);
            double value = 0;
            Btn sum = Btn.of(Items.PAPER).color(Theme.GOLD_LIGHT);
            List<String> lines = new ArrayList<>();
            for (ItemStack st : items) {
                double v = stackValue(st) * Ac.config().market.sellRate;
                value += v;
                lines.add("§7" + st.getCount() + "x §f" + st.getName().getString() + " §8» §e" + payout(cash, v));
            }
            int units = payoutUnits(cash, value);
            sum.name(Msg.tr("tr.you-get", payout(cash, value)));
            for (int i = 0; i < Math.min(14, lines.size()); i++) {
                sum.line(lines.get(i));
            }
            if (lines.isEmpty()) {
                sum.line(Msg.tr("tr.offer-empty"));
            }
            menu.icon(47, sum.build());
            menu.set(49, Btn.of(units > 0 ? Items.LIME_CONCRETE : Items.GRAY_CONCRETE).color(units > 0 ? Theme.GREEN : Theme.SOFT)
                    .name(Msg.tr("tr.sell-for", payout(cash, value))).left(Msg.tr("tr.action.sell")).glint(units > 0).build(), (pl, c) -> {
                sellForMoney(pl, t, m, cash);
                menu.refresh();
            });
            menu.set(45, Menu.back(), (pl, c) -> pl.closeHandledScreen());
            menu.set(53, Menu.close(), (pl, c) -> pl.closeHandledScreen());
        });
        m.onClose(pl -> {
            returnPayment(pl, m);
            NEGOTIATING.remove(pl.getUuid());
        });
        NEGOTIATING.add(p.getUuid());
        m.open(p);
    }

    private static int payoutUnits(boolean cash, double value) {
        if (cash) {
            return (int) Math.floor(value * Ac.config().market.cashPerValue);
        }
        return (int) Math.floor(value / Math.max(0.01, values().value("minecraft:emerald")));
    }

    private static String payout(boolean cash, double value) {
        int n = payoutUnits(cash, value);
        return cash ? Markets.cash(n) : n + " " + new ItemStack(Items.EMERALD).getName().getString();
    }

    private static void sellForMoney(ServerPlayerEntity p, Trader t, Menu m, boolean cash) {
        Ac ac = Ac.get();
        List<ItemStack> items = payment(m);
        if (items.isEmpty()) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "tr.v.empty"));
            return;
        }
        if (!ac.economy.rateOk(p.getUuid(), settings().tradesPerMinute)) {
            Msg.send(p, "trader.slow-down");
            return;
        }
        double value = 0;
        int count = 0;
        for (ItemStack st : items) {
            ItemInfo info = ItemConv.info(st);
            if (OfferEvaluator.forbiddenPayment(info)) {
                Msg.send(p, "market.no-containers");
                return;
            }
            String illegal = IllegalItems.check(info, Ac.config().illegalItems.bannedItems, Enchants.MAX_LEVELS);
            if (illegal != null) {
                Illegal.handle(p, st, illegal);
                Msg.send(p, "market.illegal");
                return;
            }
            value += stackValue(st) * Ac.config().market.sellRate;
            count += st.getCount();
        }
        int units = payoutUnits(cash, value);
        if (units <= 0) {
            Msg.send(p, "tr.too-little");
            return;
        }
        var cfg = Ac.config().traders;
        if (!ac.economy.sellAllowed(p.getUuid(), count, cfg.sellDailyCapPerPlayer, cfg.sellDailyCapServer)) {
            Msg.send(p, "trader.sell-cap");
            return;
        }
        String sold = Trades.describe(items);
        for (ItemStack st : items) {
            ac.economy.flow(Registries.ITEM.getId(st.getItem()).toString(), st.getCount(), 0);
        }
        clearPayment(m);
        ac.setEscrow(p.getUuid(), "trader", List.of());
        if (cash) {
            ac.market.addCash(p.getUuid(), units);
            Ac.markDirty("market");
        } else {
            Markets.giveItem(p, Items.EMERALD, units);
            ac.economy.flow("minecraft:emerald", 0, units);
        }
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        Msg.send(p, "tr.sold", payout(cash, value));
        ac.logs.trade(System.currentTimeMillis(), "sell-to-trader", p.getUuid(), p.getGameProfile().name(), null, t.name,
                "sold " + sold + " for " + payout(cash, value));
        Teams.xpForTrade(p);
        Ac.markDirty("economy");
    }

    /** /market <item>: how the price of matching items moved over the last day. */
    public static void showHistory(ServerPlayerEntity p, String query) {
        String q = query.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "").replace(' ', '_');
        int shown = 0;
        for (Trader t : all().values()) {
            for (TraderOffer o : t.offers) {
                if (shown >= 5 || !o.id.replace("minecraft:", "").contains(q)) {
                    continue;
                }
                shown++;
                List<double[]> h = Ac.get().market.history(o.signature());
                double now = price(t, o, p.getUuid());
                double current = Ac.get().economy.market(o.signature());
                StringBuilder spark = new StringBuilder();
                double min = Double.MAX_VALUE;
                double max = 0;
                for (double[] pt : h) {
                    min = Math.min(min, pt[1]);
                    max = Math.max(max, pt[1]);
                }
                String bars = "▁▂▃▄▅▆▇█";
                double prev = -1;
                for (double[] pt : h) {
                    int i = max - min < 1e-9 ? 3 : (int) Math.round((pt[1] - min) / (max - min) * 7);
                    spark.append(prev < 0 ? "§e" : pt[1] > prev ? "§c" : pt[1] < prev ? "§a" : "§7").append(bars.charAt(i));
                    prev = pt[1];
                }
                double first = h.isEmpty() ? current : h.get(0)[1];
                int pct = first <= 0 ? 0 : (int) Math.round((current / first - 1) * 100);
                double unit = current <= 0 ? now : now / current;
                p.sendMessage(Text.literal("§6" + stackFor(o).getName().getString() + " §8@ §f" + t.name + " §8» §e" + priceText(t, now)
                        + " " + trendText(o.signature())));
                p.sendMessage(Text.literal("  " + spark + " §7" + Msg.trFor(p, "tr.history-range", priceText(t, unit * (h.isEmpty() ? current : min)),
                        priceText(t, unit * (h.isEmpty() ? current : max)), (pct >= 0 ? "+" : "") + pct + "%")));
            }
        }
        if (shown == 0) {
            Msg.send(p, "tr.history-none", query);
        }
    }

    private static int count(ServerPlayerEntity p, Item item) {
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

    private static void take(ServerPlayerEntity p, Item item, int amount) {
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

    private static void sell(ServerPlayerEntity p, Trader t, String itemId, int amount, String reward, int rewardCount) {
        Ac ac = Ac.get();
        Item item = Registries.ITEM.get(Identifier.of(itemId));
        if (!ac.economy.rateOk(p.getUuid(), settings().tradesPerMinute)) {
            Msg.send(p, "trader.slow-down");
            return;
        }
        if (count(p, item) < amount) {
            Msg.send(p, "trader.not-enough", amount, itemId.replace("minecraft:", ""));
            return;
        }
        var cfg = Ac.config().traders;
        if (!ac.economy.sellAllowed(p.getUuid(), amount, cfg.sellDailyCapPerPlayer, cfg.sellDailyCapServer)) {
            Msg.send(p, "trader.sell-cap");
            return;
        }
        take(p, item, amount);
        ItemStack r = new ItemStack(Registries.ITEM.get(Identifier.of(reward)), rewardCount);
        Trades.giveBack(p, List.of(r));
        ac.economy.flow(itemId, amount, 0);
        ac.economy.flow(reward, 0, rewardCount);
        ac.logs.trade(System.currentTimeMillis(), "sell-to-trader", p.getUuid(), p.getGameProfile().name(), null, t.name,
                "sold " + amount + "x " + itemId + " for " + rewardCount + "x " + reward);
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        Ac.markDirty("economy");
    }

    private static void openRequest(ServerPlayerEntity p, Trader t) {
        if (t.requestItem == null || !com.vylorq.anticheat.core.stats.AcStats.day(System.currentTimeMillis()).equals(t.requestDay)) {
            rotate(t);
        }
        Menu m = Menu.std(Theme.Category.PLAYER, 3, t.name, Msg.trFor(p, "tr.request"));
        m.renderer(menu -> {
            ItemStack want = new ItemStack(Registries.ITEM.get(Identifier.of(t.requestItem)), Math.min(64, t.requestCount));
            menu.set(13, Btn.of(want).color(Theme.WHITE).name(want.getName().getString())
                    .desc(Msg.tr("tr.request-desc", t.requestCount, t.requestItem.replace("minecraft:", "")))
                    .status(Theme.GREEN, Theme.Sym.DOT.sp() + Msg.tr("tr.request-reward"))
                    .left(Msg.tr("tr.action.hand-in")).build(), (pl, c) -> handIn(pl, t));
        });
        m.open(p);
    }

    private static void handIn(ServerPlayerEntity p, Trader t) {
        Ac ac = Ac.get();
        Item item = Registries.ITEM.get(Identifier.of(t.requestItem));
        if (count(p, item) < t.requestCount) {
            Msg.send(p, "trader.not-enough", t.requestCount, t.requestItem.replace("minecraft:", ""));
            return;
        }
        String key = "request:" + t.entity + ":" + t.requestDay;
        if (!RequestLimits.firstToday(p.getUuid(), key)) {
            Msg.send(p, "trader.request-done");
            return;
        }
        TraderOffer prize = ac.economy.randomOfRarity(Rarity.UNCOMMON, settings(), Ac.config().illegalItems.bannedItems, RANDOM);
        if (prize == null) {
            Msg.send(p, "trader.no-reward");
            return;
        }
        take(p, item, t.requestCount);
        ItemStack r = stackFor(prize);
        Trades.giveBack(p, List.of(r));
        ac.economy.flow(t.requestItem, t.requestCount, 0);
        ac.logs.trade(System.currentTimeMillis(), "request-trader", p.getUuid(), p.getGameProfile().name(), null, t.name,
                "handed in " + t.requestCount + "x " + t.requestItem + " for " + ItemConv.info(r).describe());
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1f);
        Ac.markDirty("economy");
    }

    /** One hand-in per player per trader per day. */
    static final class RequestLimits {
        private static final Set<String> DONE = new HashSet<>();

        static boolean firstToday(UUID p, String key) {
            return DONE.add(p + "|" + key);
        }
    }

    // ---- Economy health ----

    /** Warns admins about profit loops and item flow imbalances (section 23.7). Runs hourly and on start. */
    public static void economyWarnings() {
        for (String pr : economyProblems()) {
            Ac.LOG.warn("[Economy] {}", pr);
            Staff.broadcast(Msg.prefixed("§6[Economy] §f" + pr));
        }
    }

    /** Sell-to rules that pay more than the item costs to buy, plus suspicious item flows. */
    public static List<String> economyProblems() {
        Ac ac = Ac.get();
        Map<String, Double> payouts = new HashMap<>();
        for (Map.Entry<String, String[]> e : ac.traders.sellRules.entrySet()) {
            try {
                double amount = Double.parseDouble(e.getValue()[0]);
                double reward = values().value(e.getValue()[1]) * Double.parseDouble(e.getValue()[2]);
                payouts.put(e.getKey(), reward / Math.max(1, amount));
            } catch (Exception ignored) {
                // bad rule, skip
            }
        }
        List<String> problems = new ArrayList<>(TraderEconomy.profitLoops(payouts, values(), Ac.config().traders.minMarkup));
        for (String f : ac.economy.flowWarnings(10.0, 200)) {
            problems.add("flow: " + f);
        }
        return problems;
    }

    // ---- Admin menus ----

    public static void openEdit(ServerPlayerEntity admin, Trader t) {
        Theme.Category cat = Theme.Category.TRADERS;
        Menu m = Menu.std(cat, Msg.trFor(admin, "cat.traders"), t.name).perm(Perm.TRADER_ADMIN);
        m.renderer(menu -> {
            menu.info(Btn.of(Items.EMERALD).name(cat, t.name).line(Msg.tr("panel.offers", t.offers.size()))
                    .line(Msg.tr("tr.next-rotation", Durations.format(Math.max(0, t.nextRotation - System.currentTimeMillis())))).build());
            menu.set(20, Btn.of(Items.NAME_TAG).name(cat, Msg.tr("cm.rename")).line(Msg.tr("settings.now", t.name))
                    .left(Msg.tr("panel.action.change")).build(), (pl, c) -> Input.text(pl, Msg.tr("cm.rename"), t.name, name -> {
                if (name != null && !name.isBlank()) {
                    t.name = name.length() > 32 ? name.substring(0, 32) : name;
                    VillagerEntity v = entity(t);
                    if (v != null) {
                        applyLook(v, t);
                    }
                    Ac.markDirty("traders");
                    Staff.log(pl, "trader-rename", null, t.name, "");
                }
                openEdit(pl, t);
            }));
            Btn spec = Btn.of(Items.BOOK).name(cat, Msg.tr("tr.specialty")).line(Msg.tr("settings.now", t.specialty.name().toLowerCase(java.util.Locale.ROOT)));
            menu.set(21, spec.left(Msg.tr("settings.next-option")).right(Msg.tr("settings.prev-option")).build(), (pl, c) -> {
                Specialty[] all = Specialty.values();
                t.specialty = all[(t.specialty.ordinal() + (c.isRight() ? all.length - 1 : 1)) % all.length];
                VillagerEntity v = entity(t);
                if (v != null) {
                    applyLook(v, t);
                }
                rotate(t);
                menu.refresh();
            });
            menu.set(22, Btn.of(Items.GRASS_BLOCK).name(cat, Msg.tr("tr.look")).line(Msg.tr("settings.now", t.look.replace("minecraft:", "")))
                    .left(Msg.tr("settings.next-option")).build(), (pl, c) -> {
                int i = LOOKS.indexOf(t.look.replace("minecraft:", ""));
                t.look = "minecraft:" + LOOKS.get((i + 1) % LOOKS.size());
                VillagerEntity v = entity(t);
                if (v != null) {
                    applyLook(v, t);
                }
                Ac.markDirty("traders");
                menu.refresh();
            });
            Btn type = Btn.of(Items.CHEST).name(cat, Msg.tr("tr.type"));
            for (Trader.Type ty : Trader.Type.values()) {
                boolean cur = ty == t.type;
                type.status(cur ? Theme.GOLD_LIGHT : Theme.SOFT, (cur ? Theme.Sym.ARROW.sp() : "  ") + Msg.tr("tr.type." + ty.name().toLowerCase(java.util.Locale.ROOT)));
            }
            menu.set(23, type.left(Msg.tr("settings.next-option")).build(), (pl, c) -> {
                Trader.Type[] all = Trader.Type.values();
                t.type = all[(t.type.ordinal() + 1) % all.length];
                rotate(t);
                menu.refresh();
            });
            Btn pb = Btn.of(Items.GOLD_INGOT).name(cat, Msg.tr("tr.payment"));
            for (String opt : List.of("default", "items", "emeralds", "cash")) {
                boolean cur = opt.equals(t.payment == null ? "default" : t.payment);
                String label = opt.equals("default") ? Msg.tr("tr.pay.default", Msg.tr("tr.pay." + payment(new Trader()))) : Msg.tr("tr.pay." + opt);
                pb.status(cur ? Theme.GOLD_LIGHT : Theme.SOFT, (cur ? Theme.Sym.ARROW.sp() : "  ") + label);
            }
            menu.set(25, pb.left(Msg.tr("settings.next-option")).build(), (pl, c) -> {
                List<String> opts = List.of("default", "items", "emeralds", "cash");
                int i = Math.max(0, opts.indexOf(t.payment == null ? "default" : t.payment));
                t.payment = opts.get((i + 1) % opts.size());
                Ac.markDirty("traders");
                Staff.log(pl, "trader-payment", null, t.name, t.payment);
                menu.refresh();
            });
            menu.set(24, Btn.of(Items.EMERALD).name(cat, Msg.tr("tr.restock-now")).desc(Msg.tr("tr.restock-now-desc"))
                    .left(Msg.tr("panel.action.do")).build(), (pl, c) -> {
                rotate(t);
                Staff.log(pl, "trader-restock", null, t.name, "");
                Msg.send(pl, "trader.restocked");
                menu.refresh();
            });
            menu.set(30, Btn.of(Items.ENDER_PEARL).name(cat, Msg.tr("tr.move")).desc(Msg.tr("tr.move-desc"))
                    .left(Msg.tr("panel.action.do")).build(), (pl, c) -> {
                Ac.session(pl).traderMove = t.entity;
                pl.closeHandledScreen();
                Msg.send(pl, "trader.move-hint");
            });
            menu.set(31, Btn.of(Items.WRITABLE_BOOK).name(cat, Msg.tr("tr.log")).desc(Msg.tr("tr.log-desc"))
                    .left(Msg.tr("ui.action.open")).build(), (pl, c) -> {
                pl.closeHandledScreen();
                try {
                    for (var row : Ac.get().db.query("SELECT id, time, a_name, detail FROM trades WHERE b_name = ? ORDER BY id DESC LIMIT 15", t.name)) {
                        pl.sendMessage(Text.literal("§7" + new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(row.time()))
                                + " §f" + row.a() + " §7" + row.b()));
                    }
                } catch (Exception e) {
                    Msg.send(pl, "general.error");
                }
            });
            menu.set(29, Btn.of(Items.BARREL).name(cat, Msg.tr("tr.stock-menu")).desc(Msg.tr("tr.stock-menu-desc"))
                    .line(Msg.tr("tr.stock-menu-count", t.pinned.size(), t.blocked.size()))
                    .left(Msg.tr("ui.action.open")).build(), (pl, c) -> openStock(pl, t));
            menu.set(33, Btn.of(Items.COMPARATOR).name(cat, Msg.tr("tr.rules")).desc(Msg.tr("tr.rules-desc"))
                    .line(Msg.tr("tr.rules-odds", Ac.config().traders.rareOdds, Ac.config().traders.legendaryOdds))
                    .line(Msg.tr("tr.rules-buys", Ac.config().traders.buysPerDay == 0 ? Msg.tr("tr.no-limit") : String.valueOf(Ac.config().traders.buysPerDay)))
                    .left(Msg.tr("ui.action.open")).build(), (pl, c) -> {
                if (Perms.require(pl, Perm.SETTINGS)) {
                    com.vylorq.anticheat.gui.SettingsMenu.page(pl, com.vylorq.anticheat.gui.SettingsMenu.Page.TRADERS);
                }
            });
            menu.set(34, Btn.of(Items.CLOCK).name(cat, Msg.tr("tr.reset-obtained")).desc(Msg.tr("tr.reset-obtained-desc"))
                    .line(Msg.tr("tr.obtained-count", Ac.get().economy.data().obtained.size()))
                    .shift(Msg.tr("panel.action.do")).build(), (pl, c) -> {
                if (!c.isShift()) {
                    Msg.warn(pl, "cm.delete-shift");
                    return;
                }
                Confirm.open(pl, cat, Msg.tr("tr.reset-obtained"), Msg.tr("tr.reset-obtained-desc"),
                        Btn.of(Items.CLOCK).name(cat, Msg.tr("tr.reset-obtained")).build(), () -> {
                            resetObtained(pl);
                            openEdit(pl, t);
                        });
            });
            menu.set(32, Btn.of(Items.TNT).name(Theme.Category.PUNISHMENTS, Msg.tr("tr.remove")).desc(Msg.tr("tr.remove-desc"))
                    .shift(Msg.tr("panel.action.delete")).build(), (pl, c) -> {
                if (!c.isShift()) {
                    Msg.warn(pl, "cm.delete-shift");
                    return;
                }
                confirmRemove(pl, t);
            });
        });
        m.open(admin);
    }

    /** One line of the stock menu: an offer on sale now, or an item taken off this trader. */
    private record StockRow(String id, TraderOffer offer) {
    }

    /** The owner's stock menu: see what's on sale, keep items always in stock, take items off, add the held item. */
    public static void openStock(ServerPlayerEntity admin, Trader t) {
        Theme.Category cat = Theme.Category.TRADERS;
        Menu m = Menu.std(cat, Msg.trFor(admin, "cat.traders"), t.name, Msg.trFor(admin, "tr.stock-menu")).perm(Perm.TRADER_ADMIN);
        m.renderer(menu -> {
            menu.info(Btn.of(Items.BARREL).name(cat, Msg.tr("tr.stock-menu")).desc(Msg.tr("tr.stock-menu-help")).build());
            List<StockRow> rows = new ArrayList<>();
            for (TraderOffer o : t.offers) {
                rows.add(new StockRow(o.id, o));
            }
            for (String b : t.blocked) {
                if (!t.pinned.containsKey(b)) {
                    rows.add(new StockRow(b, null));
                }
            }
            menu.list(rows, r -> stockIcon(t, r), r -> (pl, c) -> {
                if (r.offer() == null) {
                    t.blocked.remove(r.id());
                    rotate(t);
                    Msg.send(pl, "trader.stock-allowed", r.id().replace("minecraft:", ""));
                    menu.refresh();
                } else if (c.isShift()) {
                    if (t.pinned.remove(r.id()) != null) {
                        rotate(t);
                    }
                    menu.refresh();
                } else if (c.isRight()) {
                    stockRemove(pl, t, r.id());
                    menu.refresh();
                } else {
                    askAmount(pl, t, r.id(), t.pinned.getOrDefault(r.id(), r.offer().maxStock));
                }
            }, null, List.of(), Msg.tr("tr.no-offers"), Msg.tr("tr.stock-menu-help"));
            menu.set(Menu.SEARCH, Btn.of(Items.HOPPER).name(cat, Msg.tr("tr.stock-add-held")).desc(Msg.tr("tr.stock-add-held-desc"))
                    .left(Msg.tr("panel.action.do")).build(), (pl, c) -> {
                ItemStack held = pl.getMainHandStack();
                if (held.isEmpty()) {
                    Msg.warn(pl, "tr.stock-hold-item");
                    return;
                }
                askAmount(pl, t, Mc.itemId(held.getItem()), Math.max(1, held.getCount()));
            });
            menu.set(Menu.FILTER, Btn.of(Items.EMERALD).name(cat, Msg.tr("tr.restock-now")).desc(Msg.tr("tr.restock-now-desc"))
                    .left(Msg.tr("panel.action.do")).build(), (pl, c) -> {
                rotate(t);
                Msg.send(pl, "trader.restocked");
                menu.refresh();
            });
            menu.set(46, Btn.of(Items.LAVA_BUCKET).name(cat, Msg.tr("tr.stock-clear")).desc(Msg.tr("tr.stock-clear-desc"))
                    .shift(Msg.tr("panel.action.do")).build(), (pl, c) -> {
                if (!c.isShift()) {
                    Msg.warn(pl, "cm.delete-shift");
                    return;
                }
                stockClear(pl, t);
                menu.refresh();
            });
        });
        m.open(admin);
    }

    private static ItemStack stockIcon(Trader t, StockRow r) {
        if (r.offer() == null) {
            ItemStack s = stackFor(blockedOffer(r.id()));
            return Btn.of(s).color(Theme.RED).name(s.getName().getString())
                    .status(Theme.RED, Theme.Sym.CROSS.sp() + Msg.tr("tr.stock-never"))
                    .left(Msg.tr("tr.stock-allow")).build();
        }
        TraderOffer o = r.offer();
        ItemStack s = stackFor(o);
        boolean pinned = t.pinned.containsKey(o.id);
        Btn b = Btn.of(s).color(rarityColor(o.rarity)).name(s.getName().getString())
                .status(rarityColor(o.rarity), Theme.Sym.DOT.sp() + Msg.tr("tr.rarity." + o.rarity.name().toLowerCase(java.util.Locale.ROOT)))
                .line(Msg.tr("tr.stock", o.stock + "/" + o.maxStock));
        if (pinned) {
            b.status(Theme.GOLD_LIGHT, Theme.Sym.ARROW.sp() + Msg.tr("tr.stock-always")).glint(true);
        }
        b.left(Msg.tr(pinned ? "tr.stock-change-amount" : "tr.stock-keep")).right(Msg.tr("tr.stock-take-off"));
        if (pinned) {
            b.shift(Msg.tr("tr.stock-unkeep"));
        }
        return b.build();
    }

    private static TraderOffer blockedOffer(String id) {
        TraderOffer o = new TraderOffer();
        o.id = id;
        o.rarity = TraderEconomy.rarityOf(id);
        return o;
    }

    /** Asks how many of an item to keep in stock, then keeps it always on the trader. */
    private static void askAmount(ServerPlayerEntity admin, Trader t, String id, int current) {
        Input.text(admin, Msg.trFor(admin, "tr.stock-amount"), String.valueOf(current), txt -> {
            if (txt != null) {
                try {
                    int n = Integer.parseInt(txt.trim());
                    if (n >= 1 && n <= 1000) {
                        stockAdd(admin, t, id, n);
                    } else {
                        Msg.send(admin, "general.bad-number");
                    }
                } catch (NumberFormatException e) {
                    Msg.send(admin, "general.bad-number");
                }
            }
            openStock(admin, t);
        });
    }

    public static void confirmRemove(ServerPlayerEntity admin, Trader t) {
        Confirm.open(admin, Theme.Category.TRADERS, Msg.tr("panel.confirm.delete-trader", t.name), Msg.tr("panel.confirm.delete-trader-detail"),
                Btn.of(Items.EMERALD).name(Theme.Category.TRADERS, t.name).build(), () -> {
                    remove(admin, t);
                    admin.closeHandledScreen();
                    Msg.send(admin, "trader.removed");
                });
    }

    /** Right-click with the Trader Stick. @return true if handled. */
    public static boolean stickOnBlock(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        if (!Perms.require(p, Perm.TRADER_ADMIN)) {
            return true;
        }
        PlayerSession s = Ac.session(p);
        if (s.traderMove != null) {
            Trader t = all().get(s.traderMove);
            s.traderMove = null;
            if (t != null) {
                move(p, t, w, pos);
                Msg.send(p, "trader.moved");
            }
            return true;
        }
        Trader t = create(p, w, pos, p.getYaw());
        if (t != null) {
            openEdit(p, t);
        }
        return true;
    }

    public static boolean stickOnEntity(ServerPlayerEntity p, Entity e) {
        Trader t = of(e);
        if (t == null) {
            return false;
        }
        if (!Perms.require(p, Perm.TRADER_ADMIN)) {
            return true;
        }
        if (p.isSneaking()) {
            confirmRemove(p, t);
        } else {
            openEdit(p, t);
        }
        return true;
    }

    public static void onDisconnect(ServerPlayerEntity p) {
        // Closing the screen on disconnect runs the menu's close handler, which returns the payment.
        if (NEGOTIATING.contains(p.getUuid()) && p.currentScreenHandler != p.playerScreenHandler) {
            p.closeHandledScreen();
        }
    }
}
