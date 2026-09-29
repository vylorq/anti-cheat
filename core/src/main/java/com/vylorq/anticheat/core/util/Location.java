package com.vylorq.anticheat.core.util;

/** A world position with rotation. */
public record Location(String world, double x, double y, double z, float yaw, float pitch) {
    public Vec3 vec() {
        return new Vec3(x, y, z);
    }

    public static Location of(String world, Vec3 v) {
        return new Location(world, v.x(), v.y(), v.z(), 0, 0);
    }
}
