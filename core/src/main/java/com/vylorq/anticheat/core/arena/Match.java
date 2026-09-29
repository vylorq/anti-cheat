package com.vylorq.anticheat.core.arena;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One match's state machine: countdown → fight → round end → ... → ended. */
public final class Match {
    public enum Phase { COUNTDOWN, FIGHTING, ROUND_OVER, ENDED }

    public final String id;
    public final Arena arena;
    public final Arena.Mode mode;
    public final String kit;
    /** team -> players */
    public final List<List<UUID>> teams = new ArrayList<>();
    public final Set<UUID> alive = new HashSet<>();
    public final Map<Integer, Integer> roundWins = new HashMap<>();
    public final Map<UUID, Integer> kills = new LinkedHashMap<>();
    public final Set<UUID> spectators = new HashSet<>();
    public Phase phase = Phase.COUNTDOWN;
    public long phaseStart;
    public int round = 1;
    public boolean suddenDeath;
    public int winnerTeam = -1;

    public Match(String id, Arena arena, Arena.Mode mode, String kit, List<List<UUID>> teams, long now) {
        this.id = id;
        this.arena = arena;
        this.mode = mode;
        this.kit = kit;
        for (List<UUID> t : teams) {
            this.teams.add(new ArrayList<>(t));
        }
        startRound(now);
    }

    public List<UUID> players() {
        List<UUID> out = new ArrayList<>();
        teams.forEach(out::addAll);
        return out;
    }

    public int teamOf(UUID p) {
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).contains(p)) {
                return i;
            }
        }
        return -1;
    }

    public boolean sameTeam(UUID a, UUID b) {
        int t = teamOf(a);
        return t >= 0 && t == teamOf(b) && mode != Arena.Mode.FFA;
    }

    public void startRound(long now) {
        alive.clear();
        alive.addAll(players());
        phase = Phase.COUNTDOWN;
        phaseStart = now;
        suddenDeath = false;
    }

    /** Countdown finished: movement unfreezes. */
    public void beginFight(long now) {
        phase = Phase.FIGHTING;
        phaseStart = now;
    }

    /**
     * A player died or disconnected (a disconnect counts as a loss).
     *
     * @return the winning team index of the round if it just ended, else -1
     */
    public int eliminate(UUID dead, UUID killer) {
        if (!alive.remove(dead)) {
            return -1;
        }
        if (killer != null && !killer.equals(dead)) {
            kills.merge(killer, 1, Integer::sum);
        }
        return roundWinner();
    }

    /** Team that still has players alive when every other team is out, else -1. */
    public int roundWinner() {
        int teamAlive = -1;
        for (int i = 0; i < teams.size(); i++) {
            boolean any = false;
            for (UUID p : teams.get(i)) {
                if (alive.contains(p)) {
                    any = true;
                    break;
                }
            }
            if (any) {
                if (teamAlive != -1) {
                    return -1;
                }
                teamAlive = i;
            }
        }
        return teamAlive;
    }

    /**
     * Records a round win.
     *
     * @return true when the match is over (someone won best-of-N)
     */
    public boolean finishRound(int team, long now) {
        phase = Phase.ROUND_OVER;
        phaseStart = now;
        int wins = roundWins.merge(team, 1, Integer::sum);
        int needed = arena.rules.bestOf / 2 + 1;
        if (wins >= needed || mode == Arena.Mode.FFA) {
            winnerTeam = team;
            phase = Phase.ENDED;
            return true;
        }
        round++;
        return false;
    }

    /** Team with the most alive players (ties: most kills) — used at the time limit. */
    public int leadingTeam() {
        int best = -1;
        int bestAlive = -1;
        int bestKills = -1;
        for (int i = 0; i < teams.size(); i++) {
            int a = 0;
            int k = 0;
            for (UUID p : teams.get(i)) {
                if (alive.contains(p)) {
                    a++;
                }
                k += kills.getOrDefault(p, 0);
            }
            if (a > bestAlive || (a == bestAlive && k > bestKills)) {
                best = i;
                bestAlive = a;
                bestKills = k;
            }
        }
        return best;
    }
}
