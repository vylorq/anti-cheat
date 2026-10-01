package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.claims.ClaimAction;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.team.TeamManager;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server side of teams: territory protection, "entering ... territory" messages, name tags ([TAG] in the team
 * colour above heads and in the tab list), team chat and no friendly fire.
 */
public final class Teams {
    private Teams() {
    }

    private static final Map<UUID, String> LAST_AREA = new HashMap<>();
    private static final Set<UUID> CHAT = new HashSet<>();
    /** Names seen this session (for name tags of members who aren't online). */
    private static final Map<UUID, String> NAMES = new HashMap<>();

    public static void remember(ServerPlayerEntity p) {
        NAMES.put(p.getUuid(), p.getGameProfile().name());
    }

    public static TeamManager tm() {
        return Ac.get().teams;
    }

    private static AcConfig.Teams cfg() {
        return Ac.config().teams;
    }

    public static boolean enabled() {
        return Ac.running() && cfg().enabled;
    }

    public static TeamManager.Limits limits() {
        TeamManager.Limits l = new TeamManager.Limits();
        l.maxMembers = cfg().maxMembers;
        l.baseChunks = cfg().baseChunks;
        l.chunksPerMember = cfg().chunksPerMember;
        l.maxChunks = cfg().maxChunks;
        l.connected = cfg().connectedTerritory;
        return l;
    }

    public static Team at(World w, BlockPos pos) {
        return tm().at(Mc.worldId(w), pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static Formatting color(Team t) {
        Formatting f = Formatting.byName(t.color);
        return f == null || !f.isColor() ? Formatting.AQUA : f;
    }

    public static String tagText(Team t) {
        return "§" + color(t).getCode() + "[" + t.tag + "]";
    }

    /** Territory protection: outsiders can walk in, but can't build, break, open, or use anything. */
    public static boolean allowed(ServerPlayerEntity p, World w, BlockPos pos, ClaimAction a) {
        if (!enabled() || !cfg().protectTerritory || a == ClaimAction.ENTER || a == ClaimAction.PVP) {
            return true;
        }
        Team t = at(w, pos);
        if (t == null || t.members.contains(p.getUuid()) || Perms.isActiveStaff(p) || BuilderMode.is(p)) {
            return true;
        }
        Msg.actionBar(p, Msg.trFor(p, "team.protected", tagText(t) + " §f" + t.name));
        return false;
    }

    /** No hurting your own team (unless the team turned friendly fire on). */
    public static boolean friendlyFireBlocked(ServerPlayerEntity attacker, ServerPlayerEntity victim) {
        if (!enabled()) {
            return false;
        }
        Team t = tm().teamOf(attacker.getUuid());
        return t != null && !t.friendlyFire && t.members.contains(victim.getUuid());
    }

    // ---------------------------------------------------------------- chat

    public static boolean toggleChat(ServerPlayerEntity p) {
        if (!CHAT.remove(p.getUuid())) {
            CHAT.add(p.getUuid());
            return true;
        }
        return false;
    }

    /** Sends a team chat message. @return false if they aren't in a team */
    public static boolean teamChat(ServerPlayerEntity p, String message) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            return false;
        }
        Text line = Text.literal(tagText(t) + " §7" + p.getGameProfile().name() + " §8» §f" + message);
        for (UUID m : t.members) {
            ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
            if (o != null) {
                o.sendMessage(line);
            }
        }
        Ac.LOG.info("[Team {}] {}: {}", t.name, p.getGameProfile().name(), message);
        return true;
    }

    /** Chat hook: players with team chat on talk to their team only. @return true when handled */
    public static boolean onChat(ServerPlayerEntity p, String message) {
        if (!enabled() || !CHAT.contains(p.getUuid())) {
            return false;
        }
        if (!teamChat(p, message)) {
            CHAT.remove(p.getUuid());
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- every second

    public static void tick() {
        if (!enabled()) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            remember(p);
            ChunkPos c = p.getChunkPos();
            Team t = tm().at(Mc.worldId(p.getEntityWorld()), c.x, c.z);
            String area = t == null ? "" : t.id;
            String last = LAST_AREA.put(p.getUuid(), area);
            if (last != null && !last.equals(area)) {
                if (t != null) {
                    boolean own = t.members.contains(p.getUuid());
                    Msg.actionBar(p, Msg.trFor(p, own ? "team.enter-own" : "team.enter", tagText(t) + " §f" + t.name));
                } else {
                    Msg.actionBar(p, Msg.trFor(p, "team.wilderness"));
                }
            }
        }
    }

    public static void forget(UUID id) {
        LAST_AREA.remove(id);
    }

    // ---------------------------------------------------------------- name tags

    private static String sbName(Team t) {
        return "vt_" + (t.id.length() > 13 ? t.id.substring(0, 13) : t.id);
    }

    /** Puts every team on the server scoreboard so the tag shows above heads and in the tab list. */
    public static void syncTags() {
        if (!Ac.running()) {
            return;
        }
        Scoreboard sb = Ac.server().getScoreboard();
        Set<String> wanted = new HashSet<>();
        if (cfg().enabled && cfg().nameTags) {
            for (Team t : tm().list()) {
                String n = sbName(t);
                wanted.add(n);
                net.minecraft.scoreboard.Team st = sb.getTeam(n);
                if (st == null) {
                    st = sb.addTeam(n);
                }
                st.setPrefix(Text.literal("[" + t.tag + "] ").formatted(color(t)));
                st.setColor(color(t));
                st.setFriendlyFireAllowed(t.friendlyFire);
                for (UUID m : t.members) {
                    String name = name(m);
                    if (name != null && sb.getScoreHolderTeam(name) != st) {
                        sb.addScoreHolderToTeam(name, st);
                    }
                }
                for (String holder : new HashSet<>(st.getPlayerList())) {
                    UUID id = idOf(holder);
                    if (id == null || !t.members.contains(id)) {
                        sb.removeScoreHolderFromTeam(holder, st);
                    }
                }
            }
        }
        for (net.minecraft.scoreboard.Team st : new HashSet<>(sb.getTeams())) {
            if (st.getName().startsWith("vt_") && !wanted.contains(st.getName())) {
                sb.removeTeam(st);
            }
        }
    }

    static String name(UUID id) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
        if (p != null) {
            return p.getGameProfile().name();
        }
        String n = Ac.get().joins.name(id);
        return n != null ? n : NAMES.get(id);
    }

    private static UUID idOf(String name) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(name);
        if (p != null) {
            return p.getUuid();
        }
        for (Team t : tm().list()) {
            for (UUID m : t.members) {
                if (name.equalsIgnoreCase(name(m))) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Whether a chunk may become team territory (not in the lobby, not in an admin claim). */
    public static boolean claimable(ServerWorld w, ChunkPos c) {
        BlockPos center = new BlockPos(c.getCenterX(), 64, c.getCenterZ());
        for (int[] corner : new int[][]{{c.getStartX(), c.getStartZ()}, {c.getEndX(), c.getEndZ()}, {c.getStartX(), c.getEndZ()},
                {c.getEndX(), c.getStartZ()}, {center.getX(), center.getZ()}}) {
            BlockPos pos = new BlockPos(corner[0], 64, corner[1]);
            if (Claims.at(w, pos) != null) {
                return false;
            }
            Area lobby = Ac.get().lobby.data().area;
            if (lobby != null && lobby.world.equals(Mc.worldId(w)) && corner[0] >= lobby.minX && corner[0] <= lobby.maxX
                    && corner[1] >= lobby.minZ && corner[1] <= lobby.maxZ) {
                return false;
            }
        }
        return true;
    }
}
