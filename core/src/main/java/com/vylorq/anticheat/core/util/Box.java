package com.vylorq.anticheat.core.util;

/** Axis-aligned box. */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public static Box around(Vec3 feet, double width, double height) {
        double h = width / 2.0;
        return new Box(feet.x() - h, feet.y(), feet.z() - h, feet.x() + h, feet.y() + height, feet.z() + h);
    }

    public Box expand(double d) {
        return new Box(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    public boolean contains(Vec3 p) {
        return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY && p.z() >= minZ && p.z() <= maxZ;
    }

    /** Closest distance from a point to this box (0 when inside). */
    public double distanceTo(Vec3 p) {
        double dx = Math.max(Math.max(minX - p.x(), 0), p.x() - maxX);
        double dy = Math.max(Math.max(minY - p.y(), 0), p.y() - maxY);
        double dz = Math.max(Math.max(minZ - p.z(), 0), p.z() - maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public Vec3 center() {
        return new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    /**
     * Ray/box intersection (slab method).
     *
     * @return distance along the (unit) direction to the first hit, or -1 if the ray misses.
     */
    public double rayHit(Vec3 origin, Vec3 dir) {
        double tMin = 0;
        double tMax = Double.MAX_VALUE;
        double[] o = {origin.x(), origin.y(), origin.z()};
        double[] d = {dir.x(), dir.y(), dir.z()};
        double[] lo = {minX, minY, minZ};
        double[] hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1.0E-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) {
                    return -1;
                }
            } else {
                double t1 = (lo[i] - o[i]) / d[i];
                double t2 = (hi[i] - o[i]) / d[i];
                if (t1 > t2) {
                    double t = t1;
                    t1 = t2;
                    t2 = t;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) {
                    return -1;
                }
            }
        }
        return tMin;
    }
}
