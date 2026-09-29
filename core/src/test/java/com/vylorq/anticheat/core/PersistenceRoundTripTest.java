package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.arena.*;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.barrier.BarrierManager;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.claims.ClaimRole;
import com.vylorq.anticheat.core.deaths.DeathLog;
import com.vylorq.anticheat.core.deaths.DeathRecord;
import com.vylorq.anticheat.core.detect.*;
import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.jail.JailManager;
import com.vylorq.anticheat.core.joins.JoinGuard;
import com.vylorq.anticheat.core.lobby.Lobby;
import com.vylorq.anticheat.core.perm.AdminPins;
import com.vylorq.anticheat.core.redstone.LagMachineDetector;
import com.vylorq.anticheat.core.review.ReviewCase;
import com.vylorq.anticheat.core.review.ReviewManager;
import com.vylorq.anticheat.core.staff.*;
import com.vylorq.anticheat.core.stats.AcStats;
import com.vylorq.anticheat.core.storage.StateStore;
import com.vylorq.anticheat.core.trader.*;
import com.vylorq.anticheat.core.util.*;
import com.vylorq.anticheat.core.waiting.WaitingRoom;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Every module's saved data survives a save/load cycle (what happens across a server restart). */
class PersistenceRoundTripTest {
    final Clock.Manual clock = new Clock.Manual(1_700_000_000_000L);
    final UUID p = UUID.randomUUID();

    <T> T roundTrip(Object data, Class<T> type) {
        String json = StateStore.GSON.toJson(data);
        T back = StateStore.GSON.fromJson(json, type);
        assertNotNull(back);
        // Saving again must give the same JSON (nothing lost or reshaped).
        assertEquals(json, StateStore.GSON.toJson(back), type.getSimpleName());
        return back;
    }

    @Test
    void detectionModules() {
        Watchlist w = new Watchlist(null, clock);
        w.add(p, "Steve", "r", "a", Durations.DAY, false);
        assertTrue(new Watchlist(roundTrip(w.data(), Watchlist.Data.class), clock).isWatched(p));

        ExemptList e = new ExemptList(null);
        e.add(p, "Steve", "o", 1);
        assertTrue(new ExemptList(roundTrip(e.data(), ExemptList.Data.class)).isExempt(p));

        ShadowMode s = new ShadowMode(null);
        s.set(p, true);
        assertTrue(new ShadowMode(roundTrip(s.data(), ShadowMode.Data.class)).isShadowed(p));

        ReviewManager r = new ReviewManager(null, clock);
        ReviewCase c = r.create(p, "Steve", true, 70);
        c.flagCounts.put("speed", 3);
        r.decide(c.id, ReviewCase.Decision.WATCH, "a", null);
        ReviewManager r2 = new ReviewManager(roundTrip(r.data(), ReviewManager.Data.class), clock);
        assertEquals(1, r2.openCount());
        assertEquals(2, r2.create(p, "Steve", false, 1).id, "id counter survives");

        AcStats st = new AcStats(null);
        st.recordFlag(p, "Steve", "speed", clock.nowMillis());
        st.recordDismissal(List.of("speed"));
        AcStats st2 = new AcStats(roundTrip(st.data(), AcStats.Data.class));
        for (int i = 0; i < 70; i++) {
            st2.recordFlag(p, "Steve", "fly", clock.nowMillis() + i * Durations.DAY);
        }
        assertEquals(60, st2.data().flagsPerDay.size(), "old days trimmed after reload");
    }

    @Test
    void staffModules() {
        PunishmentManager pm = new PunishmentManager(null, clock);
        pm.add(Punishment.Type.BAN, p, "Steve", "x", "a", Durations.DAY);
        assertTrue(new PunishmentManager(roundTrip(pm.data(), PunishmentManager.Data.class), clock).isBanned(p));

        StaffState ss = new StaffState(null);
        ss.setFrozen(p, true);
        StaffState.SpectateReturn ret = new StaffState.SpectateReturn();
        ret.pos = new Vec3(1, 2, 3);
        ret.world = "w";
        ss.startSpectate(p, ret);
        StaffState ss2 = new StaffState(roundTrip(ss.data(), StaffState.Data.class));
        assertTrue(ss2.isFrozen(p));
        assertEquals(new Vec3(1, 2, 3), ss2.spectating(p).pos);

        Reports rep = new Reports(null);
        Reports.Report rr = new Reports.Report();
        rr.reporter = p;
        rr.suspect = UUID.randomUUID();
        rr.reporterPos = new Vec3(1, 2, 3);
        rep.add(rr, 1, 0);
        assertEquals(1, new Reports(roundTrip(rep.data(), Reports.Data.class)).open().size());

        DeathLog dl = new DeathLog(null, clock);
        DeathRecord d = new DeathRecord();
        d.player = p;
        d.pos = new Vec3(1, 2, 3);
        ItemInfo i = new ItemInfo("minecraft:diamond", 3);
        i.enchantments.put("minecraft:sharpness", 2);
        d.inventory.add(i);
        dl.add(d);
        DeathLog dl2 = new DeathLog(roundTrip(dl.data(), DeathLog.Data.class), clock);
        assertEquals(3, dl2.forPlayer(p, 5).get(0).inventory.get(0).count);

        AdminPins pins = new AdminPins(new HashMap<>(), clock, 3, 1000);
        pins.setPin(p, "1234");
        String json = StateStore.GSON.toJson(pins.data());
        var back = StateStore.GSON.fromJson(json, new com.google.gson.reflect.TypeToken<java.util.Map<UUID, AdminPins.Entry>>() { }.getType());
        AdminPins pins2 = new AdminPins((java.util.Map<UUID, AdminPins.Entry>) back, clock, 3, 1000);
        assertEquals(AdminPins.Result.OK, pins2.login(p, "1234"));
    }

    @Test
    void worldModules() {
        ClaimManager cm = new ClaimManager(null, clock);
        cm.create("Base", "w", 0, 0, 10, 10, p, false, Durations.DAY, 0, 1000);
        cm.setMember(cm.get("base"), p, "Steve", ClaimRole.BUILDER, Durations.HOUR);
        ClaimManager cm2 = new ClaimManager(roundTrip(cm.data(), ClaimManager.Data.class), clock);
        assertEquals(ClaimRole.BUILDER, cm2.get("base").roleOf(p, clock.nowMillis()));

        BarrierManager bm = new BarrierManager(null, clock);
        Barrier b = new Barrier();
        b.name = "b";
        b.world = "w";
        b.sides.put(p, true);
        b.lastGood.put(p, new Vec3(1, 2, 3));
        bm.add(b);
        assertNotNull(new BarrierManager(roundTrip(bm.data(), BarrierManager.Data.class), clock).respawnInside(p, "w"));

        LagMachineDetector lm = new LagMachineDetector(null);
        lm.data().disabled.add("w|1|2|3");
        roundTrip(lm.data(), LagMachineDetector.Data.class);

        JoinGuard jg = new JoinGuard(null);
        jg.recordJoin(p, "Steve", "1.2.3.4");
        assertTrue(new JoinGuard(roundTrip(jg.data(), JoinGuard.Data.class)).isKnown(p));

        Lobby lobby = new Lobby(null);
        lobby.data().area = new Area("w", 0, 0, 0, 5, 5, 5);
        lobby.data().spawn = new Location("w", 1, 2, 3, 4, 5);
        assertTrue(new Lobby(roundTrip(lobby.data(), Lobby.Data.class)).isSet());

        JailManager jm = new JailManager(null, clock);
        jm.setCell("a", new Location("w", 0, 0, 0, 0, 0));
        jm.jail(p, "Steve", "r", "a", Durations.HOUR, null, false);
        assertTrue(new JailManager(roundTrip(jm.data(), JailManager.Data.class), clock).isJailed(p));

        WaitingRoom wr = new WaitingRoom(null, clock);
        wr.start(p, "Steve", true, "ip");
        wr.answerAll(p, new String[]{"a", "b", "c"});
        assertEquals(1, new WaitingRoom(roundTrip(wr.data(), WaitingRoom.Data.class), clock).pending().size());
    }

    @Test
    void gameplayModules() {
        ArenaManager am = new ArenaManager(null, clock);
        Arena a = new Arena();
        a.name = "a";
        a.area = new Area("w", 0, 0, 0, 1, 1, 1);
        a.teamSpawns.add(List.of(new Location("w", 1, 1, 1, 0, 0)));
        am.addArena(a);
        Kit k = new Kit();
        k.name = "k";
        k.items.put(0, "{id:\"minecraft:stone\"}");
        am.saveKit(k);
        PlayerSnapshot snap = new PlayerSnapshot();
        snap.player = p;
        snap.location = new Location("w", 1, 2, 3, 0, 0);
        am.savePending(snap);
        ArenaManager am2 = new ArenaManager(roundTrip(am.data(), ArenaManager.Data.class), clock);
        assertNotNull(am2.takePending(p));
        assertEquals("{id:\"minecraft:stone\"}", am2.kit("k").items.get(0));

        TraderEconomy te = new TraderEconomy(null, clock);
        te.markObtained("minecraft:bread");
        Trader t = new Trader();
        t.entity = UUID.randomUUID();
        TraderEconomy.Settings s = new TraderEconomy.Settings();
        TraderOffer o = new TraderOffer();
        o.id = "minecraft:bread";
        o.stock = 5;
        te.purchase(t, o, p, "ip", s);
        TraderEconomy te2 = new TraderEconomy(roundTrip(te.data(), TraderEconomy.Data.class), clock);
        assertTrue(te2.isObtained("minecraft:bread"));
        assertTrue(te2.demand(o.signature(), s) > 1.0);
        t.specialty = Specialty.FARMER;
        t.location = new Location("w", 0, 0, 0, 0, 0);
        te2.rotate(t, s, List.of(), new SplittableRandom(1));
        Trader t2 = roundTrip(t, Trader.class);
        assertEquals(t.offers.size(), t2.offers.size());
    }
}
