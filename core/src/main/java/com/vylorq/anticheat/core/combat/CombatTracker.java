package com.vylorq.anticheat.core.combat;

import com.vylorq.anticheat.core.util.Stats;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Per-attacker state for multi-target and attack-cooldown checks. */
public final class CombatTracker {
    private record Hit(long time, UUID target, double yawToTarget) {
    }

    private final Deque<Hit> recent = new ArrayDeque<>();
    private UUID lastTarget;
    public final AimAnalyzer aim = new AimAnalyzer();
    public final ClickAnalyzer clicks = new ClickAnalyzer();

    /**
     * Multi-target (section 8.1): hitting different targets in directions far apart within a very short window.
     *
     * @return the largest angle between targets hit in the window, or 0.
     */
    public double onHitMultiTarget(long now, UUID target, Vec3 attackerEye, Vec3 targetPos, int windowMs) {
        Vec3 d = targetPos.subtract(attackerEye);
        double yawTo = Math.toDegrees(Math.atan2(-d.x(), d.z()));
        while (!recent.isEmpty() && recent.peekFirst().time() < now - windowMs) {
            recent.pollFirst();
        }
        double worst = 0;
        for (Hit h : recent) {
            if (!h.target().equals(target)) {
                worst = Math.max(worst, Math.abs(Stats.wrapDegrees(yawTo - h.yawToTarget())));
            }
        }
        recent.addLast(new Hit(now, target, yawTo));
        return worst;
    }

    /** @return true if the target differs from the previous hit. */
    public boolean switchedTarget(UUID target) {
        boolean switched = lastTarget != null && !lastTarget.equals(target);
        lastTarget = target;
        return switched;
    }

    /**
     * Invalid combat actions: attacking while the inventory is open, eating/drinking, or actively blocking with
     * a shield. Returns a reason or null.
     */
    public static String invalidAction(boolean inventoryOpen, boolean usingItem, boolean blocking) {
        if (inventoryOpen) {
            return "attacked with inventory open";
        }
        if (blocking) {
            return "attacked while blocking";
        }
        if (usingItem) {
            return "attacked while using an item";
        }
        return null;
    }

    /** Angle in degrees between the look vector and the direction from eye to point. */
    public static double angleTo(Vec3 eye, Vec3 look, Vec3 point) {
        Vec3 dir = point.subtract(eye).normalize();
        double dot = Stats.clamp(look.normalize().dot(dir), -1, 1);
        return Math.toDegrees(Math.acos(dot));
    }
}
