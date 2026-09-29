package com.vylorq.anticheat.core.xray;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Improved x-ray detection for one player: tracks the ratio of rare ores to ordinary stone mined and how
 * directly the player tunnels to hidden ores (ores with no exposed face when first broken).
 */
public final class MiningAnalyzer {
    private final Deque<Boolean> recent = new ArrayDeque<>();
    private int hiddenOreHits;
    private int oreHits;
    private final Map<String, Integer> ores = new LinkedHashMap<>();

    /**
     * @param rareOre     block is a rare ore (diamond, debris)
     * @param wasExposed  the ore had an air face before this player dug to it
     * @return suspicion 0..1 after this block
     */
    public double onBreak(String blockId, boolean rareOre, boolean wasExposed, boolean stoneLike, double suspiciousRatio) {
        if (stoneLike || rareOre) {
            recent.addLast(rareOre);
            if (recent.size() > 400) {
                recent.pollFirst();
            }
        }
        if (!rareOre) {
            return 0;
        }
        ores.merge(blockId, 1, Integer::sum);
        oreHits++;
        if (!wasExposed) {
            hiddenOreHits++;
        }
        int rare = 0;
        for (boolean b : recent) {
            if (b) {
                rare++;
            }
        }
        double per100 = rare * 100.0 / Math.max(50, recent.size());
        double score = 0;
        if (per100 > suspiciousRatio) {
            score = Math.min(1, (per100 - suspiciousRatio) / suspiciousRatio);
        }
        // Finding many hidden veins in a row is the strongest sign.
        if (oreHits >= 4 && hiddenOreHits / (double) oreHits > 0.8) {
            score = Math.max(score, 0.5);
        }
        return score;
    }

    public Map<String, Integer> ores() {
        return ores;
    }
}
