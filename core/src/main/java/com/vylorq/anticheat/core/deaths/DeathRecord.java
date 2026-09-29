package com.vylorq.anticheat.core.deaths;

import com.vylorq.anticheat.core.item.ItemInfo;
import com.vylorq.anticheat.core.util.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Everything saved about one death (section 16). */
public final class DeathRecord {
    public static final class DamageEntry {
        public long at;
        public String source;
        public String attacker;
        public float amount;
    }

    public static final class Pickup {
        public UUID by;
        public String byName;
        public String item;
        public long at;
    }

    public long id;
    public UUID player;
    public String playerName;
    public long at;
    public String world;
    public Vec3 pos;
    public String biome;
    public String cause;
    public String killer;
    public UUID killerUuid;
    public String weapon;
    public double killerDistance;
    public double fallHeight;
    public List<DamageEntry> lastDamage = new ArrayList<>();
    public List<ItemInfo> inventory = new ArrayList<>();
    public int xpLevel;
    public float xpProgress;
    public int totalXp;
    public List<Pickup> pickups = new ArrayList<>();
    public boolean restored;
    public String restoredBy;
    public long restoredAt;
    /** Unique tag stamped on the dropped items so pickups can be traced back to this death. */
    public String dropTag;

    /** Items from this death that someone (including the owner) already picked back up. */
    public int pickedUpCount() {
        return pickups.size();
    }
}
