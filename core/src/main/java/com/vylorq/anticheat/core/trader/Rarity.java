package com.vylorq.anticheat.core.trader;

import java.util.SplittableRandom;

/** Trader stock rarity (section 23.2). */
public enum Rarity {
    COMMON(16, 64, "§f"),
    UNCOMMON(4, 8, "§a"),
    RARE(2, 2, "§9"),
    LEGENDARY(1, 1, "§6");

    private final int minStock;
    private final int maxStock;
    public final String color;

    Rarity(int minStock, int maxStock, String color) {
        this.minStock = minStock;
        this.maxStock = maxStock;
        this.color = color;
    }

    public int rollStock(SplittableRandom r) {
        return minStock == maxStock ? minStock : minStock + r.nextInt(maxStock - minStock + 1);
    }

    public Rarity max(Rarity o) {
        return o.ordinal() > ordinal() ? o : this;
    }
}
