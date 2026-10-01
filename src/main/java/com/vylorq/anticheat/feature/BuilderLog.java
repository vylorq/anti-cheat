package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Everything a builder does, written to {@code config/vigil/builder-logs/<uuid>/<date>.log} (plain text, one line
 * per action, so it can be read in any editor): every block placed or broken by hand with its coordinates and what
 * was there before, every block changed by the builder tools, items taken from the creative menu, commands,
 * files loaded or imported, blocked attempts, joins and leaves. The owner can see a summary, the latest lines, and
 * undo everything since a given time.
 *
 * <p>Line format (tab separated): time, type, world, x, y, z, block before, block after, detail.
 */
public final class BuilderLog {
    private BuilderLog() {
    }

    private static final Map<UUID, BufferedWriter> OPEN = new HashMap<>();
    private static final Map<UUID, LocalDate> OPEN_DAY = new HashMap<>();

    public static Path dir(UUID id) {
        return Ac.get().dir.resolve("builder-logs").resolve(id.toString());
    }

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && BuilderMode.is(p) && world instanceof ServerWorld w) {
                change(p.getUuid(), "BREAK", w, pos, state, w.getBlockState(pos), "hand");
            }
        });
    }

    /** Whether this player's actions are logged (builders, and anyone with a pending draft). */
    public static boolean logs(UUID id) {
        return Ac.running() && (Ac.get().misc.builders.containsKey(id));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static synchronized void write(UUID id, String line) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            BufferedWriter w = OPEN.get(id);
            if (w == null || !today.equals(OPEN_DAY.get(id))) {
                if (w != null) {
                    w.close();
                }
                Files.createDirectories(dir(id));
                w = Files.newBufferedWriter(dir(id).resolve(today + ".log"), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                OPEN.put(id, w);
                OPEN_DAY.put(id, today);
            }
            w.write(line);
            w.newLine();
        } catch (IOException e) {
            Ac.LOG.warn("Builder log write failed", e);
        }
    }

    private static String now() {
        return Instant.now().toString();
    }

    /** A block change (by hand, a tool, an approval...). */
    public static void change(UUID id, String type, ServerWorld w, BlockPos pos, BlockState before, BlockState after, String detail) {
        write(id, now() + "\t" + type + "\t" + Mc.worldId(w) + "\t" + pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ() + "\t"
                + BuildFiles.stateString(before) + "\t" + BuildFiles.stateString(after) + "\t" + clean(detail));
    }

    /** Anything else, at the player's position. */
    public static void event(ServerPlayerEntity p, String type, String detail) {
        BlockPos pos = p.getBlockPos();
        write(p.getUuid(), now() + "\t" + type + "\t" + Mc.worldId(p.getEntityWorld()) + "\t" + pos.getX() + "\t" + pos.getY() + "\t"
                + pos.getZ() + "\t\t\t" + clean(detail));
    }

    /** An event without a position (e.g. from the owner while the builder is offline). */
    public static void event(UUID id, String type, String detail) {
        write(id, now() + "\t" + type + "\t\t\t\t\t\t\t" + clean(detail));
    }

    /** Writes buffered lines to disk (every second, and when a builder leaves). */
    public static synchronized void flush() {
        for (BufferedWriter w : OPEN.values()) {
            try {
                w.flush();
            } catch (IOException ignored) {
                // next flush tries again
            }
        }
    }

    public static synchronized void close(UUID id) {
        BufferedWriter w = OPEN.remove(id);
        OPEN_DAY.remove(id);
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // nothing to do
            }
        }
    }

    public static synchronized void closeAll() {
        for (UUID id : new ArrayList<>(OPEN.keySet())) {
            close(id);
        }
    }

    // ---------------------------------------------------------------- reading

    /** One logged line. */
    public record Entry(long time, String type, String world, int x, int y, int z, String before, String after, String detail) {
        boolean isChange() {
            return !before.isEmpty() || !after.isEmpty();
        }

        public String pretty() {
            String when = Instant.ofEpochMilli(time).toString().replace('T', ' ').substring(0, 19);
            if (isChange()) {
                return when + " " + type + " " + x + " " + y + " " + z + " " + short_(before) + " → " + short_(after)
                        + (detail.isEmpty() ? "" : " (" + detail + ")");
            }
            return when + " " + type + (world.isEmpty() ? "" : " @ " + x + " " + y + " " + z) + " " + detail;
        }
    }

    static String short_(String state) {
        return state.replace("minecraft:", "");
    }

    static Entry parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 9) {
            return null;
        }
        try {
            long t = Instant.parse(f[0]).toEpochMilli();
            int x = f[3].isEmpty() ? 0 : Integer.parseInt(f[3]);
            int y = f[4].isEmpty() ? 0 : Integer.parseInt(f[4]);
            int z = f[5].isEmpty() ? 0 : Integer.parseInt(f[5]);
            return new Entry(t, f[1], f[2], x, y, z, f[6], f[7], f[8]);
        } catch (Exception e) {
            return null;
        }
    }

    /** Every entry since a time, oldest first. */
    public static List<Entry> read(UUID id, long since) {
        flush();
        List<Entry> out = new ArrayList<>();
        Path d = dir(id);
        if (!Files.isDirectory(d)) {
            return out;
        }
        LocalDate first = Instant.ofEpochMilli(since).atZone(ZoneOffset.UTC).toLocalDate();
        try (Stream<Path> files = Files.list(d)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".log")).sorted().toList()) {
                String day = f.getFileName().toString().replace(".log", "");
                try {
                    if (LocalDate.parse(day).isBefore(first)) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                try (Stream<String> lines = Files.lines(f, StandardCharsets.UTF_8)) {
                    lines.forEach(l -> {
                        Entry e = parse(l);
                        if (e != null && e.time >= since) {
                            out.add(e);
                        }
                    });
                }
            }
        } catch (IOException e) {
            Ac.LOG.warn("Builder log read failed", e);
        }
        return out;
    }

    /** Totals since a time: blocks placed/broken by type, tool changes, items taken, commands. */
    public record Summary(Map<String, Integer> placed, Map<String, Integer> broken, Map<String, Integer> taken, int toolChanges,
                          int commands, int blocked, long first, long last, int lines) {
    }

    public static Summary summary(UUID id, long since) {
        Map<String, Integer> placed = new LinkedHashMap<>();
        Map<String, Integer> broken = new LinkedHashMap<>();
        Map<String, Integer> taken = new LinkedHashMap<>();
        int tool = 0;
        int commands = 0;
        int blocked = 0;
        long first = 0;
        long last = 0;
        List<Entry> all = read(id, since);
        for (Entry e : all) {
            first = first == 0 ? e.time : first;
            last = e.time;
            switch (e.type) {
                case "PLACE" -> placed.merge(blockName(e.after), 1, Integer::sum);
                case "BREAK" -> broken.merge(blockName(e.before), 1, Integer::sum);
                case "TAKE" -> taken.merge(e.detail.split(" ")[0], 1, Integer::sum);
                case "COMMAND" -> commands++;
                case "BLOCKED" -> blocked++;
                default -> {
                    if (e.type.startsWith("TOOL") && e.isChange()) {
                        tool++;
                        placed.merge(blockName(e.after), 1, Integer::sum);
                    }
                }
            }
        }
        return new Summary(sortByCount(placed), sortByCount(broken), sortByCount(taken), tool, commands, blocked, first, last, all.size());
    }

    static String blockName(String state) {
        int br = state.indexOf('[');
        return short_(br < 0 ? state : state.substring(0, br));
    }

    private static Map<String, Integer> sortByCount(Map<String, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /**
     * Undoes every block change a builder made since a time (newest first). A block is only put back if it still is
     * what the builder made, so later changes by others are kept.
     */
    public static int undoSince(ServerPlayerEntity by, UUID builder, long since) {
        List<Entry> changes = new ArrayList<>();
        for (Entry e : read(builder, since)) {
            if (e.isChange() && !e.type.equals("UNDONE") && !e.type.equals("DRAFT-COPY")) {
                changes.add(e);
            }
        }
        java.util.Collections.reverse(changes);
        if (changes.isEmpty()) {
            return 0;
        }
        Map<String, ServerWorld> worlds = new HashMap<>();
        for (ServerWorld w : Ac.server().getWorlds()) {
            worlds.put(Mc.worldId(w), w);
        }
        // One job per world.
        Map<String, List<Entry>> byWorld = new LinkedHashMap<>();
        for (Entry e : changes) {
            byWorld.computeIfAbsent(e.world, k -> new ArrayList<>()).add(e);
        }
        int n = 0;
        for (var group : byWorld.entrySet()) {
            ServerWorld w = worlds.get(group.getKey());
            if (w == null) {
                continue;
            }
            List<Entry> list = group.getValue();
            n += list.size();
            BuilderTools.system(by, w, new BuilderTools.Source() {
                @Override
                public int size() {
                    return list.size();
                }

                @Override
                public BlockPos pos(int i) {
                    Entry e = list.get(i);
                    return new BlockPos(e.x, e.y, e.z);
                }

                @Override
                public BlockState state(int i, BlockState old) {
                    Entry e = list.get(i);
                    return BuildFiles.stateString(old).equals(e.after) ? BuildFiles.parseState(e.before) : null;
                }
            }, "undo-builder", builder, "UNDONE", null);
        }
        event(builder, "UNDO-ALL", "by " + (by == null ? "console" : by.getGameProfile().name()) + ", " + n + " changes since "
                + Instant.ofEpochMilli(since));
        return n;
    }
}
