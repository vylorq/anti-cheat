package com.vylorq.anticheat.ui;

import com.vylorq.anticheat.Ac;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Timer bars (34.10): jail time, claim entry, restarts and maintenance. A player never sees more than one bar from
 * Vigil: a new one replaces the old, unless the old one has higher priority and is still running.
 */
public final class BossBars {
    private BossBars() {
    }

    /** Higher wins when two want the screen at once. */
    public enum Kind {
        CLAIM(1, BossBar.Color.GREEN),
        JAIL(2, BossBar.Color.WHITE),
        MAINTENANCE(3, BossBar.Color.YELLOW),
        RESTART(4, BossBar.Color.YELLOW);

        final int priority;
        final BossBar.Color color;

        Kind(int priority, BossBar.Color color) {
            this.priority = priority;
            this.color = color;
        }
    }

    private static final class Shown {
        final ServerBossBar bar;
        final Kind kind;
        long until;

        Shown(ServerBossBar bar, Kind kind) {
            this.bar = bar;
            this.kind = kind;
        }
    }

    private static final Map<UUID, Shown> SHOWN = new HashMap<>();

    /**
     * Shows or updates a bar.
     *
     * @param seconds how long it stays without being updated again
     */
    public static void show(ServerPlayerEntity p, Kind kind, Text text, float progress, int seconds) {
        if (!Ac.running() || !Ac.config().general.bossBars) {
            return;
        }
        Shown cur = SHOWN.get(p.getUuid());
        if (cur != null && cur.kind != kind) {
            if (cur.kind.priority > kind.priority) {
                return;
            }
            cur.bar.clearPlayers();
            SHOWN.remove(p.getUuid());
            cur = null;
        }
        if (cur == null) {
            cur = new Shown(new ServerBossBar(text, kind.color, BossBar.Style.PROGRESS), kind);
            cur.bar.addPlayer(p);
            SHOWN.put(p.getUuid(), cur);
        }
        cur.bar.setName(text);
        cur.bar.setPercent(Math.max(0f, Math.min(1f, progress)));
        cur.until = System.currentTimeMillis() + seconds * 1000L;
    }

    public static void hide(ServerPlayerEntity p, Kind kind) {
        Shown cur = SHOWN.get(p.getUuid());
        if (cur != null && cur.kind == kind) {
            cur.bar.clearPlayers();
            SHOWN.remove(p.getUuid());
        }
    }

    public static void forget(UUID id) {
        Shown cur = SHOWN.remove(id);
        if (cur != null) {
            cur.bar.clearPlayers();
        }
    }

    /** Called every second: removes bars that weren't updated in time. */
    public static void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Shown>> it = SHOWN.entrySet().iterator(); it.hasNext(); ) {
            Shown s = it.next().getValue();
            if (now > s.until) {
                s.bar.clearPlayers();
                it.remove();
            }
        }
    }

    public static void reset() {
        for (Shown s : SHOWN.values()) {
            s.bar.clearPlayers();
        }
        SHOWN.clear();
    }
}
