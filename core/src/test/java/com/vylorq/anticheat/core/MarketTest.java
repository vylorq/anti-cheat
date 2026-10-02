package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.market.Market;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.team.TeamManager;
import com.vylorq.anticheat.core.util.Clock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MarketTest {
    final Clock.Manual clock = new Clock.Manual(1_700_000_000_000L);
    final UUID seller = UUID.randomUUID();
    final UUID bob = UUID.randomUUID();
    final UUID cat = UUID.randomUUID();

    @Test
    void auctionGoesToHighestBidderAndRefundsTheRest() {
        Market m = new Market(null, clock);
        Market.Auction a = m.list(seller, "Seller", "item", "Diamond Sword", 10, 3_600_000);
        assertEquals(Market.Result.OWN, m.bid(a.id, seller, "Seller", 50).result());
        assertEquals(Market.Result.TOO_LOW, m.bid(a.id, bob, "Bob", 9).result());
        assertEquals(Market.Result.OK, m.bid(a.id, bob, "Bob", 10).result());
        assertEquals(11, Market.minBid(a));
        Market.BidResult r = m.bid(a.id, cat, "Cat", 20);
        assertEquals(Market.Result.OK, r.result());
        assertEquals(bob, r.refundTo());
        assertEquals(10, m.takeCoins(bob), "outbid player gets their money back");
        assertEquals(Market.Result.HAS_BIDS, m.cancelAuction(a.id, seller, false));
        assertTrue(m.settle().isEmpty(), "not over yet");
        clock.advance(3_600_000);
        assertEquals(1, m.settle().size());
        assertEquals(java.util.List.of("item"), m.takeItems(cat));
        assertEquals(20, m.takeCoins(seller));
        assertFalse(m.hasPickups(seller));
    }

    @Test
    void lastMinuteBidsExtendTheAuction() {
        Market m = new Market(null, clock);
        Market.Auction a = m.list(seller, "Seller", "item", "x", 5, 30_000);
        m.bid(a.id, bob, "Bob", 5);
        assertEquals(clock.nowMillis() + 60_000, a.endsAt);
    }

    @Test
    void unsoldAuctionReturnsTheItem() {
        Market m = new Market(null, clock);
        m.list(seller, "Seller", "item", "x", 5, 1000);
        clock.advance(1000);
        m.settle();
        assertEquals(java.util.List.of("item"), m.takeItems(seller));
    }

    @Test
    void buyOrdersPayEachFillerTheirShare() {
        Market m = new Market(null, clock);
        Market.Order o = m.order(seller, "Seller", "minecraft:iron_ingot", 64, 10);
        assertEquals(Market.Result.OWN, m.fill(o.id, seller, 5).result());
        Market.FillResult f = m.fill(o.id, bob, 32);
        assertEquals(32, f.count());
        assertEquals(5, f.pay());
        assertFalse(f.complete());
        Market.FillResult g = m.fill(o.id, cat, 100);
        assertEquals(32, g.count(), "can't hand in more than is wanted");
        assertEquals(5, g.pay());
        assertTrue(g.complete());
        assertEquals(Market.Result.NO_SUCH, m.fill(o.id, cat, 1).result());
    }

    @Test
    void cancelledOrderRefundsWhatWasntPaidOut() {
        Market m = new Market(null, clock);
        Market.Order o = m.order(seller, "Seller", "minecraft:coal", 10, 7);
        m.fill(o.id, bob, 3);
        assertEquals(Market.Result.NOT_YOURS, m.cancelOrder(o.id, bob, false));
        assertEquals(Market.Result.OK, m.cancelOrder(o.id, seller, false));
        assertEquals(7 - 7 * 3 / 10, m.takeCoins(seller));
    }

    @Test
    void bountiesGoToTheKiller() {
        Market m = new Market(null, clock);
        m.addBounty(bob, "Bob", seller, "Seller", "a", "Diamond", 50);
        m.addBounty(bob, "Bob", cat, "Cat", "b", "Gold", 10);
        assertEquals(60, m.bountyValue(bob));
        assertEquals(2, m.claimBounties(bob, cat).size());
        assertEquals(java.util.List.of("a", "b"), m.takeItems(cat));
        assertEquals(0, m.bountyValue(bob));
    }

    @Test
    void reputationDiscountGrowsAndCaps() {
        assertEquals(0, Market.discount(4));
        assertEquals(0.01, Market.discount(5), 1e-9);
        assertEquals(0.10, Market.discount(500), 1e-9);
        Market m = new Market(null, clock);
        for (int i = 0; i < 12; i++) {
            m.addDeal(bob, "t1");
        }
        assertEquals(12, m.deals(bob, "t1"));
        assertEquals(0, m.deals(bob, "t2"));
    }

    @Test
    void priceHistoryKeepsTheLastPoints() {
        Market m = new Market(null, clock);
        for (int i = 0; i < 40; i++) {
            m.recordPrice("sig", i);
        }
        assertEquals(Market.HISTORY_POINTS, m.history("sig").size());
        assertEquals(39, m.history("sig").get(Market.HISTORY_POINTS - 1)[1]);
    }

    @Test
    void teamLevelsBankAndWars() {
        TeamManager tm = new TeamManager(null, clock);
        assertEquals(1, TeamManager.level(0));
        assertEquals(2, TeamManager.level(50));
        assertEquals(3, TeamManager.level(200));
        assertEquals(10, TeamManager.level(1_000_000));
        assertEquals(200, TeamManager.xpFor(3));
        UUID l1 = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        tm.create("Reds", null, l1);
        tm.create("Blues", null, l2);
        TeamManager.Limits lim = new TeamManager.Limits();
        tm.invite(l1, m1, lim);
        tm.join(m1, "Reds", lim);
        Team reds = tm.get("Reds");
        int before = tm.chunkLimit(reds, lim);
        assertEquals(2, tm.addXp(reds, 60));
        assertEquals(before + lim.chunksPerLevel, tm.chunkLimit(reds, lim), "levels give more land");
        assertEquals(0, tm.addXp(reds, 1));

        assertEquals(TeamManager.Result.OK, tm.deposit(m1, "m1", 30));
        assertEquals(TeamManager.Result.NOT_ALLOWED, tm.withdraw(m1, "m1", 5), "members can't take money");
        assertEquals(TeamManager.Result.NOT_ENOUGH, tm.withdraw(l1, "l1", 31));
        assertEquals(TeamManager.Result.OK, tm.withdraw(l1, "l1", 10));
        assertEquals(20, reds.bank);
        assertEquals(2, reds.bankLog.size());

        assertEquals(TeamManager.Result.NOT_ALLOWED, tm.declareWar(m1, "Blues", 3_600_000, 0));
        assertEquals(TeamManager.Result.OK, tm.declareWar(l1, "Blues", 3_600_000, 3_600_000));
        assertEquals(TeamManager.Result.AT_WAR, tm.declareWar(l2, "Reds", 3_600_000, 0));
        assertNotNull(tm.warKill(m1, l2));
        assertNotNull(tm.warKill(l1, l2));
        assertNotNull(tm.warKill(l2, l1));
        assertNull(tm.warKill(l1, m1), "teammates don't score");
        TeamManager.War w = tm.warOf(reds.id);
        assertEquals(2, w.scoreA);
        assertEquals(1, w.scoreB);
        assertTrue(tm.endWars().isEmpty());
        clock.advance(3_600_000);
        assertEquals(1, tm.endWars().size());
        assertEquals(1, reds.warsWon);
        assertEquals(1, tm.get("Blues").warsLost);
        assertEquals(TeamManager.Result.WAR_COOLDOWN, tm.declareWar(l1, "Blues", 3_600_000, 0));
        assertEquals(TeamManager.Result.OK, tm.declareWar(l2, "Reds", 1000, 0), "the other team can still declare");
    }

    @Test
    void cashTransfersAndRichest() {
        Market m = new Market(null, clock);
        assertFalse(m.hasWallet(bob));
        m.addCash(bob, 100);
        assertEquals(Market.Result.NOT_ENOUGH, m.pay(bob, cat, 101));
        assertEquals(Market.Result.OWN, m.pay(bob, bob, 5));
        assertEquals(Market.Result.BAD_AMOUNT, m.pay(bob, cat, 0));
        assertEquals(Market.Result.OK, m.pay(bob, cat, 40));
        assertEquals(60, m.balance(bob));
        assertEquals(40, m.balance(cat));
        assertFalse(m.takeCash(cat, 41));
        assertEquals(bob, m.richest(1).get(0).getKey());
    }

    @Test
    void shopsSellAndBuy() {
        Market m = new Market(null, clock);
        Market.Shop sell = new Market.Shop();
        sell.owner = seller;
        sell.selling = true;
        sell.bundle = 16;
        sell.stock = 20;
        sell.pay = "cash";
        sell.price = 30;
        m.addShop(sell);
        assertEquals(Market.Result.OK, m.shopSale(sell));
        assertEquals(30, m.balance(seller), "cash goes straight to the owner");
        assertEquals(Market.Result.SOLD_OUT, m.shopSale(sell), "4 left, a sale needs 16");

        Market.Shop buy = new Market.Shop();
        buy.owner = seller;
        buy.selling = false;
        buy.bundle = 8;
        buy.pay = "minecraft:emerald";
        buy.price = 3;
        buy.funds = 5;
        m.addShop(buy);
        assertEquals(Market.Result.OK, m.shopPurchase(buy, "minecraft:wheat"));
        assertEquals(Market.Result.NOT_ENOUGH, m.shopPurchase(buy, "minecraft:wheat"), "only 2 emeralds of funds left");
        assertEquals(java.util.Map.of("minecraft:wheat", 8), m.takeOwedItems(seller));

        Market.Shop cashBuy = new Market.Shop();
        cashBuy.owner = cat;
        cashBuy.pay = "cash";
        cashBuy.price = 10;
        cashBuy.bundle = 1;
        m.addShop(cashBuy);
        assertEquals(Market.Result.NOT_ENOUGH, m.shopPurchase(cashBuy, "minecraft:dirt"));
        m.addCash(cat, 10);
        assertEquals(Market.Result.OK, m.shopPurchase(cashBuy, "minecraft:dirt"));
        assertEquals(0, m.balance(cat));
        assertSame(cashBuy, m.shopByEntity(java.util.UUID.randomUUID()) == null ? cashBuy : null);
        assertEquals(1, m.shopsBy(cat));
        assertNotNull(m.removeShop(cashBuy.id));
        assertEquals(Market.Result.NO_SUCH, m.shopPurchase(cashBuy, "minecraft:dirt"));
    }
}
