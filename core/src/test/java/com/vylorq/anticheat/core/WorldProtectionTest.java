package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.barrier.BarrierManager;
import com.vylorq.anticheat.core.blocklog.BlockChange;
import com.vylorq.anticheat.core.blocklog.RollbackPlanner;
import com.vylorq.anticheat.core.claims.*;
import com.vylorq.anticheat.core.redstone.LagMachineDetector;
import com.vylorq.anticheat.core.util.BlockPos3;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldProtectionTest {
    final Clock.Manual clock = new Clock.Manual(1_000_000);
    final UUID admin = UUID.randomUUID();
    final UUID builder = UUID.randomUUID();
    final UUID visitor = UUID.randomUUID();
    final UUID stranger = UUID.randomUUID();

    ClaimManager claims() {
        return new ClaimManager(null, clock);
    }

    @Test
    void claimCreationRules() {
        ClaimManager m = claims();
        assertEquals(ClaimManager.CreateResult.OK, m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.DAY, 5, 1_000_000));
        assertEquals(ClaimManager.CreateResult.NAME_TAKEN, m.create("base", "overworld", 100, 100, 120, 120, admin, false, Durations.DAY, 5, 1_000_000));
        assertEquals(ClaimManager.CreateResult.OVERLAP, m.create("B2", "overworld", 23, 0, 40, 20, admin, false, Durations.DAY, 5, 1_000_000), "within min gap");
        assertEquals(ClaimManager.CreateResult.OK, m.create("B3", "overworld", 26, 0, 40, 20, admin, false, Durations.DAY, 5, 1_000_000));
        assertEquals(ClaimManager.CreateResult.OK, m.create("Nether", "the_nether", 0, 0, 20, 20, admin, false, Durations.DAY, 5, 1_000_000));
        assertEquals(ClaimManager.CreateResult.TOO_BIG, m.create("Huge", "overworld", 1000, 1000, 5000, 5000, admin, false, Durations.DAY, 5, 1_000_000));
        assertEquals("base", m.at("overworld", 10, 10).id);
        assertNull(m.at("overworld", 22, 10));
        assertEquals("b3", m.at("overworld", 30.5, 5.5).id);
    }

    @Test
    void rolesAndVisibility() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.PERMANENT, 5, 1_000_000);
        Claim c = m.get("Base");
        m.setMember(c, builder, "B", ClaimRole.BUILDER, Durations.PERMANENT);
        m.setMember(c, visitor, "V", ClaimRole.VISITOR, Durations.PERMANENT);
        assertTrue(m.can(c, builder, false, ClaimAction.BREAK, true));
        assertTrue(m.can(c, builder, false, ClaimAction.CONTAINER, true));
        assertFalse(m.can(c, visitor, false, ClaimAction.BREAK, true));
        assertFalse(m.can(c, visitor, false, ClaimAction.CONTAINER, true));
        assertFalse(m.can(c, visitor, false, ClaimAction.ITEM_FRAME, true));
        assertTrue(m.can(c, visitor, false, ClaimAction.DOOR, true));
        c.settings.visitorDoors = false;
        assertFalse(m.can(c, visitor, false, ClaimAction.DOOR, true));
        assertTrue(m.can(c, stranger, false, ClaimAction.ENTER, true));
        assertFalse(m.can(c, stranger, false, ClaimAction.PLACE, true));
        c.visibility = Claim.Visibility.PRIVATE;
        assertFalse(m.can(c, stranger, false, ClaimAction.ENTER, true));
        c.visibility = Claim.Visibility.PUBLIC;
        c.bans.add(stranger);
        assertFalse(m.can(c, stranger, false, ClaimAction.ENTER, true), "claim ban");
        assertTrue(m.can(c, admin, true, ClaimAction.BREAK, true));
        c.eventLocked = true;
        assertFalse(m.can(c, admin, true, ClaimAction.BREAK, true), "event lock stops everyone");
        assertFalse(m.can(c, builder, false, ClaimAction.PLACE, true));
    }

    @Test
    void managerLimits() {
        assertTrue(ClaimManager.canManageMember(ClaimRole.MANAGER, false, null, ClaimRole.BUILDER));
        assertTrue(ClaimManager.canManageMember(ClaimRole.MANAGER, false, ClaimRole.VISITOR, null));
        assertFalse(ClaimManager.canManageMember(ClaimRole.MANAGER, false, ClaimRole.MANAGER, null));
        assertFalse(ClaimManager.canManageMember(ClaimRole.MANAGER, false, null, ClaimRole.MANAGER));
        assertFalse(ClaimManager.canManageMember(ClaimRole.BUILDER, false, null, ClaimRole.VISITOR));
    }

    @Test
    void temporaryPassExpires() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.PERMANENT, 5, 1_000_000);
        Claim c = m.get("Base");
        m.setMember(c, builder, "B", ClaimRole.BUILDER, 3 * Durations.HOUR);
        assertTrue(m.can(c, builder, false, ClaimAction.BREAK, true));
        clock.advance(3 * Durations.HOUR + 1);
        assertFalse(m.can(c, builder, false, ClaimAction.BREAK, true));
        m.tick(true);
        assertFalse(c.members.containsKey(builder));
    }

    @Test
    void expiryWarningsAndRemoval() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, 2 * Durations.HOUR, 5, 1_000_000);
        Claim c = m.get("Base");
        m.setMember(c, builder, "B", ClaimRole.MANAGER, Durations.PERMANENT);
        assertTrue(m.tick(true).isEmpty());
        clock.advance(Durations.HOUR + 1000);
        assertEquals(ClaimManager.TimerEvent.Kind.WARN_1H, m.tick(true).get(0).kind());
        assertTrue(m.tick(true).isEmpty(), "warn once");
        clock.advance(51 * Durations.MINUTE);
        assertEquals(ClaimManager.TimerEvent.Kind.WARN_10M, m.tick(true).get(0).kind());
        clock.advance(10 * Durations.MINUTE);
        List<ClaimManager.TimerEvent> ev = m.tick(true);
        assertEquals(ClaimManager.TimerEvent.Kind.EXPIRED, ev.get(0).kind());
        assertTrue(c.members.isEmpty(), "all permissions removed");
        assertFalse(c.isActive());
        assertNull(m.at("overworld", 5, 5), "protection off");
        assertTrue(m.can(c, stranger, false, ClaimAction.BREAK, true));
        assertEquals(1, m.pollExpiryNotices(builder).size());
        assertEquals(0, m.pollExpiryNotices(builder).size(), "told once");
        assertTrue(m.reactivate(c, Durations.DAY, 5));
        assertTrue(c.members.isEmpty(), "reactivated with no members");
        assertEquals(1, m.restorePreviousMembers(c));
    }

    @Test
    void pauseTimer() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.HOUR * 2, 5, 1_000_000);
        Claim c = m.get("Base");
        m.pause(c, true);
        clock.advance(Durations.DAY);
        assertTrue(m.tick(true).isEmpty());
        assertTrue(c.isActive());
        m.pause(c, false);
        assertEquals(2 * Durations.HOUR, c.remaining(clock.nowMillis()));
    }

    @Test
    void crossBorder() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.PERMANENT, 5, 1_000_000);
        assertFalse(m.crossAllowed("overworld", -1, 5, 0, 5), "piston/water from outside into claim");
        assertTrue(m.crossAllowed("overworld", 0, 5, -1, 5), "out of claim into wild");
        assertTrue(m.crossAllowed("overworld", 1, 5, 2, 5), "inside");
        assertTrue(m.crossAllowed("overworld", -10, 5, -11, 5), "wild");
    }

    @Test
    void presenceAndEntryLog() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.PERMANENT, 5, 1_000_000);
        assertNull(m.updatePresence(stranger, "S", "overworld", -5, 5));
        ClaimManager.Transition t = m.updatePresence(stranger, "S", "overworld", 5, 5);
        assertEquals("base", t.entered().id);
        assertNull(m.updatePresence(stranger, "S", "overworld", 6, 5));
        assertEquals(1, m.playersIn(m.get("base")).size());
        clock.advance(5000);
        t = m.updatePresence(stranger, "S", "overworld", -5, 5);
        assertEquals("base", t.left().id);
        assertEquals(5000, m.get("base").entryLog.get(0).leftAt - m.get("base").entryLog.get(0).enteredAt);
    }

    @Test
    void griefAlert() {
        ClaimManager m = claims();
        m.create("Base", "overworld", 0, 0, 20, 20, admin, false, Durations.PERMANENT, 5, 1_000_000);
        Claim c = m.get("base");
        for (int i = 0; i < 4; i++) {
            assertFalse(m.griefAttempt(stranger, c, 5, 30_000));
        }
        assertTrue(m.griefAttempt(stranger, c, 5, 30_000));
    }

    @Test
    void barrierBlocksCrossingBothWays() {
        BarrierManager bm = new BarrierManager(null, clock);
        Barrier b = new Barrier();
        b.name = "arena";
        b.world = "overworld";
        b.shape = Barrier.Shape.CYLINDER;
        b.cx = 0;
        b.cz = 0;
        b.radius = 10;
        b.adminsPass = true;
        bm.add(b);
        UUID in = UUID.randomUUID();
        UUID out = UUID.randomUUID();
        assertTrue(bm.check(in, false, "overworld", new Vec3(0, 64, 0), "overworld", new Vec3(1, 64, 0)).allowed());
        assertFalse(bm.check(in, false, "overworld", new Vec3(9, 64, 0), "overworld", new Vec3(11, 64, 0)).allowed(), "can't get out");
        assertTrue(bm.check(out, false, "overworld", new Vec3(20, 64, 0), "overworld", new Vec3(19, 64, 0)).allowed());
        assertFalse(bm.check(out, false, "overworld", new Vec3(11, 64, 0), "overworld", new Vec3(9, 64, 0)).allowed(), "can't get in");
        assertFalse(bm.check(out, false, "overworld", new Vec3(20, 64, 0), "overworld", new Vec3(0, 64, 0)).allowed(), "teleport/pearl in");
        assertFalse(bm.check(in, false, "overworld", new Vec3(5, 64, 0), "overworld", new Vec3(5, 400, 5000)).allowed(), "tp out");
        BarrierManager.Verdict wrong = bm.check(in, false, "overworld", new Vec3(50, 64, 0), "overworld", new Vec3(50, 64, 0));
        assertTrue(wrong.wrongSide(), "found outside after e.g. relog");
        assertNotNull(wrong.sendBackTo());
        assertTrue(bm.check(in, true, "overworld", new Vec3(9, 64, 0), "overworld", new Vec3(11, 64, 0)).allowed(), "admin passes");
        assertNotNull(bm.respawnInside(in, "overworld"));
        assertNull(bm.respawnInside(out, "overworld"));
    }

    @Test
    void barrierShapes() {
        Barrier box = new Barrier();
        box.world = "w";
        box.shape = Barrier.Shape.BOX;
        box.minX = 0; box.maxX = 9; box.minZ = 0; box.maxZ = 9;
        assertTrue(box.contains("w", 9.9, 300, 0.1));
        assertFalse(box.contains("w", 10.1, 64, 5));
        Barrier sphere = new Barrier();
        sphere.world = "w";
        sphere.shape = Barrier.Shape.SPHERE;
        sphere.cy = 64; sphere.radius = 5;
        assertTrue(sphere.contains("w", 0, 64, 0));
        assertFalse(sphere.contains("w", 0, 70, 0));
    }

    @Test
    void redstoneClockDisabledAfterSustain() {
        LagMachineDetector d = new LagMachineDetector(null);
        LagMachineDetector.Limits l = new LagMachineDetector.Limits(60, 30, 80, 40, 20);
        BlockPos3 p = new BlockPos3(1, 64, 1);
        LagMachineDetector.Alert alert = null;
        for (long t = 0; t < 40_000 && alert == null; t += 100) {
            alert = d.onRedstoneToggle("w", p, t, l);
        }
        assertNotNull(alert);
        assertTrue(d.isDisabled("w", p));
        // Slow clock (legit): never alerts.
        BlockPos3 q = new BlockPos3(5, 64, 5);
        for (long t = 0; t < 120_000; t += 400) {
            assertNull(d.onRedstoneToggle("w", q, t, l));
        }
        // Whitelisted farm
        LagMachineDetector.Whitelist wl = new LagMachineDetector.Whitelist();
        wl.world = "w"; wl.minX = 100; wl.maxX = 200; wl.minY = -64; wl.maxY = 320; wl.minZ = 100; wl.maxZ = 200;
        d.data().whitelist.add(wl);
        BlockPos3 farm = new BlockPos3(150, 64, 150);
        for (long t = 0; t < 60_000; t += 50) {
            assertNull(d.onRedstoneToggle("w", farm, t, l));
        }
    }

    @Test
    void pistonLimitPerChunk() {
        LagMachineDetector d = new LagMachineDetector(null);
        LagMachineDetector.Limits l = new LagMachineDetector.Limits(60, 30, 5, 40, 20);
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (d.onPistonMove("w", new BlockPos3(i, 64, 0), 1000, l)) allowed++;
        }
        assertEquals(5, allowed);
        assertTrue(d.onPistonMove("w", new BlockPos3(0, 64, 0), 2000, l), "new second");
        double[] v = LagMachineDetector.capVelocity(30, 0, 40, 5);
        assertEquals(5, Math.sqrt(v[0] * v[0] + v[2] * v[2]), 1e-9);
    }

    @Test
    void rollbackRestoresOldestState() {
        BlockChange a = change(1, "minecraft:stone", "minecraft:air");
        BlockChange b = change(2, "minecraft:air", "minecraft:tnt");
        List<RollbackPlanner.Op> ops = RollbackPlanner.rollback(List.of(b, a));
        assertEquals(1, ops.size());
        assertEquals("minecraft:stone", ops.get(0).state());
        a.rolledBack = true;
        b.rolledBack = true;
        List<RollbackPlanner.Op> redo = RollbackPlanner.restore(List.of(a, b));
        assertEquals("minecraft:tnt", redo.get(0).state());
    }

    static BlockChange change(long time, String before, String after) {
        BlockChange c = new BlockChange();
        c.time = time;
        c.world = "w";
        c.x = 1; c.y = 2; c.z = 3;
        c.kind = BlockChange.Kind.BREAK;
        c.before = before;
        c.after = after;
        return c;
    }
}
