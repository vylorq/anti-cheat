package com.vylorq.anticheat.core.xray;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * X-ray detection for one player: how many rare ore veins they find per stone mined, and how often they dig
 * straight into ores that had no open face.
 *
 * <p>Counted per vein, not per ore: one lucky vein of eight diamonds is one find. Blocks blown up near the
 * player (TNT and bed mining for debris) count as mined, so those miners aren't mistaken for x-ray.
 */
public final class MiningAnalyzer {
    private record Find(long time, int x, int y, int z) {
    }

    private final Deque<Boolean> recent = new ArrayDeque<>();
    private final Deque<Find> lastOres = new ArrayDeque<>();
    private int hiddenVeins;
    private int veins;
    private final Map<String, Integer> ores = new LinkedHashMap<>();
    private int unplaced;
    private static final int WINDOW = 400;
    /** Fewer mined blocks than this are judged as if this many were mined (a lucky early find isn't x-ray). */
    private static final int MIN_BLOCKS = 150;

    private void push(boolean vein) {
        recent.addLast(vein);
        if (recent.size() > WINDOW) {
            recent.pollFirst();
        }
    }

    /** Blocks destroyed by an explosion near this player count as mined. */
    public void onExplosionNearby(int blocks) {
        for (int i = 0; i < Math.min(blocks, WINDOW); i++) {
            push(false);
        }
    }

    /** Without a position: every rare ore is its own vein (kept for callers that don't know where it was). */
    public double onBreak(String blockId, boolean rareOre, boolean wasExposed, boolean stoneLike, double suspiciousRatio) {
        unplaced += 10;
        return onBreak(blockId, rareOre, wasExposed, stoneLike, suspiciousRatio, 0, unplaced, Integer.MIN_VALUE / 2, 0);
    }

    /**
     * @param rareOre     block is a rare ore (diamond, debris)
     * @param wasExposed  the ore had an open face before this player dug to it
     * @return suspicion 0..1 after this block
     */
    public double onBreak(String blockId, boolean rareOre, boolean wasExposed, boolean stoneLike, double suspiciousRatio,
                          long now, int x, int y, int z) {
        if (!rareOre) {
            if (stoneLike) {
                push(false);
            }
            return 0;
        }
        ores.merge(blockId, 1, Integer::sum);
        // Part of a vein already found (an ore within 3 blocks in the last 10 minutes)?
        boolean newVein = true;
        while (!lastOres.isEmpty() && now - lastOres.peekFirst().time() > 10 * 60_000L) {
            lastOres.pollFirst();
        }
        for (Find f : lastOres) {
            if (Math.abs(f.x() - x) <= 3 && Math.abs(f.y() - y) <= 3 && Math.abs(f.z() - z) <= 3) {
                newVein = false;
                break;
            }
        }
        lastOres.addLast(new Find(now, x, y, z));
        if (lastOres.size() > 64) {
            lastOres.pollFirst();
        }
        if (!newVein) {
            return 0;
        }
        veins++;
        if (!wasExposed) {
            hiddenVeins++;
        }
        push(true);
        int found = 0;
        for (boolean b : recent) {
            if (b) {
                found++;
            }
        }
        double per100 = found * 100.0 / Math.max(MIN_BLOCKS, recent.size());
        double score = 0;
        if (found >= 3 && per100 > suspiciousRatio) {
            score = Math.min(1, (per100 - suspiciousRatio) / suspiciousRatio);
        }
        // Digging straight into vein after vein that had no open face, while also finding them quickly.
        if (veins >= 6 && hiddenVeins / (double) veins > 0.85 && per100 > suspiciousRatio * 0.5) {
            score = Math.max(score, 0.5);
        }
        return score;
    }

    public Map<String, Integer> ores() {
        return ores;
    }
}
