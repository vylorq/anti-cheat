package com.vylorq.anticheat.core.barrier;

import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Barriers: decides whether a move or teleport crosses a wall. */
public final class BarrierManager {
    public static final class Data {
        public Map<String, Barrier> barriers = new LinkedHashMap<>();
    }

    /** Verdict for a move. */
    public record Verdict(boolean allowed, Barrier barrier, Vec3 sendBackTo, boolean wrongSide) {
        static final Verdict OK = new Verdict(true, null, null, false);
    }

    private final Data data;
    private final Clock clock;

    public BarrierManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized boolean add(Barrier b) {
        String key = b.name.toLowerCase(Locale.ROOT);
        if (data.barriers.containsKey(key)) {
            return false;
        }
        b.createdAt = clock.nowMillis();
        data.barriers.put(key, b);
        return true;
    }

    public synchronized Barrier get(String name) {
        return data.barriers.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean remove(String name) {
        return data.barriers.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public synchronized List<Barrier> list() {
        return new ArrayList<>(data.barriers.values());
    }

    /** Removes expired barriers. @return removed ones. */
    public synchronized List<Barrier> tick() {
        long now = clock.nowMillis();
        List<Barrier> removed = new ArrayList<>();
        data.barriers.values().removeIf(b -> {
            if (Durations.isExpired(b.expiresAt, now)) {
                removed.add(b);
                return true;
            }
            return false;
        });
        return removed;
    }

    /**
     * Checks a move (walking, flying, vehicles, knockback, pearls, teleports: everything goes through this).
     *
     * @param bypass admin who may pass (only where the barrier allows it)
     */
    public synchronized Verdict check(UUID player, boolean bypass, String fromWorld, Vec3 from, String toWorld, Vec3 to) {
        for (Barrier b : data.barriers.values()) {
            if (bypass && b.adminsPass) {
                continue;
            }
            boolean inFrom = b.contains(fromWorld, from.x(), from.y(), from.z());
            boolean inTo = b.contains(toWorld, to.x(), to.y(), to.z());
            Boolean side = b.sides.get(player);
            if (side == null) {
                // First time we see this player near the barrier: the side they're on is theirs.
                side = inFrom;
                b.sides.put(player, side);
            }
            if (inFrom != side) {
                // Found on the wrong side (e.g. logged in, respawned, or a teleport from another mod).
                Vec3 back = b.lastGood.getOrDefault(player, side ? b.center() : null);
                return new Verdict(false, b, back, true);
            }
            if (inTo != side) {
                return new Verdict(false, b, from, false);
            }
            b.lastGood.put(player, to);
        }
        return Verdict.OK;
    }

    /** Where a player who died should respawn if they belong inside a barrier, else null. */
    public synchronized Vec3 respawnInside(UUID player, String world) {
        for (Barrier b : data.barriers.values()) {
            if (Boolean.TRUE.equals(b.sides.get(player))) {
                Vec3 last = b.lastGood.get(player);
                return last != null ? last : b.center();
            }
        }
        return null;
    }

    public synchronized String worldOfInside(UUID player) {
        for (Barrier b : data.barriers.values()) {
            if (Boolean.TRUE.equals(b.sides.get(player))) {
                return b.world;
            }
        }
        return null;
    }

    /** Barriers within {@code dist} of a point (for the particle wall). */
    public synchronized List<Barrier> nearWall(String world, Vec3 p, double dist) {
        List<Barrier> out = new ArrayList<>();
        for (Barrier b : data.barriers.values()) {
            if (b.world.equals(world) && b.distanceToWall(p.x(), p.y(), p.z()) <= dist) {
                out.add(b);
            }
        }
        return out;
    }
}
