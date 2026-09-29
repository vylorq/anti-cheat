package com.vylorq.anticheat.core.redstone;

import com.vylorq.anticheat.core.util.BlockPos3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OP redstone and lag machine protection (section 18): fast redstone clocks, piston spam, TNT and dispenser
 * floods per chunk. Whitelisted areas (approved farms) are ignored. Not thread-safe: call on the server thread.
 */
public final class LagMachineDetector {
    public record Limits(int clockMaxTogglesPer10s, int clockSustainSeconds, int maxPistonMovesPerChunkPerSecond,
                         int maxPrimedTntPerChunk, int maxDispenserFiresPerChunkPerSecond) {
    }

    public record Alert(String kind, String world, BlockPos3 pos, String detail) {
    }

    public static final class Whitelist {
        public String name;
        public String world;
        public int minX, minY, minZ, maxX, maxY, maxZ;

        public boolean contains(String w, BlockPos3 p) {
            return world.equals(w) && p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY
                    && p.z() >= minZ && p.z() <= maxZ;
        }
    }

    public static final class Data {
        public List<Whitelist> whitelist = new ArrayList<>();
        /** Positions disabled as lag machines, "world|x|y|z". */
        public Set<String> disabled = new HashSet<>();
    }

    private final Data data;
    private final Map<String, Deque<Long>> toggles = new HashMap<>();
    private final Map<String, Long> clockSince = new HashMap<>();
    private final Map<String, int[]> pistonPerChunk = new HashMap<>();
    private final Map<String, int[]> dispenserPerChunk = new HashMap<>();
    private final Map<String, Integer> tntPerChunk = new HashMap<>();
    private long currentSecond = -1;

    public LagMachineDetector(Data data) {
        this.data = data == null ? new Data() : data;
    }

    public Data data() {
        return data;
    }

    private static String key(String world, BlockPos3 p) {
        return world + "|" + p.x() + "|" + p.y() + "|" + p.z();
    }

    private static String chunkKey(String world, BlockPos3 p) {
        return world + "|" + (p.x() >> 4) + "|" + (p.z() >> 4);
    }

    public boolean whitelisted(String world, BlockPos3 p) {
        for (Whitelist w : data.whitelist) {
            if (w.contains(world, p)) {
                return true;
            }
        }
        return false;
    }

    public boolean isDisabled(String world, BlockPos3 p) {
        return data.disabled.contains(key(world, p));
    }

    public void enable(String world, BlockPos3 p) {
        data.disabled.remove(key(world, p));
    }

    private void rollSecond(long nowMillis) {
        long sec = nowMillis / 1000;
        if (sec != currentSecond) {
            currentSecond = sec;
            pistonPerChunk.clear();
            dispenserPerChunk.clear();
        }
    }

    /**
     * A redstone component (repeater, comparator, observer, torch, wire) changed state.
     *
     * @return an alert when it's a clock that switched too fast for too long (and it gets disabled).
     */
    public Alert onRedstoneToggle(String world, BlockPos3 p, long nowMillis, Limits l) {
        if (whitelisted(world, p)) {
            return null;
        }
        String k = key(world, p);
        Deque<Long> d = toggles.computeIfAbsent(k, x -> new ArrayDeque<>());
        d.addLast(nowMillis);
        while (!d.isEmpty() && d.peekFirst() < nowMillis - 10_000) {
            d.pollFirst();
        }
        if (d.size() > l.clockMaxTogglesPer10s()) {
            long since = clockSince.computeIfAbsent(k, x -> nowMillis);
            if (nowMillis - since >= l.clockSustainSeconds() * 1000L) {
                clockSince.remove(k);
                toggles.remove(k);
                data.disabled.add(k);
                return new Alert("redstone clock", world, p, d.size() + " toggles in 10s for " + l.clockSustainSeconds() + "s");
            }
        } else {
            clockSince.remove(k);
        }
        return null;
    }

    /** @return true if the piston move may happen. */
    public boolean onPistonMove(String world, BlockPos3 p, long nowMillis, Limits l) {
        if (whitelisted(world, p)) {
            return true;
        }
        rollSecond(nowMillis);
        int[] c = pistonPerChunk.computeIfAbsent(chunkKey(world, p), x -> new int[1]);
        return ++c[0] <= l.maxPistonMovesPerChunkPerSecond();
    }

    /** @return true if the dispenser may fire. */
    public boolean onDispense(String world, BlockPos3 p, long nowMillis, Limits l) {
        if (whitelisted(world, p)) {
            return true;
        }
        rollSecond(nowMillis);
        int[] c = dispenserPerChunk.computeIfAbsent(chunkKey(world, p), x -> new int[1]);
        return ++c[0] <= l.maxDispenserFiresPerChunkPerSecond();
    }

    /** Primed TNT per chunk: call with the live count from the world. @return true if another may be primed. */
    public boolean mayPrimeTnt(String world, BlockPos3 p, int liveInChunk, Limits l) {
        if (whitelisted(world, p)) {
            return true;
        }
        tntPerChunk.put(chunkKey(world, p), liveInChunk);
        return liveInChunk < l.maxPrimedTntPerChunk();
    }

    /** Scales a velocity down to the cap. */
    public static double[] capVelocity(double vx, double vy, double vz, double max) {
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len <= max || len == 0) {
            return new double[]{vx, vy, vz};
        }
        double f = max / len;
        return new double[]{vx * f, vy * f, vz * f};
    }

    /** Entity counts per chunk that exceed limits. Returns a description, or null. */
    public static String entityOverload(int total, int minecarts, int items, int armorStands, int maxTotal, int maxCarts,
                                        int maxItems, int maxStands) {
        if (total > maxTotal) {
            return total + " entities";
        }
        if (minecarts > maxCarts) {
            return minecarts + " minecarts";
        }
        if (items > maxItems) {
            return items + " items";
        }
        if (armorStands > maxStands) {
            return armorStands + " armor stands";
        }
        return null;
    }
}
