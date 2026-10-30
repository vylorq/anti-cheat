package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.arena.Match;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Arena seasons: a month each. Every match counts: a win is 3 points and every kill 1 (at most 30 points a day, so
 * nobody can farm). When the month turns, the top three get prizes (handed over the next time they're online) and a
 * new season starts. /season shows the table.
 */
public final class ArenaSeasons {
    private ArenaSeasons() {
    }

    public static final int DAILY_CAP = 30;

    static final class State {
        String season;
        Map<String, Integer> points = new HashMap<>();
        /** uuid -> day -> points earned that day */
        Map<String, Map<String, Integer>> today = new HashMap<>();
        /** uuid -> places won and not yet handed over (1, 2 or 3) */
        Map<String, List<Integer>> owed = new HashMap<>();
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("arena_season.json");
    }

    private static String month() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }

    private static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the arena season", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.season == null) {
                state.season = month();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the arena season", e);
        }
    }

    private static void give(UUID id, int pts) {
        State s = state();
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        Map<String, Integer> mine = s.today.computeIfAbsent(id.toString(), k -> new HashMap<>());
        mine.keySet().removeIf(d -> !d.equals(day));
        int room = Math.max(0, DAILY_CAP - mine.getOrDefault(day, 0));
        int add = Math.min(room, pts);
        if (add <= 0) {
            return;
        }
        mine.merge(day, add, Integer::sum);
        s.points.merge(id.toString(), add, Integer::sum);
    }

    /** A match ended (Arenas.finish). */
    public static void record(Match m) {
        rollOver();
        for (int t = 0; t < m.teams.size(); t++) {
            for (UUID p : m.teams.get(t)) {
                give(p, (t == m.winnerTeam ? 3 : 0) + m.kills.getOrDefault(p, 0));
            }
        }
        save();
    }

    public static List<Map.Entry<String, Integer>> top(int n) {
        List<Map.Entry<String, Integer>> all = new ArrayList<>(state().points.entrySet());
        all.sort((a, b) -> b.getValue() - a.getValue());
        return all.subList(0, Math.min(n, all.size()));
    }

    private static String name(String uuid) {
        try {
            String n = Ac.get().joins.name(UUID.fromString(uuid));
            return n == null ? uuid.substring(0, 8) : n;
        } catch (IllegalArgumentException e) {
            return uuid.substring(0, 8);
        }
    }

    /** A new month: the top three are owed their prizes, and the table starts again. */
    static void rollOver() {
        State s = state();
        String now = month();
        if (now.equals(s.season)) {
            return;
        }
        List<Map.Entry<String, Integer>> top = top(3);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).getValue() <= 0) {
                continue;
            }
            s.owed.computeIfAbsent(top.get(i).getKey(), k -> new ArrayList<>()).add(i + 1);
            names.add((i + 1) + ". " + name(top.get(i).getKey()) + " (" + top.get(i).getValue() + ")");
        }
        if (!names.isEmpty() && Ac.server() != null) {
            Ac.server().getPlayerManager().broadcast(Text.literal(Msg.tr("season.ended", s.season, String.join("  ", names))), false);
        }
        s.season = now;
        s.points.clear();
        s.today.clear();
        save();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            payOut(p);
        }
    }

    private static List<ItemStack> prizes(int place) {
        List<ItemStack> out = new ArrayList<>();
        var ids = new ArrayList<>(Artifacts.DEFS.keySet());
        var r = Ac.server().getOverworld().getRandom();
        switch (place) {
            case 1 -> {
                out.add(new ItemStack(Items.NETHERITE_INGOT, 2));
                out.add(new ItemStack(Items.DIAMOND, 32));
                out.add(Artifacts.item(ids.get(r.nextInt(ids.size())), 2));
            }
            case 2 -> {
                out.add(new ItemStack(Items.NETHERITE_INGOT, 1));
                out.add(new ItemStack(Items.DIAMOND, 16));
                out.add(Artifacts.item(ids.get(r.nextInt(ids.size()))));
            }
            default -> {
                out.add(new ItemStack(Items.DIAMOND, 8));
                out.add(Artifacts.item(ids.get(r.nextInt(ids.size()))));
            }
        }
        return out;
    }

    /** Hands over any prizes this player is owed. */
    static void payOut(ServerPlayerEntity p) {
        List<Integer> places = state().owed.remove(p.getUuidAsString());
        if (places == null || places.isEmpty()) {
            return;
        }
        for (int place : places) {
            for (ItemStack s : prizes(place)) {
                if (!p.getInventory().insertStack(s)) {
                    p.dropItem(s, false);
                }
            }
            Msg.send(p, "season.prize", place);
            Achievements.unlock(p, "feat:season_top3");
        }
        save();
    }

    public static void show(ServerPlayerEntity p) {
        rollOver();
        Msg.send(p, "season.header", state().season);
        int i = 0;
        for (var e : top(10)) {
            i++;
            Msg.send(p, "season.row", i, name(e.getKey()), e.getValue());
        }
        if (i == 0) {
            Msg.send(p, "season.empty");
        }
        Msg.send(p, "season.mine", state().points.getOrDefault(p.getUuidAsString(), 0));
    }

    public static void tick(long ticks) {
        if (ticks % 1200 == 0) {
            rollOver();
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) ->
                OwnerPowers.later(60, () -> {
                    if (!h.player.isRemoved()) {
                        payOut(h.player);
                    }
                }));
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, reg, env) ->
                d.register(net.minecraft.server.command.CommandManager.literal("season").executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p != null) {
                        show(p);
                    }
                    return 1;
                })));
    }
}
