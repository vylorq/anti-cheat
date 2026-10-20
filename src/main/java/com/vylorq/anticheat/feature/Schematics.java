package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds from files: the owner puts a schematic in config/vigil/schematics (uploaded with scp, or downloaded from a
 * link with /owner schematic download), then /owner schematic paste builds it in front of them, turned to face the
 * way they look. Reads WorldEdit/Sponge .schem (v2 and v3) and vanilla structure .nbt files, chest contents included.
 * Big builds go in over several ticks so the server doesn't freeze; /owner worldundo takes it away again.
 */
public final class Schematics {
    private Schematics() {
    }

    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final int MAX_BLOCKS = 4_000_000;
    private static final int PER_TICK = 40_000;

    /** One block of a build, at its place inside the build (x right, y up, z forward before turning). */
    private record Part(int x, int y, int z, BlockState state, NbtCompound be) {
    }

    private record Build(int w, int h, int l, List<Part> parts) {
    }

    public static Path dir() {
        return Ac.get().dir.resolve("schematics").toAbsolutePath().normalize();
    }

    public static List<String> list() {
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                return s.map(f -> f.getFileName().toString()).filter(Schematics::supported).sorted().toList();
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean supported(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".schem") || n.endsWith(".nbt") || n.endsWith(".schematic") || n.endsWith(".litematic");
    }

    // ---------------------------------------------------------------- reading

    /** A link that gave a web page (a download page, a login page) instead of the file itself. */
    private static boolean webPage(byte[] data) {
        int i = 0;
        while (i < data.length && i < 64 && Character.isWhitespace(data[i])) {
            i++;
        }
        return i < data.length && data[i] == '<';
    }

    private static Build read(Path f) throws Exception {
        byte[] data = Files.readAllBytes(f);
        if (webPage(data)) {
            throw new IllegalArgumentException("this is a web page, not a build file (use a direct download link)");
        }
        NbtCompound root;
        if (data.length > 1 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            root = NbtIo.readCompressed(new java.io.ByteArrayInputStream(data), NbtSizeTracker.of(MAX_BYTES * 8));
        } else if (data.length > 0 && data[0] == 0x0A) {
            root = NbtIo.readCompound(new java.io.DataInputStream(new java.io.ByteArrayInputStream(data)), NbtSizeTracker.of(MAX_BYTES * 8));
        } else if (data.length > 1 && data[0] == 'P' && data[1] == 'K') {
            throw new IllegalArgumentException("this is a .zip; unzip it and upload the .schem/.nbt/.litematic inside");
        } else {
            throw new IllegalArgumentException("not a Minecraft build file");
        }
        if (root.contains("Regions")) {
            return litematic(data);
        }
        if (root.contains("Schematic")) {
            return sponge(root.getCompoundOrEmpty("Schematic"), 3);
        }
        if (root.contains("Palette") && root.contains("BlockData")) {
            return sponge(root, 2);
        }
        if (root.contains("palette") || root.contains("palettes")) {
            return structure(root);
        }
        throw new IllegalArgumentException("unknown format");
    }

    /** "minecraft:oak_stairs[facing=north,half=top]" -> its block state (air if the block doesn't exist). */
    private static BlockState parse(String s) {
        NbtCompound n = new NbtCompound();
        int b = s.indexOf('[');
        n.putString("Name", b < 0 ? s : s.substring(0, b));
        if (b >= 0 && s.endsWith("]")) {
            NbtCompound props = new NbtCompound();
            for (String kv : s.substring(b + 1, s.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            n.put("Properties", props);
        }
        return NbtHelper.toBlockState(Mc.blockLookup(), n);
    }

    private static Build sponge(NbtCompound s, int version) {
        int w = s.getShort("Width", (short) 0) & 0xFFFF;
        int h = s.getShort("Height", (short) 0) & 0xFFFF;
        int l = s.getShort("Length", (short) 0) & 0xFFFF;
        if ((long) w * h * l > MAX_BLOCKS) {
            throw new IllegalArgumentException("too big");
        }
        NbtCompound blocks = version == 3 ? s.getCompoundOrEmpty("Blocks") : s;
        NbtCompound pal = blocks.getCompoundOrEmpty("Palette");
        Map<Integer, BlockState> palette = new HashMap<>();
        for (String key : pal.getKeys()) {
            palette.put(pal.getInt(key, 0), parse(key));
        }
        byte[] data = (version == 3 ? blocks.getByteArray("Data") : s.getByteArray("BlockData")).orElse(new byte[0]);
        Map<Long, NbtCompound> bes = new HashMap<>();
        NbtList beList = blocks.getListOrEmpty("BlockEntities");
        for (int i = 0; i < beList.size(); i++) {
            NbtCompound e = beList.getCompoundOrEmpty(i);
            int[] pos = e.getIntArray("Pos").orElse(null);
            if (pos == null || pos.length < 3) {
                continue;
            }
            NbtCompound d = version == 3 ? e.getCompoundOrEmpty("Data") : e.copy();
            bes.put(BlockPos.asLong(pos[0], pos[1], pos[2]), d);
        }
        List<Part> parts = new ArrayList<>();
        int i = 0;
        int index = 0;
        while (i < data.length) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            int y = index / (w * l);
            int rem = index % (w * l);
            int z = rem / w;
            int x = rem % w;
            BlockState st = palette.getOrDefault(value, Blocks.AIR.getDefaultState());
            parts.add(new Part(x, y, z, st, bes.get(BlockPos.asLong(x, y, z))));
            index++;
        }
        return new Build(w, h, l, parts);
    }

    /** Litematica files go through the builder's reader (blocks only, no chest contents). */
    private static Build litematic(byte[] data) throws Exception {
        BuildFiles.Clip c = BuildFiles.read(data);
        List<Part> parts = new ArrayList<>();
        for (int y = 0; y < c.sy; y++) {
            for (int z = 0; z < c.sz; z++) {
                for (int x = 0; x < c.sx; x++) {
                    parts.add(new Part(x, y, z, c.get(x, y, z), null));
                }
            }
        }
        return new Build(c.sx, c.sy, c.sz, parts);
    }

    private static Build structure(NbtCompound root) {
        NbtList pal = root.contains("palette") ? root.getListOrEmpty("palette") : root.getListOrEmpty("palettes").getListOrEmpty(0);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) {
            palette.add(NbtHelper.toBlockState(Mc.blockLookup(), pal.getCompoundOrEmpty(i)));
        }
        NbtList size = root.getListOrEmpty("size");
        int w = size.getInt(0, 0);
        int h = size.getInt(1, 0);
        int l = size.getInt(2, 0);
        NbtList blocks = root.getListOrEmpty("blocks");
        if (blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("too big");
        }
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound b = blocks.getCompoundOrEmpty(i);
            NbtList pos = b.getListOrEmpty("pos");
            int state = b.getInt("state", 0);
            if (state < 0 || state >= palette.size()) {
                continue;
            }
            parts.add(new Part(pos.getInt(0, 0), pos.getInt(1, 0), pos.getInt(2, 0), palette.get(state),
                    b.contains("nbt") ? b.getCompoundOrEmpty("nbt") : null));
        }
        return new Build(w, h, l, parts);
    }

    // ---------------------------------------------------------------- pasting

    private static BlockRotation rotation(Direction facing) {
        return switch (facing) {
            case WEST -> BlockRotation.CLOCKWISE_90;
            case NORTH -> BlockRotation.CLOCKWISE_180;
            case EAST -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    /** Builds the file in front of the owner (its front edge 2 blocks ahead, centred), facing the way they look. */
    public static void paste(ServerPlayerEntity p, String file, boolean withAir) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Path f = dir().resolve(file).toAbsolutePath().normalize();
        if (!f.startsWith(dir()) || !Files.exists(f)) {
            Msg.send(p, "schem.not-found", file);
            return;
        }
        Build b;
        try {
            b = read(f);
        } catch (Exception e) {
            Ac.LOG.warn("Could not read schematic {}", f, e);
            Msg.send(p, "schem.bad", file, String.valueOf(e.getMessage()));
            return;
        }
        ServerWorld w = p.getEntityWorld();
        Direction fwd = p.getHorizontalFacing();
        Direction right = fwd.rotateYCounterclockwise();
        BlockRotation rot = rotation(fwd);
        BlockPos origin = p.getBlockPos().offset(fwd, 2).offset(right, -b.w() / 2);
        List<Part> todo = new ArrayList<>();
        for (Part part : b.parts()) {
            if (withAir || !part.state().isAir()) {
                todo.add(part);
            }
        }
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        Msg.send(p, "schem.building", file, todo.size());
        step(p, w, origin, fwd, right, rot, todo, 0, before);
    }

    private static void step(ServerPlayerEntity p, ServerWorld w, BlockPos origin, Direction fwd, Direction right, BlockRotation rot,
                             List<Part> todo, int from, Map<BlockPos, BlockState> before) {
        int to = Math.min(todo.size(), from + PER_TICK);
        for (int i = from; i < to; i++) {
            Part part = todo.get(i);
            BlockPos pos = origin.offset(right, part.x()).up(part.y()).offset(fwd, part.z());
            if (w.isOutOfHeightLimit(pos)) {
                continue;
            }
            before.putIfAbsent(pos, w.getBlockState(pos));
            if (w.getBlockEntity(pos) != null) {
                w.removeBlockEntity(pos);
            }
            w.setBlockState(pos, part.state().rotate(rot), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
            if (part.be() != null) {
                var be = w.getBlockEntity(pos);
                if (be != null) {
                    try {
                        Mc.loadBlockEntity(be, part.be(), w.getRegistryManager());
                    } catch (Exception ignored) {
                        // a chest whose contents don't load is still placed
                    }
                }
            }
        }
        if (to < todo.size()) {
            Msg.actionBar(p, "§6" + Msg.trFor(p, "schem.progress", to * 100 / todo.size()));
            OwnerPowers.later(1, () -> step(p, w, origin, fwd, right, rot, todo, to, before));
            return;
        }
        OwnerWorld.pushUndo(p, before);
        Msg.send(p, "schem.done", todo.size());
    }

    // ---------------------------------------------------------------- downloading

    /** Saves a file from a link into the schematics folder (owner only), off the server thread. */
    public static void download(ServerPlayerEntity p, String url) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            Msg.send(p, "schem.bad-link");
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            Msg.send(p, "schem.bad-link");
            return;
        }
        String last = uri.getPath() == null ? "" : uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
        String name = last.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!supported(name)) {
            name = (name.isEmpty() ? "download" : name) + ".schem";
        }
        String file = name;
        var server = Ac.server();
        Msg.send(p, "schem.downloading", file);
        Thread t = new Thread(() -> {
            String error = null;
            try {
                Files.createDirectories(dir());
                HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
                HttpResponse<InputStream> r = c.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() / 100 != 2) {
                    error = "HTTP " + r.statusCode();
                } else {
                    Path tmp = dir().resolve(file + ".part");
                    try (InputStream in = r.body()) {
                        byte[] all = in.readNBytes((int) MAX_BYTES + 1);
                        if (all.length > MAX_BYTES) {
                            error = "file over 50 MB";
                        } else if (webPage(all)) {
                            error = "that link gives a web page, not the file. Use the direct download link (right-click the download button, copy link)";
                        } else {
                            Files.write(tmp, all);
                            Files.move(tmp, dir().resolve(file), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            String err = error;
            server.execute(() -> {
                ServerPlayerEntity now = server.getPlayerManager().getPlayer(p.getUuid());
                if (now != null) {
                    if (err == null) {
                        Msg.send(now, "schem.downloaded", file);
                    } else {
                        Msg.send(now, "schem.download-failed", err);
                    }
                }
            });
        }, "vigil-schematic-download");
        t.setDaemon(true);
        t.start();
    }

    /** For the gametest: builds a parsed file at a spot facing south. */
    public static int pasteForTest(ServerPlayerEntity p, Path f) throws Exception {
        Build b = read(f);
        ServerWorld w = p.getEntityWorld();
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        List<Part> todo = new ArrayList<>(b.parts());
        step(p, w, p.getBlockPos(), Direction.SOUTH, Direction.EAST, BlockRotation.NONE, todo, 0, before);
        return todo.size();
    }
}
