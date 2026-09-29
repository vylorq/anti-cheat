package com.vylorq.anticheat.core.blocklog;

import java.util.UUID;

/** One logged block or container change (section 15, block logging). */
public final class BlockChange {
    public enum Kind { PLACE, BREAK, CONTAINER_ADD, CONTAINER_REMOVE, EXPLODE, BURN, OTHER }

    public long id;
    public long time;
    public UUID actor;
    public String actorName;
    public String world;
    public int x;
    public int y;
    public int z;
    public Kind kind;
    /** Block state before (serialized), e.g. "minecraft:oak_log[axis=y]". */
    public String before;
    /** Block state after. */
    public String after;
    /** Block entity NBT before the change (chests etc.), may be null. */
    public String beforeNbt;
    /** For container changes: item id and count. */
    public String item;
    public int amount;
    public boolean rolledBack;
}
