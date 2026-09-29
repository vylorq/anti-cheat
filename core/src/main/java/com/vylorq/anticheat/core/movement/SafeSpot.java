package com.vylorq.anticheat.core.movement;

import com.vylorq.anticheat.core.util.Vec3;

/**
 * Setback safety (section 7.2): never into a block, the void or lava. Checks a position and searches
 * nearby for a safe one when needed.
 */
public final class SafeSpot {
    private SafeSpot() {
    }

    public static boolean isSafe(WorldView w, Vec3 p) {
        int x = (int) Math.floor(p.x());
        int y = (int) Math.floor(p.y());
        int z = (int) Math.floor(p.z());
        if (y <= w.minY() || y + 1 >= w.maxY()) {
            return false;
        }
        if (!w.isPassable(x, y, z) || !w.isPassable(x, y + 1, z)) {
            return false;
        }
        if (w.isDangerous(x, y, z) || w.isDangerous(x, y + 1, z) || w.isDangerous(x, y - 1, z)) {
            return false;
        }
        // Must have ground within a few blocks so we don't set back into a fall to the void.
        for (int dy = 1; dy <= 4; dy++) {
            if (y - dy <= w.minY()) {
                return false;
            }
            if (w.isDangerous(x, y - dy, z)) {
                return false;
            }
            if (w.isSolid(x, y - dy, z)) {
                return true;
            }
        }
        return false;
    }

    /** Standing position on top of the ground at a column, or null. */
    private static Vec3 standOn(WorldView w, int x, int y, int z) {
        Vec3 p = new Vec3(x + 0.5, y, z + 0.5);
        if (w.isSolid(x, y - 1, z) && isSafe(w, p)) {
            return p;
        }
        return null;
    }

    /**
     * @return {@code preferred} when safe, otherwise the nearest safe spot within {@code radius} blocks, or null.
     */
    public static Vec3 find(WorldView w, Vec3 preferred, int radius) {
        if (isSafe(w, preferred)) {
            return preferred;
        }
        int bx = (int) Math.floor(preferred.x());
        int by = (int) Math.floor(preferred.y());
        int bz = (int) Math.floor(preferred.z());
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = -radius; dy <= radius; dy++) {
                        Vec3 p = standOn(w, bx + dx, by + dy, bz + dz);
                        if (p != null) {
                            double d = p.distanceSq(preferred);
                            if (d < bestDist) {
                                bestDist = d;
                                best = p;
                            }
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }
}
