package com.vylorq.anticheat.core.claims;

/** Everything a claim can protect. */
public enum ClaimAction {
    ENTER,
    BREAK,
    PLACE,
    CONTAINER,
    DOOR,
    REDSTONE,
    ITEM_FRAME,
    ARMOR_STAND,
    ENTITY,
    VEHICLE,
    BUCKET,
    FIRE,
    CROP,
    PVP;

    /** Actions that change the world or its contents. */
    public boolean isChange() {
        return this != ENTER && this != DOOR && this != PVP;
    }
}
