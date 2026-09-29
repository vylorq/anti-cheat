package com.vylorq.anticheat.core.arena;

import com.vylorq.anticheat.core.util.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything saved before a player enters an arena (or any temporary state) so it can be restored exactly once.
 * Saved to disk immediately so a crash or disconnect can't lose items.
 */
public final class PlayerSnapshot {
    public UUID player;
    public String reason;
    public long createdAt;
    /** Serialized inventory (with slots). */
    public List<String> inventory = new ArrayList<>();
    public List<String> enderChest;
    public float health;
    public int food;
    public float saturation;
    public int xpLevel;
    public float xpProgress;
    public int totalXp;
    /** Serialized status effects. */
    public List<String> effects = new ArrayList<>();
    public Location location;
    public String gameMode;
    public int fireTicks;
    public int air;
}
