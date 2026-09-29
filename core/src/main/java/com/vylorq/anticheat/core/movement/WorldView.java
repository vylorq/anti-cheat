package com.vylorq.anticheat.core.movement;

/** Minimal world access used to find a safe setback position. */
public interface WorldView {
    /** A block the player can stand on. */
    boolean isSolid(int x, int y, int z);

    /** Air or a non-colliding block the player can be inside. */
    boolean isPassable(int x, int y, int z);

    /** Lava, fire, magma, powder snow, cactus, sweet berries, wither roses... */
    boolean isDangerous(int x, int y, int z);

    int minY();

    int maxY();
}
