package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.market.Market;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.gui.Confirm;
import com.vylorq.anticheat.gui.Input;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Player shops (/shop): a stall in the lobby that sells an item to other players or buys it from them, for cash,
 * emeralds or any item the owner picks. The stall is a floating item with a name; clicking it opens the shop.
 */
public final class Shops {
    public static final String TAG = "vigil_shop";
    private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();

    private Shops() {
    }

    static Market market() {
        return Ac.get().market;
    }

    public static boolean enabled() {
        return Markets.enabled() && Ac.config().market.shops;
    }

    public static boolean isShop(Entity e) {
        return e instanceof ArmorStandEntity && e.getCommandTags().contains(TAG);
    }

    // ---------------------------------------------------------------- payment

    /** "cash", "emeralds" or an item id -> stored form ("cash" or an item id), or null if unknown. */
    static String parsePay(String arg) {
        String a = arg.toLowerCase(java.util.Locale.ROOT);
        if (a.equals("cash") || a.equals("money")) {
            return "cash";
        }
        if (a.equals("emeralds") || a.equals("emerald")) {
            return "minecraft:emerald";
        }
        Identifier id = Identifier.tryParse(a.contains(":") ? a : "minecraft:" + a);
        if (id == null || Registries.ITEM.get(id) == Items.AIR) {
            return null;
        }
        return id.toString();
    }

    static Item payItem(Market.Shop x) {
        return Registries.ITEM.get(Identifier.of(x.pay));
    }

    public static String priceText(Market.Shop x) {
        if ("cash".equals(x.pay)) {
            return Markets.cash(x.price);
        }
        return x.price + "x " + new ItemStack(payItem(x)).getName().getString();
    }

    static long buyerHas(ServerPlayerEntity p, Market.Shop x) {
        return "cash".equals(x.pay) ? market().balance(p.getUuid()) : Markets.countItem(p, payItem(x));
    }

    static ItemStack template(Market.Shop x) {
        ItemStack s = ItemConv.decode(x.item);
        return s.isEmpty() ? new ItemStack(Items.BARRIER) : s;
    }

    // ---------------------------------------------------------------- create / remove

    public static void create(ServerPlayerEntity p, boolean selling, int price, String payArg, Integer perSale) {
        if (!enabled()) {
            Msg.send(p, "market.disabled");
            return;
        }
        ItemStack held = p.getMainHandStack();
        if (held.isEmpty()) {
            Msg.send(p, "market.hold");
            return;
        }
        String pay = parsePay(payArg);
        if (pay == null) {
            Msg.send(p, "shop.bad-pay", payArg);
            return;
        }
        if (price < 1) {
            Msg.send(p, "market.bad-price");
            return;
        }
        var lobby = Ac.get().lobby;
        String world = Mc.worldId(p.getEntityWorld());
        if (Ac.config().market.shopsOnlyInLobby && (!lobby.isSet() || !lobby.inLobby(world, p.getX(), p.getY(), p.getZ()))) {
            Msg.send(p, "shop.lobby-only");
            return;
        }
        if (market().shopsBy(p.getUuid()) >= Ac.config().market.maxShopsPerPlayer) {
            Msg.send(p, "shop.too-many", Ac.config().market.maxShopsPerPlayer);
            return;
        }
        for (Market.Shop o : market().shops()) {
            if (o.world.equals(world) && Math.abs(o.x - p.getX()) < 1.5 && Math.abs(o.z - p.getZ()) < 1.5 && Math.abs(o.y - p.getY()) < 2) {
                Msg.send(p, "shop.too-close");
                return;
            }
        }
        var info = ItemConv.info(held);
        if (com.vylorq.anticheat.core.trader.OfferEvaluator.forbiddenPayment(info)) {
            Msg.send(p, "market.no-containers");
            return;
        }
        String illegal = com.vylorq.anticheat.core.items.IllegalItems.check(info, Ac.config().illegalItems.bannedItems,
                com.vylorq.anticheat.core.trader.Enchants.MAX_LEVELS);
        if (illegal != null) {
            Illegal.handle(p, held, illegal);
            Msg.send(p, "market.illegal");
            return;
        }
        int bundle = Math.max(1, Math.min(held.getMaxCount(), perSale == null ? held.getCount() : perSale));
        Market.Shop x = new Market.Shop();
        x.owner = p.getUuid();
        x.ownerName = p.getGameProfile().name();
        x.world = world;
        BlockPos b = p.getBlockPos();
        x.x = b.getX() + 0.5;
        x.y = b.getY();
        x.z = b.getZ() + 0.5;
        x.yaw = p.getYaw() + 180f;
        x.selling = selling;
        x.pay = pay;
        x.price = price;
        x.bundle = bundle;
        ItemStack t;
        if (selling) {
            t = held.copyWithCount(bundle);
            // Everything in hand goes in as the first stock.
            x.stock = held.getCount();
            p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
        } else {
            // Buying shops take plain items only (no enchantments or names), so anyone's count the same.
            t = new ItemStack(held.getItem(), bundle);
        }
        x.item = ItemConv.encode(t);
        x.itemName = t.getName().getString();
        market().addShop(x);
        spawn(x);
        Ac.markDirty("market");
        Mc.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 1.4f);
        Msg.send(p, selling ? "shop.created-sell" : "shop.created-buy", bundle + "x " + x.itemName, priceText(x));
        if (!selling && !"cash".equals(pay)) {
            Msg.send(p, "shop.add-funds-hint");
        }
        Ac.get().logs.trade(System.currentTimeMillis(), "shop-create", p.getUuid(), x.ownerName, null, null,
                (selling ? "sells " : "buys ") + bundle + "x " + x.itemName + " for " + priceText(x));
    }

    /** Takes the shop down; its stock and funds go back to the owner (now, or via /collect). */
    public static void remove(Market.Shop x, ServerPlayerEntity by) {
        market().removeShop(x.id);
        despawn(x);
        ServerPlayerEntity owner = Ac.server().getPlayerManager().getPlayer(x.owner);
        List<ItemStack> back = new ArrayList<>();
        ItemStack t = template(x);
        int left = x.stock;
        while (x.selling && left > 0) {
            int n = Math.min(left, t.getMaxCount());
            back.add(t.copyWithCount(n));
            left -= n;
        }
        if (owner != null) {
            Trades.giveBack(owner, back);
            if (!x.selling && x.funds > 0) {
                Markets.giveItem(owner, payItem(x), x.funds);
            }
        } else {
            for (ItemStack s : back) {
                market().owe(x.owner, ItemConv.encode(s));
            }
            if (!x.selling && x.funds > 0) {
                market().oweItem(x.owner, x.pay, x.funds);
            }
        }
        Ac.markDirty("market");
        if (by != null) {
            Msg.send(by, "shop.removed", x.itemName);
            Staff.log(by, "shop-remove", x.owner, x.ownerName, x.itemName);
        }
    }

    public static void removeNearest(ServerPlayerEntity p) {
        Market.Shop best = null;
        double bestD = 16;
        String w = Mc.worldId(p.getEntityWorld());
        boolean admin = Perms.has(p, Perm.TRADER_ADMIN);
        for (Market.Shop x : market().shops()) {
            if (!x.world.equals(w) || (!admin && !x.owner.equals(p.getUuid()))) {
                continue;
            }
            double d = p.squaredDistanceTo(x.x, x.y, x.z);
            if (d < bestD) {
                bestD = d;
                best = x;
            }
        }
        if (best == null) {
            Msg.send(p, "shop.none-near");
            return;
        }
        remove(best, p);
    }

    // ---------------------------------------------------------------- display

    static String label(Market.Shop x) {
        String head = x.selling ? "§a§l" + Msg.tr("shop.selling") : "§b§l" + Msg.tr("shop.buying");
        String tail = "";
        if (x.selling && x.stock < x.bundle) {
            tail = " §c(" + Msg.tr("shop.out-of-stock") + ")";
        } else if (!x.selling && !"cash".equals(x.pay) && x.funds < x.price) {
            tail = " §c(" + Msg.tr("shop.no-funds") + ")";
        }
        return head + " §f" + x.bundle + "x " + x.itemName + " §7» §e" + priceText(x) + tail;
    }

    static void spawn(Market.Shop x) {
        ServerWorld w = Mc.world(Ac.server(), x.world);
        if (w == null) {
            return;
        }
        ArmorStandEntity a = new ArmorStandEntity(w, x.x, x.y, x.z);
        a.setYaw(x.yaw);
        a.setInvisible(true);
        a.setNoGravity(true);
        a.setInvulnerable(true);
        a.setSilent(true);
        a.addCommandTag(TAG);
        a.equipStack(EquipmentSlot.HEAD, template(x).copyWithCount(1));
        a.setCustomName(Text.literal(label(x)));
        a.setCustomNameVisible(true);
        w.spawnEntity(a);
        x.entity = a.getUuid();
    }

    static void despawn(Market.Shop x) {
        ServerWorld w = Mc.world(Ac.server(), x.world);
        if (w != null && x.entity != null) {
            Entity e = w.getEntity(x.entity);
            if (e != null) {
                e.discard();
            }
        }
    }

    static void relabel(Market.Shop x) {
        ServerWorld w = Mc.world(Ac.server(), x.world);
        if (w != null && x.entity != null && w.getEntity(x.entity) instanceof ArmorStandEntity a) {
            a.setCustomName(Text.literal(label(x)));
        }
    }

    private static int timer;

    /** Every 10 seconds: put back stalls that went missing (only where the chunk is loaded). */
    public static void tick() {
        if (!enabled() || ++timer % 10 != 0) {
            return;
        }
        for (Market.Shop x : market().shops()) {
            ServerWorld w = Mc.world(Ac.server(), x.world);
            if (w == null || !w.isChunkLoaded(BlockPos.ofFloored(x.x, x.y, x.z))) {
                continue;
            }
            Entity e = x.entity == null ? null : w.getEntity(x.entity);
            if (e == null) {
                spawn(x);
                Ac.markDirty("market");
            } else if (e.squaredDistanceTo(x.x, x.y, x.z) > 0.01) {
                e.refreshPositionAndAngles(x.x, x.y, x.z, x.yaw, 0);
            }
        }
    }

    // ---------------------------------------------------------------- using a shop

    /** Right-click on a stall. */
    public static void click(ServerPlayerEntity p, Entity e) {
        long now = System.currentTimeMillis();
        Long last = LAST_CLICK.put(p.getUuid(), now);
        if (last != null && now - last < 300) {
            return;
        }
        Market.Shop x = market().shopByEntity(e.getUuid());
        if (x == null) {
            // A stall without a shop (left over): tidy it up.
            e.discard();
            return;
        }
        if (x.owner.equals(p.getUuid())) {
            manage(p, x);
        } else {
            open(p, x);
        }
    }

    public static void open(ServerPlayerEntity p, Market.Shop x) {
        Menu m = Menu.std(Theme.Category.PLAYER, 3, Msg.trFor(p, "shop.title", x.ownerName));
        m.renderer(menu -> {
            ItemStack t = template(x);
            Btn info = Btn.of(t).color(Theme.GOLD_LIGHT).name(x.bundle + "x " + x.itemName)
                    .line(Msg.tr("shop.owner", x.ownerName))
                    .line(Msg.tr("shop.price", priceText(x)))
                    .line(Msg.tr("shop.worth", Traders.fmt(Traders.stackValue(t))));
            if (x.selling) {
                info.line(Msg.tr("shop.stock", x.stock / x.bundle));
            }
            menu.set(11, info.amount(x.bundle).build(), null, null);
            String have = "cash".equals(x.pay) ? Markets.cash(buyerHas(p, x)) : buyerHas(p, x) + "x " + new ItemStack(payItem(x)).getName().getString();
            menu.set(13, Btn.of("cash".equals(x.pay) ? Items.GOLD_INGOT : payItem(x)).color(Theme.GOLD_LIGHT)
                    .name(Msg.tr("tr.you-have", have)).build(), null, null);
            if (x.selling) {
                boolean can = x.stock >= x.bundle && buyerHas(p, x) >= x.price;
                menu.set(15, Btn.of(can ? Items.LIME_CONCRETE : Items.RED_CONCRETE).color(can ? Theme.GREEN : Theme.RED)
                        .name(Msg.tr("shop.buy", x.bundle + "x " + x.itemName, priceText(x)))
                        .left(Msg.tr("shop.once")).shift(Msg.tr("shop.five")).glint(can).build(), null, (pl, c) -> {
                    int times = c.isShift() ? 5 : 1;
                    for (int i = 0; i < times && buy(pl, x); i++) {
                        // keep buying
                    }
                    menu.refresh();
                });
            } else {
                Item want = template(x).getItem();
                int got = Markets.countItem(p, want);
                boolean can = got >= x.bundle;
                menu.set(15, Btn.of(can ? Items.LIME_CONCRETE : Items.RED_CONCRETE).color(can ? Theme.GREEN : Theme.RED)
                        .name(Msg.tr("shop.sell", x.bundle + "x " + x.itemName, priceText(x)))
                        .line(Msg.tr("orders.you-have", got))
                        .left(Msg.tr("shop.once")).shift(Msg.tr("shop.five")).glint(can).build(), null, (pl, c) -> {
                    int times = c.isShift() ? 5 : 1;
                    for (int i = 0; i < times && sell(pl, x); i++) {
                        // keep selling
                    }
                    menu.refresh();
                });
            }
        });
        m.open(p);
    }

    /** Buys one bundle from a selling shop. @return true on success */
    static boolean buy(ServerPlayerEntity p, Market.Shop x) {
        if (x.stock < x.bundle) {
            Msg.send(p, "shop.out-of-stock-msg");
            return false;
        }
        boolean paid = "cash".equals(x.pay) ? market().takeCash(p.getUuid(), x.price) : Markets.takeItem(p, payItem(x), x.price);
        if (!paid) {
            Msg.send(p, "market.no-money", priceText(x));
            return false;
        }
        Market.Result r = market().shopSale(x);
        if (r != Market.Result.OK) {
            if ("cash".equals(x.pay)) {
                market().addCash(p.getUuid(), x.price);
            } else {
                Markets.giveItem(p, payItem(x), x.price);
            }
            Msg.send(p, "market.r." + r.name().toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        Trades.giveBack(p, List.of(template(x).copyWithCount(x.bundle)));
        Ac.markDirty("market");
        relabel(x);
        Mc.sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Msg.send(p, "shop.bought", x.bundle + "x " + x.itemName, priceText(x));
        ServerPlayerEntity owner = Ac.server().getPlayerManager().getPlayer(x.owner);
        if (owner != null) {
            Msg.send(owner, "shop.sold-to", p.getGameProfile().name(), x.bundle + "x " + x.itemName, priceText(x));
        }
        Teams.xpForTrade(p);
        Ac.get().logs.trade(System.currentTimeMillis(), "shop-buy", p.getUuid(), p.getGameProfile().name(), x.owner, x.ownerName,
                x.bundle + "x " + x.itemName + " for " + priceText(x));
        return true;
    }

    /** Sells one bundle to a buying shop. @return true on success */
    static boolean sell(ServerPlayerEntity p, Market.Shop x) {
        Item want = template(x).getItem();
        if (Markets.countItem(p, want) < x.bundle) {
            Msg.send(p, "orders.none-to-give", x.itemName);
            return false;
        }
        Market.Result r = market().shopPurchase(x, Registries.ITEM.getId(want).toString());
        if (r != Market.Result.OK) {
            Msg.send(p, r == Market.Result.NOT_ENOUGH ? "shop.owner-broke" : "market.r." + r.name().toLowerCase(java.util.Locale.ROOT));
            return false;
        }
        Markets.takeItem(p, want, x.bundle);
        if ("cash".equals(x.pay)) {
            market().addCash(p.getUuid(), x.price);
        } else {
            Markets.giveItem(p, payItem(x), x.price);
        }
        Ac.markDirty("market");
        relabel(x);
        Mc.sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Msg.send(p, "shop.you-sold", x.bundle + "x " + x.itemName, priceText(x));
        ServerPlayerEntity owner = Ac.server().getPlayerManager().getPlayer(x.owner);
        if (owner != null) {
            Msg.send(owner, "shop.bought-from", p.getGameProfile().name(), x.bundle + "x " + x.itemName);
        }
        Teams.xpForTrade(p);
        Ac.get().logs.trade(System.currentTimeMillis(), "shop-sell", p.getUuid(), p.getGameProfile().name(), x.owner, x.ownerName,
                x.bundle + "x " + x.itemName + " for " + priceText(x));
        return true;
    }

    /** The owner's view: stock, funds, price, remove. */
    public static void manage(ServerPlayerEntity p, Market.Shop x) {
        Menu m = Menu.std(Theme.Category.PLAYER, 3, Msg.trFor(p, "shop.yours"));
        m.renderer(menu -> {
            ItemStack t = template(x);
            Btn info = Btn.of(t).color(Theme.GOLD_LIGHT).name(Text.literal(label(x)).getString())
                    .line(Msg.tr("shop.sales", x.sales));
            if (x.selling) {
                info.line(Msg.tr("shop.stock-items", x.stock));
            } else if (!"cash".equals(x.pay)) {
                info.line(Msg.tr("shop.funds", x.funds + "x " + new ItemStack(payItem(x)).getName().getString()));
            } else {
                info.line(Msg.tr("shop.cash-from-wallet"));
            }
            menu.set(10, info.amount(x.bundle).build(), null, null);
            if (x.selling) {
                menu.set(12, Btn.of(Items.CHEST).color(Theme.GREEN).name(Msg.tr("shop.add-stock")).desc(Msg.tr("shop.add-stock-desc")).build(), null, (pl, c) -> {
                    int added = 0;
                    var inv = pl.getInventory();
                    for (int i = 0; i < inv.size(); i++) {
                        ItemStack s = inv.getStack(i);
                        if (!s.isEmpty() && ItemStack.areItemsAndComponentsEqual(s, t)) {
                            added += s.getCount();
                            inv.setStack(i, ItemStack.EMPTY);
                        }
                    }
                    x.stock += added;
                    Ac.markDirty("market");
                    relabel(x);
                    Msg.send(pl, "shop.stock-added", added);
                    menu.refresh();
                });
                menu.set(13, Btn.of(Items.HOPPER).color(Theme.GOLD_LIGHT).name(Msg.tr("shop.take-stock")).build(), null, (pl, c) -> {
                    List<ItemStack> back = new ArrayList<>();
                    int left = x.stock;
                    while (left > 0) {
                        int n = Math.min(left, t.getMaxCount());
                        back.add(t.copyWithCount(n));
                        left -= n;
                    }
                    x.stock = 0;
                    Trades.giveBack(pl, back);
                    Ac.markDirty("market");
                    relabel(x);
                    menu.refresh();
                });
            } else if (!"cash".equals(x.pay)) {
                Item pi = payItem(x);
                menu.set(12, Btn.of(pi).color(Theme.GREEN).name(Msg.tr("shop.add-funds")).desc(Msg.tr("shop.add-funds-desc")).build(), null, (pl, c) -> {
                    int n = Markets.countItem(pl, pi);
                    if (n > 0 && Markets.takeItem(pl, pi, n)) {
                        x.funds += n;
                        Ac.markDirty("market");
                        relabel(x);
                    }
                    menu.refresh();
                });
                menu.set(13, Btn.of(Items.HOPPER).color(Theme.GOLD_LIGHT).name(Msg.tr("shop.take-funds")).build(), null, (pl, c) -> {
                    if (x.funds > 0) {
                        Markets.giveItem(pl, pi, x.funds);
                        x.funds = 0;
                        Ac.markDirty("market");
                        relabel(x);
                    }
                    menu.refresh();
                });
            }
            menu.set(14, Btn.of(Items.NAME_TAG).color(Theme.GOLD_LIGHT).name(Msg.tr("shop.change-price")).line(Msg.tr("shop.price", priceText(x)))
                    .build(), null, (pl, c) -> Input.text(pl, Msg.trFor(pl, "shop.change-price"), String.valueOf(x.price), txt -> {
                try {
                    int v = Integer.parseInt(txt.trim());
                    if (v >= 1) {
                        x.price = v;
                        Ac.markDirty("market");
                        relabel(x);
                    }
                } catch (NumberFormatException | NullPointerException ignored) {
                    Msg.send(pl, "market.bad-price");
                }
                manage(pl, x);
            }));
            menu.set(15, Btn.of(Items.ENDER_EYE).color(Theme.GOLD_LIGHT).name(Msg.tr("shop.preview")).desc(Msg.tr("shop.preview-desc")).build(), null,
                    (pl, c) -> open(pl, x));
            menu.set(16, Btn.of(Items.TNT).color(Theme.RED).name(Msg.tr("shop.remove")).desc(Msg.tr("shop.remove-desc")).build(), null,
                    (pl, c) -> Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "shop.remove"), x.itemName, t.copy(), () -> {
                        remove(x, pl);
                        pl.closeHandledScreen();
                    }));
        });
        m.open(p);
    }

    /** /shops: every shop, with where it is. */
    public static void list(ServerPlayerEntity p, boolean mine) {
        Msg.send(p, mine ? "shop.list-mine" : "shop.list-all");
        int n = 0;
        for (Market.Shop x : market().shops()) {
            if (mine && !x.owner.equals(p.getUuid())) {
                continue;
            }
            n++;
            p.sendMessage(Text.literal(label(x) + " §8- §7" + x.ownerName + " §8@ §7" + (int) x.x + " " + (int) x.y + " " + (int) x.z));
        }
        if (n == 0) {
            Msg.send(p, "shop.none");
        }
    }
}
