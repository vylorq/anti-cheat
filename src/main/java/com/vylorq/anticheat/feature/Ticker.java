package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.PlayerSession;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.gui.ClaimMenu;
import com.vylorq.anticheat.gui.MenuHandler;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Tps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.time.LocalDate;

/** Everything that runs on a timer. */
public final class Ticker {
    private static long ticks;
    private static String lastBackupDay = "";

    private Ticker() {
    }

    public static void tick(MinecraftServer server) {
        Tps.onTick();
        Ac ac = Ac.get();
        if (ac == null) {
            return;
        }
        ticks++;
        for (PlayerSession s : ac.sessions.values()) {
            s.tick();
        }
        Trades.tick();
        BuilderTools.tick();
        Extras.tick();
        Watcher.tick(server);
        if (ticks % 20 == 0) {
            everySecond(ac);
        }
        if (ticks % 100 == 0) {
            Xray.flushAlerts();
            ac.saveDirty();
        }
        if (ticks % 200 == 0) {
            Redstone.scanEntities();
        }
        if (ticks % 1200 == 0) {
            Traders.tick(true);
            com.vylorq.anticheat.gui.Snapshots.tickMinute();
            LobbyFeature.tickMinute();
            ac.watchlist.purgeExpired();
            ac.reports.data();
        }
        if (ticks % 72000 == 0) {
            hourly(ac);
        }
    }

    private static void everySecond(Ac ac) {
        com.vylorq.anticheat.ui.BossBars.tick();
        MenuHandler.tickLive();
        Jail.tick();
        EndLock.tick(ac.server);
        BuilderMode.tick();
        ScareWarning.tick();
        WorldEvents.tick(ac.server);
        Teams.tick();
        Markets.tick();
        Shops.tick();
        Booths.tick();
        if (ticks % 200 == 0) {
            Teams.syncTags();
        }
        Extras.nightlyWorldBackup();
        Claims.tick();
        Barriers.tick();
        Arenas.tick();
        Traders.tick(false);
        ClaimMenu.BorderView.tick();
        Extras.tickRestarts();
        int scanEvery = Math.max(5, Ac.config().illegalItems.scanIntervalSeconds);
        for (ServerPlayerEntity p : ac.server.getPlayerManager().getPlayerList()) {
            PlayerSession s = Ac.session(p);
            ContainerLog.check(p);
            WaitingRoomFeature.tick(p);
            LobbyFeature.tickPlayer(p);
            Claims.enforceInside(p);
            if (Tools.is(p.getMainHandStack(), Tools.CLAIM_STICK)) {
                Claims.showBorders(p);
            }
            if (++s.trailTimer >= 5) {
                s.trailTimer = 0;
                s.trail.addLast(new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + " " + Mc.worldId(p.getEntityWorld())
                        .replace("minecraft:", "") + " " + Mc.vec(p.getEntityPos()).formatExact());
                while (s.trail.size() > 120) {
                    s.trail.pollFirst();
                }
                Dupes.sample(p);
            }
            if (ticks % 60 == 0) {
                ItemBlacklist.clean(p);
            }
            if (++s.dupeSampleTimer >= scanEvery) {
                s.dupeSampleTimer = 0;
                Illegal.scanPlayer(p);
            }
        }
    }

    private static void hourly(Ac ac) {
        var cfg = Ac.config();
        Traders.economyWarnings();
        long now = System.currentTimeMillis();
        long cutoff = now - cfg.storage.logRetentionDays * Durations.DAY;
        long blockCutoff = now - cfg.staff.blockLogRetentionDays * Durations.DAY;
        ac.deaths.purge(cfg.deaths.retentionDays, cfg.deaths.maxPerPlayer);
        Ac.markDirty("deaths");
        Thread t = new Thread(() -> {
            try {
                ac.logs.flush();
                ac.db.purge(cutoff, blockCutoff);
                ac.clips.purge(now - cfg.evidence.retentionDays * Durations.DAY, cfg.evidence.maxClipsPerPlayer);
                String today = LocalDate.now().toString();
                if (cfg.storage.dailyBackups && !today.equals(lastBackupDay)) {
                    lastBackupDay = today;
                    ac.db.backup(ac.dir.resolve("backups"), cfg.storage.backupsToKeep);
                }
            } catch (Exception e) {
                Ac.LOG.error("Hourly maintenance failed", e);
            }
        }, "Vigil-Maintenance");
        t.setDaemon(true);
        t.start();
    }
}
