package com.vylorq.anticheat.core.util;

/** Minecraft-independent 3D vector. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double f) {
        return new Vec3(x * f, y * f, z * f);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    public double distance(Vec3 o) {
        return subtract(o).length();
    }

    public double distanceSq(Vec3 o) {
        return subtract(o).lengthSq();
    }

    public double horizontalDistance(Vec3 o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vec3 normalize() {
        double l = length();
        return l < 1.0E-9 ? ZERO : scale(1.0 / l);
    }

    /** Unit look vector from Minecraft yaw/pitch in degrees. */
    public static Vec3 fromRotation(float yaw, float pitch) {
        double yr = Math.toRadians(-yaw) - Math.PI;
        double pr = Math.toRadians(-pitch);
        double cp = -Math.cos(pr);
        return new Vec3(Math.sin(yr) * cp, Math.sin(pr), Math.cos(yr) * cp);
    }

    public String formatExact() {
        return String.format(java.util.Locale.ROOT, "X: %.2f Y: %.2f Z: %.2f", x, y, z);
    }
}
