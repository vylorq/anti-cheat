package com.vylorq.anticheat.core.claims;

/** Roles inside a claim (section 17.2). */
public enum ClaimRole {
    VISITOR,
    BUILDER,
    MANAGER;

    public boolean canBuild() {
        return this != VISITOR;
    }
}
