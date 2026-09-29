package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.Arena;
import com.vylorq.anticheat.core.arena.ArenaManager;
import com.vylorq.anticheat.core.arena.Kit;
import com.vylorq.anticheat.core.arena.Match;
import com.vylorq.anticheat.core.arena.PlayerSnapshot;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PvP arenas: running matches (section 22). */
public final class Arenas {
    /** Players spectating an arena: snapshot to return to. */
    private static final Map<UUID, String> SPECTATING = new HashMap<>();

    private Arenas() {
    }

    private static ArenaManager am() {
        return Ac.get().arenas;
    }

    public static boolean inMatch(ServerPlayerEntity p) {
        return am().inMatch(p.getUuid());
    }

    public static boolean isCountdownFrozen(ServerPlayerEntity p) {
        Match m = am().matchOf(p.getUuid());
        return m != null && (m.phase == Match.Phase.COUNTDOWN || m.phase == Match.Phase.ROUND_OVER);
    }

    public static boolean noNaturalRegen(ServerPlayerEntity p) {
        Match m = am().matchOf(p.getUuid());
        if (m == null) {
            return false;
        }
        Kit k = am().kit(m.kit);
        return !m.arena.rules.naturalRegen || (k != null && !k.naturalRegen);
    }

    private static List<ServerPlayerEntity> online(Match m) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (UUID id : m.players()) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Can this player enter a match right now? */
    public static String blockedReason(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        if (ac.jail.isJailed(p.getUuid())) {
            return "arena.blocked.jail";
        }
        if (WaitingRoomFeature.waiting(p) || ac.staff.isFrozen(p.getUuid())) {
            return "arena.blocked.state";
        }
        if (am().inMatch(p.getUuid())) {
            return "arena.blocked.in-match";
        }
        if (am().hasPending(p.getUuid())) {
            return "arena.blocked.pending";
        }
        if (Trades.inTrade(p)) {
            return "arena.blocked.trade";
        }
        return null;
    }

    public static void join(ServerPlayerEntity p, Arena.Mode mode, String kit) {
        String why = blockedReason(p);
        if (why != null) {
            Msg.send(p, why);
            return;
        }
        if (am().kit(kit) == null) {
            Msg.send(p, "arena.no-kit", kit);
            return;
        }
        Match m = am().joinQueue(p.getUuid(), mode, kit);
        if (m == null) {
            Msg.send(p, "arena.queued", mode.label(), kit);
            return;
        }
        start(m);
    }

    /** Starts a match: save each player, teleport in, give kits, freeze for the countdown. */
    public static void start(Match m) {
        Ac ac = Ac.get();
        ServerWorld w = Mc.world(ac.server, m.arena.area.world);
        for (UUID id : m.players()) {
            ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(id);
            if (p == null || w == null || blockedAfterQueue(p)) {
                // Someone left while queued: cancel and put everyone back.
                am().end(m);
                for (ServerPlayerEntity o : online(m)) {
                    Msg.send(o, "arena.cancelled");
                }
                return;
            }
        }
        for (ServerPlayerEntity p : online(m)) {
            PlayerSnapshot snap = PlayerState.capture(p, "arena " + m.arena.name);
            ac.savePendingRestore(snap);
            Trades.cancelFor(p, com.vylorq.anticheat.core.trade.SecureTrade.CancelReason.AREA);
            p.closeHandledScreen();
        }
        placeForRound(m);
        announce(m, Msg.tr("arena.started", m.mode.label(), m.kit, m.arena.name));
    }

    private static boolean blockedAfterQueue(ServerPlayerEntity p) {
        Ac ac = Ac.get();
        return ac.jail.isJailed(p.getUuid()) || ac.staff.isFrozen(p.getUuid()) || am().hasPending(p.getUuid());
    }

    private static void placeForRound(Match m) {
        Kit kit = am().kit(m.kit);
        int[] indexInTeam = new int[m.teams.size()];
        for (ServerPlayerEntity p : online(m)) {
            int team = m.teamOf(p.getUuid());
            PlayerState.reset(p);
            if (kit != null) {
                var inv = p.getInventory();
                for (Map.Entry<Integer, String> e : kit.items.entrySet()) {
                    if (e.getKey() >= 0 && e.getKey() < inv.size()) {
                        inv.setStack(e.getKey(), ItemConv.decode(e.getValue()));
                    }
                }
                for (Map.Entry<String, Integer> e : kit.effects.entrySet()) {
                    var eff = Registries.STATUS_EFFECT.getEntry(Identifier.of(e.getKey()));
                    eff.ifPresent(en -> p.addStatusEffect(new StatusEffectInstance(en, StatusEffectInstance.INFINITE, e.getValue())));
                }
            }
            int spawnTeam = m.mode == Arena.Mode.FFA ? 0 : team;
            int idx = m.mode == Arena.Mode.FFA ? indexInTeam[0]++ : indexInTeam[team]++;
            Location l = m.arena.spawnFor(spawnTeam, idx);
            Mc.teleport(p, Ac.server(), l);
        }
        m.startRound(System.currentTimeMillis());
    }

    private static void announce(Match m, String msg) {
        for (ServerPlayerEntity p : online(m)) {
            p.sendMessage(Msg.prefixed(msg));
        }
        for (UUID id : m.spectators) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
            if (p != null) {
                p.sendMessage(Msg.prefixed(msg));
            }
        }
    }

    /** Every second. */
    public static void tick() {
        long now = System.currentTimeMillis();
        int countdown = Ac.config().arenas.countdownSeconds;
        for (Match m : am().matches()) {
            long elapsed = (now - m.phaseStart) / 1000;
            switch (m.phase) {
                case COUNTDOWN -> {
                    long left = countdown - elapsed;
                    for (ServerPlayerEntity p : online(m)) {
                        if (left > 0) {
                            Mc.title(p, "§e" + left, Msg.tr("arena.round", m.round), 0, 25, 0);
                            Mc.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 1f, 1f);
                        } else {
                            Mc.title(p, "§aFight!", "", 0, 20, 10);
                            Mc.sound(p, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1f, 2f);
                        }
                    }
                    if (left <= 0) {
                        m.beginFight(now);
                    }
                }
                case FIGHTING -> {
                    int limit = m.arena.rules.timeLimitSeconds;
                    if (limit > 0 && elapsed >= limit && !m.suddenDeath) {
                        m.suddenDeath = true;
                        announce(m, Msg.tr("arena.sudden-death"));
                        if (m.arena.rules.suddenDeath == Arena.SuddenDeath.GLOWING) {
                            for (ServerPlayerEntity p : online(m)) {
                                p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 20 * 120, 0));
                            }
                        }
                    }
                    if (m.suddenDeath && m.arena.rules.suddenDeath == Arena.SuddenDeath.SHRINKING_BORDER) {
                        shrink(m, elapsed - limit);
                    }
                    if (limit > 0 && elapsed >= limit + 60L) {
                        roundOver(m, m.leadingTeam());
                    }
                }
                case ROUND_OVER -> {
                    if (elapsed >= 3) {
                        placeForRound(m);
                    }
                }
                case ENDED -> {
                    // handled in finish()
                }
            }
        }
        // Spectators stay inside the arena.
        for (Map.Entry<UUID, String> e : new ArrayList<>(SPECTATING.entrySet())) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(e.getKey());
            Arena a = am().arena(e.getValue());
            if (p == null || a == null) {
                continue;
            }
            if (!a.area.contains(Mc.worldId(p.getWorld()), p.getX(), p.getY(), p.getZ())) {
                stopSpectating(p);
            }
        }
    }

    /** Sudden death border: damage players who are far from the arena centre, shrinking over time. */
    private static void shrink(Match m, long secondsIntoSudden) {
        var c = m.arena.area.center();
        double maxR = Math.max(m.arena.area.maxX - m.arena.area.minX, m.arena.area.maxZ - m.arena.area.minZ) / 2.0;
        double r = Math.max(3, maxR - secondsIntoSudden * 0.5);
        for (ServerPlayerEntity p : online(m)) {
            if (m.alive.contains(p.getUuid()) && Math.hypot(p.getX() - c.x(), p.getZ() - c.z()) > r) {
                p.damage((ServerWorld) p.getWorld(), p.getDamageSources().outOfWorld(), 2.0f);
            }
        }
    }

    /**
     * A player in a match would die: the death is cancelled (no drops), they're taken out of the round.
     *
     * @return true when the death was handled here (cancel vanilla death)
     */
    public static boolean onDeath(ServerPlayerEntity p, ServerPlayerEntity killer) {
        Match m = am().matchOf(p.getUuid());
        if (m == null) {
            return false;
        }
        p.setHealth(p.getMaxHealth());
        p.clearStatusEffects();
        p.setFireTicks(0);
        p.getInventory().clear();
        if (m.phase != Match.Phase.FIGHTING) {
            return true;
        }
        int winner = m.eliminate(p.getUuid(), killer == null ? null : killer.getUuid());
        announce(m, killer != null ? Msg.tr("arena.killed", p.getGameProfile().getName(), killer.getGameProfile().getName())
                : Msg.tr("arena.died", p.getGameProfile().getName()));
        p.changeGameMode(GameMode.SPECTATOR);
        Location spot = m.arena.spectatorSpot != null ? m.arena.spectatorSpot : m.arena.spawnFor(0, 0);
        Mc.teleport(p, Ac.server(), spot);
        if (winner >= 0) {
            roundOver(m, winner);
        }
        return true;
    }

    private static void roundOver(Match m, int team) {
        boolean over = m.finishRound(team, System.currentTimeMillis());
        String names = teamNames(m, team);
        if (over) {
            finish(m);
        } else {
            announce(m, Msg.tr("arena.round-won", names, m.roundWins.getOrDefault(team, 0)));
        }
    }

    private static String teamNames(Match m, int team) {
        List<String> names = new ArrayList<>();
        if (team >= 0 && team < m.teams.size()) {
            for (UUID id : m.teams.get(team)) {
                String n = Ac.get().joins.name(id);
                names.add(n == null ? id.toString().substring(0, 8) : n);
            }
        }
        return String.join(", ", names);
    }

    /** Match over: announce, return everyone with their own items, reset the arena. */
    public static void finish(Match m) {
        Ac ac = Ac.get();
        String winners = teamNames(m, m.winnerTeam);
        Ac.server().getPlayerManager().broadcast(Text.literal(Msg.tr("arena.winner", winners, m.mode.label(), m.arena.name)), false);
        am().recordResult(m);
        am().end(m);
        for (UUID id : m.players()) {
            ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(id);
            if (p != null) {
                restore(p);
            }
        }
        for (UUID id : new ArrayList<>(m.spectators)) {
            ServerPlayerEntity p = ac.server.getPlayerManager().getPlayer(id);
            if (p != null) {
                stopSpectating(p);
            }
        }
        resetBlocks(m.arena);
        Ac.markDirty("arenas");
    }

    public static void resetBlocks(Arena a) {
        if (a.snapshot == null || !BlockSnapshots.exists(a.snapshot)) {
            return;
        }
        ServerWorld w = Mc.world(Ac.server(), a.area.world);
        if (w == null) {
            return;
        }
        try {
            BlockSnapshots.restore(w, a.snapshot);
        } catch (Exception e) {
            Ac.LOG.error("Arena reset failed for {}", a.name, e);
        }
    }

    /** Restores the saved state exactly once (the snapshot is removed before it is applied). */
    public static boolean restore(ServerPlayerEntity p) {
        PlayerSnapshot s = am().takePending(p.getUuid());
        if (s == null) {
            return false;
        }
        Ac.saveNow("arenas");
        PlayerState.apply(p, s, true);
        return true;
    }

    /** Disconnecting mid-match counts as a loss. Items come back on rejoin. */
    public static void onDisconnect(ServerPlayerEntity p) {
        am().leaveQueue(p.getUuid());
        am().denyDuel(p.getUuid());
        if (SPECTATING.containsKey(p.getUuid())) {
            stopSpectating(p);
        }
        Match m = am().matchOf(p.getUuid());
        if (m == null) {
            return;
        }
        // Clear kit items so they are never saved with the player; the snapshot restores their own items on rejoin.
        p.getInventory().clear();
        int winner = m.eliminate(p.getUuid(), null);
        am().detach(p.getUuid());
        announce(m, Msg.tr("arena.left", p.getGameProfile().getName()));
        if (winner >= 0 && m.phase != Match.Phase.ENDED) {
            roundOver(m, winner);
        } else if (m.phase == Match.Phase.COUNTDOWN && online(m).size() <= 1) {
            m.winnerTeam = online(m).isEmpty() ? -1 : m.teamOf(online(m).get(0).getUuid());
            finish(m);
        }
    }

    public static void onJoin(ServerPlayerEntity p) {
        if (am().hasPending(p.getUuid()) && !am().inMatch(p.getUuid())) {
            restore(p);
            Msg.send(p, "arena.restored");
        }
    }

    // ---- Duels ----

    public static void duel(ServerPlayerEntity from, ServerPlayerEntity to, String kit) {
        String why = blockedReason(from);
        if (why != null) {
            Msg.send(from, why);
            return;
        }
        if (am().kit(kit) == null) {
            Msg.send(from, "arena.no-kit", kit);
            return;
        }
        am().requestDuel(from.getUuid(), to.getUuid(), kit);
        Msg.send(from, "duel.sent", to.getGameProfile().getName());
        String n = Msg.q(from.getGameProfile().getName());
        to.sendMessage(Msg.prefixed(Msg.tr("duel.received", from.getGameProfile().getName(), kit)).append(Text.literal(" "))
                .append(Msg.button("§a[Accept]", "/duel accept " + n, "Accept the duel")).append(Text.literal(" "))
                .append(Msg.button("§c[Decline]", "/duel deny " + n, "Decline")));
    }

    public static void acceptDuel(ServerPlayerEntity to, ServerPlayerEntity from) {
        ArenaManager.Duel d = am().acceptDuel(to.getUuid(), from.getUuid(), Ac.config().arenas.duelRequestSeconds * 1000L);
        if (d == null) {
            Msg.send(to, "duel.none");
            return;
        }
        for (ServerPlayerEntity p : List.of(to, from)) {
            String why = blockedReason(p);
            if (why != null) {
                Msg.send(to, why);
                Msg.send(from, why);
                return;
            }
        }
        Arena a = am().freeArena(Arena.Mode.ONE_V_ONE, d.kit());
        if (a == null) {
            Msg.send(to, "arena.none-free");
            Msg.send(from, "arena.none-free");
            return;
        }
        start(am().start(a, Arena.Mode.ONE_V_ONE, d.kit(), List.of(from.getUuid(), to.getUuid())));
    }

    // ---- Spectating ----

    public static void spectate(ServerPlayerEntity p, Arena a) {
        if (am().inMatch(p.getUuid()) || am().hasPending(p.getUuid())) {
            Msg.send(p, "arena.blocked.in-match");
            return;
        }
        if (a.spectatorSpot == null) {
            Msg.send(p, "arena.no-spectator-spot");
            return;
        }
        PlayerSnapshot snap = PlayerState.capture(p, "arena-spectate");
        snap.inventory.clear();
        snap.effects.clear();
        snap.health = p.getHealth();
        Ac.get().savePendingRestore(snap);
        SPECTATING.put(p.getUuid(), a.name);
        p.changeGameMode(GameMode.SPECTATOR);
        Mc.teleport(p, Ac.server(), a.spectatorSpot);
        for (Match m : am().matches()) {
            if (m.arena == a) {
                m.spectators.add(p.getUuid());
            }
        }
        Msg.send(p, "arena.spectating", a.name);
    }

    public static void stopSpectating(ServerPlayerEntity p) {
        SPECTATING.remove(p.getUuid());
        for (Match m : am().matches()) {
            m.spectators.remove(p.getUuid());
        }
        PlayerSnapshot s = am().takePending(p.getUuid());
        Ac.saveNow("arenas");
        if (s != null) {
            p.changeGameMode(GameMode.byName(s.gameMode, GameMode.SURVIVAL));
            if (s.location != null) {
                Mc.teleport(p, Ac.server(), s.location);
            }
        }
    }

    public static boolean isSpectating(ServerPlayerEntity p) {
        return SPECTATING.containsKey(p.getUuid());
    }
}
