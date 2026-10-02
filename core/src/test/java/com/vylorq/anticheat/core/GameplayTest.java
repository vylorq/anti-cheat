package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.arena.*;
import com.vylorq.anticheat.core.deaths.DeathLog;
import com.vylorq.anticheat.core.deaths.DeathRecord;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.jail.JailManager;
import com.vylorq.anticheat.core.lobby.Lobby;
import com.vylorq.anticheat.core.staff.Punishment;
import com.vylorq.anticheat.core.staff.PunishmentManager;
import com.vylorq.anticheat.core.trade.SecureTrade;
import com.vylorq.anticheat.core.trade.TradeRequests;
import com.vylorq.anticheat.core.trader.*;
import com.vylorq.anticheat.core.util.*;
import com.vylorq.anticheat.core.waiting.WaitingRoom;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GameplayTest {
    final Clock.Manual clock = new Clock.Manual(1_700_000_000_000L);
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();

    @Test
    void lobbyBlocksBuildingEvenForAdminsUnlessEditing() {
        Lobby l = new Lobby(null);
        assertFalse(l.allowed(a, Lobby.Action.BREAK));
        assertFalse(l.allowed(a, Lobby.Action.PLACE));
        assertFalse(l.allowed(a, Lobby.Action.ITEM_FRAME));
        assertFalse(l.allowed(a, Lobby.Action.TRAMPLE));
        assertTrue(l.allowed(a, Lobby.Action.CHEST));
        assertTrue(l.allowed(a, Lobby.Action.DOOR));
        assertTrue(l.toggleEdit(a));
        assertTrue(l.allowed(a, Lobby.Action.BREAK));
        Lobby.ChestConfig c = new Lobby.ChestConfig();
        c.mode = Lobby.ChestMode.LOOT;
        l.setChest(1, 2, 3, c);
        assertEquals(1, l.dueRefills(clock.nowMillis(), 60_000).size());
        assertEquals(0, l.dueRefills(clock.nowMillis() + 1000, 60_000).size());
    }

    @Test
    void jailCountsOnlineTime() {
        JailManager j = new JailManager(null, clock);
        j.setCell("A", new Location("w", 0, 64, 0, 0, 0));
        j.jail(a, "Steve", "Griefing", "admin", 2 * Durations.HOUR, null, false);
        assertEquals("a", j.get(a).cell);
        for (int i = 0; i < 3600; i++) {
            assertTrue(j.tick(Set.of(), 1000, true).isEmpty(), "offline time doesn't count");
        }
        List<JailManager.Record> done = List.of();
        for (int i = 0; i < 7200 && done.isEmpty(); i++) {
            done = j.tick(Set.of(a), 1000, true);
        }
        assertEquals(1, done.size());
        assertNotNull(j.release(a));
        assertFalse(j.isJailed(a));
    }

    @Test
    void waitingRoomFlowAndEscalation() {
        WaitingRoom w = new WaitingRoom(null, clock);
        w.data().spawn = new Location("w", 0, 0, 0, 0, 0);
        assertTrue(w.mustWait(a, false, true));
        assertFalse(w.mustWait(a, true, true), "staff never wait");
        w.start(a, "Steve", false, "1.1.1.1");
        assertEquals(WaitingRoom.QUESTIONS[1], w.answer(a, "a friend"));
        assertEquals(WaitingRoom.QUESTIONS[2], w.answer(a, "Steve"));
        assertNull(w.answer(a, "Alex"));
        assertEquals(1, w.pending().size());
        w.decide(a, false, "admin");
        List<String> steps = List.of("24h", "3d", "7d");
        assertEquals(Durations.DAY, w.denyDuration(a, true, steps, Durations.DAY));
        w.resetAfterBan(a);
        w.start(a, "Steve", false, "1.1.1.1");
        w.answerAll(a, new String[]{"x", "y", "z"});
        w.decide(a, false, "admin");
        assertEquals(3 * Durations.DAY, w.denyDuration(a, true, steps, Durations.DAY));
        w.resetAfterBan(a);
        w.start(a, "Steve", false, "1.1.1.1");
        w.answerAll(a, new String[]{"x", "y", "z"});
        assertNotNull(w.decide(a, true, "admin"));
        assertFalse(w.mustWait(a, false, true));
    }

    @Test
    void denyScreenShowsOnlyTimeLeft() {
        PunishmentManager pm = new PunishmentManager(null, clock);
        Punishment p = pm.add(Punishment.Type.DENY, a, "Steve", "denied", "admin", Durations.DAY);
        String screen = pm.banScreen(p);
        assertEquals("You are temporarily banned. Time remaining: 1d 0h.", screen);
        assertFalse(screen.toLowerCase().contains("denied"));
        assertTrue(pm.isBanned(a));
        clock.advance(Durations.DAY);
        assertFalse(pm.isBanned(a));
    }

    @Test
    void punishmentLadder() {
        PunishmentManager pm = new PunishmentManager(null, clock);
        Map<String, String> ladder = Map.of("3", "mute 1d");
        pm.add(Punishment.Type.WARN, a, "S", "x", "admin", 0);
        pm.add(Punishment.Type.WARN, a, "S", "x", "admin", 0);
        assertNull(pm.ladderStep(a, ladder));
        pm.add(Punishment.Type.WARN, a, "S", "x", "admin", 0);
        PunishmentManager.LadderStep s = pm.ladderStep(a, ladder);
        assertEquals(Punishment.Type.MUTE, s.type());
        assertEquals(Durations.DAY, s.duration());
    }

    @Test
    void deathRestoreOnlyOnce() {
        DeathLog log = new DeathLog(null, clock);
        DeathRecord r = new DeathRecord();
        r.player = a;
        r.playerName = "Steve";
        log.add(r);
        assertEquals(DeathLog.RestoreResult.OK, log.markRestored(r.id, "admin"));
        assertEquals(DeathLog.RestoreResult.ALREADY_RESTORED, log.markRestored(r.id, "admin"));
        DeathRecord r2 = new DeathRecord();
        r2.player = a;
        log.add(r2);
        log.recordPickup(r2.dropTag, a, "Steve", "diamond_sword");
        assertEquals(DeathLog.RestoreResult.ITEMS_RECOVERED, log.markRestored(r2.id, "admin"), "already picked up");
        DeathRecord r3 = new DeathRecord();
        r3.player = a;
        log.add(r3);
        log.recordPickup(r3.dropTag, b, "Thief", "diamond_sword");
        assertEquals(DeathLog.RestoreResult.OK, log.markRestored(r3.id, "admin"), "someone else took them: restore allowed");
    }

    Arena arena(String name) {
        Arena ar = new Arena();
        ar.name = name;
        ar.area = new Area("w", 0, 0, 0, 50, 100, 50);
        ar.teamSpawns.add(List.of(new Location("w", 1, 64, 1, 0, 0)));
        ar.teamSpawns.add(List.of(new Location("w", 40, 64, 40, 0, 0)));
        ar.modes = List.of(Arena.Mode.ONE_V_ONE, Arena.Mode.TWO_V_TWO);
        ar.rules.bestOf = 3;
        return ar;
    }

    @Test
    void arenaQueueAndBestOf3() {
        ArenaManager m = new ArenaManager(null, clock);
        m.addArena(arena("a1"));
        assertNull(m.joinQueue(a, Arena.Mode.ONE_V_ONE, "sword"));
        Match match = m.joinQueue(b, Arena.Mode.ONE_V_ONE, "sword");
        assertNotNull(match);
        assertTrue(m.inMatch(a) && m.inMatch(b));
        assertNull(m.freeArena(Arena.Mode.ONE_V_ONE, "sword"), "arena busy");
        UUID winner = match.teams.get(0).get(0);
        UUID loser = match.teams.get(1).get(0);
        match.beginFight(0);
        assertEquals(0, match.eliminate(loser, winner));
        assertFalse(match.finishRound(0, 0));
        match.startRound(0);
        assertEquals(0, match.eliminate(loser, winner));
        assertTrue(match.finishRound(0, 0));
        assertEquals(-1, match.eliminate(loser, winner), "can't eliminate twice");
        m.recordResult(match);
        m.end(match);
        assertEquals(1, m.stats(winner).wins);
        assertEquals(1, m.stats(loser).losses);
        assertEquals(2, m.stats(winner).kills);
        assertFalse(m.inMatch(a));
    }

    @Test
    void arenaRestoreExactlyOnce() {
        ArenaManager m = new ArenaManager(null, clock);
        PlayerSnapshot s = new PlayerSnapshot();
        s.player = a;
        m.savePending(s);
        assertNotNull(m.takePending(a));
        assertNull(m.takePending(a));
    }

    @Test
    void duelRequestExpires() {
        ArenaManager m = new ArenaManager(null, clock);
        m.requestDuel(a, b, "sword");
        clock.advance(31_000);
        assertNull(m.acceptDuel(b, a, 30_000));
        m.requestDuel(a, b, "sword");
        assertNotNull(m.acceptDuel(b, a, 30_000));
        assertNull(m.acceptDuel(b, a, 30_000), "once");
    }

    ItemValues values() {
        Map<String, Double> base = new HashMap<>();
        base.put("minecraft:diamond", 50.0);
        base.put("minecraft:stick", 0.5);
        base.put("minecraft:dirt", 0.1);
        base.put("minecraft:iron_ingot", 8.0);
        Map<String, List<ItemValues.Recipe>> rec = new HashMap<>();
        rec.put("minecraft:diamond_sword", List.of(new ItemValues.Recipe(Map.of("minecraft:diamond", 2, "minecraft:stick", 1), 1)));
        rec.put("minecraft:iron_block", List.of(new ItemValues.Recipe(Map.of("minecraft:iron_ingot", 9), 1)));
        rec.put("minecraft:iron_nugget", List.of(new ItemValues.Recipe(Map.of("minecraft:iron_ingot", 1), 9)));
        return new ItemValues(base, rec);
    }

    @Test
    void valuesFromRecipes() {
        ItemValues v = values();
        assertEquals((100.5) * 1.05, v.value("minecraft:diamond_sword"), 1e-9);
        assertEquals(9 * 8 * 1.05, v.value("minecraft:iron_block"), 1e-9);
        assertEquals(ItemValues.UNKNOWN, v.value("minecraft:mystery"));
        ItemInfo damaged = new ItemInfo("minecraft:diamond_sword", 1);
        damaged.maxDamage = 1561;
        damaged.damage = 1561 / 2;
        assertTrue(v.unitValue(damaged) < v.value("minecraft:diamond_sword") * 0.6);
    }

    @Test
    void offerVerdictsAndJunkSpam() {
        ItemValues v = values();
        TraderOffer o = new TraderOffer();
        o.id = "minecraft:diamond_sword";
        double itemVal = v.value(o.id);
        // Junk: 10,000 dirt never equals a diamond sword
        List<ItemInfo> junk = new ArrayList<>();
        for (int i = 0; i < 156; i++) junk.add(new ItemInfo("minecraft:dirt", 64));
        assertEquals(OfferEvaluator.Verdict.WAY_TOO_LOW, OfferEvaluator.evaluate(o, junk, v, 1, 1.3, 0.97, 0.65, null).verdict());
        assertTrue(OfferEvaluator.paymentValue(junk, v, 0.97) < 4, "diminishing returns cap cheap items");
        assertEquals(OfferEvaluator.Verdict.GETTING_CLOSER,
                OfferEvaluator.evaluate(o, List.of(new ItemInfo("minecraft:diamond", 2)), v, 1, 1.3, 0.97, 0.65, null).verdict());
        assertEquals(OfferEvaluator.Verdict.DEAL,
                OfferEvaluator.evaluate(o, List.of(new ItemInfo("minecraft:diamond", 3)), v, 1, 1.3, 0.97, 0.65, null).verdict());
        assertTrue(itemVal * 1.3 > 2 * 50 * 0.99, "deals favour the trader");
        assertEquals(OfferEvaluator.Verdict.REJECTED,
                OfferEvaluator.evaluate(o, List.of(new ItemInfo("minecraft:red_shulker_box", 1)), v, 1, 1.3, 0.97, 0.65, null).verdict());
        assertEquals(OfferEvaluator.Verdict.REJECTED,
                OfferEvaluator.evaluate(o, List.of(new ItemInfo("minecraft:diamond", 3)), v, 1, 1.3, 0.97, 0.65, "illegal").verdict());
    }

    TraderEconomy.Settings settings() {
        TraderEconomy.Settings s = new TraderEconomy.Settings();
        s.neverSell = List.of("minecraft:mace", "minecraft:elytra");
        return s;
    }

    @Test
    void neverSellsNetheriteOrUnobtained() {
        TraderEconomy e = new TraderEconomy(null, clock);
        TraderEconomy.Settings s = settings();
        assertTrue(e.maySell("minecraft:diamond_sword", s, List.of()), "traders have stock on a new server");
        s.onlyObtained = true;
        assertFalse(e.maySell("minecraft:diamond_sword", s, List.of()), "not obtained yet");
        e.markObtained("minecraft:diamond_sword");
        assertTrue(e.maySell("minecraft:diamond_sword", s, List.of()));
        e.markObtained("minecraft:netherite_sword");
        assertFalse(e.maySell("minecraft:netherite_sword", s, List.of()));
        e.markObtained("minecraft:netherite_upgrade_smithing_template");
        assertFalse(e.maySell("minecraft:netherite_upgrade_smithing_template", s, List.of()));
        e.markObtained("minecraft:mace");
        assertFalse(e.maySell("minecraft:mace", s, List.of()));
        e.markObtained("minecraft:bedrock");
        assertFalse(e.maySell("minecraft:bedrock", s, List.of()));
        e.markObtained("minecraft:zombie_spawn_egg");
        assertFalse(e.maySell("minecraft:zombie_spawn_egg", s, List.of()));
    }

    @Test
    void marketPricesMoveEveryPeriod() {
        Clock.Manual c = new Clock.Manual(1_700_000_000_000L);
        TraderEconomy e = new TraderEconomy(null, c);
        TraderEconomy.Settings s = settings();
        Trader t = new Trader();
        t.entity = UUID.randomUUID();
        e.rotate(t, s, List.of(), new SplittableRandom(3));
        assertFalse(t.offers.isEmpty(), "a new server's trader has stock");
        SplittableRandom r = new SplittableRandom(4);
        assertTrue(e.marketTick(List.of(t), s, r));
        String sig = t.offers.get(0).signature();
        double start = e.market(sig);
        assertTrue(start >= 0.85 && start <= 1.15);
        assertFalse(e.marketTick(List.of(t), s, r), "no change inside the same period");
        assertEquals(start, e.market(sig));
        for (int i = 0; i < 40; i++) {
            c.advance(50 * Durations.MINUTE);
            double before = e.market(sig);
            assertTrue(e.marketTick(List.of(t), s, r));
            double now = e.market(sig);
            assertNotEquals(before, now, "price changed");
            assertTrue(now >= s.minPrice && now <= s.maxPrice);
            assertEquals(now / before - 1, e.trend(sig), 1e-9);
        }
        assertTrue(e.untilPriceChange(s) > 0 && e.untilPriceChange(s) <= 50 * Durations.MINUTE);
    }

    @Test
    void rotationOffersAreValid() {
        TraderEconomy e = new TraderEconomy(null, clock);
        TraderEconomy.Settings s = settings();
        for (Specialty sp : Specialty.values()) {
            for (Specialty.Entry en : sp.pool()) e.markObtained(en.id());
        }
        e.markObtained("minecraft:enchanted_book");
        SplittableRandom r = new SplittableRandom(9);
        for (Specialty sp : Specialty.values()) {
            Trader t = new Trader();
            t.entity = UUID.randomUUID();
            t.specialty = sp;
            e.rotate(t, s, List.of(), r);
            assertTrue(t.offers.size() >= 4 && t.offers.size() <= 8, sp + " " + t.offers.size());
            assertTrue(t.mood >= 1.2 && t.mood <= 1.5);
            long minutes = (t.nextRotation - clock.nowMillis()) / Durations.MINUTE;
            assertTrue(minutes >= 120 && minutes <= 240);
            for (TraderOffer o : t.offers) {
                assertFalse(o.id.equals("minecraft:mace"));
                assertFalse(o.id.contains("netherite"));
                switch (o.rarity) {
                    case COMMON -> assertTrue(o.stock >= 16 && o.stock <= 64);
                    case UNCOMMON -> assertTrue(o.stock >= 4 && o.stock <= 8);
                    case RARE -> assertEquals(2, o.stock);
                    case LEGENDARY -> assertEquals(1, o.stock);
                }
                for (Map.Entry<String, Integer> en : o.enchantments.entrySet()) {
                    assertTrue(en.getValue() <= Enchants.max(en.getKey()));
                }
            }
        }
    }

    @Test
    void lastItemLockUnderConcurrency() throws Exception {
        TraderEconomy e = new TraderEconomy(null, clock);
        TraderEconomy.Settings s = settings();
        s.weeklyLegendaryCap = 100;
        s.perPlayerLegendaryPerWeek = 100;
        Trader t = new Trader();
        t.entity = UUID.randomUUID();
        TraderOffer o = new TraderOffer();
        o.id = "minecraft:enchanted_book";
        o.rarity = Rarity.LEGENDARY;
        o.stock = o.maxStock = 1;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            fs.add(ex.submit(() -> {
                go.await();
                if (e.purchase(t, o, UUID.randomUUID(), null, s) == TraderEconomy.Refusal.NONE) ok.incrementAndGet();
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        ex.shutdown();
        assertEquals(1, ok.get(), "only one buyer gets the last item");
        assertEquals(0, o.stock);
    }

    @Test
    void weeklyCapsPerTraderPlayerAndIp() {
        TraderEconomy e = new TraderEconomy(null, clock);
        TraderEconomy.Settings s = settings();
        Trader t = new Trader();
        t.entity = UUID.randomUUID();
        TraderOffer leg = new TraderOffer();
        leg.id = "minecraft:enchanted_book";
        leg.rarity = Rarity.LEGENDARY;
        leg.stock = 10;
        assertEquals(TraderEconomy.Refusal.NONE, e.purchase(t, leg, a, "1.1.1.1", s));
        assertEquals(TraderEconomy.Refusal.TRADER_WEEKLY_CAP, e.purchase(t, leg, b, "2.2.2.2", s));
        Trader t2 = new Trader();
        t2.entity = UUID.randomUUID();
        assertEquals(TraderEconomy.Refusal.PLAYER_WEEKLY_CAP, e.purchase(t2, leg, a, "1.1.1.1", s));
        assertEquals(TraderEconomy.Refusal.IP_WEEKLY_CAP, e.purchase(t2, leg, UUID.randomUUID(), "1.1.1.1", s), "alt on same IP");
        clock.advance(8 * Durations.DAY);
        assertEquals(TraderEconomy.Refusal.NONE, e.purchase(t2, leg, a, "1.1.1.1", s), "new week");
    }

    @Test
    void demandRisesAndDecays() {
        TraderEconomy e = new TraderEconomy(null, clock);
        TraderEconomy.Settings s = settings();
        Trader t = new Trader();
        t.entity = UUID.randomUUID();
        TraderOffer o = new TraderOffer();
        o.id = "minecraft:bread";
        o.stock = 64;
        for (int i = 0; i < 10; i++) e.purchase(t, o, a, null, s);
        assertEquals(1.5, e.demand(o.signature(), s), 1e-6);
        clock.advance(100 * Durations.HOUR);
        assertEquals(1.0, e.demand(o.signature(), s), 1e-6);
    }

    @Test
    void macroDetection() {
        TraderEconomy e = new TraderEconomy(null, clock);
        for (int i = 0; i < 10; i++) {
            assertTrue(e.rateOk(a, 100));
            clock.advance(1000);
        }
        assertNotNull(e.macroCheck(a, 0), "perfectly timed");
        assertNull(e.macroCheck(b, 0));
        for (int i = 0; i < 3; i++) {
            String r = e.macroCheck(b, clock.nowMillis() - 1000);
            if (i == 2) assertNotNull(r, "restock sniping");
        }
    }

    @Test
    void profitLoopsFound() {
        ItemValues v = values();
        assertEquals(1, TraderEconomy.profitLoops(Map.of("minecraft:diamond", 70.0, "minecraft:iron_ingot", 5.0), v, 1.2).size());
    }

    @Test
    void secureTradeStateMachine() {
        SecureTrade t = new SecureTrade(a, b, 3000);
        assertFalse(t.confirm(a, t.revision(), 0), "can't confirm before ready");
        t.toggleReady(a);
        t.toggleReady(b);
        assertEquals(SecureTrade.Step.BOTH_READY, t.step());
        int rev = t.revision();
        assertTrue(t.confirm(a, rev, 0));
        t.changed();
        assertFalse(t.isReady(a) || t.isReady(b), "any change resets both");
        assertFalse(t.confirm(b, rev, 0), "stale confirm rejected");
        t.toggleReady(a);
        t.toggleReady(b);
        rev = t.revision();
        t.confirm(a, rev, 1000);
        t.confirm(b, rev, 1000);
        assertEquals(SecureTrade.Step.CONFIRMED_COUNTDOWN, t.step());
        assertFalse(t.tick(2000));
        t.changed();
        assertEquals(SecureTrade.Step.OPEN, t.step(), "change during countdown cancels it");
        t.toggleReady(a);
        t.toggleReady(b);
        rev = t.revision();
        t.confirm(a, rev, 5000);
        t.confirm(b, rev, 5000);
        assertEquals(3, t.secondsLeft(5000));
        assertTrue(t.tick(8000));
        assertFalse(t.tick(9000), "completes exactly once");
        t.changed();
        assertEquals(SecureTrade.Step.COMPLETE, t.step());
        SecureTrade c = new SecureTrade(a, b, 3000);
        c.cancel(SecureTrade.CancelReason.DISCONNECTED);
        assertFalse(c.toggleReady(a));
        assertFalse(SecureTrade.fits(5, 2, 1));
        assertTrue(SecureTrade.fits(5, 4, 1));
    }

    @Test
    void tradeRequestsExpire() {
        TradeRequests r = new TradeRequests();
        r.request(a, b, 0);
        assertFalse(r.take(b, a, 31_000, 30_000));
        r.request(a, b, 0);
        assertFalse(r.take(a, b, 1000, 30_000), "wrong direction");
        assertTrue(r.take(b, a, 1000, 30_000));
    }
}
