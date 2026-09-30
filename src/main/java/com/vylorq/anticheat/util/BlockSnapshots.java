package com.vylorq.anticheat.util;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.util.Area;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Saves and restores the blocks of an area (arena resets, claim snapshots). */
public final class BlockSnapshots {
    private BlockSnapshots() {
    }

    public static Path dir() {
        return Ac.get().dir.resolve("snapshots");
    }

    public static Path file(String name) {
        return dir().resolve(name.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".nbt");
    }

    /** @return number of blocks saved */
    public static int save(ServerWorld w, Area a, String name) throws Exception {
        if (a.volume() > 4_000_000) {
            throw new IllegalArgumentException("area too large to snapshot (" + a.volume() + " blocks)");
        }
        Map<BlockState, Integer> palette = new HashMap<>();
        List<BlockState> order = new ArrayList<>();
        int[] data = new int[(int) a.volume()];
        NbtList bes = new NbtList();
        int i = 0;
        for (int y = a.minY; y <= a.maxY; y++) {
            for (int z = a.minZ; z <= a.maxZ; z++) {
                for (int x = a.minX; x <= a.maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState st = w.getBlockState(pos);
                    Integer idx = palette.get(st);
                    if (idx == null) {
                        idx = order.size();
                        palette.put(st, idx);
                        order.add(st);
                    }
                    data[i++] = idx;
                    BlockEntity be = w.getBlockEntity(pos);
                    if (be != null) {
                        NbtCompound n = be.createNbtWithIdentifyingData(w.getRegistryManager());
                        n.putInt("ac_x", x);
                        n.putInt("ac_y", y);
                        n.putInt("ac_z", z);
                        bes.add(n);
                    }
                }
            }
        }
        NbtCompound root = new NbtCompound();
        NbtList pal = new NbtList();
        for (BlockState st : order) {
            pal.add(NbtHelper.fromBlockState(st));
        }
        root.put("palette", pal);
        root.putIntArray("data", data);
        root.put("blockEntities", bes);
        root.putString("world", Mc.worldId(w));
        root.putIntArray("area", new int[]{a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ});
        Files.createDirectories(dir());
        NbtIo.writeCompressed(root, file(name));
        return data.length;
    }

    public static boolean exists(String name) {
        return Files.exists(file(name));
    }

    /** Restores only blocks that differ. @return number of blocks changed. */
    public static int restore(ServerWorld w, String name) throws Exception {
        Path f = file(name);
        if (!Files.exists(f)) {
            return -1;
        }
        NbtCompound root = NbtIo.readCompressed(f, NbtSizeTracker.ofUnlimitedBytes());
        NbtList pal = root.getListOrEmpty("palette");
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < pal.size(); i++) {
            palette.add(NbtHelper.toBlockState(Mc.blockLookup(), pal.getCompoundOrEmpty(i)));
        }
        int[] area = root.getIntArray("area").orElse(new int[6]);
        int[] data = root.getIntArray("data").orElse(new int[0]);
        int changed = 0;
        int i = 0;
        for (int y = area[1]; y <= area[4]; y++) {
            for (int z = area[2]; z <= area[5]; z++) {
                for (int x = area[0]; x <= area[3]; x++) {
                    BlockState want = palette.get(data[i++]);
                    BlockPos pos = new BlockPos(x, y, z);
                    if (w.getBlockState(pos) != want) {
                        if (w.getBlockEntity(pos) != null) {
                            w.removeBlockEntity(pos);
                        }
                        w.setBlockState(pos, want, Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
                        changed++;
                    }
                }
            }
        }
        NbtList bes = root.getListOrEmpty("blockEntities");
        for (int j = 0; j < bes.size(); j++) {
            NbtCompound n = bes.getCompoundOrEmpty(j);
            BlockPos pos = new BlockPos(n.getInt("ac_x", 0), n.getInt("ac_y", 0), n.getInt("ac_z", 0));
            BlockEntity be = w.getBlockEntity(pos);
            if (be != null) {
                Mc.loadBlockEntity(be, n, w.getRegistryManager());
            }
        }
        // Items dropped during the match are cleared too.
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(area[0], area[1], area[2], area[3] + 1, area[4] + 1, area[5] + 1);
        for (var e : w.getEntitiesByType(net.minecraft.entity.EntityType.ITEM, box, e -> true)) {
            e.discard();
        }
        return changed;
    }

}
