package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.blocklog.BlockChange;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.claims.ClaimRole;
import com.vylorq.anticheat.core.detect.Watchlist;
import com.vylorq.anticheat.core.storage.Database;
import com.vylorq.anticheat.core.storage.LogWriter;
import com.vylorq.anticheat.core.storage.StateStore;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @Test
    void stateRoundTripsThroughSqlite(@TempDir Path dir) throws Exception {
        Clock.Manual clock = new Clock.Manual(1000);
        UUID p = UUID.randomUUID();
        try (Database db = Database.openSqlite(dir.resolve("ac.db"))) {
            StateStore store = new StateStore(db, e -> fail(e));
            ClaimManager cm = new ClaimManager(null, clock);
            cm.create("Base", "minecraft:overworld", 0, 0, 10, 10, p, true, Durations.DAY, 5, 1000);
            cm.setMember(cm.get("base"), p, "Steve", ClaimRole.MANAGER, Durations.PERMANENT);
            Watchlist w = new Watchlist(null, clock);
            w.add(p, "Steve", "test", "admin", Durations.PERMANENT, false);
            store.save("claims", cm.data());
            store.save("watchlist", w.data());
            store.close();
            ClaimManager.Data loaded = new StateStore(db, e -> fail(e)).load("claims", ClaimManager.Data.class, null);
            ClaimManager cm2 = new ClaimManager(loaded, clock);
            assertEquals(ClaimRole.MANAGER, cm2.get("base").roleOf(p, clock.nowMillis()));
            assertEquals("base", cm2.at("minecraft:overworld", 5, 5).id, "index rebuilt");
            Watchlist.Data wd = new StateStore(db, e -> fail(e)).load("watchlist", Watchlist.Data.class, null);
            assertTrue(new Watchlist(wd, clock).isWatched(p));
            assertNull(new StateStore(db, e -> fail(e)).load("missing", Watchlist.Data.class, null));
        }
    }

    @Test
    void logsBatchAndRollbackQueries(@TempDir Path dir) throws Exception {
        UUID griefer = UUID.randomUUID();
        try (Database db = Database.openSqlite(dir.resolve("ac.db"))) {
            LogWriter w = new LogWriter(db, e -> fail(e));
            for (int i = 0; i < 50; i++) {
                BlockChange c = new BlockChange();
                c.time = 1000 + i;
                c.actor = griefer;
                c.actorName = "Griefer";
                c.world = "minecraft:overworld";
                c.x = i;
                c.y = 64;
                c.z = 0;
                c.kind = BlockChange.Kind.BREAK;
                c.before = "minecraft:stone";
                c.after = "minecraft:air";
                w.block(c);
            }
            w.staff(1, UUID.randomUUID(), "Admin", "ban", griefer, "Griefer", "griefing");
            w.flag(2, griefer, "Griefer", "speed", 1.5, 30, "x");
            w.close();
            List<BlockChange> changes = db.blockChanges(griefer, 0, "minecraft:overworld", 10, 0, 5, false, 1000);
            assertEquals(11, changes.size(), "radius filter");
            db.setRolledBack(changes.stream().map(c -> c.id).toList(), true);
            assertEquals(11, db.blockChanges(griefer, 0, null, null, null, null, true, 1000).size());
            assertEquals(39, db.blockChanges(griefer, 0, null, null, null, null, false, 1000).size());
            assertEquals(1, db.blockHistory("minecraft:overworld", 3, 64, 0, 10).size());
            assertEquals(1, db.staffLog(10, null).size());
            assertEquals("speed", db.flags(griefer, 10).get(0).a());
            Path backup = db.backup(dir.resolve("backups"), 2);
            assertTrue(Files.exists(backup));
            assertTrue(db.purge(Long.MAX_VALUE, Long.MAX_VALUE) > 0);
        }
    }
}
