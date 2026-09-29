package com.vylorq.anticheat.core.combat;

import com.vylorq.anticheat.core.util.Stats;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rotation analysis for killaura / aim assist (section 8.1). Looks for:
 * <ul>
 *   <li>Snaps: a huge rotation in one tick right before a hit, repeatedly.</li>
 *   <li>Robotic rotation: rotation changes that are too consistent (tiny variation) while in combat.</li>
 *   <li>Perfect aim: the look vector lands on the exact centre of the target's hitbox again and again.</li>
 *   <li>Missing mouse sensitivity steps (GCD): real mice move in fixed steps; generated rotations don't.</li>
 * </ul>
 * Each method returns a suspicion amount 0..1 for this sample (0 = normal).
 */
public final class AimAnalyzer {
    private final Deque<Double> yawDeltas = new ArrayDeque<>();
    private final Deque<Double> pitchDeltas = new ArrayDeque<>();
    private final Deque<Double> centreErrors = new ArrayDeque<>();
    private float lastYaw;
    private float lastPitch;
    private boolean hasLast;
    private int snapHits;
    private int hits;
    private static final int WINDOW = 40;

    /** Feed every rotation packet. */
    public void onRotation(float yaw, float pitch) {
        if (hasLast) {
            double dy = Math.abs(Stats.wrapDegrees(yaw - lastYaw));
            double dp = Math.abs(pitch - lastPitch);
            if (dy > 0.001 || dp > 0.001) {
                push(yawDeltas, dy);
                push(pitchDeltas, dp);
            }
        }
        lastYaw = yaw;
        lastPitch = pitch;
        hasLast = true;
    }

    private static void push(Deque<Double> d, double v) {
        d.addLast(v);
        if (d.size() > WINDOW) {
            d.pollFirst();
        }
    }

    /**
     * Feed every hit.
     *
     * @param rotationBeforeHit degrees rotated in the tick of the hit
     * @param centreError       angle in degrees between the look vector and the direction to the hitbox centre
     * @param targetSwitched    the hit target differs from the previous hit's target
     * @return suspicion for this hit, 0..1
     */
    public double onHit(double rotationBeforeHit, double centreError, boolean targetSwitched) {
        hits++;
        push(centreErrors, centreError);
        double score = 0;
        // Instant snap onto a (new) target.
        if (rotationBeforeHit > 60 && targetSwitched) {
            snapHits++;
            if (snapHits >= 3) {
                score = Math.max(score, 0.6);
            }
        }
        // Perfect aim: centre error nearly zero for most recent hits.
        if (centreErrors.size() >= 15) {
            double[] errs = Stats.toArray(centreErrors);
            double mean = Stats.mean(errs);
            if (mean < 0.6 && Stats.stddev(errs) < 0.5) {
                score = Math.max(score, 0.7);
            }
        }
        score = Math.max(score, roboticScore());
        return score;
    }

    /** Too-consistent rotation changes while fighting. */
    public double roboticScore() {
        if (yawDeltas.size() < 20) {
            return 0;
        }
        double[] yd = Stats.toArray(yawDeltas);
        double mean = Stats.mean(yd);
        if (mean < 2.0) {
            // Barely moving the mouse: nothing to judge.
            return 0;
        }
        double cv = Stats.cv(yd);
        double distinct = Stats.distinctRatio(yd, 2);
        double score = 0;
        if (cv < 0.05) {
            score = 0.8;
        } else if (cv < 0.1) {
            score = 0.4;
        }
        if (distinct < 0.2) {
            score = Math.max(score, 0.6);
        }
        return score;
    }

    /**
     * Mouse sensitivity check: the pitch deltas of a real mouse share a common step (GCD). Returns 0..1.
     * Many generated rotations have no common step.
     */
    public double sensitivityScore() {
        if (pitchDeltas.size() < 30) {
            return 0;
        }
        double g = 0;
        int counted = 0;
        for (double d : pitchDeltas) {
            if (d < 0.01) {
                continue;
            }
            g = counted == 0 ? d : Stats.gcd(g, d, 1.0E-4);
            counted++;
        }
        if (counted < 20) {
            return 0;
        }
        // Smallest vanilla step at sensitivity 0% is ~0.0096 degrees; below this there is no real step.
        return g < 0.0075 ? 0.5 : 0;
    }

    public int hits() {
        return hits;
    }
}
