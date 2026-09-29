package com.vylorq.anticheat.core.claims;

import com.vylorq.anticheat.core.util.BlockPos3;
import com.vylorq.anticheat.core.util.Clock;
import com.vylorq.anticheat.core.util.Durations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Owns every claim, the permission rules inside them, timers and entry tracking (section 17). */
public final class ClaimManager {
    public static final class Data {
        public Map<String, Claim> claims = new LinkedHashMap<>();
        public Map<String, Map<UUID, Claim.Member>> templates = new LinkedHashMap<>();
    }

    public enum CreateResult { OK, OVERLAP, NAME_TAKEN, TOO_BIG, INVALID }

    /** Something that happened on a timer tick. */
    public record TimerEvent(Claim claim, Kind kind) {
        public enum Kind { WARN_1H, WARN_10M, EXPIRED, DELETED }
    }

    /** Result of a move across claim borders. */
    public record Transition(Claim left, Claim entered) {
    }

    private final Data data;
    private final Clock clock;
    private final Map<String, Map<Long, List<Claim>>> index = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentClaim = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> griefAttempts = new ConcurrentHashMap<>();

    public ClaimManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
        reindex();
    }

    public Data data() {
        return data;
    }

    public synchronized void reindex() {
        index.clear();
        for (Claim c : data.claims.values()) {
            if (!c.isActive()) {
                continue;
            }
            Map<Long, List<Claim>> w = index.computeIfAbsent(c.world, k -> new ConcurrentHashMap<>());
            for (int cx = c.minX >> 4; cx <= c.maxX >> 4; cx++) {
                for (int cz = c.minZ >> 4; cz <= c.maxZ >> 4; cz++) {
                    w.computeIfAbsent(BlockPos3.chunkKey(cx, cz), k -> new ArrayList<>()).add(c);
                }
            }
        }
    }

    public static String idFor(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    public synchronized CreateResult create(String name, String world, int x1, int z1, int x2, int z2, UUID by,
                                            boolean byOwner, long duration, int minGap, int maxArea) {
        if (name == null || name.isBlank() || name.length() > 32) {
            return CreateResult.INVALID;
        }
        String id = idFor(name);
        if (data.claims.containsKey(id)) {
            return CreateResult.NAME_TAKEN;
        }
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > maxArea) {
            return CreateResult.TOO_BIG;
        }
        if (overlapsAny(world, minX, minZ, maxX, maxZ, minGap, null)) {
            return CreateResult.OVERLAP;
        }
        Claim c = new Claim();
        c.id = id;
        c.name = name;
        c.world = world;
        c.minX = minX;
        c.maxX = maxX;
        c.minZ = minZ;
        c.maxZ = maxZ;
        c.createdBy = by;
        c.createdByOwner = byOwner;
        c.createdAt = clock.nowMillis();
        c.expiresAt = Durations.expiryFrom(c.createdAt, duration);
        data.claims.put(id, c);
        reindex();
        return CreateResult.OK;
    }

    private boolean overlapsAny(String world, int minX, int minZ, int maxX, int maxZ, int gap, Claim except) {
        for (Claim o : data.claims.values()) {
            if (o != except && o.isActive() && o.overlaps(world, minX, minZ, maxX, maxZ, gap)) {
                return true;
            }
        }
        return false;
    }

    public synchronized Claim get(String nameOrId) {
        return nameOrId == null ? null : data.claims.get(idFor(nameOrId));
    }

    public synchronized Collection<Claim> all() {
        return new ArrayList<>(data.claims.values());
    }

    public synchronized boolean delete(String id) {
        boolean removed = data.claims.remove(idFor(id)) != null;
        reindex();
        return removed;
    }

    public synchronized boolean rename(Claim c, String newName) {
        String id = idFor(newName);
        if (data.claims.containsKey(id)) {
            return false;
        }
        data.claims.remove(c.id);
        c.id = id;
        c.name = newName;
        data.claims.put(id, c);
        reindex();
        return true;
    }

    /** The active claim covering a position (claims never overlap, so there's at most one). */
    public Claim at(String world, int x, int z) {
        Map<Long, List<Claim>> w = index.get(world);
        if (w == null) {
            return null;
        }
        List<Claim> list = w.get(BlockPos3.chunkKey(x >> 4, z >> 4));
        if (list == null) {
            return null;
        }
        for (Claim c : list) {
            if (c.contains(world, x, z)) {
                return c;
            }
        }
        return null;
    }

    public Claim at(String world, double x, double z) {
        return at(world, (int) Math.floor(x), (int) Math.floor(z));
    }

    /** Whether protection is switched on right now (scheduled protection). */
    public static boolean protectionOn(Claim c, long worldTimeOfDay, boolean anyMemberOnline, int hourOfDay) {
        if (!c.isActive()) {
            return false;
        }
        return switch (c.schedule) {
            case ALWAYS -> true;
            case NIGHT -> worldTimeOfDay >= 13000 && worldTimeOfDay < 23000;
            case DAY -> worldTimeOfDay < 13000 || worldTimeOfDay >= 23000;
            case MEMBERS_OFFLINE -> !anyMemberOnline;
            case HOURS -> c.scheduleStartHour <= c.scheduleEndHour
                    ? hourOfDay >= c.scheduleStartHour && hourOfDay < c.scheduleEndHour
                    : hourOfDay >= c.scheduleStartHour || hourOfDay < c.scheduleEndHour;
        };
    }

    /**
     * Core permission rule (section 17.2 / 17.6).
     *
     * @param staff owner or logged-in admin
     * @param protectionOn result of {@link #protectionOn}
     */
    public boolean can(Claim c, UUID player, boolean staff, ClaimAction action, boolean protectionOn) {
        if (c == null || !c.isActive()) {
            return true;
        }
        if (c.eventLocked && action.isChange()) {
            // Event lock: nothing may change, not even for staff (unlock first).
            return false;
        }
        if (staff) {
            return true;
        }
        if (c.bans.contains(player)) {
            return false;
        }
        if (action == ClaimAction.PVP) {
            return c.settings.pvp && !c.spawnProtection;
        }
        if (c.spawnProtection) {
            return action == ClaimAction.ENTER || action == ClaimAction.DOOR || action == ClaimAction.REDSTONE;
        }
        if (!protectionOn) {
            return true;
        }
        long now = clock.nowMillis();
        ClaimRole role = c.roleOf(player, now);
        if (role != null && role.canBuild()) {
            return true;
        }
        if (role == ClaimRole.VISITOR) {
            return action == ClaimAction.ENTER || (action == ClaimAction.DOOR && c.settings.visitorDoors);
        }
        return action == ClaimAction.ENTER && c.visibility == Claim.Visibility.PUBLIC;
    }

    /**
     * Cross-border rule for pistons, liquids, fire, dispensers and hoppers: something from {@code from} may affect
     * {@code to} only if {@code to} is unclaimed or in the same claim.
     */
    public boolean crossAllowed(String world, int fromX, int fromZ, int toX, int toZ) {
        Claim target = at(world, toX, toZ);
        if (target == null) {
            return true;
        }
        if (target.eventLocked) {
            return false;
        }
        Claim source = at(world, fromX, fromZ);
        return source == target;
    }

    // ---- Members ----

    /** Whether {@code actorRole} (null = staff) may set a member to {@code newRole} or remove a member with {@code currentRole}. */
    public static boolean canManageMember(ClaimRole actorRole, boolean actorStaff, ClaimRole currentRole, ClaimRole newRole) {
        if (actorStaff) {
            return true;
        }
        if (actorRole != ClaimRole.MANAGER) {
            return false;
        }
        // Managers add/remove Builders and Visitors, but can't touch Managers or create new ones.
        if (currentRole == ClaimRole.MANAGER) {
            return false;
        }
        return newRole == null || newRole != ClaimRole.MANAGER;
    }

    public synchronized void setMember(Claim c, UUID player, String name, ClaimRole role, long passDuration) {
        Claim.Member m = new Claim.Member();
        m.name = name;
        m.role = role;
        m.expiresAt = passDuration == Durations.PERMANENT ? Durations.PERMANENT : clock.nowMillis() + passDuration;
        c.members.put(player, m);
    }

    public synchronized boolean removeMember(Claim c, UUID player) {
        return c.members.remove(player) != null;
    }

    public synchronized void saveTemplate(String name, Claim c) {
        data.templates.put(name.toLowerCase(Locale.ROOT), new LinkedHashMap<>(c.members));
    }

    public synchronized boolean applyTemplate(String name, Claim c) {
        Map<UUID, Claim.Member> t = data.templates.get(name.toLowerCase(Locale.ROOT));
        if (t == null) {
            return false;
        }
        for (Map.Entry<UUID, Claim.Member> e : t.entrySet()) {
            Claim.Member m = new Claim.Member();
            m.name = e.getValue().name;
            m.role = e.getValue().role;
            c.members.put(e.getKey(), m);
        }
        return true;
    }

    // ---- Timers ----

    public synchronized void extend(Claim c, long millis) {
        if (c.expiresAt == Durations.PERMANENT) {
            return;
        }
        if (c.paused) {
            c.pausedRemaining += millis;
        } else {
            c.expiresAt += millis;
        }
        resetWarnings(c);
    }

    public synchronized void setDuration(Claim c, long duration) {
        c.paused = false;
        c.expiresAt = Durations.expiryFrom(clock.nowMillis(), duration);
        resetWarnings(c);
    }

    public synchronized void pause(Claim c, boolean pause) {
        if (c.expiresAt == Durations.PERMANENT || c.paused == pause) {
            return;
        }
        long now = clock.nowMillis();
        if (pause) {
            c.pausedRemaining = Math.max(0, c.expiresAt - now);
            c.paused = true;
        } else {
            c.expiresAt = now + c.pausedRemaining;
            c.paused = false;
        }
    }

    private static void resetWarnings(Claim c) {
        c.warned1h = false;
        c.warned10m = false;
    }

    /** Call about once a second. Handles expiry warnings, expiry and expired access passes. */
    public synchronized List<TimerEvent> tick(boolean archiveOnExpiry) {
        long now = clock.nowMillis();
        List<TimerEvent> events = new ArrayList<>();
        Iterator<Claim> it = data.claims.values().iterator();
        boolean changed = false;
        while (it.hasNext()) {
            Claim c = it.next();
            if (!c.isActive()) {
                continue;
            }
            c.members.values().removeIf(m -> Durations.isExpired(m.expiresAt, now));
            if (c.expiresAt == Durations.PERMANENT || c.paused) {
                continue;
            }
            long left = c.expiresAt - now;
            if (left <= 0) {
                expire(c);
                changed = true;
                if (archiveOnExpiry) {
                    events.add(new TimerEvent(c, TimerEvent.Kind.EXPIRED));
                } else {
                    it.remove();
                    events.add(new TimerEvent(c, TimerEvent.Kind.DELETED));
                }
            } else if (left <= 10 * Durations.MINUTE && !c.warned10m) {
                c.warned10m = true;
                c.warned1h = true;
                events.add(new TimerEvent(c, TimerEvent.Kind.WARN_10M));
            } else if (left <= Durations.HOUR && !c.warned1h) {
                c.warned1h = true;
                events.add(new TimerEvent(c, TimerEvent.Kind.WARN_1H));
            }
        }
        if (changed) {
            reindex();
        }
        return events;
    }

    /** Expiry: every player permission is removed instantly and protection turns off. */
    private void expire(Claim c) {
        c.previousMembers = new LinkedHashMap<>(c.members);
        c.pendingExpiryNotice.addAll(c.members.keySet());
        c.members.clear();
        c.status = Claim.Status.ARCHIVED;
    }

    /** Reactivates an archived claim with no members. @return false if it would now overlap another claim. */
    public synchronized boolean reactivate(Claim c, long duration, int minGap) {
        if (overlapsAny(c.world, c.minX, c.minZ, c.maxX, c.maxZ, minGap, c)) {
            return false;
        }
        c.status = Claim.Status.ACTIVE;
        c.members.clear();
        c.paused = false;
        c.expiresAt = Durations.expiryFrom(clock.nowMillis(), duration);
        resetWarnings(c);
        reindex();
        return true;
    }

    public synchronized int restorePreviousMembers(Claim c) {
        long now = clock.nowMillis();
        int n = 0;
        for (Map.Entry<UUID, Claim.Member> e : c.previousMembers.entrySet()) {
            if (!Durations.isExpired(e.getValue().expiresAt, now)) {
                c.members.put(e.getKey(), e.getValue());
                n++;
            }
        }
        return n;
    }

    // ---- Presence ----

    /** Tracks which claim a player is in. Returns a transition when it changes, else null. */
    public Transition updatePresence(UUID player, String name, String world, double x, double z) {
        Claim now = at(world, x, z);
        String nowId = now == null ? null : now.id;
        String before = currentClaim.get(player);
        if (java.util.Objects.equals(before, nowId)) {
            return null;
        }
        if (nowId == null) {
            currentClaim.remove(player);
        } else {
            currentClaim.put(player, nowId);
        }
        Claim left = before == null ? null : data.claims.get(before);
        long t = clock.nowMillis();
        synchronized (this) {
            if (left != null) {
                for (int i = left.entryLog.size() - 1; i >= 0; i--) {
                    Claim.EntryLogEntry e = left.entryLog.get(i);
                    if (e.player.equals(player) && e.leftAt == 0) {
                        e.leftAt = t;
                        break;
                    }
                }
            }
            if (now != null) {
                Claim.EntryLogEntry e = new Claim.EntryLogEntry();
                e.player = player;
                e.name = name;
                e.enteredAt = t;
                now.entryLog.add(e);
                if (now.entryLog.size() > 500) {
                    now.entryLog.remove(0);
                }
            }
        }
        return new Transition(left, now);
    }

    public void clearPresence(UUID player) {
        String before = currentClaim.remove(player);
        if (before != null) {
            Claim c = data.claims.get(before);
            if (c != null) {
                synchronized (this) {
                    for (Claim.EntryLogEntry e : c.entryLog) {
                        if (e.player.equals(player) && e.leftAt == 0) {
                            e.leftAt = clock.nowMillis();
                        }
                    }
                }
            }
        }
    }

    /** Players currently inside a claim. */
    public List<UUID> playersIn(Claim c) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : currentClaim.entrySet()) {
            if (e.getValue().equals(c.id)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** Claims within {@code radius} blocks of a point. */
    public synchronized List<Claim> near(String world, int x, int z, int radius) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : data.claims.values()) {
            if (c.isActive() && c.overlaps(world, x - radius, z - radius, x + radius, z + radius, 0)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Counts denied attempts; returns true when the grief-alert threshold is reached (then resets). */
    public boolean griefAttempt(UUID player, Claim c, int threshold, long windowMillis) {
        String key = player + "@" + c.id;
        Deque<Long> d = griefAttempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = clock.nowMillis();
        synchronized (d) {
            d.addLast(now);
            while (!d.isEmpty() && d.peekFirst() < now - windowMillis) {
                d.pollFirst();
            }
            if (d.size() >= threshold) {
                d.clear();
                return true;
            }
        }
        return false;
    }

    /** Claims a player has a role in. */
    public synchronized List<Claim> claimsOf(UUID player) {
        List<Claim> out = new ArrayList<>();
        long now = clock.nowMillis();
        for (Claim c : data.claims.values()) {
            if (c.roleOf(player, now) != null) {
                out.add(c);
            }
        }
        return out;
    }

    /** Claims whose expiry this player hasn't been told about yet. Each is returned once. */
    public synchronized List<Claim> pollExpiryNotices(UUID player) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : data.claims.values()) {
            if (c.pendingExpiryNotice.remove(player)) {
                out.add(c);
            }
        }
        return out;
    }

    public synchronized List<Claim> filter(Predicate<Claim> p) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : data.claims.values()) {
            if (p.test(c)) {
                out.add(c);
            }
        }
        return out;
    }

}
