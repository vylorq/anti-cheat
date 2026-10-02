package com.vylorq.anticheat.core.team;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A player team: a leader, officers, members, a short tag, a colour, a home and its territory (chunks). */
public final class Team {
    public String id;
    public String name;
    public String tag;
    /** Minecraft colour name (aqua, red, gold...). */
    public String color = "aqua";
    public UUID leader;
    public Set<UUID> officers = new LinkedHashSet<>();
    /** Everyone in the team, leader and officers included. */
    public Set<UUID> members = new LinkedHashSet<>();
    public long created;
    public String description = "";
    /** Anyone can join without an invite. */
    public boolean open;
    public boolean friendlyFire;
    public String homeWorld;
    public double homeX;
    public double homeY;
    public double homeZ;
    public float homeYaw;
    /** Claimed chunks as "world|x|z". */
    public Set<String> chunks = new LinkedHashSet<>();
    /** Allied team ids (both sides list each other). */
    public Set<String> allies = new LinkedHashSet<>();
    /** Teams that asked to be allies with this one. */
    public Set<String> allyRequests = new LinkedHashSet<>();
    /** Shared vault, as encoded "slot:item" strings. */
    public java.util.List<String> vault = new java.util.ArrayList<>();
    /** Message shown to members when they join. */
    public String motd = "";
    /** Allies can hurt this team's members (and the other way round); either team can turn it off. */
    public boolean allyFire = true;
    /** Allies see this team's land on the map and borders; either team can turn it off. */
    public boolean shareMap = true;
    /** Team experience (playtime, kills, trades, wars): decides the level. */
    public long xp;
    /** Team bank: currency items held for the team. */
    public long bank;
    /** Recent bank deposits and withdrawals, newest last. */
    public java.util.List<String> bankLog = new java.util.ArrayList<>();
    /** Wars won and lost. */
    public int warsWon;
    public int warsLost;

    public Role role(UUID player) {
        if (player == null || !members.contains(player)) {
            return null;
        }
        if (player.equals(leader)) {
            return Role.LEADER;
        }
        return officers.contains(player) ? Role.OFFICER : Role.MEMBER;
    }

    public boolean hasHome() {
        return homeWorld != null;
    }

    public enum Role {
        LEADER, OFFICER, MEMBER;

        public boolean atLeast(Role r) {
            return ordinal() <= r.ordinal();
        }
    }
}
