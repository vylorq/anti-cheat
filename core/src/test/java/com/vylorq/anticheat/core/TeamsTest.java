package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.team.TeamManager;
import com.vylorq.anticheat.core.team.TeamManager.Result;
import com.vylorq.anticheat.core.util.Clock;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TeamsTest {
    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();
    final TeamManager.Limits l = new TeamManager.Limits();

    TeamManager tm() {
        return new TeamManager(null, new Clock.Manual(0));
    }

    @Test
    void createInviteJoinRoles() {
        TeamManager m = tm();
        assertEquals(Result.BAD_NAME, m.create("x", null, a));
        assertEquals(Result.OK, m.create("Wolves", "WLF", a));
        assertEquals(Result.NAME_TAKEN, m.create("wolves", null, b));
        assertEquals(Result.TAG_TAKEN, m.create("Wolfpack", "wlf", b));
        assertEquals(Result.ALREADY_IN_TEAM, m.create("Other", null, a));
        assertEquals(Result.NOT_INVITED, m.join(b, "Wolves", l));
        assertEquals(Result.OK, m.invite(a, b, l));
        assertEquals(Result.OK, m.join(b, "wolves", l));
        assertTrue(m.sameTeam(a, b));
        assertEquals(Team.Role.MEMBER, m.teamOf(b).role(b));
        assertEquals(Result.NOT_ALLOWED, m.invite(b, c, l), "members can't invite");
        assertEquals(Result.OK, m.setOfficer(a, b, true));
        assertEquals(Result.OK, m.invite(b, c, l), "officers can");
        assertEquals(Result.OK, m.join(c, "Wolves", l));
        assertEquals(Result.NOT_ALLOWED, m.kick(b, a), "officer can't kick the leader");
        assertEquals(Result.OK, m.kick(b, c));
        assertNull(m.teamOf(c));
    }

    @Test
    void leaderLeavingAndHandOver() {
        TeamManager m = tm();
        m.create("Wolves", null, a);
        m.invite(a, b, l);
        m.join(b, "Wolves", l);
        assertEquals(Result.LEADER_MUST_HAND_OVER, m.leave(a));
        assertEquals(Result.OK, m.handOver(a, b));
        assertEquals(b, m.teamOf(a).leader);
        assertEquals(Result.OK, m.leave(a));
        assertEquals(Result.OK, m.leave(b), "last one out disbands");
        assertNull(m.get("Wolves"));
    }

    @Test
    void territoryRules() {
        TeamManager m = tm();
        m.create("Wolves", null, a);
        m.create("Bears", null, c);
        l.baseChunks = 2;
        l.chunksPerMember = 0;
        assertEquals(Result.OK, m.claim(a, "w", 0, 0, l));
        assertEquals(Result.NOT_CONNECTED, m.claim(a, "w", 5, 5, l));
        assertEquals(Result.OK, m.claim(a, "w", 1, 0, l));
        assertEquals(Result.CHUNK_LIMIT, m.claim(a, "w", 2, 0, l));
        assertEquals(Result.CHUNK_TAKEN, m.claim(c, "w", 0, 0, l));
        assertEquals("wolves", m.at("w", 1, 0).id);
        assertEquals(Result.CHUNK_NOT_YOURS, m.unclaim(c, "w", 0, 0));
        assertEquals(Result.OK, m.unclaim(a, "w", 1, 0));
        assertNull(m.at("w", 1, 0));
        m.disband(a);
        assertNull(m.at("w", 0, 0), "disbanding frees the land");
    }

    @Test
    void alliesNeedBothSides() {
        TeamManager m = tm();
        m.create("Wolves", null, a);
        m.create("Bears", null, c);
        assertEquals(Result.ALLY_REQUESTED, m.ally(a, "Bears", 3));
        assertFalse(m.allied(a, c), "not until they accept");
        assertEquals(Result.OK, m.ally(c, "Wolves", 3));
        assertTrue(m.allied(a, c));
        assertEquals(Result.ALREADY_ALLIES, m.ally(a, "Bears", 3));
        assertEquals(Result.OK, m.unally(c, "Wolves"));
        assertFalse(m.allied(a, c));
        m.ally(a, "Bears", 3);
        m.ally(c, "Wolves", 3);
        m.disband(c);
        assertTrue(m.teamOf(a).allies.isEmpty(), "disbanding removes the alliance");
    }
}
