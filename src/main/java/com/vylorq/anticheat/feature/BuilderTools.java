package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.perm.Perm;
import com.vylorq.anticheat.feature.BuildFiles.Clip;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Builder tools (for builders and the owner): a wand to select a box; fill, replace, walls, hollow boxes, lines,
 * spheres, cylinders and pyramids; brushes; copy, paste (with a preview first), rotate, flip and undo; block mixes
 * ("70%stone_bricks,30%cracked_stone_bricks"); and build files. Big edits run a little every tick so the server
 * doesn't freeze. Builders only ever place building blocks, only where they may build, and every block they change
 * is written to their {@link BuilderLog}.
 */
public final class BuilderTools {
    private BuilderTools() {
    }

    /** Blocks changed per tick by running edits. */
    static final int PER_TICK = 40_000;
    /** Biggest edit at once. */
    public static final int MAX_EDIT = 2_000_000;
    /** Edits bigger than this can't be undone (the old blocks would take too much memory). Fits a whole lobby paste. */
    static final int MAX_UNDO = 1_000_000;
    /** Most changed blocks kept for undo per player, over all their saved edits. */
    static final int MAX_UNDO_KEPT = 1_500_000;
    public static final int MAX_RADIUS = 64;

    // ---------------------------------------------------------------- block mixes

    /** What to place: one block, or a random mix. */
    public interface Pattern {
        BlockState pick();

        String describe();

        default boolean allowed() {
            return true;
        }
    }

    public static Pattern single(BlockState s) {
        return new Pattern() {
            @Override
            public BlockState pick() {
                return s;
            }

            @Override
            public String describe() {
                return BuilderLog.blockName(BuildFiles.stateString(s));
            }

            @Override
            public boolean allowed() {
                return allowedBlock(s);
            }
        };
    }

    public static Pattern mix(List<BlockState> blocks, List<Integer> weights) {
        if (blocks.size() == 1) {
            return single(blocks.get(0));
        }
        int total = weights.stream().mapToInt(Integer::intValue).sum();
        return new Pattern() {
            @Override
            public BlockState pick() {
                int r = ThreadLocalRandom.current().nextInt(Math.max(1, total));
                for (int i = 0; i < blocks.size(); i++) {
                    r -= weights.get(i);
                    if (r < 0) {
                        return blocks.get(i);
                    }
                }
                return blocks.get(blocks.size() - 1);
            }

            @Override
            public String describe() {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < blocks.size(); i++) {
                    parts.add(Math.round(weights.get(i) * 100.0 / total) + "% " + BuilderLog.blockName(BuildFiles.stateString(blocks.get(i))));
                }
                return String.join(", ", parts);
            }

            @Override
            public boolean allowed() {
                return blocks.stream().allMatch(BuilderTools::allowedBlock);
            }
        };
    }

    public static BlockState block(String name) {
        Identifier id = Identifier.tryParse(name.trim().contains(":") ? name.trim() : "minecraft:" + name.trim());
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        return Registries.BLOCK.get(id).getDefaultState();
    }

    /** "stone", "stone,dirt" (even mix) or "70%stone_bricks,20%cracked_stone_bricks,10%mossy_stone_bricks". */
    public static Pattern pattern(String text) {
        List<BlockState> blocks = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (String part : text.trim().split("[,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            int weight = 1;
            String name = part;
            int pct = part.indexOf('%');
            if (pct > 0) {
                try {
                    weight = Math.max(1, Integer.parseInt(part.substring(0, pct)));
                } catch (NumberFormatException e) {
                    return null;
                }
                name = part.substring(pct + 1);
            }
            BlockState s = block(name);
            if (s == null) {
                return null;
            }
            blocks.add(s);
            weights.add(weight);
        }
        return blocks.isEmpty() ? null : mix(blocks, weights);
    }

    /** A mix of the building blocks in a player's hotbar, weighted by how many of each they hold. */
    public static Pattern hotbarMix(ServerPlayerEntity p) {
        Map<Block, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < 9; i++) {
            var st = p.getInventory().getStack(i);
            if (st.getItem() instanceof net.minecraft.item.BlockItem bi && BuilderMode.allowedBlock(bi.getBlock())) {
                counts.merge(bi.getBlock(), st.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return null;
        }
        List<BlockState> blocks = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        counts.forEach((b, n) -> {
            blocks.add(b.getDefaultState());
            weights.add(n);
        });
        return mix(blocks, weights);
    }

    // ---------------------------------------------------------------- jobs

    record Change(BlockPos pos, BlockState before) {
    }

    /** What an edit does at each of its positions. */
    public interface Source {
        int size();

        BlockPos pos(int i);

        /** @return the new state, or null to leave the block as it is */
        BlockState state(int i, BlockState old);

        /** Block entity data to give the new block (signs, banners...), or null. */
        default NbtCompound blockEntity(int i) {
            return null;
        }
    }

    static final class Job {
        final UUID key;
        final ServerPlayerEntity player;
        final ServerWorld world;
        final Source source;
        final String what;
        final List<Change> changes;
        /** System edits (approving, backups, undo by the owner) skip the builder rules. */
        final boolean system;
        /** Whose log gets the changes (null: nobody's). */
        final UUID journal;
        final String journalType;
        final Consumer<Job> onDone;
        int next;
        int changed;
        int skipped;

        Job(UUID key, ServerPlayerEntity player, ServerWorld world, Source source, String what, boolean undoable, boolean system,
            UUID journal, String journalType, Consumer<Job> onDone) {
            this.key = key;
            this.player = player;
            this.world = world;
            this.source = source;
            this.what = what;
            this.changes = undoable ? new ArrayList<>() : null;
            this.system = system;
            this.journal = journal;
            this.journalType = journalType;
            this.onDone = onDone;
        }
    }

    static final class Sel {
        String world;
        BlockPos a;
        BlockPos b;
    }

    /** A paste waiting for confirmation, shown as an outline. */
    record Pending(String world, BlockPos origin, boolean air, long expires) {
    }

    public enum BrushMode { SPHERE, PAINT, SMOOTH, SCATTER }

    public record Brush(BrushMode mode, Pattern pattern, int radius) {
    }

    private static final Map<UUID, Sel> SEL = new HashMap<>();
    private static final Map<UUID, Clip> CLIP = new HashMap<>();
    private static final Map<UUID, Deque<List<Change>>> UNDO = new HashMap<>();
    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Brush> BRUSH = new HashMap<>();
    private static int ticks;

    public static boolean canUse(ServerPlayerEntity p) {
        return BuilderMode.is(p) || Perms.has(p, Perm.MANAGE_ADMINS);
    }

    private static boolean check(ServerPlayerEntity p) {
        if (!canUse(p)) {
            Msg.send(p, "general.no-permission");
            return false;
        }
        if (JOBS.containsKey(p.getUuid())) {
            Msg.send(p, "build.busy");
            return false;
        }
        return true;
    }

    private static boolean checkPattern(ServerPlayerEntity p, Pattern pat) {
        if (pat == null) {
            Msg.send(p, "build.unknown-block", "?");
            return false;
        }
        if (BuilderMode.is(p) && !pat.allowed()) {
            Msg.send(p, "builder.blocks-only");
            return false;
        }
        return true;
    }

    /** Starts an edit by a player (their rules apply, it's logged and can be undone). */
    private static void start(ServerPlayerEntity p, Source src, String what) {
        boolean undoable = src.size() <= MAX_UNDO;
        UUID journal = BuilderMode.is(p) ? p.getUuid() : null;
        if (journal != null) {
            BuilderLog.event(p, "OP", what + " (" + src.size() + " positions)");
        }
        JOBS.put(p.getUuid(), new Job(p.getUuid(), p, (ServerWorld) p.getEntityWorld(), src, what, undoable, false, journal,
                "TOOL:" + what, null));
        if (!undoable) {
            Msg.send(p, "build.no-undo", MAX_UNDO);
        }
    }

    /** Starts a system edit (no builder rules): approving drafts, copying them, undoing a builder's work. */
    public static void system(ServerPlayerEntity by, ServerWorld w, Source src, String what, UUID journal, String journalType,
                              Consumer<Job> onDone) {
        UUID key = UUID.randomUUID();
        JOBS.put(key, new Job(key, by, w, src, what, false, true, journal, journalType, onDone));
    }

    public static boolean busy(UUID key) {
        return JOBS.containsKey(key);
    }

    /** Every tick: runs a slice of each edit; every half second: outlines for previews and selections. */
    public static void tick() {
        ticks++;
        if (ticks % 10 == 0) {
            drawOutlines();
        }
        if (JOBS.isEmpty()) {
            return;
        }
        int budget = PER_TICK;
        for (Iterator<Job> it = JOBS.values().iterator(); it.hasNext() && budget > 0; ) {
            Job j = it.next();
            if (!j.system && (j.player == null || j.player.isRemoved() || !canUse(j.player))) {
                it.remove();
                continue;
            }
            int size = j.source.size();
            int first = j.next;
            int end = Math.min(size, j.next + budget);
            for (; j.next < end; j.next++) {
                BlockPos pos = j.source.pos(j.next);
                BlockState old = j.world.getBlockState(pos);
                BlockState now = j.source.state(j.next, old);
                if (now == null || now == old) {
                    continue;
                }
                if (!j.system && (!allowedBlock(now) || !mayEdit(j.player, j.world, pos))) {
                    j.skipped++;
                    continue;
                }
                if (j.system && j.world.getBlockEntity(pos) instanceof Inventory && !(now.getBlock() == old.getBlock())) {
                    // Never wipe a container (and its items), even in a system edit.
                    j.skipped++;
                    continue;
                }
                if (j.changes != null) {
                    j.changes.add(new Change(pos, old));
                }
                j.world.setBlockState(pos, now, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
                NbtCompound be = j.source.blockEntity(j.next);
                if (be != null) {
                    BlockEntity target = j.world.getBlockEntity(pos);
                    if (target != null && !(target instanceof Inventory)) {
                        Mc.loadBlockEntity(target, be, j.world.getRegistryManager());
                    }
                }
                if (j.journal != null) {
                    BuilderLog.change(j.journal, j.journalType, j.world, pos, old, now, j.what);
                }
                j.changed++;
            }
            budget -= j.next - first;
            if (j.next >= size) {
                it.remove();
                if (j.changes != null && !j.changes.isEmpty()) {
                    Deque<List<Change>> stack = UNDO.computeIfAbsent(j.key, k -> new ArrayDeque<>());
                    stack.addFirst(j.changes);
                    int kept = 0;
                    for (List<Change> l : stack) {
                        kept += l.size();
                    }
                    while (stack.size() > 10 || (stack.size() > 1 && kept > MAX_UNDO_KEPT)) {
                        kept -= stack.removeLast().size();
                    }
                }
                if (j.player != null && !j.player.isRemoved()) {
                    if (!j.system) {
                        Staff.log(j.player, "build-" + j.what, null, Mc.worldId(j.world), j.changed + " blocks");
                    }
                    Msg.send(j.player, j.skipped > 0 ? "build.done-skipped" : "build.done", j.changed, j.skipped);
                }
                if (j.onDone != null) {
                    j.onDone.accept(j);
                }
            } else if (size > PER_TICK * 5 && j.player != null) {
                Msg.actionBar(j.player, Msg.trFor(j.player, "build.progress", (int) ((long) j.next * 100 / size)));
            }
        }
    }

    /** Whether this player may change this block with the tools. */
    static boolean mayEdit(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        if (w.getBlockEntity(pos) instanceof Inventory) {
            return false;
        }
        if (pos.getY() < w.getBottomY() || pos.getY() > w.getTopYInclusive()) {
            return false;
        }
        return !BuilderMode.is(p) || BuilderMode.mayBuildAt(p, w, pos);
    }

    /** Builders may only place building blocks; containers already in the world are never touched. */
    public static boolean allowedBlock(BlockState s) {
        return s.isAir() || BuilderMode.allowedBlock(s.getBlock());
    }

    // ---------------------------------------------------------------- selection

    public static void corner(ServerPlayerEntity p, ServerWorld w, BlockPos pos, boolean first) {
        if (!canUse(p)) {
            return;
        }
        Sel s = SEL.computeIfAbsent(p.getUuid(), k -> new Sel());
        if (!Mc.worldId(w).equals(s.world)) {
            s.a = null;
            s.b = null;
            s.world = Mc.worldId(w);
        }
        if (first) {
            s.a = pos.toImmutable();
        } else {
            s.b = pos.toImmutable();
        }
        String size = s.a != null && s.b != null ? sizeText(s) : "-";
        Msg.send(p, first ? "build.pos1" : "build.pos2", pos.toShortString(), size);
        if (BuilderMode.is(p)) {
            BuilderLog.event(p, first ? "POS1" : "POS2", pos.toShortString());
        }
    }

    private static String sizeText(Sel s) {
        int dx = Math.abs(s.a.getX() - s.b.getX()) + 1;
        int dy = Math.abs(s.a.getY() - s.b.getY()) + 1;
        int dz = Math.abs(s.a.getZ() - s.b.getZ()) + 1;
        return dx + "x" + dy + "x" + dz + " (" + ((long) dx * dy * dz) + ")";
    }

    /** The selection's corners [min, max], or null with a message. */
    private static BlockPos[] box(ServerPlayerEntity p) {
        Sel s = SEL.get(p.getUuid());
        if (s == null || s.a == null || s.b == null) {
            Msg.send(p, "build.need-selection");
            return null;
        }
        if (!s.world.equals(Mc.worldId(p.getEntityWorld()))) {
            Msg.send(p, "build.other-world");
            return null;
        }
        BlockPos min = new BlockPos(Math.min(s.a.getX(), s.b.getX()), Math.min(s.a.getY(), s.b.getY()), Math.min(s.a.getZ(), s.b.getZ()));
        BlockPos max = new BlockPos(Math.max(s.a.getX(), s.b.getX()), Math.max(s.a.getY(), s.b.getY()), Math.max(s.a.getZ(), s.b.getZ()));
        long vol = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (vol > MAX_EDIT) {
            Msg.send(p, "build.too-big", vol, MAX_EDIT);
            return null;
        }
        return new BlockPos[]{min, max};
    }

    public static String selectionInfo(ServerPlayerEntity p) {
        Sel s = SEL.get(p.getUuid());
        if (s == null || s.a == null || s.b == null) {
            return Msg.trFor(p, "build.no-selection");
        }
        return s.a.toShortString() + " → " + s.b.toShortString() + " · " + sizeText(s);
    }

    /** How many of each block are in the selection (null with a message when there's none). */
    public static Map<Block, Integer> blocksIn(ServerPlayerEntity p) {
        BlockPos[] b = box(p);
        if (b == null) {
            return null;
        }
        Map<Block, Integer> out = new HashMap<>();
        for (BlockPos pos : BlockPos.iterate(b[0], b[1])) {
            out.merge(p.getEntityWorld().getBlockState(pos).getBlock(), 1, Integer::sum);
        }
        return out;
    }

    public static Clip clipboard(ServerPlayerEntity p) {
        return CLIP.get(p.getUuid());
    }

    public static void clear(ServerPlayerEntity p) {
        SEL.remove(p.getUuid());
        Msg.send(p, "build.cleared");
    }

    /** Every position of a box, in order (x fastest, then z, then y). */
    static Source boxSource(BlockPos min, BlockPos max, java.util.function.BiFunction<BlockPos, BlockState, BlockState> fn) {
        int dx = max.getX() - min.getX() + 1;
        int dz = max.getZ() - min.getZ() + 1;
        int dy = max.getY() - min.getY() + 1;
        return new Source() {
            @Override
            public int size() {
                return dx * dy * dz;
            }

            @Override
            public BlockPos pos(int i) {
                return min.add(i % dx, i / (dx * dz), (i / dx) % dz);
            }

            @Override
            public BlockState state(int i, BlockState old) {
                return fn.apply(pos(i), old);
            }
        };
    }

    // ---------------------------------------------------------------- selection edits

    public static void set(ServerPlayerEntity p, Pattern pat) {
        BlockPos[] b = check(p) && checkPattern(p, pat) ? box(p) : null;
        if (b != null) {
            start(p, boxSource(b[0], b[1], (pos, old) -> pat.pick()), "set " + pat.describe());
        }
    }

    public static void set(ServerPlayerEntity p, BlockState to) {
        set(p, single(to));
    }

    public static void replace(ServerPlayerEntity p, Block from, Pattern pat) {
        BlockPos[] b = check(p) && checkPattern(p, pat) ? box(p) : null;
        if (b != null) {
            start(p, boxSource(b[0], b[1], (pos, old) -> old.isOf(from) ? pat.pick() : null),
                    "replace " + BuilderLog.blockName(BuildFiles.stateString(from.getDefaultState())) + " with " + pat.describe());
        }
    }

    public static void replace(ServerPlayerEntity p, Block from, BlockState to) {
        replace(p, from, single(to));
    }

    public static void walls(ServerPlayerEntity p, Pattern pat) {
        BlockPos[] b = check(p) && checkPattern(p, pat) ? box(p) : null;
        if (b != null) {
            BlockPos min = b[0];
            BlockPos max = b[1];
            start(p, boxSource(min, max, (pos, old) -> pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ() ? pat.pick() : null), "walls " + pat.describe());
        }
    }

    public static void walls(ServerPlayerEntity p, BlockState to) {
        walls(p, single(to));
    }

    /** All six sides of the selection. */
    public static void hollow(ServerPlayerEntity p, Pattern pat) {
        BlockPos[] b = check(p) && checkPattern(p, pat) ? box(p) : null;
        if (b != null) {
            BlockPos min = b[0];
            BlockPos max = b[1];
            start(p, boxSource(min, max, (pos, old) -> pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getY() == min.getY() || pos.getY() == max.getY()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ() ? pat.pick() : null), "hollow box " + pat.describe());
        }
    }

    /** A straight line from the first corner to the second. */
    public static void line(ServerPlayerEntity p, Pattern pat) {
        Sel s = SEL.get(p.getUuid());
        if (!check(p) || !checkPattern(p, pat)) {
            return;
        }
        if (s == null || s.a == null || s.b == null) {
            Msg.send(p, "build.need-selection");
            return;
        }
        List<BlockPos> pts = new ArrayList<>();
        int steps = Math.max(Math.abs(s.b.getX() - s.a.getX()), Math.max(Math.abs(s.b.getY() - s.a.getY()), Math.abs(s.b.getZ() - s.a.getZ())));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            BlockPos q = BlockPos.ofFloored(s.a.getX() + 0.5 + (s.b.getX() - s.a.getX()) * t, s.a.getY() + 0.5 + (s.b.getY() - s.a.getY()) * t,
                    s.a.getZ() + 0.5 + (s.b.getZ() - s.a.getZ()) * t);
            if (pts.isEmpty() || !pts.get(pts.size() - 1).equals(q)) {
                pts.add(q);
            }
        }
        start(p, listSource(pts, (pos, old) -> pat.pick()), "line " + pat.describe());
    }

    static Source listSource(List<BlockPos> pts, java.util.function.BiFunction<BlockPos, BlockState, BlockState> fn) {
        return new Source() {
            @Override
            public int size() {
                return pts.size();
            }

            @Override
            public BlockPos pos(int i) {
                return pts.get(i);
            }

            @Override
            public BlockState state(int i, BlockState old) {
                return fn.apply(pts.get(i), old);
            }
        };
    }

    // ---------------------------------------------------------------- shapes (at the player)

    private static boolean radiusOk(ServerPlayerEntity p, int r) {
        if (r < 1 || r > MAX_RADIUS) {
            Msg.send(p, "build.bad-radius", MAX_RADIUS);
            return false;
        }
        return true;
    }

    /** A ball centred on the player's feet. */
    public static void sphere(ServerPlayerEntity p, Pattern pat, int r, boolean hollow) {
        if (!check(p) || !checkPattern(p, pat) || !radiusOk(p, r)) {
            return;
        }
        BlockPos c = p.getBlockPos();
        double outer = (r + 0.5) * (r + 0.5);
        double inner = (r - 0.5) * (r - 0.5);
        start(p, boxSource(c.add(-r, -r, -r), c.add(r, r, r), (pos, old) -> {
            double d = pos.getSquaredDistance(c);
            return d <= outer && (!hollow || d > inner) ? pat.pick() : null;
        }), (hollow ? "hollow sphere r" : "sphere r") + r + " " + pat.describe());
    }

    /** An upright cylinder standing on the player's feet. */
    public static void cylinder(ServerPlayerEntity p, Pattern pat, int r, int h, boolean hollow) {
        if (!check(p) || !checkPattern(p, pat) || !radiusOk(p, r) || !radiusOk(p, h)) {
            return;
        }
        BlockPos c = p.getBlockPos();
        double outer = (r + 0.5) * (r + 0.5);
        double inner = (r - 0.5) * (r - 0.5);
        start(p, boxSource(c.add(-r, 0, -r), c.add(r, h - 1, r), (pos, old) -> {
            double dx = pos.getX() - c.getX();
            double dz = pos.getZ() - c.getZ();
            double d = dx * dx + dz * dz;
            return d <= outer && (!hollow || d > inner) ? pat.pick() : null;
        }), (hollow ? "hollow cylinder r" : "cylinder r") + r + " h" + h + " " + pat.describe());
    }

    /** A pyramid whose base (size x size... each step 1 smaller) stands on the player's feet. */
    public static void pyramid(ServerPlayerEntity p, Pattern pat, int size, boolean hollow) {
        if (!check(p) || !checkPattern(p, pat) || !radiusOk(p, size)) {
            return;
        }
        BlockPos c = p.getBlockPos();
        start(p, boxSource(c.add(-size, 0, -size), c.add(size, size, size), (pos, old) -> {
            int y = pos.getY() - c.getY();
            int half = size - y;
            int ax = Math.abs(pos.getX() - c.getX());
            int az = Math.abs(pos.getZ() - c.getZ());
            if (ax > half || az > half) {
                return null;
            }
            boolean edge = ax == half || az == half;
            return !hollow || edge || half == 0 ? pat.pick() : null;
        }), (hollow ? "hollow pyramid " : "pyramid ") + size + " " + pat.describe());
    }

    // ---------------------------------------------------------------- terrain

    /**
     * A mountain (or hills) filling the selection's ground: rough peaks up to {@code height} blocks above the selection's
     * bottom, stone inside, dirt and grass on top, snow on the high peaks. Hills are lower and rounder, with several bumps.
     */
    public static void terrain(ServerPlayerEntity p, int height, boolean hills) {
        if (!check(p)) {
            return;
        }
        BlockPos[] b = box(p);
        if (b == null) {
            return;
        }
        if (height < 3 || height > 256) {
            Msg.send(p, "build.bad-radius", 256);
            return;
        }
        BlockPos min = b[0];
        BlockPos max = new BlockPos(b[1].getX(), Math.min(min.getY() + height, p.getEntityWorld().getTopYInclusive()), b[1].getZ());
        long vol = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (vol > MAX_EDIT) {
            Msg.send(p, "build.too-big", vol, MAX_EDIT);
            return;
        }
        int w = max.getX() - min.getX() + 1;
        int l = max.getZ() - min.getZ() + 1;
        long seed = p.getRandom().nextLong();
        int[] top = new int[w * l];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < l; z++) {
                // 0 at the selection's edge, 1 in its middle
                double nx = (x + 0.5) / w * 2 - 1;
                double nz = (z + 0.5) / l * 2 - 1;
                double d = Math.min(1, Math.sqrt(nx * nx + nz * nz));
                double shape;
                double n = noise(seed, x / 14.0, z / 14.0) * 0.6 + noise(seed + 1, x / 6.0, z / 6.0) * 0.3
                        + noise(seed + 2, x / 2.5, z / 2.5) * 0.1;
                if (hills) {
                    double fall = Math.cos(d * Math.PI / 2);
                    shape = fall * (0.35 + 0.65 * noise(seed + 3, x / 18.0, z / 18.0)) * (0.8 + 0.2 * n);
                } else {
                    double fall = Math.pow(1 - d, 1.6);
                    shape = fall * (0.65 + 0.55 * n);
                }
                top[x * l + z] = (int) Math.round(Math.max(0, Math.min(1, shape)) * height);
            }
        }
        int snow = (int) (height * 0.78);
        start(p, boxSource(min, max, (pos, old) -> {
            int x = pos.getX() - min.getX();
            int z = pos.getZ() - min.getZ();
            int y = pos.getY() - min.getY();
            int t = top[x * l + z];
            if (y > t) {
                return y == t + 1 && t >= snow && !hills ? Blocks.SNOW.getDefaultState() : null;
            }
            if (y == t) {
                return t >= snow && !hills ? Blocks.SNOW_BLOCK.getDefaultState()
                        : t > height * 0.55 && !hills ? Blocks.STONE.getDefaultState() : Blocks.GRASS_BLOCK.getDefaultState();
            }
            if (y >= t - 3 && (hills || t <= height * 0.55)) {
                return Blocks.DIRT.getDefaultState();
            }
            return (x * 31 + y * 17 + z * 7) % 23 == 0 ? Blocks.ANDESITE.getDefaultState() : Blocks.STONE.getDefaultState();
        }), (hills ? "hills " : "mountain ") + height);
    }

    /** Smooth value noise in 0..1. */
    private static double noise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        fx = fx * fx * (3 - 2 * fx);
        fz = fz * fz * (3 - 2 * fz);
        double a = hash(seed, x0, z0);
        double b = hash(seed, x0 + 1, z0);
        double c = hash(seed, x0, z0 + 1);
        double d = hash(seed, x0 + 1, z0 + 1);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static double hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 11) / (double) (1L << 53);
    }

    // ---------------------------------------------------------------- brushes

    public static void setBrush(ServerPlayerEntity p, BrushMode mode, Pattern pat, int radius) {
        if (!canUse(p) || (mode != BrushMode.SMOOTH && !checkPattern(p, pat)) || !radiusOk(p, Math.min(radius, 16)) || radius > 16) {
            if (radius > 16) {
                Msg.send(p, "build.bad-radius", 16);
            }
            return;
        }
        BRUSH.put(p.getUuid(), new Brush(mode, pat, radius));
        boolean has = false;
        for (int i = 0; i < p.getInventory().size(); i++) {
            has |= Tools.is(p.getInventory().getStack(i), Tools.BUILDER_BRUSH);
        }
        if (!has) {
            p.getInventory().insertStack(Tools.builderBrush());
        }
        Msg.send(p, "build.brush-set", mode.name().toLowerCase(), radius, pat == null ? "-" : pat.describe());
        if (BuilderMode.is(p)) {
            BuilderLog.event(p, "BRUSH", mode + " r" + radius + " " + (pat == null ? "" : pat.describe()));
        }
    }

    public static Brush brush(ServerPlayerEntity p) {
        return BRUSH.get(p.getUuid());
    }

    /** Uses the brush where the player is looking (up to 120 blocks away). */
    public static void useBrush(ServerPlayerEntity p) {
        Brush b = BRUSH.get(p.getUuid());
        if (b == null) {
            Msg.send(p, "build.no-brush");
            return;
        }
        if (!check(p)) {
            return;
        }
        HitResult hit = p.raycast(120, 0, false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
            Msg.actionBar(p, Msg.trFor(p, "build.brush-nothing"));
            return;
        }
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        BlockPos c = bh.getBlockPos();
        int r = b.radius;
        double outer = (r + 0.5) * (r + 0.5);
        BlockPos min = c.add(-r, -r, -r);
        BlockPos max = c.add(r, r, r);
        Source src = switch (b.mode) {
            case SPHERE -> boxSource(min, max, (pos, old) -> pos.getSquaredDistance(c) <= outer ? b.pattern.pick() : null);
            case PAINT -> boxSource(min, max, (pos, old) -> pos.getSquaredDistance(c) <= outer && !old.isAir() && exposed(w, pos)
                    ? b.pattern.pick() : null);
            case SCATTER -> boxSource(min, max, (pos, old) -> pos.getSquaredDistance(c) <= outer && old.isAir()
                    && w.getBlockState(pos.down()).isSolidBlock(w, pos.down())
                    && ThreadLocalRandom.current().nextInt(4) == 0 ? b.pattern.pick() : null);
            case SMOOTH -> smoothSource(w, c, r);
        };
        start(p, src, "brush " + b.mode.name().toLowerCase() + " r" + r + " at " + c.toShortString()
                + (b.pattern == null ? "" : " " + b.pattern.describe()));
    }

    private static boolean exposed(ServerWorld w, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (w.getBlockState(pos.offset(d)).isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Evens out the ground: each column moves toward the average height of its neighbours. */
    private static Source smoothSource(ServerWorld w, BlockPos c, int r) {
        int size = 2 * r + 1;
        int[][] top = new int[size][size];
        BlockState[][] surface = new BlockState[size][size];
        int minY = c.getY() - r;
        int maxY = c.getY() + r;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                top[dx][dz] = minY - 1;
                for (int y = maxY; y >= minY; y--) {
                    BlockPos at = new BlockPos(c.getX() - r + dx, y, c.getZ() - r + dz);
                    BlockState s = w.getBlockState(at);
                    if (!s.isAir() && s.isSolidBlock(w, at)) {
                        top[dx][dz] = y;
                        surface[dx][dz] = s;
                        break;
                    }
                }
            }
        }
        int[][] goal = new int[size][size];
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int sum = 0;
                int n = 0;
                for (int ox = -2; ox <= 2; ox++) {
                    for (int oz = -2; oz <= 2; oz++) {
                        int x = dx + ox;
                        int z = dz + oz;
                        if (x >= 0 && z >= 0 && x < size && z < size && top[x][z] >= minY) {
                            sum += top[x][z];
                            n++;
                        }
                    }
                }
                goal[dx][dz] = n == 0 ? top[dx][dz] : Math.round((float) sum / n);
            }
        }
        double outer = (r + 0.5) * (r + 0.5);
        return boxSource(c.add(-r, -r, -r), c.add(r, r, r), (pos, old) -> {
            int dx = pos.getX() - c.getX() + r;
            int dz = pos.getZ() - c.getZ() + r;
            double hd = (pos.getX() - c.getX()) * (pos.getX() - c.getX()) + (pos.getZ() - c.getZ()) * (pos.getZ() - c.getZ());
            if (hd > outer || surface[dx][dz] == null) {
                return null;
            }
            int y = pos.getY();
            if (y <= goal[dx][dz] && y > top[dx][dz]) {
                return surface[dx][dz];
            }
            if (y > goal[dx][dz] && y <= top[dx][dz] && !old.isAir()) {
                return Blocks.AIR.getDefaultState();
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- clipboard

    public static void copy(ServerPlayerEntity p) {
        BlockPos[] b = check(p) ? box(p) : null;
        if (b == null) {
            return;
        }
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        BlockPos min = b[0];
        BlockPos max = b[1];
        Clip c = new Clip(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1);
        for (int y = 0; y < c.sy; y++) {
            for (int z = 0; z < c.sz; z++) {
                for (int x = 0; x < c.sx; x++) {
                    BlockState s = w.getBlockState(min.add(x, y, z));
                    c.set(x, y, z, allowedBlock(s) ? s : Blocks.AIR.getDefaultState());
                }
            }
        }
        BlockPos at = p.getBlockPos();
        c.ox = min.getX() - at.getX();
        c.oy = min.getY() - at.getY();
        c.oz = min.getZ() - at.getZ();
        CLIP.put(p.getUuid(), c);
        Msg.send(p, "build.copied", c.volume());
        if (BuilderMode.is(p)) {
            BuilderLog.event(p, "COPY", min.toShortString() + " to " + max.toShortString());
        }
    }

    /** Shows where the clipboard would go and asks to confirm (the outline stays for 30 seconds). */
    public static void paste(ServerPlayerEntity p, boolean air) {
        if (!check(p)) {
            return;
        }
        Clip c = CLIP.get(p.getUuid());
        if (c == null) {
            Msg.send(p, "build.empty-clipboard");
            return;
        }
        BlockPos origin = p.getBlockPos().add(c.ox, c.oy, c.oz);
        PENDING.put(p.getUuid(), new Pending(Mc.worldId(p.getEntityWorld()), origin, air, System.currentTimeMillis() + 30_000));
        MutableText t = Msg.typed(Msg.Type.INFO, Msg.trFor(p, "build.preview", origin.toShortString(), c.sx + "x" + c.sy + "x" + c.sz));
        t.append(" ").append(Msg.button("§a[" + Msg.trFor(p, "build.confirm") + "]", "/build confirm", Msg.trFor(p, "build.confirm")));
        t.append(" ").append(Msg.button("§c[" + Msg.trFor(p, "build.cancel") + "]", "/build cancel", Msg.trFor(p, "build.cancel")));
        Msg.sendRaw(p, t);
    }

    public static void confirmPaste(ServerPlayerEntity p) {
        Pending pend = PENDING.remove(p.getUuid());
        if (pend == null || System.currentTimeMillis() > pend.expires || !pend.world.equals(Mc.worldId(p.getEntityWorld()))) {
            Msg.send(p, "build.no-preview");
            return;
        }
        pasteAt(p, pend.origin, pend.air);
    }

    public static void cancelPaste(ServerPlayerEntity p) {
        PENDING.remove(p.getUuid());
        Msg.send(p, "build.cancelled");
    }

    /** Pastes straight away, without a preview. */
    public static void pasteNow(ServerPlayerEntity p, boolean air) {
        Clip c = CLIP.get(p.getUuid());
        if (c == null) {
            Msg.send(p, "build.empty-clipboard");
            return;
        }
        pasteAt(p, p.getBlockPos().add(c.ox, c.oy, c.oz), air);
    }

    private static void pasteAt(ServerPlayerEntity p, BlockPos origin, boolean air) {
        if (!check(p)) {
            return;
        }
        Clip c = CLIP.get(p.getUuid());
        if (c == null) {
            Msg.send(p, "build.empty-clipboard");
            return;
        }
        start(p, new Source() {
            @Override
            public int size() {
                return c.volume();
            }

            @Override
            public BlockPos pos(int i) {
                return origin.add(i % c.sx, i / (c.sx * c.sz), (i / c.sx) % c.sz);
            }

            @Override
            public BlockState state(int i, BlockState old) {
                BlockState s = c.states[i];
                return !air && s.isAir() ? null : s;
            }
        }, "paste " + c.sx + "x" + c.sy + "x" + c.sz + " at " + origin.toShortString() + (air ? "" : " (no air)"));
    }

    /** Turns the clipboard a quarter turn clockwise (seen from above), around the player. */
    public static void rotate(ServerPlayerEntity p) {
        Clip c = CLIP.get(p.getUuid());
        if (c == null) {
            Msg.send(p, "build.empty-clipboard");
            return;
        }
        Clip r = new Clip(c.sz, c.sy, c.sx);
        for (int y = 0; y < c.sy; y++) {
            for (int z = 0; z < c.sz; z++) {
                for (int x = 0; x < c.sx; x++) {
                    r.set(c.sz - 1 - z, y, x, c.get(x, y, z).rotate(BlockRotation.CLOCKWISE_90));
                }
            }
        }
        r.ox = -(c.oz + c.sz - 1);
        r.oy = c.oy;
        r.oz = c.ox;
        CLIP.put(p.getUuid(), r);
        Msg.send(p, "build.rotated");
    }

    /** Mirrors the clipboard east-west (x) or north-south (z), around the player. */
    public static void flip(ServerPlayerEntity p, boolean xAxis) {
        Clip c = CLIP.get(p.getUuid());
        if (c == null) {
            Msg.send(p, "build.empty-clipboard");
            return;
        }
        Clip r = new Clip(c.sx, c.sy, c.sz);
        BlockMirror mirror = xAxis ? BlockMirror.FRONT_BACK : BlockMirror.LEFT_RIGHT;
        for (int y = 0; y < c.sy; y++) {
            for (int z = 0; z < c.sz; z++) {
                for (int x = 0; x < c.sx; x++) {
                    int nx = xAxis ? c.sx - 1 - x : x;
                    int nz = xAxis ? z : c.sz - 1 - z;
                    r.set(nx, y, nz, c.get(x, y, z).mirror(mirror));
                }
            }
        }
        r.ox = xAxis ? -(c.ox + c.sx - 1) : c.ox;
        r.oy = c.oy;
        r.oz = xAxis ? c.oz : -(c.oz + c.sz - 1);
        CLIP.put(p.getUuid(), r);
        Msg.send(p, "build.flipped", xAxis ? "x" : "z");
    }

    public static void undo(ServerPlayerEntity p) {
        if (!check(p)) {
            return;
        }
        Deque<List<Change>> stack = UNDO.get(p.getUuid());
        List<Change> last = stack == null ? null : stack.pollFirst();
        if (last == null) {
            Msg.send(p, "build.nothing-to-undo");
            return;
        }
        UUID journal = BuilderMode.is(p) ? p.getUuid() : null;
        JOBS.put(p.getUuid(), new Job(p.getUuid(), p, (ServerWorld) p.getEntityWorld(), new Source() {
            @Override
            public int size() {
                return last.size();
            }

            @Override
            public BlockPos pos(int i) {
                return last.get(i).pos();
            }

            @Override
            public BlockState state(int i, BlockState old) {
                return last.get(i).before();
            }
        }, "undo", false, false, journal, "TOOL:undo", null));
    }

    // ---------------------------------------------------------------- outlines

    /** Particle outline of a paste waiting for confirmation, and of the selection while holding the wand. */
    private static void drawOutlines() {
        long now = System.currentTimeMillis();
        PENDING.values().removeIf(pd -> now > pd.expires);
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Pending pd = PENDING.get(p.getUuid());
            Clip c = CLIP.get(p.getUuid());
            if (pd != null && c != null && pd.world.equals(Mc.worldId(p.getEntityWorld()))) {
                outline(p, pd.origin, pd.origin.add(c.sx - 1, c.sy - 1, c.sz - 1), ParticleTypes.END_ROD);
            }
            Sel s = SEL.get(p.getUuid());
            if (s != null && s.a != null && s.b != null && s.world.equals(Mc.worldId(p.getEntityWorld()))
                    && Tools.is(p.getMainHandStack(), Tools.BUILDER_WAND)) {
                outline(p, s.a, s.b, ParticleTypes.HAPPY_VILLAGER);
            }
        }
    }

    private static void outline(ServerPlayerEntity p, BlockPos a, BlockPos b, net.minecraft.particle.ParticleEffect effect) {
        double x0 = Math.min(a.getX(), b.getX());
        double y0 = Math.min(a.getY(), b.getY());
        double z0 = Math.min(a.getZ(), b.getZ());
        double x1 = Math.max(a.getX(), b.getX()) + 1;
        double y1 = Math.max(a.getY(), b.getY()) + 1;
        double z1 = Math.max(a.getZ(), b.getZ()) + 1;
        double longest = Math.max(x1 - x0, Math.max(y1 - y0, z1 - z0));
        double step = Math.max(1, longest / 40);
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        for (double[] e : edges) {
            double len = Math.max(Math.abs(e[3] - e[0]), Math.max(Math.abs(e[4] - e[1]), Math.abs(e[5] - e[2])));
            for (double t = 0; t <= len; t += step) {
                double f = len == 0 ? 0 : t / len;
                Mc.particle(p, effect, e[0] + (e[3] - e[0]) * f, e[1] + (e[4] - e[1]) * f, e[2] + (e[5] - e[2]) * f);
            }
        }
    }

    // ---------------------------------------------------------------- files

    public static void load(ServerPlayerEntity p, String name) {
        if (!canUse(p)) {
            return;
        }
        Path f = BuildFiles.list().get(BuildFiles.cleanName(name));
        if (f == null) {
            Msg.send(p, "build.file-not-found", name);
            return;
        }
        try {
            Clip c = BuildFiles.read(f);
            int removed = 0;
            for (int i = 0; i < c.states.length; i++) {
                if (!allowedBlock(c.states[i])) {
                    c.states[i] = Blocks.AIR.getDefaultState();
                    removed++;
                }
            }
            CLIP.put(p.getUuid(), c);
            boolean centred = c.ox != 0 || c.oy != 0 || c.oz != 0;
            Msg.send(p, centred ? "build.loaded-spot" : "build.loaded", BuildFiles.cleanName(name), c.sx + "x" + c.sy + "x" + c.sz);
            if (BuilderMode.is(p)) {
                BuilderLog.event(p, "LOAD", BuildFiles.cleanName(name) + " " + c.sx + "x" + c.sy + "x" + c.sz
                        + (removed > 0 ? ", " + removed + " blocks left out" : ""));
            }
        } catch (Exception e) {
            Msg.send(p, "build.bad-file", name, String.valueOf(e.getMessage()));
        }
    }

    public static void save(ServerPlayerEntity p, String name) {
        if (!canUse(p)) {
            return;
        }
        Clip c = CLIP.get(p.getUuid());
        if (c == null) {
            Msg.send(p, "build.empty-clipboard");
            return;
        }
        String n = BuildFiles.cleanName(name);
        if (n.isEmpty()) {
            Msg.send(p, "build.bad-name");
            return;
        }
        try {
            BuildFiles.write(c, BuildFiles.dir().resolve(n + ".schem"));
            Msg.send(p, "build.saved", n);
            if (BuilderMode.is(p)) {
                BuilderLog.event(p, "SAVE", n + ".schem");
            }
        } catch (Exception e) {
            Msg.send(p, "build.bad-file", n, String.valueOf(e.getMessage()));
        }
    }

    /** Downloads a build file from a link into the builds folder (in the background), then loads it. */
    public static void importUrl(ServerPlayerEntity p, String url, String name) {
        if (!canUse(p)) {
            return;
        }
        String n = BuildFiles.cleanName(name);
        if (n.isEmpty()) {
            Msg.send(p, "build.bad-name");
            return;
        }
        Msg.send(p, "build.downloading");
        if (BuilderMode.is(p)) {
            BuilderLog.event(p, "IMPORT", n + " from " + url);
        }
        var server = Ac.server();
        Thread t = new Thread(() -> {
            try {
                byte[] data = BuildFiles.download(url);
                String ext = BuildFiles.extensionFor(url, data);
                Files.createDirectories(BuildFiles.dir());
                Files.write(BuildFiles.dir().resolve(n + ext), data);
                server.execute(() -> {
                    Staff.log(p, "build-import", null, n, url);
                    load(p, n);
                });
            } catch (Exception e) {
                server.execute(() -> Msg.send(p, "build.download-failed", String.valueOf(e.getMessage())));
            }
        }, "Vigil build import");
        t.setDaemon(true);
        t.start();
    }

    public static void forget(UUID id) {
        SEL.remove(id);
        CLIP.remove(id);
        UNDO.remove(id);
        JOBS.remove(id);
        PENDING.remove(id);
        BRUSH.remove(id);
    }
}
