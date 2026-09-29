package com.vylorq.anticheat.core.arena;

import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Location;

import java.util.ArrayList;
import java.util.List;

/** A PvP arena (section 22). */
public final class Arena {
    public enum Mode {
        ONE_V_ONE(2, 1), TWO_V_TWO(4, 2), THREE_V_THREE(6, 3), FFA(8, 1);

        public final int players;
        public final int teamSize;

        Mode(int players, int teamSize) {
            this.players = players;
            this.teamSize = teamSize;
        }

        public int teams() {
            return this == FFA ? players : 2;
        }

        public static Mode parse(String s) {
            return switch (s.toLowerCase()) {
                case "1v1" -> ONE_V_ONE;
                case "2v2" -> TWO_V_TWO;
                case "3v3" -> THREE_V_THREE;
                case "ffa" -> FFA;
                default -> null;
            };
        }

        public String label() {
            return switch (this) {
                case ONE_V_ONE -> "1v1";
                case TWO_V_TWO -> "2v2";
                case THREE_V_THREE -> "3v3";
                case FFA -> "ffa";
            };
        }
    }

    public enum SuddenDeath { NONE, SHRINKING_BORDER, GLOWING }

    public static final class Rules {
        public int bestOf = 1;
        public int timeLimitSeconds = 300;
        public SuddenDeath suddenDeath = SuddenDeath.GLOWING;
        public boolean naturalRegen = true;
        public List<String> allowedKits = new ArrayList<>();
    }

    public String name;
    public Area area;
    /** Spawn points per team (team index -> spawns). FFA uses team 0's list for everyone. */
    public List<List<Location>> teamSpawns = new ArrayList<>();
    public Location spectatorSpot;
    public Location returnPoint;
    public boolean enabled = true;
    public List<Mode> modes = new ArrayList<>(List.of(Mode.ONE_V_ONE));
    public Rules rules = new Rules();
    /** Name of the block snapshot used to reset the arena after each match. */
    public String snapshot;

    public boolean ready(Mode mode) {
        if (!enabled || area == null || !modes.contains(mode)) {
            return false;
        }
        if (mode == Mode.FFA) {
            return !teamSpawns.isEmpty() && !teamSpawns.get(0).isEmpty();
        }
        return teamSpawns.size() >= 2 && !teamSpawns.get(0).isEmpty() && !teamSpawns.get(1).isEmpty();
    }

    public Location spawnFor(int team, int indexInTeam) {
        List<Location> list = teamSpawns.get(Math.min(team, teamSpawns.size() - 1));
        return list.get(indexInTeam % list.size());
    }
}
