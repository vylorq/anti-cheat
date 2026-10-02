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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;

import java.util.List;

/**
 * The server's own shop (/servershop): staff list items with a fixed buy price and/or sell-back price in cash.
 * Unlimited stock; selling only takes plain items (no enchantments or names) so nothing special gets sold by
 * mistake.
 */
public final class ServerShop {
    private ServerShop() {
    }

    static Market market() {
        return Ac.get().market;
    }

    static ItemStack template(Market.ServerItem x) {
        ItemStack s = ItemConv.decode(x.item);
        return s.isEmpty() ? new ItemStack(Items.BARRIER) : s;
    }

    /** Staff: list the item in hand (its count is the amount per sale). */
    public static void add(ServerPlayerEntity p, int buy, int sell) {
        if (!Perms.require(p, Perm.SETTINGS)) {
            return;
        }
        ItemStack held = p.getMainHandStack();
        if (held.isEmpty()) {
            Msg.send(p, "market.hold");
            return;
        }
        if (buy <= 0 && sell <= 0) {
            Msg.send(p, "market.bad-price");
            return;
        }
        ItemStack t = held.copy();
        Market.ServerItem x = market().addServerItem(ItemConv.encode(t), t.getCount() + "x " + t.getName().getString(), buy, sell);
        Ac.markDirty("market");
        Staff.log(p, "servershop-add", null, x.itemName, "buy " + buy + " sell " + sell);
        Msg.send(p, "sshop.added", x.itemName, buy > 0 ? Markets.cash(buy) : "-", sell > 0 ? Markets.cash(sell) : "-");
    }

    public static void open(ServerPlayerEntity p) {
        if (!Features.on(Features.Feature.SERVER_SHOP)) {
            Msg.send(p, "features.is-off", Msg.trFor(p, "feature.server-shop"));
            return;
        }
        if (!Markets.enabled()) {
            Msg.send(p, "market.disabled");
            return;
        }
        boolean staff = Perms.has(p, Perm.SETTINGS);
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "sshop.title"));
        m.renderer(menu -> {
            menu.info(Btn.of(Items.GOLD_BLOCK).color(Theme.GOLD_LIGHT).name(Msg.tr("sshop.title")).desc(Msg.tr("sshop.desc"))
                    .line(Msg.tr("market.you-have", Markets.cash(market().balance(p.getUuid())))).build());
            menu.list(market().serverShop(), x -> {
                ItemStack s = template(x);
                Btn b = Btn.of(s).color(Theme.GOLD_LIGHT).name(x.itemName);
                if (x.buy > 0) {
                    b.line(Msg.tr("sshop.buy-price", Markets.cash(x.buy)));
                    b.left(Msg.tr("sshop.buy"));
                }
                if (x.sell > 0) {
                    b.line(Msg.tr("sshop.sell-price", Markets.cash(x.sell)));
                    b.line(Msg.tr("orders.you-have", Markets.countItem(p, s.getItem())));
                    b.right(Msg.tr("sshop.sell"));
                }
                if (staff) {
                    b.shiftRight(Msg.tr("sshop.edit"));
                }
                return b.amount(s.getCount()).build();
            }, x -> (pl, c) -> {
                if (c == Menu.Click.SHIFT_RIGHT && staff) {
                    edit(pl, x, menu);
                } else if (c.isRight() && x.sell > 0) {
                    sell(pl, x, c.isShift() ? 64 : 1);
                    menu.refresh();
                } else if (x.buy > 0) {
                    buy(pl, x, c.isShift() ? 8 : 1);
                    menu.refresh();
                }
            }, x -> x.itemName, List.of(), Msg.tr("sshop.empty"), Msg.tr(staff ? "sshop.empty-staff" : "sshop.empty-hint"));
        });
        m.open(p);
    }

    public static void buy(ServerPlayerEntity p, Market.ServerItem x, int times) {
        int done = 0;
        for (int i = 0; i < times; i++) {
            if (!market().takeCash(p.getUuid(), x.buy)) {
                if (done == 0) {
                    Msg.send(p, "market.no-money", Markets.cash(x.buy));
                }
                break;
            }
            Trades.giveBack(p, List.of(template(x).copy()));
            done++;
        }
        if (done > 0) {
            Ac.markDirty("market");
            Mc.sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            Msg.send(p, "sshop.bought", done + " × " + x.itemName, Markets.cash((long) done * x.buy));
            Ac.get().logs.trade(System.currentTimeMillis(), "servershop-buy", p.getUuid(), p.getGameProfile().name(), null, "server",
                    done + "x " + x.itemName + " for " + (long) done * x.buy);
        }
    }

    /** Sells plain items of this kind: as many bundles as asked and they have. */
    public static void sell(ServerPlayerEntity p, Market.ServerItem x, int times) {
        ItemStack t = template(x);
        int per = t.getCount();
        int have = Markets.countItem(p, t.getItem());
        int bundles = Math.min(times, have / per);
        if (bundles <= 0) {
            Msg.send(p, "sshop.need", per + "x " + t.getName().getString());
            return;
        }
        Markets.takeItem(p, t.getItem(), bundles * per);
        market().addCash(p.getUuid(), (long) bundles * x.sell);
        Ac.markDirty("market");
        Mc.sound(p, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        Msg.send(p, "sshop.sold", bundles * per + "x " + t.getName().getString(), Markets.cash((long) bundles * x.sell));
        Ac.get().logs.trade(System.currentTimeMillis(), "servershop-sell", p.getUuid(), p.getGameProfile().name(), null, "server",
                bundles * per + "x " + x.itemName + " for " + (long) bundles * x.sell);
    }

    static void edit(ServerPlayerEntity p, Market.ServerItem x, Menu back) {
        Menu m = Menu.std(Theme.Category.PLAYER, Msg.trFor(p, "sshop.title"), x.itemName);
        m.parent(back);
        m.renderer(menu -> {
            menu.set(11, Btn.of(Items.EMERALD).color(Theme.GREEN).name(Msg.tr("sshop.buy-price", x.buy > 0 ? Markets.cash(x.buy) : "-"))
                    .left(Msg.tr("panel.action.change")).build(), null, (pl, c) -> Input.text(pl, Msg.trFor(pl, "sshop.set-buy"), String.valueOf(x.buy), t -> {
                setPrice(pl, x, t, true);
                edit(pl, x, back);
            }));
            menu.set(13, Btn.of(Items.GOLD_INGOT).color(Theme.GOLD).name(Msg.tr("sshop.sell-price", x.sell > 0 ? Markets.cash(x.sell) : "-"))
                    .left(Msg.tr("panel.action.change")).build(), null, (pl, c) -> Input.text(pl, Msg.trFor(pl, "sshop.set-sell"), String.valueOf(x.sell), t -> {
                setPrice(pl, x, t, false);
                edit(pl, x, back);
            }));
            menu.set(15, Btn.of(Items.TNT).color(Theme.RED).name(Msg.tr("sshop.remove")).left(Msg.tr("panel.action.delete")).build(), null,
                    (pl, c) -> Confirm.open(pl, Theme.Category.PLAYER, Msg.trFor(pl, "sshop.remove"), x.itemName, template(x), () -> {
                        market().removeServerItem(x.id);
                        Ac.markDirty("market");
                        Staff.log(pl, "servershop-remove", null, x.itemName, "");
                        open(pl);
                    }));
        });
        m.open(p);
    }

    static void setPrice(ServerPlayerEntity p, Market.ServerItem x, String txt, boolean buy) {
        try {
            int v = Math.max(0, Integer.parseInt(txt.trim()));
            if (buy) {
                x.buy = v;
            } else {
                x.sell = v;
            }
            Ac.markDirty("market");
            Staff.log(p, "servershop-price", null, x.itemName, (buy ? "buy " : "sell ") + v);
        } catch (NumberFormatException | NullPointerException e) {
            Msg.send(p, "market.bad-price");
        }
    }
}
