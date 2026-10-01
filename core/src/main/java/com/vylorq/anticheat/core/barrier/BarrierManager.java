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
        /** 2: sides decided by the spawn side (older data had players stuck on the wrong side, so it's cleared once). */
        public int version;
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
        if (this.data.version < 2) {
            for (Barrier b : this.data.barriers.values()) {
                b.sides.clear();
                b.lastGood.clear();
            }
            this.data.version = 2;
        }
    }

    private volatile String spawnWorld;
    private volatile Vec3 spawn;

    /** The world spawn (where new and respawning players appear), kept up to date by the server. */
    public void setSpawn(String world, Vec3 pos) {
        this.spawnWorld = world;
        this.spawn = pos;
    }

    private boolean spawnInside(Barrier b) {
        Vec3 s = spawn;
        return s != null && spawnWorld != null && b.contains(spawnWorld, s.x(), s.y(), s.z());
    }

    /** The side a player belongs on the first time they're seen near a barrier. */
    private boolean firstSide(Barrier b, boolean inFrom) {
        String mode = b.newPlayers == null ? "auto" : b.newPlayers;
        return switch (mode) {
            case "inside" -> true;
            case "outside" -> false;
            default -> spawnInside(b) || inFrom;
        };
    }

    /** A spot on the given side to send someone back to (never null). Heights are fixed up by the server. */
    public synchronized Vec3 safeSpot(Barrier b, UUID player, boolean inside, Vec3 near) {
        Vec3 last = b.lastGood.get(player);
        if (last != null && b.contains(b.world, last.x(), last.y(), last.z()) == inside && near != null
                && Math.abs(last.x() - near.x()) + Math.abs(last.z() - near.z()) < 64) {
            return last;
        }
        if (inside) {
            Vec3 s = spawn;
            return spawnInside(b) ? s : b.center();
        }
        // Just outside the nearest wall.
        Vec3 p = near != null ? near : b.center();
        return switch (b.shape) {
            case BOX, CUBE -> {
                double toW = p.x() - b.minX;
                double toE = b.maxX + 1 - p.x();
                double toN = p.z() - b.minZ;
                double toS = b.maxZ + 1 - p.z();
                double m = Math.min(Math.min(toW, toE), Math.min(toN, toS));
                if (m == toW) {
                    yield new Vec3(b.minX - 2, p.y(), p.z());
                } else if (m == toE) {
                    yield new Vec3(b.maxX + 3, p.y(), p.z());
                } else if (m == toN) {
                    yield new Vec3(p.x(), p.y(), b.minZ - 2);
                }
                yield new Vec3(p.x(), p.y(), b.maxZ + 3);
            }
            case CYLINDER, SPHERE -> {
                double dx = p.x() - b.cx;
                double dz = p.z() - b.cz;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1e-3) {
                    dx = 1;
                    dz = 0;
                    len = 1;
                }
                double r = b.radius + 2;
                yield new Vec3(b.cx + dx / len * r, p.y(), b.cz + dz / len * r);
            }
        };
    }

    /** Forgets which side everyone belongs on (they're decided again). */
    public synchronized void resetSides(Barrier b) {
        b.sides.clear();
        b.lastGood.clear();
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
                // First time we see this player: they belong on the spawn's side (or where they are).
                side = firstSide(b, inFrom);
                b.sides.put(player, side);
            }
            if (inFrom != side) {
                // Found on the wrong side (joined, respawned, spawned outside it, or teleported by something else).
                return new Verdict(false, b, safeSpot(b, player, side, from), true);
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
                // The world spawn if it's inside, else the middle (never a corner where they last stood).
                return spawnInside(b) ? spawn : b.center();
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
