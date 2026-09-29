package com.vylorq.anticheat.core.claims;

import com.vylorq.anticheat.core.util.Durations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** An admin-made protected area from bedrock to sky (section 17). */
public final class Claim {
    public enum Status { ACTIVE, ARCHIVED }

    public enum Visibility { PUBLIC, PRIVATE }

    public enum Schedule { ALWAYS, NIGHT, DAY, MEMBERS_OFFLINE, HOURS }

    public static final class Member {
        public String name;
        public ClaimRole role;
        /** Temporary access pass expiry, or {@link Durations#PERMANENT}. */
        public long expiresAt = Durations.PERMANENT;
    }

    public static final class Settings {
        public boolean pvp;
        public boolean mobSpawning = true;
        public boolean fireSpread;
        public boolean explosions;
        public boolean visitorDoors = true;
        public boolean entryMessages = true;
        public boolean alerts;
        public boolean hunger = true;
        public boolean fallDamage = true;
    }

    public static final class EntryLogEntry {
        public UUID player;
        public String name;
        public long enteredAt;
        public long leftAt;
    }

    public String id;
    public String name;
    public String world;
    public int minX;
    public int minZ;
    public int maxX;
    public int maxZ;
    public UUID createdBy;
    public boolean createdByOwner;
    public long createdAt;
    /** {@link Durations#PERMANENT} for permanent. */
    public long expiresAt = Durations.PERMANENT;
    public boolean paused;
    public long pausedRemaining;
    public Status status = Status.ACTIVE;
    public Visibility visibility = Visibility.PUBLIC;
    public Map<UUID, Member> members = new LinkedHashMap<>();
    public Map<UUID, Member> previousMembers = new LinkedHashMap<>();
    public Set<UUID> bans = new HashSet<>();
    public Settings settings = new Settings();
    public boolean eventLocked;
    public Schedule schedule = Schedule.ALWAYS;
    public int scheduleStartHour;
    public int scheduleEndHour;
    public boolean warned1h;
    public boolean warned10m;
    public List<String> snapshots = new ArrayList<>();
    public List<EntryLogEntry> entryLog = new ArrayList<>();
    /** Spawn protection claim: PvP, explosions and building off for everyone but staff. */
    public boolean spawnProtection;
    /** Former members who haven't been told yet that protection expired. */
    public Set<UUID> pendingExpiryNotice = new HashSet<>();

    public boolean contains(String w, int x, int z) {
        return world.equals(w) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean contains(String w, double x, double z) {
        return contains(w, (int) Math.floor(x), (int) Math.floor(z));
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public long area() {
        return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
    }

    public long remaining(long now) {
        if (expiresAt == Durations.PERMANENT) {
            return Durations.PERMANENT;
        }
        if (paused) {
            return pausedRemaining;
        }
        return Math.max(0, expiresAt - now);
    }

    public ClaimRole roleOf(UUID player, long now) {
        Member m = members.get(player);
        if (m == null) {
            return null;
        }
        if (Durations.isExpired(m.expiresAt, now)) {
            return null;
        }
        return m.role;
    }

    public boolean overlaps(String w, int aMinX, int aMinZ, int aMaxX, int aMaxZ, int gap) {
        if (!world.equals(w)) {
            return false;
        }
        return aMinX <= maxX + gap && aMaxX >= minX - gap && aMinZ <= maxZ + gap && aMaxZ >= minZ - gap;
    }
}
