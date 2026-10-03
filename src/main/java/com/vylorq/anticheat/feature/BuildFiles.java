package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Build files: WorldEdit / Sponge schematics ({@code .schem}), Litematica ({@code .litematic}) and vanilla structure
 * files ({@code .nbt}, from structure blocks). They live in {@code config/vigil/builds}: put files there yourself, or
 * import them from a link with {@code /build import}. Saving writes {@code .schem}.
 */
public final class BuildFiles {
    private BuildFiles() {
    }

    /** Biggest build that can be loaded (blocks). */
    public static final int MAX_VOLUME = 2_000_000;
    /** Biggest file that can be downloaded. */
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final List<String> EXTENSIONS = List.of(".schem", ".schematic", ".litematic", ".nbt");

    /** Blocks in a box, plus where the box sits relative to the player who pastes it. */
    public static final class Clip {
        public final int sx;
        public final int sy;
        public final int sz;
        public final BlockState[] states;
        public int ox;
        public int oy;
        public int oz;

        public Clip(int sx, int sy, int sz) {
            if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > MAX_VOLUME) {
                throw new IllegalArgumentException("size " + sx + "x" + sy + "x" + sz);
            }
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.states = new BlockState[sx * sy * sz];
            java.util.Arrays.fill(states, Blocks.AIR.getDefaultState());
        }

        public int index(int x, int y, int z) {
            return (y * sz + z) * sx + x;
        }

        public BlockState get(int x, int y, int z) {
            return states[index(x, y, z)];
        }

        public void set(int x, int y, int z, BlockState s) {
            states[index(x, y, z)] = s;
        }

        public int volume() {
            return states.length;
        }
    }

    public static Path dir() {
        return Ac.get().dir.resolve("builds");
    }

    /** Builds that come with the mod (in vigil/builds/ of the jar). */
    public static final List<String> BUNDLED = List.of("lobby_enraze.schem");

    /** Puts the builds that come with the mod into the builds folder (never over a file that is already there). */
    public static void installBundled() {
        for (String name : BUNDLED) {
            Path f = dir().resolve(name);
            if (Files.exists(f)) {
                continue;
            }
            try (var in = BuildFiles.class.getResourceAsStream("/vigil/builds/" + name)) {
                if (in != null) {
                    Files.createDirectories(f.getParent());
                    Files.copy(in, f);
                }
            } catch (IOException e) {
                Ac.LOG.warn("Could not add the build {}", name, e);
            }
        }
    }

    /** Safe file name: letters, digits, - and _. */
    public static String cleanName(String name) {
        String n = name.toLowerCase(Locale.ROOT).replaceAll("\\.(schem|schematic|litematic|nbt)$", "")
                .replaceAll("[^a-z0-9_-]", "_");
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    /** Saved builds, by name (without extension). */
    public static Map<String, Path> list() {
        Map<String, Path> out = new LinkedHashMap<>();
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                s.filter(p -> EXTENSIONS.stream().anyMatch(e -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(e)))
                        .sorted()
                        .forEach(p -> out.put(cleanName(p.getFileName().toString()), p));
            }
        } catch (IOException e) {
            Ac.LOG.warn("Could not list builds", e);
        }
        return out;
    }

    // ---------------------------------------------------------------- reading

    public static Clip read(Path file) throws IOException {
        return read(Files.readAllBytes(file));
    }

    public static Clip read(byte[] data) throws IOException {
        NbtCompound root;
        try {
            root = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtSizeTracker.of(256L * 1024 * 1024));
        } catch (IOException e) {
            root = NbtIo.readCompound(new java.io.DataInputStream(new ByteArrayInputStream(data)), NbtSizeTracker.of(256L * 1024 * 1024));
        }
        if (root.contains("Regions")) {
            return litematic(root);
        }
        if (root.contains("Schematic")) {
            return sponge(root.getCompoundOrEmpty("Schematic"));
        }
        if (root.contains("Palette") && root.contains("BlockData")) {
            return sponge(root);
        }
        if (root.contains("blocks") && root.contains("size")) {
            return structure(root);
        }
        throw new IOException("not a build file");
    }

    private static int num(NbtElement e) {
        return e == null ? 0 : NbtOps.INSTANCE.getNumberValue(e).result().map(Number::intValue).orElse(0);
    }

    /** "minecraft:oak_stairs[facing=north,half=top]" to a block state (unknown blocks become air). */
    public static BlockState parseState(String s) {
        NbtCompound c = new NbtCompound();
        int br = s.indexOf('[');
        String name = br < 0 ? s : s.substring(0, br);
        c.putString("Name", name.contains(":") ? name : "minecraft:" + name);
        if (br >= 0 && s.endsWith("]")) {
            NbtCompound props = new NbtCompound();
            for (String kv : s.substring(br + 1, s.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    props.putString(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            c.put("Properties", props);
        }
        return NbtHelper.toBlockState(Mc.blockLookup(), c);
    }

    public static String stateString(BlockState s) {
        NbtCompound c = NbtHelper.fromBlockState(s);
        StringBuilder sb = new StringBuilder(c.getString("Name").orElse("minecraft:air"));
        NbtCompound props = c.getCompoundOrEmpty("Properties");
        List<String> parts = new ArrayList<>();
        NbtOps.INSTANCE.getMapValues(props).result().ifPresent(st -> st.forEach(pair ->
                parts.add(NbtOps.INSTANCE.getStringValue(pair.getFirst()).result().orElse("") + "="
                        + NbtOps.INSTANCE.getStringValue(pair.getSecond()).result().orElse(""))));
        if (!parts.isEmpty()) {
            parts.sort(null);
            sb.append('[').append(String.join(",", parts)).append(']');
        }
        return sb.toString();
    }

    /** Sponge schematic v2 (root) or v3 (inside "Schematic"). */
    private static Clip sponge(NbtCompound s) throws IOException {
        int w = s.getInt("Width", 0) & 0xFFFF;
        int h = s.getInt("Height", 0) & 0xFFFF;
        int l = s.getInt("Length", 0) & 0xFFFF;
        NbtCompound blocks = s.contains("Blocks") ? s.getCompoundOrEmpty("Blocks") : s;
        NbtCompound pal = blocks.getCompoundOrEmpty("Palette");
        byte[] data = (s.contains("Blocks") ? blocks.getByteArray("Data") : s.getByteArray("BlockData")).orElse(new byte[0]);
        Map<Integer, BlockState> palette = new HashMap<>();
        NbtOps.INSTANCE.getMapValues(pal).result().ifPresent(st -> st.forEach(pair ->
                palette.put(num(pair.getSecond()), parseState(NbtOps.INSTANCE.getStringValue(pair.getFirst()).result().orElse("minecraft:air")))));
        Clip c = new Clip(w, h, l);
        int i = 0;
        int index = 0;
        while (i < data.length && index < c.volume()) {
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            // Sponge order is (y * length + z) * width + x, the same as Clip's.
            c.states[index++] = palette.getOrDefault(value, Blocks.AIR.getDefaultState());
        }
        // Where the box sits from the spot it was saved at (WorldEdit's "Offset").
        int[] off = s.getIntArray("Offset").orElse(new int[0]);
        if (off.length == 3) {
            c.ox = off[0];
            c.oy = off[1];
            c.oz = off[2];
        }
        return c;
    }

    /** Vanilla structure file (structure blocks / jigsaw). */
    private static Clip structure(NbtCompound root) {
        NbtList size = root.getListOrEmpty("size");
        Clip c = new Clip(num(size.get(0)), num(size.get(1)), num(size.get(2)));
        NbtList pal = root.getListOrEmpty("palette");
        if (pal.isEmpty() && !root.getListOrEmpty("palettes").isEmpty() && root.getListOrEmpty("palettes").get(0) instanceof NbtList first) {
            pal = first;
        }
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) {
            palette.add(NbtHelper.toBlockState(Mc.blockLookup(), pal.getCompoundOrEmpty(i)));
        }
        NbtList blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound b = blocks.getCompoundOrEmpty(i);
            NbtList pos = b.getListOrEmpty("pos");
            int x = num(pos.get(0));
            int y = num(pos.get(1));
            int z = num(pos.get(2));
            int st = b.getInt("state", 0);
            if (x >= 0 && y >= 0 && z >= 0 && x < c.sx && y < c.sy && z < c.sz && st >= 0 && st < palette.size()) {
                c.set(x, y, z, palette.get(st));
            }
        }
        return c;
    }

    /** Litematica: every region goes into one box. */
    private static Clip litematic(NbtCompound root) throws IOException {
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        record Region(int x, int y, int z, int sx, int sy, int sz, List<BlockState> palette, long[] bits) {
        }
        List<Region> list = new ArrayList<>();
        NbtOps.INSTANCE.getMapValues(regions).result().ifPresent(st -> st.forEach(pair -> {
            if (!(pair.getSecond() instanceof NbtCompound r)) {
                return;
            }
            NbtCompound pos = r.getCompoundOrEmpty("Position");
            NbtCompound size = r.getCompoundOrEmpty("Size");
            int sx = size.getInt("x", 0);
            int sy = size.getInt("y", 0);
            int sz = size.getInt("z", 0);
            // A negative size means the region grows the other way from its position.
            int x = pos.getInt("x", 0) + (sx < 0 ? sx + 1 : 0);
            int y = pos.getInt("y", 0) + (sy < 0 ? sy + 1 : 0);
            int z = pos.getInt("z", 0) + (sz < 0 ? sz + 1 : 0);
            NbtList pal = r.getListOrEmpty("BlockStatePalette");
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < pal.size(); i++) {
                palette.add(NbtHelper.toBlockState(Mc.blockLookup(), pal.getCompoundOrEmpty(i)));
            }
            list.add(new Region(x, y, z, Math.abs(sx), Math.abs(sy), Math.abs(sz), palette, r.getLongArray("BlockStates").orElse(new long[0])));
        }));
        if (list.isEmpty()) {
            throw new IOException("no regions");
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Region r : list) {
            minX = Math.min(minX, r.x);
            minY = Math.min(minY, r.y);
            minZ = Math.min(minZ, r.z);
            maxX = Math.max(maxX, r.x + r.sx - 1);
            maxY = Math.max(maxY, r.y + r.sy - 1);
            maxZ = Math.max(maxZ, r.z + r.sz - 1);
        }
        Clip c = new Clip(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
        for (Region r : list) {
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, r.palette.size() - 1)));
            long mask = (1L << bits) - 1;
            for (int y = 0; y < r.sy; y++) {
                for (int z = 0; z < r.sz; z++) {
                    for (int x = 0; x < r.sx; x++) {
                        long i = ((long) y * r.sz + z) * r.sx + x;
                        long start = i * bits;
                        int startLong = (int) (start >> 6);
                        int endLong = (int) ((start + bits - 1) >> 6);
                        int off = (int) (start & 63);
                        if (endLong >= r.bits.length) {
                            continue;
                        }
                        long v = startLong == endLong ? (r.bits[startLong] >>> off) & mask
                                : ((r.bits[startLong] >>> off) | (r.bits[endLong] << (64 - off))) & mask;
                        if (v > 0 && v < r.palette.size()) {
                            c.set(r.x - minX + x, r.y - minY + y, r.z - minZ + z, r.palette.get((int) v));
                        }
                    }
                }
            }
        }
        return c;
    }

    // ---------------------------------------------------------------- writing

    /** Saves as a Sponge v2 schematic (opens in WorldEdit, Litematica, Amulet...). */
    public static void write(Clip c, Path file) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 2);
        root.putInt("DataVersion", SharedConstants.WORLD_VERSION);
        root.putShort("Width", (short) c.sx);
        root.putShort("Height", (short) c.sy);
        root.putShort("Length", (short) c.sz);
        root.putIntArray("Offset", new int[] {c.ox, c.oy, c.oz});
        Map<String, Integer> ids = new LinkedHashMap<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (BlockState s : c.states) {
            int id = ids.computeIfAbsent(stateString(s), k -> ids.size());
            while ((id & ~0x7F) != 0) {
                data.write((id & 0x7F) | 0x80);
                id >>>= 7;
            }
            data.write(id);
        }
        NbtCompound pal = new NbtCompound();
        ids.forEach(pal::putInt);
        root.put("Palette", pal);
        root.putInt("PaletteMax", ids.size());
        root.putByteArray("BlockData", data.toByteArray());
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(root, file);
    }

    // ---------------------------------------------------------------- downloading

    /** Turns share links into direct downloads (Google Drive, Dropbox). */
    static String direct(String url) {
        var drive = java.util.regex.Pattern.compile("https://drive\\.google\\.com/file/d/([A-Za-z0-9_-]+)").matcher(url);
        if (drive.find()) {
            return "https://drive.google.com/uc?export=download&id=" + drive.group(1);
        }
        if (url.contains("dropbox.com/")) {
            return url.replace("dl=0", "dl=1");
        }
        return url;
    }

    /** Downloads a build file (on a background thread). Only public web addresses, at most {@link #MAX_BYTES}. */
    public static byte[] download(String url) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        URI uri = URI.create(direct(url.trim()));
        for (int hop = 0; hop < 5; hop++) {
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("only http(s) links");
            }
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()
                        || a.isMulticastAddress() || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC)) {
                    throw new IOException("that address isn't allowed");
                }
            }
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent", "Vigil").GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                res.body().close();
                String loc = res.headers().firstValue("location").orElseThrow(() -> new IOException("bad redirect"));
                uri = uri.resolve(loc);
                continue;
            }
            if (code != 200) {
                res.body().close();
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = res.body()) {
                byte[] all = in.readNBytes(MAX_BYTES + 1);
                if (all.length > MAX_BYTES) {
                    throw new IOException("file too big");
                }
                return all;
            }
        }
        throw new IOException("too many redirects");
    }

    /** The extension to save a downloaded file under. */
    public static String extensionFor(String url, byte[] data) throws IOException {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String e : EXTENSIONS) {
            if (lower.contains(e)) {
                return e.equals(".schematic") ? ".schem" : e;
            }
        }
        read(data);
        return ".schem";
    }
}
