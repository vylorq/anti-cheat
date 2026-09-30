package com.vylorq.anticheat.core.watcher;

import com.vylorq.anticheat.core.util.Vec3;

/**
 * Vanish rules for a figure the player can see (33.2): gone when the player comes within ~12 blocks, or after
 * looking straight at it for ~3 seconds (not necessarily in one go).
 */
public final class Gaze {
    public static final double VANISH_DISTANCE = 12.0;
    /** Degrees between the view direction and the figure that count as "looking at him". */
    public static final double LOOK_ANGLE = 10.0;
    public static final int LOOK_TICKS = 60;

    private int lookedTicks;

    /** Angle in degrees between a view direction (yaw/pitch, Minecraft convention) and eye→target. */
    public static double angleTo(Vec3 eye, float yaw, float pitch, Vec3 target) {
        double yr = Math.toRadians(yaw);
        double pr = Math.toRadians(pitch);
        double lx = -Math.sin(yr) * Math.cos(pr);
        double ly = -Math.sin(pr);
        double lz = Math.cos(yr) * Math.cos(pr);
        double dx = target.x() - eye.x();
        double dy = target.y() - eye.y();
        double dz = target.z() - eye.z();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) {
            return 0;
        }
        double dot = (lx * dx + ly * dy + lz * dz) / len;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    /** Yaw (Minecraft convention) for something at {@code from} to face {@code to}. */
    public static float yawTowards(Vec3 from, Vec3 to) {
        return (float) (Math.toDegrees(Math.atan2(to.z() - from.z(), to.x() - from.x())) - 90.0);
    }

    public static float pitchTowards(Vec3 from, Vec3 to) {
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(to.y() - from.y(), horizontal));
    }

    /**
     * One tick of watching.
     *
     * @return true when the figure should vanish
     */
    public boolean tick(Vec3 eye, float yaw, float pitch, Vec3 figureHead) {
        if (eye.distance(figureHead) < VANISH_DISTANCE) {
            return true;
        }
        if (angleTo(eye, yaw, pitch, figureHead) <= LOOK_ANGLE) {
            lookedTicks++;
        }
        return lookedTicks >= LOOK_TICKS;
    }

    public int lookedTicks() {
        return lookedTicks;
    }
}
