package com.vylorq.anticheat.feature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vylorq.anticheat.Ac;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.ServerStatHandler;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Player stats for /stats: Minecraft's own statistics (online players live, others from their saved stats file). */
public final class PlayerStats {
    private PlayerStats() {
    }

    public record Row(UUID id, String name, long playTicks, long mobKills, long playerKills, long deaths, long mined, long walkedCm) {
        public long get(String what) {
            return switch (what) {
                case "kills" -> mobKills + playerKills;
                case "pvp" -> playerKills;
                case "deaths" -> deaths;
                case "mined" -> mined;
                case "walked" -> walkedCm;
                default -> playTicks;
            };
        }
    }

    public static Row of(ServerPlayerEntity p) {
        ServerStatHandler h = p.getStatHandler();
        long mined = 0;
        for (Block b : Registries.BLOCK) {
            mined += h.getStat(Stats.MINED, b);
        }
        return new Row(p.getUuid(), p.getGameProfile().name(), custom(h, Stats.PLAY_TIME), custom(h, Stats.MOB_KILLS),
                custom(h, Stats.PLAYER_KILLS), custom(h, Stats.DEATHS), mined,
                custom(h, Stats.WALK_ONE_CM) + custom(h, Stats.SPRINT_ONE_CM));
    }

    private static long custom(ServerStatHandler h, Identifier id) {
        return h.getStat(Stats.CUSTOM.getOrCreateStat(id));
    }

    private static String nameOf(UUID id) {
        String n = Ac.get().joins.name(id);
        return n == null ? id.toString().substring(0, 8) : n;
    }

    /** From the saved stats file of a player who isn't online. */
    public static Row fromFile(UUID id) {
        Path f = Ac.server().getSavePath(WorldSavePath.STATS).resolve(id + ".json");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonObject stats = root.has("stats") ? root.getAsJsonObject("stats") : new JsonObject();
            JsonObject custom = stats.has("minecraft:custom") ? stats.getAsJsonObject("minecraft:custom") : new JsonObject();
            long mined = 0;
            if (stats.has("minecraft:mined")) {
                for (var e : stats.getAsJsonObject("minecraft:mined").entrySet()) {
                    mined += e.getValue().getAsLong();
                }
            }
            return new Row(id, nameOf(id), num(custom, "minecraft:play_time"), num(custom, "minecraft:mob_kills"),
                    num(custom, "minecraft:player_kills"), num(custom, "minecraft:deaths"), mined,
                    num(custom, "minecraft:walk_one_cm") + num(custom, "minecraft:sprint_one_cm"));
        } catch (Exception e) {
            return null;
        }
    }

    private static long num(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsLong() : 0;
    }

    public static Row of(UUID id) {
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
        return p != null ? of(p) : fromFile(id);
    }

    /** Everyone who ever played, sorted by one stat (most first). */
    public static List<Row> top(String what, int n) {
        Map<UUID, Row> all = new HashMap<>();
        Path dir = Ac.server().getSavePath(WorldSavePath.STATS);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    String file = f.getFileName().toString();
                    if (!file.endsWith(".json")) {
                        continue;
                    }
                    try {
                        UUID id = UUID.fromString(file.substring(0, file.length() - 5));
                        Row r = fromFile(id);
                        if (r != null) {
                            all.put(id, r);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // not a player file
                    }
                }
            } catch (Exception e) {
                Ac.LOG.warn("Could not read stats", e);
            }
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            all.put(p.getUuid(), of(p));
        }
        List<Row> rows = new ArrayList<>(all.values());
        rows.sort(Comparator.comparingLong((Row r) -> r.get(what)).reversed());
        return rows.subList(0, Math.min(n, rows.size()));
    }

    public static String time(long ticks) {
        long min = ticks / 20 / 60;
        return min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
    }

    public static String format(String what, long v) {
        return switch (what) {
            case "playtime" -> time(v);
            case "walked" -> String.format("%.1f km", v / 100000.0);
            default -> String.format("%,d", v);
        };
    }
}
