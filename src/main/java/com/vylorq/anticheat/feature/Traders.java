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
            if (minute && now >= t.nextRotation) {
                rotate(t);
            }
        }
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

    private static ItemStack offerIcon(TraderOffer o, boolean clickable) {
        ItemStack s = stackFor(o);
        if (o.soldOut()) {
            return Btn.of(Items.GRAY_STAINED_GLASS_PANE).color(Theme.SOFT).name(s.getName().getString())
                    .status(Theme.RED, Theme.Sym.CROSS.sp() + Msg.tr("tr.sold-out")).desc(Msg.tr("tr.restock")).build();
        }
        Btn b = Btn.of(s).color(rarityColor(o.rarity)).name(s.getName().getString())
                .status(rarityColor(o.rarity), Theme.Sym.DOT.sp() + Msg.tr("tr.rarity." + o.rarity.name().toLowerCase(java.util.Locale.ROOT)))
                .line(Msg.tr("tr.stock", o.stock));
        if (clickable) {
            b.left(Msg.tr("tr.action.offer"));
        }
        return b.amount(s.getCount()).build();
    }

    private static ItemStack mysteryIcon(TraderOffer o, boolean clickable) {
        Btn b = Btn.of(Items.CHEST).color(Theme.VIOLET).name(Msg.tr("tr.mystery")).desc(Msg.tr("tr.mystery-desc")).line(Msg.tr("tr.stock", o.stock));
        if (clickable) {
            b.left(Msg.tr("tr.action.offer"));
        }
        return b.build();
    }

    // ---- Player interaction ----

    public static void open(ServerPlayerEntity p, Trader t) {
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
                    .line(Msg.tr("tr.next-rotation", Durations.format(Math.max(0, t.nextRotation - System.currentTimeMillis())))).build());
            menu.list(t.offers, o -> t.type == Trader.Type.MYSTERY ? mysteryIcon(o, true) : offerIcon(o, true),
                    o -> (pl, c) -> {
                        if (o.soldOut()) {
                            Msg.send(pl, "trader.sold-out");
                            return;
                        }
                        openNegotiation(pl, t, o);
                    },
                    null, List.of(), Msg.tr("tr.no-offers"), Msg.tr("tr.restock"));
        });
        m.open(p);
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
        m.editable(pay, (pl, slot) -> saveEscrow(pl, m));
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
            menu.icon(20, t.type == Trader.Type.MYSTERY ? mysteryIcon(o, false) : offerIcon(o, false));
            menu.icon(4, Btn.of(Items.OAK_SIGN).color(Theme.GOLD_LIGHT).name(Msg.tr("tr.how")).desc(Msg.tr("tr.how-desc")).build());
            Btn button;
            if (verdict[0] == null) {
                button = Btn.of(Items.EMERALD).color(Theme.GREEN).name(Msg.tr("tr.offer")).desc(Msg.tr("tr.offer-desc"));
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
            PlayerSessionFlags.flag(p, CheckType.TRADE_MACRO, 0.5, "trade rate limit");
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
        double demand = ac.economy.demand(o.signature(), st);
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
        Rarity mysteryRarity = null;
        ItemStack goods;
        if (t.type == Trader.Type.MYSTERY) {
            mysteryRarity = TraderEconomy.rollMystery(RANDOM);
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
        String paid = Trades.describe(payStacks);
        for (ItemInfo i : infos) {
            ac.economy.flow(i.id, i.count, 0);
        }
        clearPayment(m);
        ac.setEscrow(p.getUuid(), "trader", List.of());
        p.getInventory().insertStack(goods.copy());
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
