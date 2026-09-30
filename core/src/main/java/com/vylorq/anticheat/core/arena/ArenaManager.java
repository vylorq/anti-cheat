package com.vylorq.anticheat.core.arena;

import com.vylorq.anticheat.core.util.Clock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Arenas, kits, queues, duels, matches, stats and pending restores (section 22). */
public final class ArenaManager {
    public static final class Stats {
        public int wins;
        public int losses;
        public int kills;
        public Map<String, int[]> perKit = new LinkedHashMap<>();
    }

    public static final class Data {
        public Map<String, Arena> arenas = new LinkedHashMap<>();
        public Map<String, Kit> kits = new LinkedHashMap<>();
        public Map<UUID, Stats> stats = new LinkedHashMap<>();
        /** Snapshots waiting to be restored (disconnect/crash mid-match). Restored exactly once. */
        public Map<UUID, PlayerSnapshot> pendingRestore = new LinkedHashMap<>();
    }

    public record Duel(UUID from, UUID to, String kit, long at) {
    }

    private final Data data;
    private final Clock clock;
    /** "mode|kit" -> queue */
    private final Map<String, List<UUID>> queues = new LinkedHashMap<>();
    private final Map<String, Match> matches = new ConcurrentHashMap<>();
    private final Map<UUID, Match> byPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Duel> duels = new ConcurrentHashMap<>();
    private int nextMatch = 1;

    public ArenaManager(Data data, Clock clock) {
        this.data = data == null ? new Data() : data;
        this.clock = clock;
    }

    public Data data() {
        return data;
    }

    public synchronized Arena arena(String name) {
        return data.arenas.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean addArena(Arena a) {
        return data.arenas.putIfAbsent(a.name.toLowerCase(Locale.ROOT), a) == null;
    }

    public synchronized boolean removeArena(String name) {
        return data.arenas.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    public synchronized List<Arena> arenas() {
        return new ArrayList<>(data.arenas.values());
    }

    public synchronized Kit kit(String name) {
        return data.kits.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized void saveKit(Kit k) {
        data.kits.put(k.name.toLowerCase(Locale.ROOT), k);
    }

    public synchronized List<Kit> kits() {
        return new ArrayList<>(data.kits.values());
    }

    public Match matchOf(UUID player) {
        return byPlayer.get(player);
    }

    public boolean inMatch(UUID player) {
        return byPlayer.containsKey(player);
    }

    public List<Match> matches() {
        return new ArrayList<>(matches.values());
    }

    public synchronized boolean inQueue(UUID player) {
        for (List<UUID> q : queues.values()) {
            if (q.contains(player)) {
                return true;
            }
        }
        return false;
    }

    /** Players waiting for a match in this mode (any kit). */
    public synchronized int queued(Arena.Mode mode) {
        int n = 0;
        for (Map.Entry<String, List<UUID>> e : queues.entrySet()) {
            if (e.getKey().startsWith(mode.name() + "|")) {
                n += e.getValue().size();
            }
        }
        return n;
    }

    public synchronized void leaveQueue(UUID player) {
        for (List<UUID> q : queues.values()) {
            q.remove(player);
        }
    }

    /** Joins a queue. @return a new match when the queue fills and a free arena exists, else null. */
    public synchronized Match joinQueue(UUID player, Arena.Mode mode, String kit) {
        if (inMatch(player)) {
            return null;
        }
        leaveQueue(player);
        String key = mode.name() + "|" + kit;
        List<UUID> q = queues.computeIfAbsent(key, k -> new ArrayList<>());
        q.add(player);
        int need = mode == Arena.Mode.FFA ? Math.max(2, Math.min(q.size(), mode.players)) : mode.players;
        if (q.size() < need || (mode == Arena.Mode.FFA && q.size() < 3)) {
            return null;
        }
        Arena free = freeArena(mode, kit);
        if (free == null) {
            return null;
        }
        List<UUID> picked = new ArrayList<>(q.subList(0, need));
        q.removeAll(picked);
        return start(free, mode, kit, picked);
    }

    public synchronized Arena freeArena(Arena.Mode mode, String kit) {
        for (Arena a : data.arenas.values()) {
            if (!a.ready(mode)) {
                continue;
            }
            if (!a.rules.allowedKits.isEmpty() && !a.rules.allowedKits.contains(kit)) {
                continue;
            }
            boolean busy = false;
            for (Match m : matches.values()) {
                if (m.arena == a) {
                    busy = true;
                    break;
                }
            }
            if (!busy) {
                return a;
            }
        }
        return null;
    }

    /** Starts a match with players split into teams in order. */
    public synchronized Match start(Arena a, Arena.Mode mode, String kit, List<UUID> players) {
        List<UUID> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);
        List<List<UUID>> teams = new ArrayList<>();
        if (mode == Arena.Mode.FFA) {
            for (UUID p : shuffled) {
                teams.add(List.of(p));
            }
        } else {
            teams.add(new ArrayList<>(shuffled.subList(0, shuffled.size() / 2)));
            teams.add(new ArrayList<>(shuffled.subList(shuffled.size() / 2, shuffled.size())));
        }
        Match m = new Match("m" + nextMatch++, a, mode, kit, teams, clock.nowMillis());
        matches.put(m.id, m);
        for (UUID p : players) {
            byPlayer.put(p, m);
            leaveQueue(p);
        }
        return m;
    }

    public synchronized void end(Match m) {
        matches.remove(m.id);
        for (UUID p : m.players()) {
            byPlayer.remove(p, m);
        }
    }

    /** Removes a player from their match's player index (after they are returned). */
    public void detach(UUID player) {
        byPlayer.remove(player);
    }

    // ---- Duels ----

    public void requestDuel(UUID from, UUID to, String kit) {
        duels.put(to, new Duel(from, to, kit, clock.nowMillis()));
    }

    /** @return the duel if it exists, is from {@code from} and hasn't expired. */
    public Duel acceptDuel(UUID to, UUID from, long timeoutMillis) {
        Duel d = duels.get(to);
        if (d == null || !d.from().equals(from) || clock.nowMillis() - d.at() > timeoutMillis) {
            return null;
        }
        duels.remove(to);
        return d;
    }

    public Duel denyDuel(UUID to) {
        return duels.remove(to);
    }

    // ---- Stats ----

    public synchronized void recordResult(Match m) {
        for (int t = 0; t < m.teams.size(); t++) {
            for (UUID p : m.teams.get(t)) {
                Stats s = data.stats.computeIfAbsent(p, k -> new Stats());
                int[] kit = s.perKit.computeIfAbsent(m.kit, k -> new int[3]);
                if (t == m.winnerTeam) {
                    s.wins++;
                    kit[0]++;
                } else {
                    s.losses++;
                    kit[1]++;
                }
                int k = m.kills.getOrDefault(p, 0);
                s.kills += k;
                kit[2] += k;
            }
        }
    }

    public synchronized Stats stats(UUID p) {
        return data.stats.getOrDefault(p, new Stats());
    }

    // ---- Restores ----

    public synchronized void savePending(PlayerSnapshot s) {
        data.pendingRestore.put(s.player, s);
    }

    /** Takes the snapshot out so it can only be restored once. */
    public synchronized PlayerSnapshot takePending(UUID player) {
        return data.pendingRestore.remove(player);
    }

    public synchronized boolean hasPending(UUID player) {
        return data.pendingRestore.containsKey(player);
    }
}
