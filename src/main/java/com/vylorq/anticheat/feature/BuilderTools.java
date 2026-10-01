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
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builder tools (for builders and the owner): a wand to select a box, then set, replace, walls, copy, paste, rotate
 * and undo, plus build files (load, save, import from a link). Big edits run a little every tick so the server
 * doesn't freeze. Builders only ever place building blocks, and only where they may build.
 */
public final class BuilderTools {
    private BuilderTools() {
    }

    /** Blocks changed per tick by running edits. */
    static final int PER_TICK = 40_000;
    /** Biggest selection that can be edited at once. */
    public static final int MAX_EDIT = 2_000_000;
    /** Edits bigger than this can't be undone (the old blocks would take too much memory). */
    static final int MAX_UNDO = 400_000;

    static final class Sel {
        String world;
        BlockPos a;
        BlockPos b;
    }

    record Change(BlockPos pos, BlockState before) {
    }

    /** What an edit does at each of its positions. */
    interface Source {
        int size();

        BlockPos pos(int i);

        /** @return the new state, or null to leave the block as it is */
        BlockState state(int i, BlockState old);
    }

    static final class Job {
        final ServerPlayerEntity player;
        final ServerWorld world;
        final Source source;
        final String what;
        final List<Change> changes;
        int next;
        int changed;
        int skipped;

        Job(ServerPlayerEntity player, ServerWorld world, Source source, String what, boolean undoable) {
            this.player = player;
            this.world = world;
            this.source = source;
            this.what = what;
            this.changes = undoable ? new ArrayList<>() : null;
        }
    }

    private static final Map<UUID, Sel> SEL = new HashMap<>();
    private static final Map<UUID, Clip> CLIP = new HashMap<>();
    private static final Map<UUID, Deque<List<Change>>> UNDO = new HashMap<>();
    private static final Map<UUID, Job> JOBS = new HashMap<>();

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

    /** Every box position, in order (x fastest, then z, then y). */
    private static Source boxSource(BlockPos min, BlockPos max, java.util.function.BiFunction<BlockPos, BlockState, BlockState> fn) {
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

    // ---------------------------------------------------------------- edits

    public static BlockState block(String name) {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return null;
        }
        return Registries.BLOCK.get(id).getDefaultState();
    }

    public static void set(ServerPlayerEntity p, BlockState to) {
        BlockPos[] b = check(p) ? box(p) : null;
        if (b != null) {
            start(p, boxSource(b[0], b[1], (pos, old) -> to), "set");
        }
    }

    public static void replace(ServerPlayerEntity p, Block from, BlockState to) {
        BlockPos[] b = check(p) ? box(p) : null;
        if (b != null) {
            start(p, boxSource(b[0], b[1], (pos, old) -> old.isOf(from) ? to : null), "replace");
        }
    }

    public static void walls(ServerPlayerEntity p, BlockState to) {
        BlockPos[] b = check(p) ? box(p) : null;
        if (b != null) {
            BlockPos min = b[0];
            BlockPos max = b[1];
            start(p, boxSource(min, max, (pos, old) -> pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ() ? to : null), "walls");
        }
    }

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
    }

    /** Pastes the clipboard where it was copied from, relative to where the player stands now. */
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
        }, "paste");
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
        Job j = new Job(p, (ServerWorld) p.getEntityWorld(), new Source() {
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
        }, "undo", false);
        JOBS.put(p.getUuid(), j);
    }

    /** Builders may only place building blocks; containers already in the world are never touched. */
    public static boolean allowedBlock(BlockState s) {
        return s.isAir() || BuilderMode.allowedBlock(s.getBlock());
    }

    private static void start(ServerPlayerEntity p, Source src, String what) {
        Job j = new Job(p, (ServerWorld) p.getEntityWorld(), src, what, src.size() <= MAX_UNDO);
        JOBS.put(p.getUuid(), j);
        if (src.size() > MAX_UNDO) {
            Msg.send(p, "build.no-undo", MAX_UNDO);
        }
    }

    /** Every tick: runs a slice of each edit. */
    public static void tick() {
        if (JOBS.isEmpty()) {
            return;
        }
        int budget = PER_TICK;
        for (Iterator<Job> it = JOBS.values().iterator(); it.hasNext() && budget > 0; ) {
            Job j = it.next();
            if (j.player.isRemoved() || !canUse(j.player)) {
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
                if (!allowedBlock(now) || !mayEdit(j.player, j.world, pos)) {
                    j.skipped++;
                    continue;
                }
                if (j.changes != null) {
                    j.changes.add(new Change(pos, old));
                }
                j.world.setBlockState(pos, now, Block.NOTIFY_LISTENERS);
                j.changed++;
            }
            budget -= j.next - first;
            if (j.next >= size) {
                it.remove();
                if (j.changes != null && !j.changes.isEmpty()) {
                    Deque<List<Change>> stack = UNDO.computeIfAbsent(j.player.getUuid(), k -> new ArrayDeque<>());
                    stack.addFirst(j.changes);
                    while (stack.size() > 10) {
                        stack.removeLast();
                    }
                }
                Staff.log(j.player, "build-" + j.what, null, Mc.worldId(j.world), j.changed + " blocks");
                Msg.send(j.player, j.skipped > 0 ? "build.done-skipped" : "build.done", j.changed, j.skipped);
            } else if (size > PER_TICK * 5) {
                Msg.actionBar(j.player, Msg.trFor(j.player, "build.progress", j.next * 100 / size));
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
            for (int i = 0; i < c.states.length; i++) {
                if (!allowedBlock(c.states[i])) {
                    c.states[i] = Blocks.AIR.getDefaultState();
                }
            }
            c.ox = 0;
            c.oy = 0;
            c.oz = 0;
            CLIP.put(p.getUuid(), c);
            Msg.send(p, "build.loaded", BuildFiles.cleanName(name), c.sx + "x" + c.sy + "x" + c.sz);
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
    }
}
