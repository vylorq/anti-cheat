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
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lobby booths (/booth): admins place booth spots, a player claims one and lists items (each with its own price
 * and payment). Visitors buy straight away or make an offer; the seller doesn't need to be there. Offers are paid
 * up front and held, so accepting works even when the buyer is offline. Sellers get every offer and sale on screen.
 */
public final class Booths {
    public static final String TAG = "vigil_booth";
    private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();

    private Booths() {
    }

    static Market market() {
        return Ac.get().market;
    }

    public static boolean enabled() {
        return Markets.enabled() && Ac.config().market.booths;
    }

    public static boolean isBooth(Entity e) {
        return e instanceof ArmorStandEntity && e.getCommandTags().contains(TAG);
    }

    // ---------------------------------------------------------------- money helpers

    static Item payItem(String pay) {
        return Registries.ITEM.get(Identifier.of(pay));
    }

    public static String priceText(String pay, int amount) {
        return "cash".equals(pay) ? Markets.cash(amount) : amount + "x " + new ItemStack(payItem(pay)).getName().getString();
    }

    static long has(ServerPlayerEntity p, String pay) {
        return "cash".equals(pay) ? market().balance(p.getUuid()) : Markets.countItem(p, payItem(pay));
    }

    static boolean take(ServerPlayerEntity p, String pay, int amount) {
        if ("cash".equals(pay)) {
            boolean ok = market().takeCash(p.getUuid(), amount);
            Ac.markDirty("market");
            return ok;
        }
        return Markets.takeItem(p, payItem(pay), amount);
    }

    static void giveBack(ServerPlayerEntity p, String pay, int amount) {
        if ("cash".equals(pay)) {
            market().addCash(p.getUuid(), amount);
        } else {
            Markets.giveItem(p, payItem(pay), amount);
        }
        Ac.markDirty("market");
    }

    private static void tellRefunded(List<Market.BoothOffer> refunded, String why) {
        for (Market.BoothOffer o : refunded) {
            Markets.alert(o.buyer, "booth.offer-title", why, o.itemName, priceText(o.pay, o.amount));
        }
    }

    // ---------------------------------------------------------------- admin: booth spots

    public static void create(ServerPlayerEntity admin) {
        BlockPos b = admin.getBlockPos();
        String w = Mc.worldId(admin.getEntityWorld());
        for (Market.Booth o : market().booths()) {
            if (o.world.equals(w) && admin.squaredDistanceTo(o.x, o.y, o.z) < 4) {
                Msg.send(admin, "booth.too-close");
                return;
            }
        }
        Market.Booth booth = market().addBooth(w, b.getX() + 0.5, b.getY(), b.getZ() + 0.5, admin.getYaw() + 180f);
        spawn(booth);
        Ac.markDirty("market");
        Msg.send(admin, "booth.created", booth.id);
        Staff.log(admin, "booth-create", null, "#" + booth.id, b.toShortString());
    }

    static Market.Booth nearest(ServerPlayerEntity p, double maxDist, boolean free, boolean mine) {
        Market.Booth best = null;
        double bestD = maxDist * maxDist;
        String w = Mc.worldId(p.getEntityWorld());
        for (Market.Booth b : market().booths()) {
            if (!b.world.equals(w) || (free && b.owner != null) || (mine && !p.getUuid().equals(b.owner))) {
                continue;
            }
            double d = p.squaredDistanceTo(b.x, b.y, b.z);
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    public static void delete(ServerPlayerEntity admin) {
        Market.Booth b = nearest(admin, 5, false, false);
        if (b == null) {
            Msg.send(admin, "booth.none-near");
            return;
        }
        UUID owner = b.owner;
        tellRefunded(market().deleteBooth(b.id), "booth.offer-refunded");
        despawn(b);
        if (owner != null) {
            Markets.alert(owner, "booth.title-short", "booth.removed-by-staff");
        }
        Ac.markDirty("market");
        Msg.send(admin, "booth.deleted", b.id);
        Staff.log(admin, "booth-delete", owner, "#" + b.id, "");
    }

    // ---------------------------------------------------------------- claim / leave

    public static void claim(ServerPlayerEntity p, Market.Booth b) {
        if (!enabled()) {
            Msg.send(p, "market.disabled");
            return;
        }
        Market.Result r = market().claimBooth(b.id, p.getUuid(), p.getGameProfile().name());
        if (r != Market.Result.OK) {
            Msg.send(p, "booth.r." + r.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        Ac.markDirty("market");
        relabel(b);
        Mc.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        Msg.send(p, "booth.claimed");
        manage(p, b);
    }

    public static void claimNearest(ServerPlayerEntity p) {
        Market.Booth b = nearest(p, 5, true, false);
        if (b == null) {
            Msg.send(p, "booth.no-free-near");
            return;
        }
        claim(p, b);
    }

    public static void leave(ServerPlayerEntity p) {
        Market.Booth b = market().boothOf(p.getUuid());
        if (b == null) {
            Msg.send(p, "booth.none");
            return;
        }
        tellRefunded(market().freeBooth(b.id), "booth.offer-refunded");
        Ac.markDirty("market");
        relabel(b);
        Markets.collect(p);
        Msg.send(p, "booth.left");
    }

    // ---------------------------------------------------------------- listing

    /** Lists the item in hand for a price (from anywhere: you don't need to be at your booth). */
    public static void add(ServerPlayerEntity p, int price, String payArg) {
        Market.Booth b = market().boothOf(p.getUuid());
        if (b == null) {
            Msg.send(p, "booth.none");
            return;
        }
        ItemStack held = p.getMainHandStack();
        if (held.isEmpty()) {
            Msg.send(p, "market.hold");
            return;
        }
        String pay = Shops.parsePay(payArg);
        if (pay == null) {
            Msg.send(p, "shop.bad-pay", payArg);
            return;
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
        ItemStack s = held.copy();
        String name = s.getCount() + "x " + s.getName().getString();
        Market.Result r = market().list(b.id, p.getUuid(), ItemConv.encode(s), name, price, pay, Ac.config().market.boothSlots);
        if (r != Market.Result.OK) {
            Msg.send(p, "booth.r." + r.name().toLowerCase(java.util.Locale.ROOT), Ac.config().market.boothSlots);
            return;
        }
        p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
        Ac.markDirty("market");
        relabel(b);
        Msg.send(p, "booth.listed", name, priceText(pay, price));
        Ac.get().logs.trade(System.currentTimeMillis(), "booth-list", p.getUuid(), p.getGameProfile().name(), null, null,
                name + " for " + priceText(pay, price));
    }

    // ---------------------------------------------------------------- display

    static String label(Market.Booth b) {
        if (b.owner == null) {
            return "§a§l" + Msg.tr("booth.for-rent") + " §7" + Msg.tr("booth.click-claim");
        }
        return "§6§l" + Msg.tr("booth.of", b.ownerName) + " §7(" + Msg.tr("booth.items", b.listings.size()) + ")";
    }

    static ItemStack headItem(Market.Booth b) {
        if (b.owner == null) {
            return new ItemStack(Items.OAK_SIGN);
        }
        if (b.listings.isEmpty()) {
            return new ItemStack(Items.CHEST);
        }
        ItemStack s = ItemConv.decode(b.listings.get(0).item);
        return s.isEmpty() ? new ItemStack(Items.CHEST) : s.copyWithCount(1);
    }

    static void spawn(Market.Booth b) {
        ServerWorld w = Mc.world(Ac.server(), b.world);
        if (w == null) {
            return;
        }
        ArmorStandEntity a = new ArmorStandEntity(w, b.x, b.y, b.z);
        a.setYaw(b.yaw);
        a.setInvisible(true);
        a.setNoGravity(true);
        a.setInvulnerable(true);
        a.setSilent(true);
        a.addCommandTag(TAG);
        a.equipStack(EquipmentSlot.HEAD, headItem(b));
        a.setCustomName(Text.literal(label(b)));
        a.setCustomNameVisible(true);
        w.spawnEntity(a);
        b.entity = a.getUuid();
    }

    static void despawn(Market.Booth b) {
        ServerWorld w = Mc.world(Ac.server(), b.world);
        if (w != null && b.entity != null) {
            Entity e = w.getEntity(b.entity);
            if (e != null) {
                e.discard();
            }
        }
    }

    static void relabel(Market.Booth b) {
        ServerWorld w = Mc.world(Ac.server(), b.world);
        if (w != null && b.entity != null && w.getEntity(b.entity) instanceof ArmorStandEntity a) {
            a.setCustomName(Text.literal(label(b)));
            a.equipStack(EquipmentSlot.HEAD, headItem(b));
        }
    }

    private static int timer;

    /** Every 10 seconds: restore missing booth displays; every minute: refund offers nobody answered. */
    public static void tick() {
        if (!enabled()) {
            return;
        }
        timer++;
        if (timer % 60 == 0) {
            List<Market.BoothOffer> old = market().expireOffers(Ac.config().market.offerHours * 3_600_000L);
            if (!old.isEmpty()) {
                Ac.markDirty("market");
                tellRefunded(old, "booth.offer-expired");
            }
        }
        if (timer % 10 != 0) {
            return;
        }
        for (Market.Booth b : market().booths()) {
            ServerWorld w = Mc.world(Ac.server(), b.world);
            if (w == null || !w.isChunkLoaded(BlockPos.ofFloored(b.x, b.y, b.z))) {
                continue;
            }
            Entity e = b.entity == null ? null : w.getEntity(b.entity);
            if (e == null) {
                spawn(b);
                Ac.markDirty("market");
            } else if (e.squaredDistanceTo(b.x, b.y, b.z) > 0.01) {
                e.refreshPositionAndAngles(b.x, b.y, b.z, b.yaw, 0);
            }
        }
    }

    public static void onJoin(ServerPlayerEntity p) {
        if (!enabled()) {
            return;
        }
        Market.Booth b = market().boothOf(p.getUuid());
        if (b != null) {
            int n = market().offersFor(b.id).size();
            if (n > 0) {
                MutableText m = Msg.prefixed(Msg.trFor(p, "booth.offers-waiting", n));
                m.append(" ").append(Msg.button("§e[" + Msg.trFor(p, "booth.see-offers") + "]", "/booth offers", ""));
                p.sendMessage(m);
            }
        }
    }

    // ---------------------------------------------------------------- clicking a booth

    public static void click(ServerPlayerEntity p, Entity e) {
        long now = System.currentTimeMillis();
        Long last = LAST_CLICK.put(p.getUuid(), now);
        if (last != null && now - last < 300) {
            return;
        }
        Market.Booth b = market().boothByEntity(e.getUuid());
        if (b == null) {
            e.discard();
            return;
        }
        if (b.owner == null) {
            Confirm.open(p, Theme.Category.PLAYER, Msg.trFor(p, "booth.claim-q"), Msg.trFor(p, "booth.claim-detail"),
                    new ItemStack(Items.OAK_SIGN), () -> claim(p, b));
        } else if (b.owner.equals(p.getUuid())) {
            manage(p, b);
        } else {
            browse(p, b);
        }
    }

    /** A visitor looks at a booth: buy or make an offer. */
    public static void browse(ServerPlayerEntity p, Market.Booth b) {
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "booth.of", b.ownerName));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.OAK_SIGN).color(Theme.GOLD_LIGHT).name(Msg.tr("booth.of", b.ownerName)).desc(Msg.tr("booth.browse-desc"))
                    .line(Msg.tr("market.you-have", Markets.cash(market().balance(p.getUuid())))).build());
            menu.list(new ArrayList<>(b.listings), l -> listingIcon(p, b, l, false), l -> (pl, c) -> {
                if (c.isRight()) {
                    Input.text(pl, Msg.trFor(pl, "booth.your-offer", priceText(l.pay, l.price)), "", txt -> {
                        try {
                            makeOffer(pl, b, l, Integer.parseInt(txt.trim()));
                        } catch (NumberFormatException | NullPointerException e) {
                            Msg.send(pl, "market.bad-price");
                        }
                        browse(pl, b);
                    });
                } else {
                    Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "booth.buy-q", l.itemName, priceText(l.pay, l.price)), b.ownerName,
                            ItemConv.decode(l.item), () -> {
                                buy(pl, b, l);
                                browse(pl, b);
                            });
                }
            }, l -> l.itemName, List.of(), Msg.tr("booth.empty"), Msg.tr("booth.empty-visitor"));
        });
        m.open(p);
    }

    private static ItemStack listingIcon(ServerPlayerEntity viewer, Market.Booth b, Market.Listing l, boolean owner) {
        ItemStack s = ItemConv.decode(l.item);
        if (s.isEmpty()) {
            s = new ItemStack(Items.BARRIER);
        }
        Btn btn = Btn.of(s).color(Theme.GOLD_LIGHT).name(l.itemName)
                .line(Msg.tr("shop.price", priceText(l.pay, l.price)))
                .line(Msg.tr("shop.worth", Traders.fmt(Traders.stackValue(s))));
        int offers = 0;
        int best = 0;
        for (Market.BoothOffer o : market().offersFor(b.id)) {
            if (o.listing == l.id) {
                offers++;
                best = Math.max(best, o.amount);
                if (!owner && o.buyer.equals(viewer.getUuid())) {
                    btn.status(Theme.GOLD, Msg.tr("booth.your-offer-is", priceText(o.pay, o.amount)));
                }
            }
        }
        if (offers > 0) {
            btn.line(Msg.tr("booth.offers-on", offers, priceText(l.pay, best)));
        }
        if (owner) {
            btn.left(Msg.tr("booth.change-price")).shift(Msg.tr("booth.take-back"));
        } else {
            btn.left(Msg.tr("booth.buy-now")).right(Msg.tr("booth.make-offer"));
        }
        return btn.amount(s.getCount()).build();
    }

    public static void buy(ServerPlayerEntity p, Market.Booth b, Market.Listing l) {
        if (has(p, l.pay) < l.price) {
            Msg.send(p, "market.no-money", priceText(l.pay, l.price));
            return;
        }
        if (!take(p, l.pay, l.price)) {
            Msg.send(p, "market.no-money", priceText(l.pay, l.price));
            return;
        }
        Market.Sale s = market().buy(b.id, l.id, p.getUuid());
        if (s.result() != Market.Result.OK) {
            giveBack(p, l.pay, l.price);
            Msg.send(p, "booth.r." + s.result().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        ItemStack item = ItemConv.decode(l.item);
        Trades.giveBack(p, List.of(item));
        Ac.markDirty("market");
        relabel(b);
        tellRefunded(s.refunded(), "booth.offer-sold-elsewhere");
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1.2f);
        Msg.send(p, "booth.you-bought", l.itemName, priceText(l.pay, l.price));
        Markets.alert(b.owner, "booth.sale-title", "booth.sold", p.getGameProfile().name(), l.itemName, priceText(l.pay, l.price));
        Teams.xpForTrade(p);
        Ac.get().logs.trade(System.currentTimeMillis(), "booth-buy", p.getUuid(), p.getGameProfile().name(), b.owner, b.ownerName,
                l.itemName + " for " + priceText(l.pay, l.price));
    }

    public static void makeOffer(ServerPlayerEntity p, Market.Booth b, Market.Listing l, int amount) {
        if (amount < 1) {
            Msg.send(p, "market.bad-price");
            return;
        }
        if (amount >= l.price) {
            Msg.send(p, "booth.just-buy");
            return;
        }
        if (!take(p, l.pay, amount)) {
            Msg.send(p, "market.no-money", priceText(l.pay, amount));
            return;
        }
        Market.BoothOffer o = market().makeOffer(b.id, l.id, p.getUuid(), p.getGameProfile().name(), amount);
        if (o == null) {
            giveBack(p, l.pay, amount);
            Msg.send(p, "booth.r.sold_out");
            return;
        }
        Ac.markDirty("market");
        Msg.send(p, "booth.offer-sent", priceText(l.pay, amount), l.itemName, b.ownerName, Ac.config().market.offerHours);
        // The seller sees it on screen (or on their next join), with Accept / Decline right in chat.
        Markets.alert(b.owner, "booth.offer-title", "booth.offer-in", p.getGameProfile().name(), priceText(l.pay, amount), l.itemName,
                priceText(l.pay, l.price));
        ServerPlayerEntity owner = Ac.server().getPlayerManager().getPlayer(b.owner);
        if (owner != null) {
            owner.sendMessage(offerButtons(owner, o));
            com.vylorq.anticheat.ui.BedrockPrompt.ask(owner, Msg.trFor(owner, "booth.offer-title"),
                    Msg.trFor(owner, "booth.offer-in", p.getGameProfile().name(), priceText(l.pay, amount), l.itemName, priceText(l.pay, l.price)),
                    List.of(Msg.trFor(owner, "booth.accept"), Msg.trFor(owner, "booth.decline"), Msg.trFor(owner, "booth.see-offers")),
                    List.of("booth accept " + o.id, "booth decline " + o.id, "booth offers"));
        }
        Ac.get().logs.trade(System.currentTimeMillis(), "booth-offer", p.getUuid(), p.getGameProfile().name(), b.owner, b.ownerName,
                l.itemName + " offer " + priceText(l.pay, amount));
    }

    static MutableText offerButtons(ServerPlayerEntity viewer, Market.BoothOffer o) {
        MutableText m = Text.literal("  ");
        m.append(Msg.button("§a[" + Msg.trFor(viewer, "booth.accept") + "]", "/booth accept " + o.id, ""));
        m.append(" ").append(Msg.button("§c[" + Msg.trFor(viewer, "booth.decline") + "]", "/booth decline " + o.id, ""));
        m.append(" ").append(Msg.button("§e[" + Msg.trFor(viewer, "booth.see-offers") + "]", "/booth offers", ""));
        return m;
    }

    public static void accept(ServerPlayerEntity p, long offerId) {
        Market.BoothOffer o = market().offer(offerId);
        Market.Sale s = market().accept(offerId, p.getUuid());
        if (s.result() != Market.Result.OK || o == null) {
            Msg.send(p, "booth.r." + s.result().name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        Ac.markDirty("market");
        relabel(s.booth());
        tellRefunded(s.refunded(), "booth.offer-sold-elsewhere");
        Mc.sound(p, SoundEvents.ENTITY_VILLAGER_YES, 1f, 1.2f);
        Msg.send(p, "booth.accepted", o.itemName, priceText(o.pay, o.amount), o.buyerName);
        Markets.alert(o.buyer, "booth.offer-title", "booth.offer-accepted", o.itemName, priceText(o.pay, o.amount));
        ServerPlayerEntity buyer = Ac.server().getPlayerManager().getPlayer(o.buyer);
        if (buyer != null) {
            Markets.collect(buyer);
        }
        Teams.xpForTrade(p);
        Ac.get().logs.trade(System.currentTimeMillis(), "booth-accept", o.buyer, o.buyerName, p.getUuid(), p.getGameProfile().name(),
                o.itemName + " for " + priceText(o.pay, o.amount));
    }

    public static void decline(ServerPlayerEntity p, long offerId) {
        Market.BoothOffer o = market().decline(offerId, p.getUuid());
        if (o == null) {
            Msg.send(p, "booth.r.no_such");
            return;
        }
        Ac.markDirty("market");
        Msg.send(p, "booth.declined", o.buyerName);
        Markets.alert(o.buyer, "booth.offer-title", "booth.offer-declined", o.itemName, priceText(o.pay, o.amount));
    }

    // ---------------------------------------------------------------- owner menus

    public static void manage(ServerPlayerEntity p, Market.Booth b) {
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "booth.yours"));
        m.renderer(menu -> {
            int offers = market().offersFor(b.id).size();
            menu.info(Btn.of(Items.OAK_SIGN).color(Theme.GOLD_LIGHT).name(Msg.tr("booth.yours"))
                    .line(Msg.tr("booth.items", b.listings.size()) + " / " + Ac.config().market.boothSlots)
                    .line(Msg.tr("shop.sales", b.sales)).desc(Msg.tr("booth.manage-desc")).build());
            menu.list(new ArrayList<>(b.listings), l -> listingIcon(p, b, l, true), l -> (pl, c) -> {
                if (c.isShift()) {
                    tellRefunded(market().unlist(b.id, l.id, pl.getUuid()), "booth.offer-withdrawn");
                    Ac.markDirty("market");
                    relabel(b);
                    Markets.collect(pl);
                    menu.refresh();
                } else {
                    Input.text(pl, Msg.trFor(pl, "shop.change-price"), String.valueOf(l.price), txt -> {
                        try {
                            if (market().setPrice(b.id, l.id, pl.getUuid(), Integer.parseInt(txt.trim())) == Market.Result.OK) {
                                Ac.markDirty("market");
                            }
                        } catch (NumberFormatException | NullPointerException e) {
                            Msg.send(pl, "market.bad-price");
                        }
                        manage(pl, b);
                    });
                }
            }, l -> l.itemName, List.of(), Msg.tr("booth.empty"), Msg.tr("booth.empty-owner"));
            menu.set(46, Btn.of(Items.WRITABLE_BOOK).color(Theme.GREEN).name(Msg.tr("booth.add")).desc(Msg.tr("booth.add-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Msg.sendRaw(pl, Msg.suggest("§b[/booth add <price> <cash|emeralds|item>]", "/booth add ", ""));
            });
            menu.set(48, Btn.of(Items.PAPER).color(offers > 0 ? Theme.GOLD : Theme.SOFT).name(Msg.tr("booth.offers")).count(offers).glint(offers > 0)
                    .build(), null, (pl, c) -> offers(pl));
            menu.set(50, Btn.of(Items.OAK_DOOR).color(Theme.RED).name(Msg.tr("booth.leave")).desc(Msg.tr("booth.leave-desc")).build(), null,
                    (pl, c) -> Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "booth.leave"), Msg.trFor(pl, "booth.leave-desc"),
                            new ItemStack(Items.OAK_DOOR), () -> {
                                leave(pl);
                                pl.closeHandledScreen();
                            }));
        });
        m.open(p);
    }

    public static void openMine(ServerPlayerEntity p) {
        Market.Booth b = market().boothOf(p.getUuid());
        if (b == null) {
            Msg.send(p, "booth.help");
            return;
        }
        manage(p, b);
    }

    /** Every open offer on the player's booth, accept or decline. */
    public static void offers(ServerPlayerEntity p) {
        Market.Booth b = market().boothOf(p.getUuid());
        if (b == null) {
            Msg.send(p, "booth.none");
            return;
        }
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "booth.yours"), Msg.trFor(p, "booth.offers"));
        m.renderer(menu -> {
            List<Market.BoothOffer> all = market().offersFor(b.id);
            menu.list(all, o -> {
                Market.Listing l = market().listing(b, o.listing);
                ItemStack s = l == null ? new ItemStack(Items.BARRIER) : ItemConv.decode(l.item);
                Btn btn = Btn.of(s.isEmpty() ? new ItemStack(Items.PAPER) : s).color(Theme.GOLD_LIGHT).name(o.itemName)
                        .line(Msg.tr("booth.offer-line", o.buyerName, priceText(o.pay, o.amount)));
                if (l != null) {
                    btn.line(Msg.tr("shop.price", priceText(l.pay, l.price)));
                }
                return btn.line(Msg.tr("booth.offer-age", com.vylorq.anticheat.core.util.Durations.format(System.currentTimeMillis() - o.created)))
                        .left(Msg.tr("booth.accept")).right(Msg.tr("booth.decline")).build();
            }, o -> (pl, c) -> {
                if (c.isRight()) {
                    decline(pl, o.id);
                } else {
                    accept(pl, o.id);
                }
                menu.refresh();
            }, o -> o.itemName + " " + o.buyerName, List.of(), Msg.tr("booth.no-offers"), Msg.tr("booth.no-offers-hint"));
        });
        m.open(p);
    }

    /** /booths: every booth and who has it. */
    public static void list(ServerPlayerEntity p) {
        Msg.send(p, "booth.list-head");
        int n = 0;
        for (Market.Booth b : market().booths()) {
            n++;
            p.sendMessage(Text.literal(label(b) + " §8@ §7" + (int) b.x + " " + (int) b.y + " " + (int) b.z));
        }
        if (n == 0) {
            Msg.send(p, "booth.none-yet");
        }
    }

    public static boolean isAdmin(ServerPlayerEntity p) {
        return Perms.has(p, Perm.TRADER_ADMIN);
    }
}
