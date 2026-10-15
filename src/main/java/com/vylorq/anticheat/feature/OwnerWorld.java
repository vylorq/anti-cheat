package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's world tools: a Terrain Brush (raise or flatten a circle of land), a Tree Spawner (a full-grown tree of
 * any kind), Instant Builds (a house, tower, bridge, well or watchtower in front of you), and Region Reset (chunks back
 * to how they were first generated, done while the server restarts). Every change can be undone with /owner worldundo.
 */
public final class OwnerWorld {
    private OwnerWorld() {
    }

    static final String TOOL = "vigil_world_tool";

    // ---------------------------------------------------------------- undo

    private static final Map<UUID, Deque<Map<BlockPos, BlockState>>> UNDO = new HashMap<>();
    private static final int MAX_UNDO = 15;

    /** One change in progress: every block it touches, as it was before. */
    private static final class Change {
        final ServerWorld w;
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();

        Change(ServerWorld w) {
            this.w = w;
        }

        void set(BlockPos pos, BlockState st) {
            before.putIfAbsent(pos.toImmutable(), w.getBlockState(pos));
            w.setBlockState(pos, st);
        }

        void done(ServerPlayerEntity p) {
            if (before.isEmpty()) {
                return;
            }
            Deque<Map<BlockPos, BlockState>> stack = UNDO.computeIfAbsent(p.getUuid(), x -> new ArrayDeque<>());
            stack.push(before);
            while (stack.size() > MAX_UNDO) {
                stack.removeLast();
            }
        }
    }

    public static void undo(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        int n = undoAt(p);
        Msg.send(p, n < 0 ? "world.nothing-to-undo" : "world.undone", Math.max(0, n));
    }

    /** Puts back the last change; the number of blocks, or -1 if there was nothing. */
    static int undoAt(ServerPlayerEntity p) {
        var stack = UNDO.get(p.getUuid());
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        var before = stack.pop();
        ServerWorld w = p.getEntityWorld();
        List<BlockPos> order = new ArrayList<>(before.keySet());
        for (int i = order.size() - 1; i >= 0; i--) {
            w.setBlockState(order.get(i), before.get(order.get(i)));
        }
        return before.size();
    }

    public static void buildForTest(ServerPlayerEntity p, String name) {
        buildAt(p, name);
    }

    public static int undoForTest(ServerPlayerEntity p) {
        return undoAt(p);
    }

    // ---------------------------------------------------------------- tools

    private static ItemStack tool(Item base, String id, String name, String... lore) {
        ItemStack s = Icons.glint(Icons.of(base, name, lore));
        ItemConv.setTag(s, TOOL, id);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    public static ItemStack brush() {
        return tool(Items.GOLDEN_SHOVEL, "brush", "§6⛰ Terrain Brush", "Right-click: raise the land where you look",
                "Sneak + right-click: flatten it to that height", "Radius: /owner brush <1-16>", "Undo: /owner worldundo", "§8Owner only");
    }

    public static ItemStack treeSpawner() {
        return tool(Items.OAK_SAPLING, "tree", "§a🌳 Tree Spawner", "Right-click a block: grow a full tree there",
                "Sneak + right-click: change the kind of tree", "Undo: /owner worldundo", "§8Owner only");
    }

    public static List<ItemStack> tools() {
        return List.of(brush(), treeSpawner());
    }

    private static final Map<UUID, Integer> RADIUS = new HashMap<>();
    private static final Map<UUID, Integer> TREE = new HashMap<>();

    private static final Map<UUID, Integer> RESET_RADIUS = new HashMap<>();

    public static int radius(ServerPlayerEntity p) {
        return RADIUS.getOrDefault(p.getUuid(), 4);
    }

    public static int resetRadius(ServerPlayerEntity p) {
        return RESET_RADIUS.getOrDefault(p.getUuid(), 0);
    }

    public static void setResetRadius(ServerPlayerEntity p, int r) {
        RESET_RADIUS.put(p.getUuid(), Math.max(0, Math.min(8, r)));
    }

    public static int undoCount(ServerPlayerEntity p) {
        var s = UNDO.get(p.getUuid());
        return s == null ? 0 : s.size();
    }

    public static void setRadius(ServerPlayerEntity p, int r) {
        if (OwnerPowers.require(p)) {
            RADIUS.put(p.getUuid(), r);
            Msg.send(p, "world.radius", r);
        }
    }

    private static BlockPos target(ServerPlayerEntity p) {
        HitResult hit = p.raycast(160, 1f, false);
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
    }

    // ---------------------------------------------------------------- terrain brush

    private static boolean solid(BlockState st) {
        return !st.isAir() && st.getFluidState().isEmpty() && !st.isReplaceable();
    }

    /** Raise: the circle grows a block higher (more in the middle), topped with whatever its ground is. */
    private static void raise(ServerPlayerEntity p, BlockPos at) {
        ServerWorld w = p.getEntityWorld();
        int r = RADIUS.getOrDefault(p.getUuid(), 4);
        Change c = new Change(w);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r) {
                    continue;
                }
                int up = d < r * 0.5 ? 2 : 1;
                BlockPos top = w.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at.add(dx, 0, dz)).down();
                BlockState surface = w.getBlockState(top);
                if (!solid(surface)) {
                    continue;
                }
                BlockState under = surface.isOf(Blocks.GRASS_BLOCK) ? Blocks.DIRT.getDefaultState() : surface;
                for (int k = 1; k <= up; k++) {
                    c.set(top.up(k - 1), under);
                    c.set(top.up(k), surface);
                }
            }
        }
        c.done(p);
        w.playSound(null, at, SoundEvents.BLOCK_ROOTED_DIRT_PLACE, SoundCategory.BLOCKS, 1f, 0.8f);
    }

    /** Flatten: everything in the circle above the clicked height is cleared, holes below it are filled. */
    private static void flatten(ServerPlayerEntity p, BlockPos at) {
        ServerWorld w = p.getEntityWorld();
        int r = RADIUS.getOrDefault(p.getUuid(), 4);
        BlockState surface = w.getBlockState(at);
        if (!solid(surface)) {
            surface = Blocks.GRASS_BLOCK.getDefaultState();
        }
        BlockState fill = surface.isOf(Blocks.GRASS_BLOCK) ? Blocks.DIRT.getDefaultState() : surface;
        Change c = new Change(w);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                BlockPos col = at.add(dx, 0, dz);
                for (int y = 1; y <= 24; y++) {
                    BlockPos q = col.up(y);
                    if (!w.getBlockState(q).isAir()) {
                        c.set(q, Blocks.AIR.getDefaultState());
                    }
                }
                for (int y = 0; y >= -6; y--) {
                    BlockPos q = col.up(y);
                    if (!solid(w.getBlockState(q))) {
                        c.set(q, y == 0 ? surface : fill);
                    }
                }
                if (w.getBlockState(col).isOf(Blocks.DIRT)) {
                    c.set(col, surface);
                }
            }
        }
        c.done(p);
        w.playSound(null, at, SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 1f, 0.8f);
    }

    // ---------------------------------------------------------------- tree spawner

    private static final String[][] TREES = {
            {"oak", "minecraft:oak"}, {"big oak", "minecraft:fancy_oak"}, {"birch", "minecraft:birch"},
            {"tall birch", "minecraft:super_birch_bees_0002"}, {"spruce", "minecraft:spruce"}, {"giant spruce", "minecraft:mega_spruce"},
            {"jungle", "minecraft:jungle_tree"}, {"giant jungle", "minecraft:mega_jungle_tree"}, {"acacia", "minecraft:acacia"},
            {"dark oak", "minecraft:dark_oak"}, {"cherry", "minecraft:cherry"}, {"mangrove", "minecraft:mangrove"},
            {"pale oak", "minecraft:pale_oak"}, {"azalea", "minecraft:azalea_tree"}, {"huge red mushroom", "minecraft:huge_red_mushroom"},
            {"huge brown mushroom", "minecraft:huge_brown_mushroom"}, {"crimson fungus", "minecraft:crimson_fungus_planted"},
            {"warped fungus", "minecraft:warped_fungus_planted"}};

    private static void cycleTree(ServerPlayerEntity p) {
        int i = (TREE.getOrDefault(p.getUuid(), 0) + 1) % TREES.length;
        TREE.put(p.getUuid(), i);
        Msg.actionBar(p, "§a🌳 " + TREES[i][0]);
    }

    private static void growTree(ServerPlayerEntity p, BlockPos ground) {
        ServerWorld w = p.getEntityWorld();
        String[] kind = TREES[TREE.getOrDefault(p.getUuid(), 0)];
        var feature = w.getRegistryManager().getOrThrow(RegistryKeys.CONFIGURED_FEATURE)
                .getEntry(Identifier.of(kind[1])).orElse(null);
        if (feature == null) {
            Msg.send(p, "world.no-tree", kind[0]);
            return;
        }
        // Remember the area the tree can grow into, then grow it.
        Change c = new Change(w);
        BlockPos base = ground.up();
        List<BlockPos> box = new ArrayList<>();
        for (BlockPos q : BlockPos.iterate(base.add(-8, -1, -8), base.add(8, 40, 8))) {
            box.add(q.toImmutable());
        }
        Map<BlockPos, BlockState> snap = new HashMap<>();
        for (BlockPos q : box) {
            snap.put(q, w.getBlockState(q));
        }
        boolean grew = feature.value().generate(w, w.getChunkManager().getChunkGenerator(), w.getRandom(), base);
        if (!grew) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "world.tree-no-room"));
            return;
        }
        for (BlockPos q : box) {
            BlockState was = snap.get(q);
            if (w.getBlockState(q) != was) {
                c.before.put(q, was);
            }
        }
        c.done(p);
        w.playSound(null, base, SoundEvents.ITEM_BONE_MEAL_USE, SoundCategory.BLOCKS, 1f, 1f);
    }

    // ---------------------------------------------------------------- instant builds

    public static final List<String> BUILDS = List.of("house", "tower", "bridge", "well", "watchtower");

    /** Places a small build in front of the owner, facing them. */
    public static void build(ServerPlayerEntity p, String name) {
        if (OwnerPowers.require(p)) {
            buildAt(p, name);
            Msg.send(p, "world.built", name);
        }
    }

    static void buildAt(ServerPlayerEntity p, String name) {
        ServerWorld w = p.getEntityWorld();
        Direction f = p.getHorizontalFacing();
        Direction right = f.rotateYClockwise();
        BlockPos origin = p.getBlockPos().offset(f, 3);
        Change c = new Change(w);
        // local (x right, y up, z forward) -> world
        java.util.function.BiConsumer<int[], BlockState> put = (xyz, st) ->
                c.set(origin.offset(right, xyz[0]).up(xyz[1]).offset(f, xyz[2]), st);
        BlockState plank = Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState log = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState glass = Blocks.GLASS_PANE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        switch (name) {
            case "house" -> {
                for (int x = -3; x <= 3; x++) {
                    for (int z = 0; z <= 6; z++) {
                        put.accept(new int[]{x, -1, z}, Blocks.COBBLESTONE.getDefaultState());
                        for (int y = 0; y <= 3; y++) {
                            boolean wall = x == -3 || x == 3 || z == 0 || z == 6;
                            boolean corner = (x == -3 || x == 3) && (z == 0 || z == 6);
                            put.accept(new int[]{x, y, z}, !wall ? air : corner ? log : y == 1 && (x == 0 || z == 3) ? glass : plank);
                        }
                    }
                }
                for (int k = 0; k <= 3; k++) {
                    for (int x = -4 + k; x <= 4 - k; x++) {
                        for (int z = -1 + k; z <= 7 - k; z++) {
                            if (x == -4 + k || x == 4 - k || z == -1 + k || z == 7 - k) {
                                put.accept(new int[]{x, 4 + k, z}, Blocks.DARK_OAK_PLANKS.getDefaultState());
                            }
                        }
                    }
                }
                put.accept(new int[]{0, 0, 0}, air);
                put.accept(new int[]{0, 1, 0}, air);
                put.accept(new int[]{0, 2, 3}, Blocks.LANTERN.getDefaultState());
                put.accept(new int[]{-2, 0, 5}, Blocks.CRAFTING_TABLE.getDefaultState());
                put.accept(new int[]{2, 0, 5}, Blocks.CHEST.getDefaultState());
            }
            case "tower" -> {
                for (int x = -2; x <= 2; x++) {
                    for (int z = 0; z <= 4; z++) {
                        put.accept(new int[]{x, -1, z}, stone);
                        for (int y = 0; y <= 13; y++) {
                            boolean wall = x == -2 || x == 2 || z == 0 || z == 4;
                            put.accept(new int[]{x, y, z}, !wall ? (y % 5 == 4 ? plank : air)
                                    : (y % 4 == 2 && (x == 0 || z == 2)) ? glass : stone);
                        }
                        if ((x + z) % 2 == 0 && (x == -2 || x == 2 || z == 0 || z == 4)) {
                            put.accept(new int[]{x, 14, z}, stone);
                        }
                    }
                }
                for (int y = 0; y <= 13; y++) {
                    put.accept(new int[]{1, y, 3}, Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, f.getOpposite()));
                }
                put.accept(new int[]{0, 0, 0}, air);
                put.accept(new int[]{0, 1, 0}, air);
            }
            case "bridge" -> {
                for (int z = 0; z <= 16; z++) {
                    for (int x = -1; x <= 1; x++) {
                        put.accept(new int[]{x, -1, z}, plank);
                        put.accept(new int[]{x, 0, z}, air);
                        put.accept(new int[]{x, 1, z}, air);
                    }
                    put.accept(new int[]{-2, 0, z}, Blocks.SPRUCE_FENCE.getDefaultState());
                    put.accept(new int[]{2, 0, z}, Blocks.SPRUCE_FENCE.getDefaultState());
                    if (z % 4 == 0) {
                        put.accept(new int[]{-2, 1, z}, Blocks.LANTERN.getDefaultState());
                        put.accept(new int[]{2, 1, z}, Blocks.LANTERN.getDefaultState());
                    }
                }
            }
            case "well" -> {
                for (int x = -2; x <= 2; x++) {
                    for (int z = 0; z <= 4; z++) {
                        boolean edge = x == -2 || x == 2 || z == 0 || z == 4;
                        put.accept(new int[]{x, -1, z}, Blocks.COBBLESTONE.getDefaultState());
                        put.accept(new int[]{x, 0, z}, edge ? Blocks.COBBLESTONE.getDefaultState() : Blocks.WATER.getDefaultState());
                        put.accept(new int[]{x, -2, z}, Blocks.COBBLESTONE.getDefaultState());
                    }
                }
                for (int[] c2 : new int[][]{{-2, 0}, {2, 0}, {-2, 4}, {2, 4}}) {
                    for (int y = 1; y <= 3; y++) {
                        put.accept(new int[]{c2[0], y, c2[1]}, Blocks.SPRUCE_FENCE.getDefaultState());
                    }
                }
                for (int x = -2; x <= 2; x++) {
                    for (int z = 0; z <= 4; z++) {
                        put.accept(new int[]{x, 4, z}, Blocks.SPRUCE_SLAB.getDefaultState());
                    }
                }
            }
            default -> { // watchtower: a wooden lookout on stilts with a ladder
                for (int[] c2 : new int[][]{{-2, 0}, {2, 0}, {-2, 4}, {2, 4}}) {
                    for (int y = 0; y <= 9; y++) {
                        put.accept(new int[]{c2[0], y, c2[1]}, log);
                    }
                }
                for (int x = -2; x <= 2; x++) {
                    for (int z = 0; z <= 4; z++) {
                        put.accept(new int[]{x, 9, z}, plank);
                        if (x == -2 || x == 2 || z == 0 || z == 4) {
                            put.accept(new int[]{x, 10, z}, Blocks.SPRUCE_FENCE.getDefaultState());
                        }
                        put.accept(new int[]{x, 13, z}, Blocks.SPRUCE_SLAB.getDefaultState());
                    }
                }
                for (int[] c2 : new int[][]{{-2, 0}, {2, 0}, {-2, 4}, {2, 4}}) {
                    for (int y = 10; y <= 12; y++) {
                        put.accept(new int[]{c2[0], y, c2[1]}, Blocks.SPRUCE_FENCE.getDefaultState());
                    }
                }
                put.accept(new int[]{0, 10, 2}, Blocks.LANTERN.getDefaultState());
                for (int y = 0; y <= 9; y++) {
                    put.accept(new int[]{0, y, -1}, Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, f.getOpposite()));
                    put.accept(new int[]{0, y, 0}, y == 9 ? air : plank);
                }
            }
        }
        c.done(p);
        w.playSound(null, origin, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 1f, 1f);
    }

    // ---------------------------------------------------------------- region reset

    public static final class ResetQueue {
        /** world id -> chunk keys ("x,z") to put back as first generated at the next start. */
        public Map<String, List<String>> chunks = new HashMap<>();
    }

    private static Path queueFile() {
        return Ac.get().dir.resolve("region_reset.json");
    }

    /** Queues the chunks within {@code radius} chunks of the owner; they're reset the next time the server starts. */
    public static void queueReset(ServerPlayerEntity p, int radius) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ResetQueue q = readQueue();
        String world = com.vylorq.anticheat.util.Mc.worldId(p.getEntityWorld());
        List<String> list = q.chunks.computeIfAbsent(world, x -> new ArrayList<>());
        ChunkPos c = p.getChunkPos();
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                String k = (c.x + dx) + "," + (c.z + dz);
                if (!list.contains(k)) {
                    list.add(k);
                    n++;
                }
            }
        }
        try {
            Files.createDirectories(queueFile().getParent());
            Files.writeString(queueFile(), ConfigManager.GSON.toJson(q), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the region reset list", e);
        }
        Msg.send(p, "world.reset-queued", n, (2 * radius + 1) * 16);
    }

    public static void cancelReset(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        try {
            Files.deleteIfExists(queueFile());
        } catch (Exception ignored) {
            // nothing queued
        }
        Msg.send(p, "world.reset-cancelled");
    }

    private static ResetQueue readQueue() {
        try {
            Path f = queueFile();
            if (Files.exists(f)) {
                ResetQueue q = ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), ResetQueue.class);
                if (q != null && q.chunks != null) {
                    return q;
                }
            }
        } catch (Exception e) {
            Ac.LOG.warn("Could not read the region reset list", e);
        }
        return new ResetQueue();
    }

    /** The folder holding a dimension's region/entities/poi folders. */
    private static Path dimDir(Path root, String world) {
        return switch (world) {
            case "minecraft:the_nether" -> root.resolve("DIM-1");
            case "minecraft:the_end" -> root.resolve("DIM1");
            case "minecraft:overworld" -> root;
            default -> {
                Identifier id = Identifier.tryParse(world);
                yield id == null ? root : root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
            }
        };
    }

    /**
     * Runs as the server starts, before any world is loaded: each queued chunk is cut out of its region file (and its
     * entities and points of interest), so the game generates it fresh. Region files are backed up first.
     */
    public static void applyQueuedResets(net.minecraft.server.MinecraftServer server) {
        Path f;
        try {
            f = server.getRunDirectory().resolve("config").resolve("vigil").resolve("region_reset.json");
        } catch (Exception e) {
            return;
        }
        if (!Files.exists(f)) {
            return;
        }
        ResetQueue q;
        try {
            q = ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), ResetQueue.class);
        } catch (Exception e) {
            Ac.LOG.warn("Could not read the region reset list", e);
            return;
        }
        Path root = server.getSavePath(WorldSavePath.ROOT);
        Path backup = server.getRunDirectory().resolve("config").resolve("vigil").resolve("backups")
                .resolve("region-reset-" + System.currentTimeMillis());
        int done = 0;
        for (var e : q.chunks.entrySet()) {
            Path dim = dimDir(root, e.getKey());
            for (String k : e.getValue()) {
                String[] xz = k.split(",");
                int cx = Integer.parseInt(xz[0]);
                int cz = Integer.parseInt(xz[1]);
                for (String folder : List.of("region", "entities", "poi")) {
                    Path mca = dim.resolve(folder).resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
                    if (!Files.exists(mca)) {
                        continue;
                    }
                    try {
                        Path copy = backup.resolve(root.relativize(mca).toString());
                        if (!Files.exists(copy)) {
                            Files.createDirectories(copy.getParent());
                            Files.copy(mca, copy, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                        try (RandomAccessFile raf = new RandomAccessFile(mca.toFile(), "rw")) {
                            int index = (cx & 31) + (cz & 31) * 32;
                            raf.seek(4L * index);
                            raf.writeInt(0);
                            raf.seek(4096L + 4L * index);
                            raf.writeInt(0);
                        }
                    } catch (Exception ex) {
                        Ac.LOG.warn("Could not reset chunk {} in {}", k, mca, ex);
                    }
                }
                done++;
            }
        }
        try {
            Files.delete(f);
        } catch (Exception ignored) {
            // it'll be applied again (harmlessly) next start
        }
        Ac.LOG.info("Region reset: {} chunks will generate fresh (backup in {})", done, backup);
    }

    // ---------------------------------------------------------------- hooks

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(OwnerWorld::applyQueuedResets);
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld)) {
                return ActionResult.PASS;
            }
            String id = ItemConv.tag(p.getStackInHand(hand), TOOL);
            if (id == null || !OwnerPowers.require(p)) {
                return id == null ? ActionResult.PASS : ActionResult.FAIL;
            }
            use(p, id, hit.getBlockPos());
            return ActionResult.SUCCESS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld)) {
                return ActionResult.PASS;
            }
            String id = ItemConv.tag(p.getStackInHand(hand), TOOL);
            if (id == null || !OwnerPowers.require(p)) {
                return id == null ? ActionResult.PASS : ActionResult.FAIL;
            }
            use(p, id, target(p));
            return ActionResult.SUCCESS;
        });
    }

    private static long lastUse;

    private static void use(ServerPlayerEntity p, String id, BlockPos at) {
        // A click on a block sends both events: act once.
        long now = System.currentTimeMillis();
        if (now - lastUse < 250) {
            return;
        }
        lastUse = now;
        if (id.equals("tree") && p.isSneaking()) {
            cycleTree(p);
            return;
        }
        if (at == null) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "world.look-at-block"));
            return;
        }
        switch (id) {
            case "brush" -> {
                if (p.isSneaking()) {
                    flatten(p, at);
                } else {
                    raise(p, at);
                }
            }
            case "tree" -> growTree(p, at);
            default -> {
            }
        }
    }
}
