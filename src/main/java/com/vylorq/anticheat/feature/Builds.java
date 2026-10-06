package com.vylorq.anticheat.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Whole builds brought over from other worlds (the PvP arena, The Prison): the owner places one where they stand
 * (/owner build place), and can take it away again in an instant, the ground put back as it was.
 *
 * <p>A build file (vigil/builds/NAME.json.gz, made by scripts/builds/convert.py) holds the whole box, ground included,
 * as a palette and runs, plus its block entities, decorations and the map's functions. Command block commands mark
 * their coordinates ({X:n} {Y:n} {Z:n}) and selectors ({SEL:...}): placing fills them in for the new spot and limits
 * every selector to the build's own area, so a map's "tp @a" never takes anyone from outside it.</p>
 *
 * <p>Inside a placed build nobody but the owner can break or place blocks; in an adventure map (The Prison) players are
 * in adventure mode while they're inside, as the map was made for.</p>
 */
public final class Builds {
    private Builds() {
    }

    public static final List<String> NAMES = List.of("the_prison", "pvp_arena");
    private static final String TAG = "vigil_build";
    /** Blocks placed per tick (the rest follow over the next ticks, so the server keeps up). */
    private static final int PER_TICK = 60_000;

    /** A build that stands in the world. */
    public record Placed(String name, String world, int x, int y, int z, int sx, int sy, int sz, int dx, int dy, int dz,
                         boolean adventure) {
        boolean contains(ServerWorld w, double px, double py, double pz) {
            return Mc.worldId(w).equals(world) && px >= x && px < x + sx && py >= y - 2 && py < y + sy + 30 && pz >= z && pz < z + sz;
        }
    }

    public static final class State {
        public List<Placed> placed = new ArrayList<>();
        /** Players this put in adventure mode (put back in survival once they leave). */
        public Set<String> adventured = new HashSet<>();
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("builds.json");
    }

    static synchronized State state() {
        if (state == null) {
            try {
                state = Files.exists(file()) ? ConfigManager.GSON.fromJson(Files.readString(file()), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the placed builds", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.placed == null) {
                state.placed = new ArrayList<>();
            }
            if (state.adventured == null) {
                state.adventured = new HashSet<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()));
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the placed builds", e);
        }
    }

    // ---------------------------------------------------------------- the build files

    static JsonObject load(String name) throws Exception {
        try (InputStream in = Builds.class.getResourceAsStream("/vigil/builds/" + name + ".json.gz")) {
            if (in == null) {
                throw new IllegalArgumentException("no build called " + name);
            }
            try (var r = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(r).getAsJsonObject();
            }
        }
    }

    private static final Map<String, BlockState> STATES = new HashMap<>();

    /** "minecraft:oak_stairs[facing=north,half=bottom]" as a block state (unknown blocks become air). */
    static BlockState parse(String s) {
        return STATES.computeIfAbsent(s, k -> {
            int b = k.indexOf('[');
            String id = b < 0 ? k : k.substring(0, b);
            Identifier ident = Identifier.tryParse(id);
            if (ident == null || !Registries.BLOCK.containsId(ident)) {
                Ac.LOG.warn("Build block {} doesn't exist in this version; left as air", id);
                return Blocks.AIR.getDefaultState();
            }
            Block block = Registries.BLOCK.get(ident);
            BlockState st = block.getDefaultState();
            if (b >= 0) {
                for (String kv : k.substring(b + 1, k.length() - 1).split(",")) {
                    String[] p = kv.split("=", 2);
                    Property<?> prop = block.getStateManager().getProperty(p[0]);
                    if (prop != null && p.length == 2) {
                        st = with(st, prop, p[1]);
                    }
                }
            }
            return st;
        });
    }

    private static <T extends Comparable<T>> BlockState with(BlockState st, Property<T> prop, String value) {
        return prop.parse(value).map(v -> st.with(prop, v)).orElse(st);
    }

    // ---------------------------------------------------------------- placeholders

    private static final Pattern COORD = Pattern.compile("\\{([XYZ]):(-?\\d+(?:\\.\\d+)?)\\}");

    /** Fills in a command's coordinates for where the build stands, and keeps its selectors inside the build. */
    static String fill(String s, Placed b) {
        Matcher m = COORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            double v = Double.parseDouble(m.group(2)) + switch (m.group(1)) {
                case "X" -> b.dx();
                case "Y" -> b.dy();
                default -> b.dz();
            };
            String txt = m.group(2).contains(".") ? String.format(Locale.ROOT, "%.2f", v) : Integer.toString((int) Math.round(v));
            m.appendReplacement(out, Matcher.quoteReplacement(txt));
        }
        m.appendTail(out);
        String area = String.format(Locale.ROOT, "x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d", b.x(), b.y() - 2, b.z(), b.sx(), b.sy() + 30, b.sz());
        // Selectors can hold brackets of their own (nbt={...}), so they are matched by hand.
        String t = out.toString();
        StringBuilder res = new StringBuilder();
        int i = 0;
        while (true) {
            int at = t.indexOf("{SEL:", i);
            if (at < 0) {
                res.append(t, i, t.length());
                break;
            }
            res.append(t, i, at);
            int depth = 0;
            int end = at;
            for (int k = at; k < t.length(); k++) {
                char c = t.charAt(k);
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        end = k;
                        break;
                    }
                }
            }
            String sel = t.substring(at + 5, end);
            if (sel.length() > 2 && sel.charAt(2) == '[') {
                res.append(sel, 0, 3).append(area).append(',').append(sel.substring(3));
            } else {
                res.append(sel, 0, 2).append('[').append(area).append(']');
            }
            i = end + 1;
        }
        return res.toString();
    }

    // ---------------------------------------------------------------- placing

    private static final class Job {
        final ServerPlayerEntity owner;
        final ServerWorld world;
        final JsonObject data;
        final Placed placed;
        final BlockState[] palette;
        final JsonArray runs;
        int run;
        int left;
        int index;
        long cell;

        Job(ServerPlayerEntity owner, ServerWorld world, JsonObject data, Placed placed) {
            this.owner = owner;
            this.world = world;
            this.data = data;
            this.placed = placed;
            JsonArray pal = data.getAsJsonArray("palette");
            this.palette = new BlockState[pal.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = parse(pal.get(i).getAsString());
            }
            this.runs = data.getAsJsonArray("runs");
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    public static Placed placedOf(String name) {
        for (Placed p : state().placed) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    public static void place(ServerPlayerEntity p, String name) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (placedOf(name) != null) {
            Placed b = placedOf(name);
            Msg.send(p, "builds.already", name.replace('_', ' '), b.x(), b.y(), b.z());
            return;
        }
        if (!JOBS.isEmpty()) {
            Msg.send(p, "builds.busy");
            return;
        }
        JsonObject data;
        try {
            data = load(name);
        } catch (Exception e) {
            Ac.LOG.warn("Could not read build {}", name, e);
            Msg.send(p, "builds.failed", e.toString());
            return;
        }
        ServerWorld w = p.getEntityWorld();
        JsonArray size = data.getAsJsonArray("size");
        JsonArray spawn = data.getAsJsonArray("spawn");
        JsonArray origin = data.getAsJsonArray("origin");
        int sx = size.get(0).getAsInt();
        int sy = size.get(1).getAsInt();
        int sz = size.get(2).getAsInt();
        // The map's own spawn lands where the owner stands.
        int ox = p.getBlockX() - spawn.get(0).getAsInt();
        int oy = p.getBlockY() - spawn.get(1).getAsInt();
        int oz = p.getBlockZ() - spawn.get(2).getAsInt();
        Placed b = new Placed(name, Mc.worldId(w), ox, oy, oz, sx, sy, sz, ox - origin.get(0).getAsInt(), oy - origin.get(1).getAsInt(),
                oz - origin.get(2).getAsInt(), data.has("adventure") && data.get("adventure").getAsBoolean());
        try {
            BlockSnapshots.save(w, new Area(Mc.worldId(w), ox, oy, oz, ox + sx - 1, oy + sy - 1, oz + sz - 1), snap(name));
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the ground under build {}", name, e);
            Msg.send(p, "builds.failed", e.toString());
            return;
        }
        state().placed.add(b);
        save();
        Job job = new Job(p, w, data, b);
        if (job.runs.size() >= 2) {
            job.left = job.runs.get(0).getAsInt();
            job.index = job.runs.get(1).getAsInt();
        }
        JOBS.add(job);
        Staff.log(p, "build-place", null, null, name + " " + ox + " " + oy + " " + oz);
        Msg.send(p, "builds.placing", name.replace('_', ' '));
    }

    private static String snap(String name) {
        return "build_" + name;
    }

    public static void tick(long ticks) {
        if (!JOBS.isEmpty()) {
            Job job = JOBS.get(0);
            try {
                if (step(job)) {
                    JOBS.remove(0);
                    finish(job);
                }
            } catch (RuntimeException e) {
                JOBS.remove(0);
                Ac.LOG.warn("Placing build {} failed", job.placed.name(), e);
                Msg.send(job.owner, "builds.failed", e.toString());
            }
        }
        if (ticks % 20 == 0) {
            adventure();
        }
    }

    /** Places the next blocks. @return true when the whole box is done */
    private static boolean step(Job j) {
        Placed b = j.placed;
        long total = (long) b.sx() * b.sy() * b.sz();
        int placedNow = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        while (placedNow < PER_TICK && j.cell < total) {
            if (j.left == 0) {
                j.run += 2;
                if (j.run + 1 >= j.runs.size()) {
                    return true;
                }
                j.left = j.runs.get(j.run).getAsInt();
                j.index = j.runs.get(j.run + 1).getAsInt();
            }
            long c = j.cell;
            int x = (int) (c % b.sx());
            int z = (int) ((c / b.sx()) % b.sz());
            int y = (int) (c / ((long) b.sx() * b.sz()));
            pos.set(b.x() + x, b.y() + y, b.z() + z);
            BlockState st = j.palette[j.index];
            if (j.world.getBlockState(pos) != st) {
                j.world.setBlockState(pos, st, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
            j.left--;
            j.cell++;
            placedNow++;
        }
        return j.cell >= total;
    }

    private static void finish(Job j) {
        Placed b = j.placed;
        ServerWorld w = j.world;
        var src = Ac.server().getCommandSource().withWorld(w).withSilent();
        var cm = Ac.server().getCommandManager();
        int bes = 0;
        for (var el : j.data.getAsJsonArray("blockEntities")) {
            JsonArray a = el.getAsJsonArray();
            int x = b.x() + a.get(0).getAsInt();
            int y = b.y() + a.get(1).getAsInt();
            int z = b.z() + a.get(2).getAsInt();
            String data = a.get(3).isJsonNull() ? null : a.get(3).getAsString();
            String cmd = a.get(4).isJsonNull() ? null : a.get(4).getAsString();
            if (cmd != null) {
                String merge = (data == null ? "{" : data.substring(0, data.length() - 1) + ",") + "Command:" + quote(fill(cmd, b)) + "}";
                cm.parseAndExecute(src, "data merge block " + x + " " + y + " " + z + " " + merge);
            } else if (data != null) {
                cm.parseAndExecute(src, "data merge block " + x + " " + y + " " + z + " " + fill(data, b));
            }
            bes++;
        }
        int ents = 0;
        for (var el : j.data.getAsJsonArray("entities")) {
            JsonArray a = el.getAsJsonArray();
            double x = b.x() + a.get(0).getAsDouble();
            double y = b.y() + a.get(1).getAsDouble();
            double z = b.z() + a.get(2).getAsDouble();
            String nbt = fill(a.get(4).getAsString(), b);
            String tag = "\"" + TAG + "\",\"" + TAG + ":" + b.name() + "\"";
            nbt = nbt.contains("\"Tags\":[") ? nbt.replace("\"Tags\":[", "\"Tags\":[" + tag + ",")
                    : "{\"Tags\":[" + tag + "]" + (nbt.length() > 2 ? "," + nbt.substring(1) : "}");
            cm.parseAndExecute(src, String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f %s", a.get(3).getAsString(), x, y, z, nbt));
            ents++;
        }
        if (ARENA.equals(b.name())) {
            arena(b, j.owner);
        }
        Msg.send(j.owner, "builds.placed", b.name().replace('_', ' '), b.x(), b.y(), b.z(), bes, ents);
        Ac.LOG.info("Build {} placed at {} {} {} ({} block entities, {} decorations)", b.name(), b.x(), b.y(), b.z(), bes, ents);
    }

    /** The PvP arena build becomes a real arena (duels, the queue and kits use it). */
    static final String ARENA = "pvp_arena";
    /** Spawn spots on the arena floor, relative to the build: one side faces the other. */
    private static final int[][] SIDE_A = {{68, 2, 97}, {68, 2, 93}, {68, 2, 101}};
    private static final int[][] SIDE_B = {{98, 2, 97}, {98, 2, 101}, {100, 2, 97}};

    private static void arena(Placed b, ServerPlayerEntity owner) {
        var am = Ac.get().arenas;
        am.removeArena(ARENA);
        com.vylorq.anticheat.core.arena.Arena a = new com.vylorq.anticheat.core.arena.Arena();
        a.name = ARENA;
        a.area = new Area(b.world(), b.x() + 62, b.y(), b.z() + 70, b.x() + 105, b.y() + 30, b.z() + 125);
        a.teamSpawns = new ArrayList<>();
        for (int[][] side : new int[][][]{SIDE_A, SIDE_B}) {
            List<com.vylorq.anticheat.core.util.Location> l = new ArrayList<>();
            for (int[] s : side) {
                l.add(new com.vylorq.anticheat.core.util.Location(b.world(), b.x() + s[0] + 0.5, b.y() + s[1], b.z() + s[2] + 0.5,
                        side == SIDE_A ? -90f : 90f, 0f));
            }
            a.teamSpawns.add(l);
        }
        a.spectatorSpot = new com.vylorq.anticheat.core.util.Location(b.world(), b.x() + 83.5, b.y() + 22, b.z() + 97.5, 0f, 60f);
        a.returnPoint = Mc.location(owner);
        a.modes = new ArrayList<>(List.of(com.vylorq.anticheat.core.arena.Arena.Mode.ONE_V_ONE,
                com.vylorq.anticheat.core.arena.Arena.Mode.TWO_V_TWO, com.vylorq.anticheat.core.arena.Arena.Mode.THREE_V_THREE));
        am.addArena(a);
        Ac.markDirty("arenas");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- removing

    public static void remove(ServerPlayerEntity p, String name) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Placed b = placedOf(name);
        if (b == null) {
            Msg.send(p, "builds.none", name.replace('_', ' '));
            return;
        }
        JOBS.removeIf(j -> j.placed.name().equals(name));
        ServerWorld w = null;
        for (ServerWorld sw : Ac.server().getWorlds()) {
            if (Mc.worldId(sw).equals(b.world())) {
                w = sw;
            }
        }
        if (w == null) {
            Msg.send(p, "builds.none", name.replace('_', ' '));
            return;
        }
        var box = new net.minecraft.util.math.Box(b.x() - 2, b.y() - 4, b.z() - 2, b.x() + b.sx() + 2, b.y() + b.sy() + 30, b.z() + b.sz() + 2);
        for (Entity e : w.getOtherEntities(null, box, e -> e.getCommandTags().contains(TAG + ":" + b.name()))) {
            e.discard();
        }
        try {
            BlockSnapshots.restore(w, snap(name));
            Files.deleteIfExists(BlockSnapshots.file(snap(name)));
        } catch (Exception e) {
            Ac.LOG.warn("Could not put the ground back under build {}", name, e);
            Msg.send(p, "builds.failed", e.toString());
            return;
        }
        state().placed.remove(b);
        save();
        if (ARENA.equals(name) && Ac.get().arenas.removeArena(ARENA)) {
            Ac.markDirty("arenas");
        }
        Staff.log(p, "build-remove", null, null, name);
        Msg.send(p, "builds.removed", name.replace('_', ' '));
    }

    public static void list(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        for (String n : NAMES) {
            Placed b = placedOf(n);
            Msg.send(p, b == null ? "builds.entry-none" : "builds.entry", n.replace('_', ' '),
                    b == null ? 0 : b.x(), b == null ? 0 : b.y(), b == null ? 0 : b.z());
        }
    }

    // ---------------------------------------------------------------- inside a build

    static Placed at(ServerWorld w, double x, double y, double z) {
        for (Placed b : state().placed) {
            if (b.contains(w, x, y, z)) {
                return b;
            }
        }
        return null;
    }

    public static boolean protects(ServerWorld w, BlockPos pos) {
        return at(w, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) != null;
    }

    /** Adventure maps: players are in adventure mode while inside, and back in survival once they leave. */
    private static void adventure() {
        State s = state();
        boolean changed = false;
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (Perms.isOwner(p.getUuid())) {
                continue;
            }
            Placed b = at(p.getEntityWorld(), p.getX(), p.getY(), p.getZ());
            String id = p.getUuidAsString();
            if (b != null && b.adventure() && p.interactionManager.getGameMode() == GameMode.SURVIVAL) {
                p.changeGameMode(GameMode.ADVENTURE);
                s.adventured.add(id);
                Msg.actionBar(p, "§6" + Msg.trFor(p, "builds.adventure"));
                changed = true;
            } else if ((b == null || !b.adventure()) && s.adventured.contains(id)) {
                if (p.interactionManager.getGameMode() == GameMode.ADVENTURE) {
                    p.changeGameMode(GameMode.SURVIVAL);
                }
                s.adventured.remove(id);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    // ---------------------------------------------------------------- the map's functions

    private static List<String> function(Placed b, String fn) {
        try {
            JsonObject funcs = load(b.name()).getAsJsonObject("functions");
            if (!funcs.has(fn)) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (var l : funcs.getAsJsonArray(fn)) {
                out.add(fill(l.getAsString(), b));
            }
            return out;
        } catch (Exception e) {
            Ac.LOG.warn("Could not read function {} of build {}", fn, b.name(), e);
            return null;
        }
    }

    public static void registerCommands(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("vigilbuild")
                // A player's chat answer in a map's quiz (they can't run /function themselves).
                .then(CommandManager.literal("answer").then(CommandManager.argument("fn", StringArgumentType.greedyString()).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p == null) {
                        return 0;
                    }
                    Placed b = at(p.getEntityWorld(), p.getX(), p.getY(), p.getZ());
                    List<String> lines = b == null ? null : function(b, StringArgumentType.getString(ctx, "fn"));
                    if (lines == null) {
                        return 0;
                    }
                    var src = Ac.server().getCommandSource().withWorld(p.getEntityWorld()).withEntity(p).withPosition(p.getEntityPos())
                            .withSilent();
                    for (String l : lines) {
                        Ac.server().getCommandManager().parseAndExecute(src, l);
                    }
                    return 1;
                })))
                // A command block of the map calling one of its functions.
                .then(CommandManager.literal("run").requires(s -> s.getPlayer() == null)
                        .then(CommandManager.argument("fn", StringArgumentType.greedyString()).executes(ctx -> {
                            var src = ctx.getSource();
                            var pos = src.getPosition();
                            Placed b = at(src.getWorld(), pos.x, pos.y, pos.z);
                            List<String> lines = b == null ? null : function(b, StringArgumentType.getString(ctx, "fn"));
                            if (lines == null) {
                                return 0;
                            }
                            for (String l : lines) {
                                Ac.server().getCommandManager().parseAndExecute(src, l);
                            }
                            return 1;
                        }))));
    }

    public static void register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, reg, env) -> registerCommands(d));
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) ->
                !(Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p
                        && !Perms.isOwner(p.getUuid()) && protects(w, pos)));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !Perms.isOwner(p.getUuid())) {
                var held = p.getStackInHand(hand);
                Placed b = at(w, hit.getBlockPos().getX() + 0.5, hit.getBlockPos().getY() + 0.5, hit.getBlockPos().getZ() + 0.5);
                // Adventure maps place their own key buttons (can_place_on); the arena takes nothing.
                if (b != null && !b.adventure() && (held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem)) {
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
    }

    /** For the game tests. */
    public static JsonObject loadForTest(String name) throws Exception {
        return load(name);
    }

    /** For the game tests. */
    public static BlockState parseForTest(String s) {
        return parse(s);
    }

    /** For the game tests. */
    public static String fillForTest(String s, int x, int y, int z, int dx, int dy, int dz) {
        return fill(s, new Placed("test", "minecraft:overworld", x, y, z, 10, 10, 10, dx, dy, dz, false));
    }
}
