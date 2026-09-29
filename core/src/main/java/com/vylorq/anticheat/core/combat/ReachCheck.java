package com.vylorq.anticheat.core.combat;

import com.vylorq.anticheat.core.util.Box;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.List;

/**
 * Reach with lag compensation (section 8.1). The target could have been anywhere in its recent history
 * that matches the attacker's ping, so the hit is measured against the closest of those positions.
 */
public final class ReachCheck {
    private ReachCheck() {
    }

    /**
     * @param eye       attacker eye position
     * @param history   target position history
     * @param now       time of the hit
     * @param pingMs    attacker ping
     * @return the smallest possible distance from the eye to the target's hitbox over the compensated window.
     */
    public static double compensatedDistance(Vec3 eye, PositionHistory history, long now, int pingMs) {
        // Window: what the attacker saw ~ping ago, widened for jitter and the 3-tick interpolation on the client.
        long back = pingMs + 150L;
        List<PositionHistory.Sample> samples = history.between(now - back, now);
        double best = Double.MAX_VALUE;
        for (PositionHistory.Sample s : samples) {
            // Vanilla expands the target hitbox by 0.1 for hit detection on the client.
            Box b = s.box().expand(0.1);
            best = Math.min(best, b.distanceTo(eye));
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    /** Hit ray from the attacker's look direction: does it pass through any compensated hitbox? */
    public static boolean lookHitsAny(Vec3 eye, Vec3 look, PositionHistory history, long now, int pingMs, double maxDist) {
        long back = pingMs + 150L;
        for (PositionHistory.Sample s : history.between(now - back, now)) {
            double t = s.box().expand(0.25).rayHit(eye, look.normalize());
            if (t >= 0 && t <= maxDist) {
                return true;
            }
        }
        return false;
    }
}
