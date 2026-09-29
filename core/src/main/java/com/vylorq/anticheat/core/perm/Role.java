package com.vylorq.anticheat.core.perm;

/** Staff role of a player. Ordered from least to most powerful. */
public enum Role {
    PLAYER,
    ADMIN,
    OWNER;

    public boolean atLeast(Role other) {
        return ordinal() >= other.ordinal();
    }

    public boolean isStaff() {
        return this != PLAYER;
    }
}
