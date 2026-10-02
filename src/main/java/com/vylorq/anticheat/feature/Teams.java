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
        l.chunksPerLevel = cfg().chunksPerLevel;
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

    /**
     * Team land isn't protected: anyone can build, break, open or fight in it. Members just get a raid alert when
     * an outsider changes something or opens a container there.
     */
    public static void watch(ServerPlayerEntity p, World w, BlockPos pos, ClaimAction a) {
        if (!enabled() || !(a.isChange() || a == ClaimAction.CONTAINER)) {
            return;
        }
        Team t = at(w, pos);
        if (t == null || t.members.contains(p.getUuid()) || Perms.isActiveStaff(p) || BuilderMode.is(p)) {
            return;
        }
        raidAlert(t, p, pos);
    }

    private static final Map<String, Long> ALERTED = new HashMap<>();

    /** Tells the team's online members someone is trying to get into their land (not too often). */
    static void raidAlert(Team t, ServerPlayerEntity intruder, BlockPos pos) {
        String k = t.id + "|" + intruder.getUuid();
        long now = System.currentTimeMillis();
        Long last = ALERTED.get(k);
        if (last != null && now - last < cfg().raidAlertSeconds * 1000L) {
            return;
        }
        ALERTED.put(k, now);
        for (UUID m : t.members) {
            ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
            if (o != null) {
                o.sendMessage(Msg.prefixed(Msg.trFor(o, "team.raid", intruder.getGameProfile().name(),
                        pos.getX() + " " + pos.getY() + " " + pos.getZ())));
                o.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                        net.minecraft.registry.Registries.SOUND_EVENT.getEntry(net.minecraft.sound.SoundEvents.BLOCK_BELL_USE), net.minecraft.sound.SoundCategory.MASTER,
                        o.getX(), o.getY(), o.getZ(), 1f, 0.8f, o.getRandom().nextLong()));
            }
        }
    }

    /** No hurting your own team (unless the team turned friendly fire on). */
    public static boolean friendlyFireBlocked(ServerPlayerEntity attacker, ServerPlayerEntity victim) {
        if (!enabled()) {
            return false;
        }
        Team t = tm().teamOf(attacker.getUuid());
        return t != null && !t.friendlyFire && t.members.contains(victim.getUuid());
    }

    /** Whether a player may not hurt another: teammates (friendly fire off) or allies (ally fire off on either side). */
    public static boolean damageBlocked(ServerPlayerEntity attacker, ServerPlayerEntity victim) {
        if (!enabled()) {
            return false;
        }
        if (friendlyFireBlocked(attacker, victim)) {
            return true;
        }
        if (!tm().allied(attacker.getUuid(), victim.getUuid())) {
            return false;
        }
        Team a = tm().teamOf(attacker.getUuid());
        Team v = tm().teamOf(victim.getUuid());
        return !a.allyFire || !v.allyFire;
    }

    // ---------------------------------------------------------------- vault

    private static final Map<String, net.minecraft.inventory.SimpleInventory> VAULTS = new HashMap<>();

    /** The team's shared chest (27 slots), saved whenever it changes. */
    /** 27 slots, or a double chest (54) from the configured team level. */
    public static int vaultSize(Team t) {
        return TeamManager.level(t.xp) >= cfg().bigVaultLevel ? 54 : 27;
    }

    public static net.minecraft.inventory.SimpleInventory vault(Team t) {
        net.minecraft.inventory.SimpleInventory cached = VAULTS.get(t.id);
        if (cached != null && cached.size() != vaultSize(t)) {
            // The team levelled up: rebuild bigger (t.vault already holds every item by slot).
            closeViewers(cached);
            VAULTS.remove(t.id);
        }
        int size = vaultSize(t);
        return VAULTS.computeIfAbsent(t.id, k -> {
            net.minecraft.inventory.SimpleInventory inv = new net.minecraft.inventory.SimpleInventory(size);
            for (String e : t.vault) {
                int slot = com.vylorq.anticheat.util.ItemConv.slotOf(e);
                if (slot >= 0 && slot < size) {
                    inv.setStack(slot, com.vylorq.anticheat.util.ItemConv.decodeSlot(e));
                }
            }
            inv.addListener(changed -> {
                Team now = tm().get(k);
                if (now == null) {
                    return;
                }
                now.vault.clear();
                for (int i = 0; i < changed.size(); i++) {
                    if (!changed.getStack(i).isEmpty()) {
                        now.vault.add(com.vylorq.anticheat.util.ItemConv.encodeSlot(i, changed.getStack(i)));
                    }
                }
                Ac.markDirty("teams");
            });
            return inv;
        });
    }

    public static void openVault(ServerPlayerEntity p) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            Msg.send(p, "team.r.not_in_team");
            return;
        }
        var inv = vault(t);
        p.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, pl) -> inv.size() == 54
                        ? net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x6(syncId, playerInv, inv)
                        : net.minecraft.screen.GenericContainerScreenHandler.createGeneric9x3(syncId, playerInv, inv),
                Text.literal(tagText(t) + " §8" + Msg.trFor(p, "team.vault"))));
    }

    /** When a team is gone its vault items drop at the leader's feet if online, else are kept in the log. */
    /** Drops the cached vault; its items go to {@code to} (if given) so nothing is lost on disband. */
    public static void forgetVault(Team t, ServerPlayerEntity to) {
        net.minecraft.inventory.SimpleInventory inv = vault(t);
        VAULTS.remove(t.id);
        closeViewers(inv);
        if (to != null) {
            for (int i = 0; i < inv.size(); i++) {
                net.minecraft.item.ItemStack st = inv.getStack(i);
                if (!st.isEmpty()) {
                    to.getInventory().offerOrDrop(st.copy());
                }
            }
        }
        t.vault.clear();
    }

    private static void closeViewers(net.minecraft.inventory.Inventory inv) {
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            if (o.currentScreenHandler instanceof net.minecraft.screen.GenericContainerScreenHandler h && h.getInventory() == inv) {
                o.closeHandledScreen();
            }
        }
    }

    // ---------------------------------------------------------------- levels and wars

    /** Adds team XP and celebrates a level-up. */
    public static void addXp(Team t, long amount) {
        if (t == null || amount <= 0) {
            return;
        }
        int lvl = tm().addXp(t, amount);
        Ac.markDirty("teams");
        if (lvl > 0) {
            for (UUID m : t.members) {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
                if (o != null) {
                    Mc.title(o, "§6§l" + Msg.trFor(o, "team.level-up-title"), Msg.trFor(o, "team.level-up", lvl), 10, 60, 20);
                    Mc.sound(o, net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                    Msg.send(o, "team.level-perks", tm().chunkLimit(t, limits()), vaultSize(t));
                }
            }
        }
    }

    /** A trade by this player gives their team some XP. */
    public static void xpForTrade(ServerPlayerEntity p) {
        if (p != null && enabled()) {
            addXp(tm().teamOf(p.getUuid()), cfg().xpPerTrade);
        }
    }

    /** A player killed another: team XP and war points. */
    public static void onKill(ServerPlayerEntity victim, ServerPlayerEntity killer) {
        if (!enabled() || killer == null || killer == victim || tm().sameTeam(killer.getUuid(), victim.getUuid())) {
            return;
        }
        String vip = Ac.session(victim).ip;
        if (vip != null && vip.equals(Ac.session(killer).ip)) {
            return;
        }
        Team kt = tm().teamOf(killer.getUuid());
        addXp(kt, cfg().xpPerKill);
        TeamManager.War w = tm().warKill(killer.getUuid(), victim.getUuid());
        if (w != null) {
            Ac.markDirty("teams");
            Team a = tm().data().teams.get(w.a);
            Team b = tm().data().teams.get(w.b);
            for (Team t : new Team[]{a, b}) {
                if (t == null) {
                    continue;
                }
                for (UUID m : t.members) {
                    ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
                    if (o != null) {
                        Msg.actionBar(o, Msg.trFor(o, "team.war.score", warLine(w)));
                    }
                }
            }
        }
    }

    public static String warLine(TeamManager.War w) {
        Team a = tm().data().teams.get(w.a);
        Team b = tm().data().teams.get(w.b);
        return (a == null ? "?" : tagText(a) + " " + a.name) + " §f" + w.scoreA + " §7- §f" + w.scoreB + " " + (b == null ? "?" : tagText(b) + " " + b.name);
    }

    public static void declareWar(ServerPlayerEntity p, String other) {
        TeamManager.Result r = tm().declareWar(p.getUuid(), other, cfg().warMinutes * 60_000L, cfg().warCooldownMinutes * 60_000L);
        if (r != TeamManager.Result.OK) {
            Msg.send(p, "team.r." + r.name().toLowerCase(java.util.Locale.ROOT), other);
            return;
        }
        Ac.markDirty("teams");
        Team a = tm().teamOf(p.getUuid());
        Team b = tm().get(other);
        String msg = Msg.tr("team.war.declared", tagText(a) + " " + a.name, tagText(b) + " " + b.name, cfg().warMinutes);
        com.vylorq.anticheat.command.TeamCommands.showOnScreen(Ac.server().getPlayerManager().getPlayerList(), "team.war.title",
                "§c§l" + Msg.tr("team.war.title") + "|" + msg);
    }

    private static int minuteTimer;

    private static void tickWarsAndXp() {
        for (TeamManager.War w : tm().endWars()) {
            Ac.markDirty("teams");
            String win = w.winner();
            Team winner = win == null ? null : tm().data().teams.get(win);
            String sub = winner == null ? Msg.tr("team.war.draw", warLine(w)) : Msg.tr("team.war.won", tagText(winner) + " " + winner.name, warLine(w));
            com.vylorq.anticheat.command.TeamCommands.showOnScreen(Ac.server().getPlayerManager().getPlayerList(), "team.war.title",
                    "§6§l" + Msg.tr("team.war.over") + "|" + sub);
            addXp(winner, cfg().xpWarWin);
        }
        if (++minuteTimer >= 60) {
            minuteTimer = 0;
            for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                addXp(tm().teamOf(p.getUuid()), cfg().xpPerMinute);
            }
        }
    }

    public static int cfgAllies() {
        return cfg().maxAllies;
    }

    // ---------------------------------------------------------------- join, respawn, border

    public static void onJoin(ServerPlayerEntity p) {
        if (!enabled()) {
            return;
        }
        remember(p);
        Team t = tm().teamOf(p.getUuid());
        if (t != null && t.motd != null && !t.motd.isBlank()) {
            p.sendMessage(Text.literal(tagText(t) + " §6" + Msg.trFor(p, "team.motd-head") + " §f" + t.motd.replace('&', '§')));
        }
        if (t != null && !t.allyRequests.isEmpty() && t.role(p.getUuid()).atLeast(Team.Role.OFFICER)) {
            Msg.send(p, "team.ally-requests", String.join(", ", t.allyRequests));
        }
    }

    private static final Map<UUID, Long> BORDER = new HashMap<>();

    /** Shows the edges of team land around the player for 30 seconds. @return whether it's now on */
    public static boolean toggleBorder(ServerPlayerEntity p) {
        if (BORDER.remove(p.getUuid()) != null) {
            return false;
        }
        BORDER.put(p.getUuid(), System.currentTimeMillis() + 30_000);
        return true;
    }

    private static void drawBorders(ServerPlayerEntity p) {
        String w = Mc.worldId(p.getEntityWorld());
        ChunkPos c = p.getChunkPos();
        double y = p.getY() + 0.5;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int cx = c.x + dx;
                int cz = c.z + dz;
                Team t = tm().at(w, cx, cz);
                if (t == null || !visible(t, tm().teamOf(p.getUuid()))) {
                    continue;
                }
                var effect = t.members.contains(p.getUuid()) ? net.minecraft.particle.ParticleTypes.HAPPY_VILLAGER
                        : net.minecraft.particle.ParticleTypes.SOUL_FIRE_FLAME;
                int x0 = cx << 4;
                int z0 = cz << 4;
                // Only the edges that touch land that isn't this team's.
                for (int i = 0; i <= 16; i += 2) {
                    if (tm().at(w, cx, cz - 1) != t) {
                        Mc.particle(p, effect, x0 + i, y, z0);
                    }
                    if (tm().at(w, cx, cz + 1) != t) {
                        Mc.particle(p, effect, x0 + i, y, z0 + 16);
                    }
                    if (tm().at(w, cx - 1, cz) != t) {
                        Mc.particle(p, effect, x0, y, z0 + i);
                    }
                    if (tm().at(w, cx + 1, cz) != t) {
                        Mc.particle(p, effect, x0 + 16, y, z0 + i);
                    }
                }
            }
        }
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

    /** Sends a message to the team and all its allies. @return false if they aren't in a team */
    public static boolean allyChat(ServerPlayerEntity p, String message) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            return false;
        }
        Text line = Text.literal("§3[" + Msg.tr("team.ally") + "] " + tagText(t) + " §7" + p.getGameProfile().name() + " §8» §b" + message);
        Set<UUID> to = new HashSet<>(t.members);
        for (String a : t.allies) {
            Team o = tm().get(a);
            if (o != null) {
                to.addAll(o.members);
            }
        }
        for (UUID m : to) {
            ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
            if (o != null) {
                o.sendMessage(line);
            }
        }
        Ac.LOG.info("[Allies {}] {}: {}", t.name, p.getGameProfile().name(), message);
        return true;
    }

    // ---------------------------------------------------------------- ping

    /** Whether {@code mine}'s members may see {@code t}'s land: their own, or an ally's while both share the map. */
    public static boolean visible(Team t, Team mine) {
        return t != null && mine != null && (t == mine || (mine.allies.contains(t.id) && mine.shareMap && t.shareMap));
    }

    /** A small map of the land around the player, in chat. Only their own team and allies are shown, never other teams. */
    public static void showMap(ServerPlayerEntity p) {
        String w = Mc.worldId(p.getEntityWorld());
        ChunkPos c = p.getChunkPos();
        Team mine = tm().teamOf(p.getUuid());
        p.sendMessage(Text.literal("§8§m          §r §6" + Msg.trFor(p, "team.map-head") + " §8§m          "));
        for (int dz = -4; dz <= 4; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -8; dx <= 8; dx++) {
                if (dx == 0 && dz == 0) {
                    row.append("§f✚");
                    continue;
                }
                Team t = tm().at(w, c.x + dx, c.z + dz);
                if (!visible(t, mine)) {
                    row.append("§7▪");
                } else if (t == mine) {
                    row.append("§a■");
                } else {
                    row.append("§b■");
                }
            }
            p.sendMessage(Text.literal(row.toString()));
        }
        p.sendMessage(Text.literal(Msg.trFor(p, "team.map-key")));
    }

    private static final Map<UUID, Long> PINGED = new HashMap<>();

    /** Tells teammates where the player is standing. @return false if they aren't in a team */
    public static boolean ping(ServerPlayerEntity p, String note) {
        Team t = tm().teamOf(p.getUuid());
        if (t == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = PINGED.get(p.getUuid());
        if (last != null && now - last < 5000) {
            Msg.send(p, "team.ping-wait");
            return true;
        }
        PINGED.put(p.getUuid(), now);
        BlockPos pos = p.getBlockPos();
        String where = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        for (UUID m : t.members) {
            ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(m);
            if (o == null) {
                continue;
            }
            String dist = o.getEntityWorld() == p.getEntityWorld()
                    ? String.valueOf((int) Math.sqrt(o.squaredDistanceTo(p.getX(), p.getY(), p.getZ()))) + "m"
                    : Mc.worldId(p.getEntityWorld());
            o.sendMessage(Text.literal(tagText(t) + " §e" + Msg.trFor(o, "team.ping", p.getGameProfile().name(), where, dist)
                    + (note == null || note.isBlank() ? "" : " §7- §f" + note)));
            o.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket(
                    net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL, net.minecraft.sound.SoundCategory.MASTER,
                    o.getX(), o.getY(), o.getZ(), 1f, 1.5f, o.getRandom().nextLong()));
        }
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
        long now = System.currentTimeMillis();
        tickWarsAndXp();
        BORDER.values().removeIf(until -> now > until);
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            remember(p);
            if (BORDER.containsKey(p.getUuid())) {
                drawBorders(p);
            }
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
