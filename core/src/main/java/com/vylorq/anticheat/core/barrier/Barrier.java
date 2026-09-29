package com.vylorq.anticheat.core.barrier;

import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An invisible wall nobody crosses (section 19). Enforced by position checks, never by real blocks.
 * Box and cylinder shapes go from bedrock to sky; spheres are true spheres.
 */
public final class Barrier {
    public enum Shape { BOX, CYLINDER, SPHERE }

    public String name;
    public String world;
    public Shape shape = Shape.BOX;
    public double minX;
    public double minZ;
    public double maxX;
    public double maxZ;
    public double cx;
    public double cy;
    public double cz;
    public double radius;
    public boolean adminsPass = true;
    public boolean blockProjectiles = true;
    public long createdAt;
    public long expiresAt = Durations.PERMANENT;
    public String createdBy;
    /** Which side each player belongs on (true = inside). Survives logouts and restarts. */
    public Map<UUID, Boolean> sides = new HashMap<>();
    /** Last position on the correct side, per player, used to send them back. */
    public Map<UUID, Vec3> lastGood = new HashMap<>();

    public boolean contains(String w, double x, double y, double z) {
        if (!world.equals(w)) {
            return false;
        }
        return switch (shape) {
            case BOX -> x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
            case CYLINDER -> {
                double dx = x - cx;
                double dz = z - cz;
                yield dx * dx + dz * dz <= radius * radius;
            }
            case SPHERE -> {
                double dx = x - cx;
                double dy = y - cy;
                double dz = z - cz;
                yield dx * dx + dy * dy + dz * dz <= radius * radius;
            }
        };
    }

    /** Distance from a point to the wall (positive either side), for showing the particle wall nearby. */
    public double distanceToWall(double x, double y, double z) {
        return switch (shape) {
            case BOX -> {
                boolean inside = x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
                if (inside) {
                    yield Math.min(Math.min(x - minX, maxX + 1 - x), Math.min(z - minZ, maxZ + 1 - z));
                }
                double dx = Math.max(Math.max(minX - x, 0), x - (maxX + 1));
                double dz = Math.max(Math.max(minZ - z, 0), z - (maxZ + 1));
                yield Math.sqrt(dx * dx + dz * dz);
            }
            case CYLINDER -> Math.abs(Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)) - radius);
            case SPHERE -> Math.abs(Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz)) - radius);
        };
    }

    public Vec3 center() {
        return shape == Shape.BOX ? new Vec3((minX + maxX + 1) / 2, cy, (minZ + maxZ + 1) / 2) : new Vec3(cx, cy, cz);
    }
}
