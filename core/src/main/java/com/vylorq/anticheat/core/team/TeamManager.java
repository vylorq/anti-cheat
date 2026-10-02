package com.vylorq.anticheat.core.team;

import com.vylorq.anticheat.core.util.Clock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Teams: create, invite, join, leave, kick, promote, demote, hand over leadership, disband, and claim chunks as the
 * team's territory. Pure rules; the server does the messages, protection and name tags.
 */
public final class TeamManager {
    public static final class Data {
        public Map<String, Team> teams = new LinkedHashMap<>();
        public Map<UUID, String> playerTeam = new HashMap<>();
        /** Pending invites: player to team ids. */
        public Map<UUID, Set<String>> invites = new HashMap<>();
        /** "world|x|z" to team id. */
        public Map<String, String> chunkOwner = new HashMap<>();
        public List<War> wars = new ArrayList<>();
        /** Team id -> when it may declare war again. */
        public Map<String, Long> warCooldown = new HashMap<>();
    }

    /** A war between two teams: kills between them score points until it ends. */
    public static final class War {
        public String a;
        public String b;
        public int scoreA;
        public int scoreB;
        public long started;
        public long endsAt;

        public boolean involves(String team) {
            return a.equals(team) || b.equals(team);
        }

        /** Winner's id, or null for a draw. */
        public String winner() {
            return scoreA == scoreB ? null : scoreA > scoreB ? a : b;
        }
    }

    public static final int MAX_LEVEL = 10;

    /** Level 1 at 0 XP, then 50, 200, 450, 800 ... (50 x (level-1)^2), up to level 10. */
    public static int level(long xp) {
        return Math.min(MAX_LEVEL, 1 + (int) Math.floor(Math.sqrt(Math.max(0, xp) / 50.0)));
    }

    /** XP needed to reach a level. */
    public static long xpFor(int level) {
        long n = Math.max(0, level - 1);
        return 50 * n * n;
    }

    public enum Result {
        OK, BAD_NAME, NAME_TAKEN, TAG_TAKEN, ALREADY_IN_TEAM, NOT_IN_TEAM, NO_SUCH_TEAM, NOT_ALLOWED, NOT_INVITED, FULL,
        NOT_A_MEMBER, LEADER_MUST_HAND_OVER, CHUNK_TAKEN, CHUNK_NOT_YOURS, CHUNK_LIMIT, NOT_CONNECTED, SELF,
        ALREADY_ALLIES, ALLY_REQUESTED, NOT_ALLIES, TOO_MANY, AT_WAR, ARE_ALLIES, WAR_COOLDOWN, NOT_ENOUGH
    }

    /** Limits (from the config). */
    public static final class Limits {
        public int maxMembers = 10;
        public int baseChunks = 4;
        public int chunksPerMember = 2;
        public int maxChunks = 40;
        /** New chunks must touch the team's other chunks. */
        public boolean connected = true;
        /** Extra chunks for every team level above 1. */
        public int chunksPerLevel = 2;
    }

    private final Data data;
    private final Clock clock;

    public TeamManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public static String key(String world, int cx, int cz) {
        return world + "|" + cx + "|" + cz;
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{3,16}");
    }

    public static boolean validTag(String tag) {
        return tag != null && tag.matches("[A-Za-z0-9]{1,5}");
    }

    // ---------------------------------------------------------------- lookups

    public synchronized Team get(String name) {
        return name == null ? null : data.teams.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized Team teamOf(UUID player) {
        String id = data.playerTeam.get(player);
        return id == null ? null : data.teams.get(id);
    }

    public synchronized Team at(String world, int cx, int cz) {
        String id = data.chunkOwner.get(key(world, cx, cz));
        return id == null ? null : data.teams.get(id);
    }

    public synchronized List<Team> list() {
        return new ArrayList<>(data.teams.values());
    }

    public synchronized Set<String> invitesOf(UUID player) {
        return new LinkedHashSet<>(data.invites.getOrDefault(player, Set.of()));
    }

    public synchronized boolean sameTeam(UUID a, UUID b) {
        String ta = data.playerTeam.get(a);
        return ta != null && ta.equals(data.playerTeam.get(b));
    }

    public int chunkLimit(Team t, Limits l) {
        return Math.min(l.maxChunks, l.baseChunks + l.chunksPerMember * t.members.size()) + l.chunksPerLevel * (level(t.xp) - 1);
    }

    // ---------------------------------------------------------------- membership

    public synchronized Result create(String name, String tag, UUID leader) {
        if (!validName(name) || (tag != null && !validTag(tag))) {
            return Result.BAD_NAME;
        }
        if (data.playerTeam.containsKey(leader)) {
            return Result.ALREADY_IN_TEAM;
        }
        String id = name.toLowerCase(Locale.ROOT);
        if (data.teams.containsKey(id)) {
            return Result.NAME_TAKEN;
        }
        String t = (tag == null ? name.substring(0, Math.min(4, name.length())) : tag).toUpperCase(Locale.ROOT);
        for (Team o : data.teams.values()) {
            if (o.tag.equalsIgnoreCase(t)) {
                return Result.TAG_TAKEN;
            }
        }
        Team team = new Team();
        team.id = id;
        team.name = name;
        team.tag = t;
        team.leader = leader;
        team.members.add(leader);
        team.created = clock.nowMillis();
        data.teams.put(id, team);
        data.playerTeam.put(leader, id);
        data.invites.remove(leader);
        return Result.OK;
    }

    public synchronized Result invite(UUID by, UUID target, Limits l) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!t.role(by).atLeast(Team.Role.OFFICER)) {
            return Result.NOT_ALLOWED;
        }
        if (by.equals(target)) {
            return Result.SELF;
        }
        if (data.playerTeam.containsKey(target)) {
            return Result.ALREADY_IN_TEAM;
        }
        if (t.members.size() >= l.maxMembers) {
            return Result.FULL;
        }
        data.invites.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(t.id);
        return Result.OK;
    }

    public synchronized Result join(UUID player, String name, Limits l) {
        Team t = get(name);
        if (t == null) {
            return Result.NO_SUCH_TEAM;
        }
        if (data.playerTeam.containsKey(player)) {
            return Result.ALREADY_IN_TEAM;
        }
        if (!t.open && !data.invites.getOrDefault(player, Set.of()).contains(t.id)) {
            return Result.NOT_INVITED;
        }
        if (t.members.size() >= l.maxMembers) {
            return Result.FULL;
        }
        t.members.add(player);
        data.playerTeam.put(player, t.id);
        data.invites.remove(player);
        return Result.OK;
    }

    public synchronized Result leave(UUID player) {
        Team t = teamOf(player);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (player.equals(t.leader)) {
            if (t.members.size() > 1) {
                return Result.LEADER_MUST_HAND_OVER;
            }
            removeTeam(t);
            return Result.OK;
        }
        t.members.remove(player);
        t.officers.remove(player);
        data.playerTeam.remove(player);
        return Result.OK;
    }

    public synchronized Result kick(UUID by, UUID target) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        Team.Role mine = t.role(by);
        Team.Role theirs = t.role(target);
        if (theirs == null) {
            return Result.NOT_A_MEMBER;
        }
        if (by.equals(target)) {
            return Result.SELF;
        }
        // Officers kick members; the leader kicks anyone.
        if (!(mine == Team.Role.LEADER || (mine == Team.Role.OFFICER && theirs == Team.Role.MEMBER))) {
            return Result.NOT_ALLOWED;
        }
        t.members.remove(target);
        t.officers.remove(target);
        data.playerTeam.remove(target);
        return Result.OK;
    }

    public synchronized Result setOfficer(UUID by, UUID target, boolean officer) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (t.role(by) != Team.Role.LEADER) {
            return Result.NOT_ALLOWED;
        }
        if (t.role(target) == null) {
            return Result.NOT_A_MEMBER;
        }
        if (by.equals(target)) {
            return Result.SELF;
        }
        if (officer) {
            t.officers.add(target);
        } else {
            t.officers.remove(target);
        }
        return Result.OK;
    }

    public synchronized Result handOver(UUID by, UUID target) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (t.role(by) != Team.Role.LEADER) {
            return Result.NOT_ALLOWED;
        }
        if (t.role(target) == null) {
            return Result.NOT_A_MEMBER;
        }
        if (by.equals(target)) {
            return Result.SELF;
        }
        t.leader = target;
        t.officers.remove(target);
        t.officers.add(by);
        return Result.OK;
    }

    public synchronized Result disband(UUID by) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (t.role(by) != Team.Role.LEADER) {
            return Result.NOT_ALLOWED;
        }
        removeTeam(t);
        return Result.OK;
    }

    // ---------------------------------------------------------------- allies

    /** Whether two players' teams are allies. */
    public synchronized boolean allied(UUID a, UUID b) {
        Team ta = teamOf(a);
        Team tb = teamOf(b);
        return ta != null && tb != null && ta != tb && ta.allies.contains(tb.id);
    }

    /**
     * Asks another team to be allies, or accepts if they already asked. Leader or officers only.
     *
     * @return OK when they're now allies, ALLY_REQUESTED when it's waiting for the other team
     */
    public synchronized Result ally(UUID by, String otherName, int maxAllies) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!t.role(by).atLeast(Team.Role.OFFICER)) {
            return Result.NOT_ALLOWED;
        }
        Team o = get(otherName);
        if (o == null) {
            return Result.NO_SUCH_TEAM;
        }
        if (o == t) {
            return Result.SELF;
        }
        if (t.allies.contains(o.id)) {
            return Result.ALREADY_ALLIES;
        }
        if (t.allies.size() >= maxAllies || (t.allyRequests.contains(o.id) && o.allies.size() >= maxAllies)) {
            return Result.TOO_MANY;
        }
        if (t.allyRequests.remove(o.id)) {
            t.allies.add(o.id);
            o.allies.add(t.id);
            o.allyRequests.remove(t.id);
            return Result.OK;
        }
        o.allyRequests.add(t.id);
        return Result.ALLY_REQUESTED;
    }

    public synchronized Result unally(UUID by, String otherName) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!t.role(by).atLeast(Team.Role.OFFICER)) {
            return Result.NOT_ALLOWED;
        }
        Team o = get(otherName);
        if (o == null) {
            return Result.NO_SUCH_TEAM;
        }
        if (!t.allies.remove(o.id)) {
            t.allyRequests.remove(o.id);
            return Result.NOT_ALLIES;
        }
        o.allies.remove(t.id);
        return Result.OK;
    }

    /** Removes a team completely (admins, or the last member leaving). */
    public synchronized void removeTeam(Team t) {
        data.wars.removeIf(w -> w.involves(t.id));
        data.warCooldown.remove(t.id);
        for (Team o : data.teams.values()) {
            o.allies.remove(t.id);
            o.allyRequests.remove(t.id);
        }
        data.teams.remove(t.id);
        for (UUID m : t.members) {
            data.playerTeam.remove(m);
        }
        for (String c : t.chunks) {
            data.chunkOwner.remove(c);
        }
        for (Set<String> inv : data.invites.values()) {
            inv.remove(t.id);
        }
    }

    // ---------------------------------------------------------------- territory

    public synchronized Result claim(UUID by, String world, int cx, int cz, Limits l) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!t.role(by).atLeast(Team.Role.OFFICER)) {
            return Result.NOT_ALLOWED;
        }
        String k = key(world, cx, cz);
        if (data.chunkOwner.containsKey(k)) {
            return Result.CHUNK_TAKEN;
        }
        if (t.chunks.size() >= chunkLimit(t, l)) {
            return Result.CHUNK_LIMIT;
        }
        if (l.connected && !t.chunks.isEmpty()
                && !t.chunks.contains(key(world, cx + 1, cz)) && !t.chunks.contains(key(world, cx - 1, cz))
                && !t.chunks.contains(key(world, cx, cz + 1)) && !t.chunks.contains(key(world, cx, cz - 1))) {
            return Result.NOT_CONNECTED;
        }
        t.chunks.add(k);
        data.chunkOwner.put(k, t.id);
        return Result.OK;
    }

    public synchronized Result unclaim(UUID by, String world, int cx, int cz) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!t.role(by).atLeast(Team.Role.OFFICER)) {
            return Result.NOT_ALLOWED;
        }
        String k = key(world, cx, cz);
        if (!t.chunks.remove(k)) {
            return Result.CHUNK_NOT_YOURS;
        }
        data.chunkOwner.remove(k);
        return Result.OK;
    }

    /** Admin: frees a chunk whoever owns it. @return the team it belonged to, or null */
    public synchronized Team adminUnclaim(String world, int cx, int cz) {
        String k = key(world, cx, cz);
        String id = data.chunkOwner.remove(k);
        Team t = id == null ? null : data.teams.get(id);
        if (t != null) {
            t.chunks.remove(k);
        }
        return t;
    }

    // ---------------------------------------------------------------- levels, bank, wars

    /** @return the new level if this XP made the team level up, else 0 */
    public synchronized int addXp(Team t, long amount) {
        int before = level(t.xp);
        t.xp = Math.max(0, t.xp + amount);
        int after = level(t.xp);
        return after > before ? after : 0;
    }

    private static void bankLog(Team t, String line) {
        t.bankLog.add(line);
        while (t.bankLog.size() > 20) {
            t.bankLog.remove(0);
        }
    }

    /** The player has already handed in the currency. */
    public synchronized Result deposit(UUID by, String byName, int amount) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (amount <= 0) {
            return Result.NOT_ENOUGH;
        }
        t.bank += amount;
        bankLog(t, clock.nowMillis() + "|" + byName + "|+" + amount);
        return Result.OK;
    }

    /** Officers and the leader take from the bank (the server then gives the currency). */
    public synchronized Result withdraw(UUID by, String byName, int amount) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (!t.role(by).atLeast(Team.Role.OFFICER)) {
            return Result.NOT_ALLOWED;
        }
        if (amount <= 0 || amount > t.bank) {
            return Result.NOT_ENOUGH;
        }
        t.bank -= amount;
        bankLog(t, clock.nowMillis() + "|" + byName + "|-" + amount);
        return Result.OK;
    }

    public synchronized War warOf(String teamId) {
        for (War w : data.wars) {
            if (w.involves(teamId)) {
                return w;
            }
        }
        return null;
    }

    public synchronized List<War> wars() {
        return new ArrayList<>(data.wars);
    }

    /** A leader starts a war with another team; both must be free and not allies. */
    public synchronized Result declareWar(UUID by, String otherName, long durationMs, long cooldownMs) {
        Team t = teamOf(by);
        if (t == null) {
            return Result.NOT_IN_TEAM;
        }
        if (t.role(by) != Team.Role.LEADER) {
            return Result.NOT_ALLOWED;
        }
        Team o = get(otherName);
        if (o == null) {
            return Result.NO_SUCH_TEAM;
        }
        if (o == t) {
            return Result.SELF;
        }
        if (t.allies.contains(o.id)) {
            return Result.ARE_ALLIES;
        }
        if (warOf(t.id) != null || warOf(o.id) != null) {
            return Result.AT_WAR;
        }
        long now = clock.nowMillis();
        if (data.warCooldown.getOrDefault(t.id, 0L) > now) {
            return Result.WAR_COOLDOWN;
        }
        War w = new War();
        w.a = t.id;
        w.b = o.id;
        w.started = now;
        w.endsAt = now + durationMs;
        data.wars.add(w);
        data.warCooldown.put(t.id, now + durationMs + cooldownMs);
        return Result.OK;
    }

    /** A kill between two teams at war scores a point. @return the war, or null if they aren't at war */
    public synchronized War warKill(UUID killer, UUID victim) {
        Team k = teamOf(killer);
        Team v = teamOf(victim);
        if (k == null || v == null || k == v) {
            return null;
        }
        War w = warOf(k.id);
        if (w == null || !w.involves(v.id)) {
            return null;
        }
        if (w.a.equals(k.id)) {
            w.scoreA++;
        } else {
            w.scoreB++;
        }
        return w;
    }

    /** Ends wars whose hour is up: records wins and losses. */
    public synchronized List<War> endWars() {
        long now = clock.nowMillis();
        List<War> ended = new ArrayList<>();
        for (War w : new ArrayList<>(data.wars)) {
            if (now < w.endsAt) {
                continue;
            }
            data.wars.remove(w);
            String win = w.winner();
            if (win != null) {
                Team winner = data.teams.get(win);
                Team loser = data.teams.get(win.equals(w.a) ? w.b : w.a);
                if (winner != null) {
                    winner.warsWon++;
                }
                if (loser != null) {
                    loser.warsLost++;
                }
            }
            ended.add(w);
        }
        return ended;
    }
}
