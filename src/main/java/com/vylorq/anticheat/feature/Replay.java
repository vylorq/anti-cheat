package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.evidence.EvidenceClip;
import com.vylorq.anticheat.core.evidence.EvidenceEvent;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-game replay of an evidence clip: a ghost of the player, seen only by the staff member watching, walks, looks
 * and swings exactly as recorded. Hits, clicks, blocks and flags show on the action bar as they happen.
 */
public final class Replay {
    private Replay() {
    }

    private static final class Session {
        final UUID viewer;
        final EvidenceClip clip;
        final List<EvidenceEvent> events;
        final WatcherFigure ghost;
        final String world;
        final long start;
        final long end;
        double at;
        double speed = 1.0;
        boolean paused;
        int next;

        Session(UUID viewer, EvidenceClip clip, List<EvidenceEvent> events, WatcherFigure ghost, String world) {
            this.viewer = viewer;
            this.clip = clip;
            this.events = events;
            this.ghost = ghost;
            this.world = world;
            this.start = events.get(0).time;
            this.end = events.get(events.size() - 1).time;
            this.at = start;
        }
    }

    private static final Map<UUID, Session> RUNNING = new ConcurrentHashMap<>();

    public static boolean watching(ServerPlayerEntity p) {
        return RUNNING.containsKey(p.getUuid());
    }

    /** Where the clip starts (its first movement), or null when it has none. */
    public static Vec3d startOf(EvidenceClip clip) {
        for (EvidenceEvent e : clip.events) {
            if (e.type == EvidenceEvent.Type.MOVE) {
                return new Vec3d(e.x, e.y, e.z);
            }
        }
        return null;
    }

    /** The world the clip was recorded in, or null if it isn't known (older clips). */
    public static ServerWorld worldOf(EvidenceClip clip) {
        return clip.world == null ? null : Mc.world(Ac.server(), clip.world);
    }

    /** Starts watching; the caller already made sure the viewer is near the scene. */
    public static void start(ServerPlayerEntity viewer, EvidenceClip clip) {
        stop(viewer, false);
        List<EvidenceEvent> events = new ArrayList<>(clip.events);
        events.sort(Comparator.comparingLong(e -> e.time));
        events.removeIf(e -> e.type != EvidenceEvent.Type.MOVE && e.x == 0 && e.y == 0 && e.z == 0 && e.type != EvidenceEvent.Type.FLAG);
        Vec3d first = startOf(clip);
        if (events.isEmpty() || first == null) {
            Msg.send(viewer, "replay.empty");
            return;
        }
        ServerWorld w = viewer.getEntityWorld();
        WatcherFigure ghost = WatcherFigure.ghost(w, clip.player, clip.playerName);
        ghost.at(first, 0, 0);
        ghost.show(viewer);
        Session s = new Session(viewer.getUuid(), clip, events, ghost, Mc.worldId(w));
        RUNNING.put(viewer.getUuid(), s);
        Staff.log(viewer, "evidence-replay", clip.player, clip.playerName, clip.id);
        controls(viewer, s);
    }

    private static void controls(ServerPlayerEntity p, Session s) {
        MutableText t = Msg.typed(Msg.Type.INFO, Msg.trFor(p, "replay.started", s.clip.playerName, (s.end - s.start) / 1000));
        t.append(" ").append(Msg.button("§e[" + Msg.trFor(p, "replay.pause") + "]", "/replay pause", Msg.trFor(p, "replay.pause")));
        t.append(" ").append(Msg.button("§b[x0.5]", "/replay speed 0.5", "x0.5"));
        t.append(" ").append(Msg.button("§b[x1]", "/replay speed 1", "x1"));
        t.append(" ").append(Msg.button("§b[x2]", "/replay speed 2", "x2"));
        t.append(" ").append(Msg.button("§a[" + Msg.trFor(p, "replay.restart") + "]", "/replay restart", Msg.trFor(p, "replay.restart")));
        t.append(" ").append(Msg.button("§c[" + Msg.trFor(p, "replay.stop") + "]", "/replay stop", Msg.trFor(p, "replay.stop")));
        Msg.sendRaw(p, t);
    }

    public static void stop(ServerPlayerEntity viewer, boolean say) {
        Session s = RUNNING.remove(viewer.getUuid());
        if (s != null) {
            s.ghost.hide(viewer);
            if (say) {
                Msg.send(viewer, "replay.stopped");
            }
        }
    }

    public static void pause(ServerPlayerEntity viewer) {
        Session s = RUNNING.get(viewer.getUuid());
        if (s == null) {
            Msg.send(viewer, "replay.none");
            return;
        }
        s.paused = !s.paused;
        Msg.send(viewer, s.paused ? "replay.paused" : "replay.resumed");
    }

    public static void speed(ServerPlayerEntity viewer, double speed) {
        Session s = RUNNING.get(viewer.getUuid());
        if (s == null) {
            Msg.send(viewer, "replay.none");
            return;
        }
        s.speed = Math.max(0.1, Math.min(4, speed));
        s.paused = false;
        Msg.send(viewer, "replay.speed", s.speed);
    }

    public static void restart(ServerPlayerEntity viewer) {
        Session s = RUNNING.get(viewer.getUuid());
        if (s == null) {
            Msg.send(viewer, "replay.none");
            return;
        }
        s.at = s.start;
        s.next = 0;
        s.paused = false;
        Msg.send(viewer, "replay.restarted");
    }

    /** Every tick: move the ghosts forward. */
    public static void tick() {
        for (Session s : RUNNING.values().toArray(new Session[0])) {
            ServerPlayerEntity v = Ac.server().getPlayerManager().getPlayer(s.viewer);
            if (v == null || !Mc.worldId(v.getEntityWorld()).equals(s.world)) {
                RUNNING.remove(s.viewer);
                if (v != null) {
                    // Changed world: the ghost is gone with the old world anyway.
                    Msg.send(v, "replay.stopped");
                }
                continue;
            }
            if (s.paused) {
                continue;
            }
            s.at += 50 * s.speed;
            String bar = null;
            while (s.next < s.events.size() && s.events.get(s.next).time <= s.at) {
                EvidenceEvent e = s.events.get(s.next++);
                switch (e.type) {
                    case MOVE -> s.ghost.moveTo(v, new Vec3d(e.x, e.y, e.z), e.yaw, e.pitch);
                    case HIT, CLICK, BREAK, PLACE -> {
                        s.ghost.swing(v);
                        if (e.type != EvidenceEvent.Type.CLICK) {
                            bar = "§f" + e.describe();
                        }
                    }
                    case FLAG -> bar = "§c" + e.describe();
                    default -> {
                        if (e.text != null && !e.text.isEmpty()) {
                            bar = "§7" + e.describe();
                        }
                    }
                }
            }
            long secs = (long) ((s.at - s.start) / 1000);
            String clock = "§8[" + secs + "/" + (s.end - s.start) / 1000 + "s x" + s.speed + "] ";
            if (bar != null) {
                v.sendMessage(Text.literal(clock + bar), true);
            }
            if (s.next >= s.events.size()) {
                RUNNING.remove(s.viewer);
                s.ghost.hide(v);
                Msg.send(v, "replay.finished");
            }
        }
    }
}
