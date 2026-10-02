package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.AcConfig;
import com.vylorq.anticheat.core.redstone.LagMachineDetector;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** OP redstone and lag machine protection (section 18) and the lag finder. */
public final class Redstone {
    private Redstone() {
    }

    public static LagMachineDetector.Limits limits() {
        AcConfig.Redstone r = Ac.config().redstone;
        return new LagMachineDetector.Limits(r.clockMaxTogglesPer10s, r.clockSustainSeconds, r.maxPistonMovesPerChunkPerSecond,
                r.maxPrimedTntPerChunk, r.maxDispenserFiresPerChunkPerSecond);
    }

    /** A clock component ticked. @return false to stop it (disabled lag machine). */
    public static boolean onToggle(ServerWorld w, BlockPos pos) {
        if (!Features.on(Features.Feature.REDSTONE)) {
            return true;
        }
        Ac ac = Ac.get();
        if (ac == null) {
            return true;
        }
        String world = Mc.worldId(w);
        if (ac.redstone.isDisabled(world, Mc.pos(pos))) {
            return false;
        }
        LagMachineDetector.Alert a = ac.redstone.onRedstoneToggle(world, Mc.pos(pos), System.currentTimeMillis(), limits());
        if (a != null) {
            alert(w, pos, a.kind() + ": " + a.detail());
            Ac.markDirty("redstone");
            return false;
        }
        return true;
    }

    public static boolean onPiston(ServerWorld w, BlockPos pos) {
        if (!Features.on(Features.Feature.REDSTONE)) {
            return true;
        }
        Ac ac = Ac.get();
        return ac == null || ac.redstone.onPistonMove(Mc.worldId(w), Mc.pos(pos), System.currentTimeMillis(), limits());
    }

    public static boolean onDispense(ServerWorld w, BlockPos pos) {
        if (!Features.on(Features.Feature.REDSTONE)) {
            return true;
        }
        Ac ac = Ac.get();
        return ac == null || ac.redstone.onDispense(Mc.worldId(w), Mc.pos(pos), System.currentTimeMillis(), limits());
    }

    public static boolean mayPrimeTnt(ServerWorld w, BlockPos pos) {
        if (!Features.on(Features.Feature.REDSTONE)) {
            return true;
        }
        Ac ac = Ac.get();
        if (ac == null) {
            return true;
        }
        ChunkPos cp = new ChunkPos(pos);
        Box box = new Box(cp.getStartX(), w.getBottomY(), cp.getStartZ(), cp.getEndX() + 1, w.getTopYInclusive() + 1, cp.getEndZ() + 1);
        int live = w.getEntitiesByClass(TntEntity.class, box, e -> true).size();
        return ac.redstone.mayPrimeTnt(Mc.worldId(w), Mc.pos(pos), live, limits());
    }

    private static final Map<String, Long> LAST_ALERT = new HashMap<>();

    public static void alert(ServerWorld w, BlockPos pos, String what) {
        String key = Mc.worldId(w) + pos.toShortString() + what;
        long now = System.currentTimeMillis();
        Long last = LAST_ALERT.get(key);
        if (last != null && now - last < 60_000) {
            return;
        }
        LAST_ALERT.put(key, now);
        ServerPlayerEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayerEntity p : w.getPlayers()) {
            double d = p.squaredDistanceTo(pos.getX(), pos.getY(), pos.getZ());
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        String near = nearest == null ? "nobody" : nearest.getGameProfile().name() + String.format(" (%.0f blocks)", Math.sqrt(best));
        String where = Mc.worldId(w) + " " + pos.toShortString();
        Staff.broadcast(Msg.prefixed(Msg.tr("lag.alert", what, where, near)).append(Text.literal(" "))
                .append(Msg.button("§b[TP]", "/ac tp " + Mc.worldId(w) + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), "Teleport")));
        Discord.send("lagMachine", "Lag machine stopped", what + " at " + where + "\nNearest: " + near, 0x8E44AD);
    }

    /** Every 10 seconds: entity counts per chunk; excess dropped items are removed. */
    public static void scanEntities() {
        if (!Features.on(Features.Feature.REDSTONE)) {
            return;
        }
        AcConfig.Redstone r = Ac.config().redstone;
        for (ServerWorld w : Ac.server().getWorlds()) {
            Map<Long, int[]> counts = new HashMap<>();
            Map<Long, List<ItemEntity>> items = new HashMap<>();
            for (Entity e : w.iterateEntities()) {
                long key = e.getChunkPos().toLong();
                int[] c = counts.computeIfAbsent(key, k -> new int[4]);
                c[0]++;
                if (e instanceof AbstractMinecartEntity) {
                    c[1]++;
                } else if (e instanceof ItemEntity ie) {
                    c[2]++;
                    items.computeIfAbsent(key, k -> new ArrayList<>()).add(ie);
                } else if (e instanceof ArmorStandEntity) {
                    c[3]++;
                }
            }
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                int[] c = e.getValue();
                String problem = LagMachineDetector.entityOverload(c[0], c[1], c[2], c[3], r.maxEntitiesPerChunk,
                        r.maxMinecartsPerChunk, r.maxItemsPerChunk, r.maxArmorStandsPerChunk);
                if (problem == null) {
                    continue;
                }
                ChunkPos cp = new ChunkPos(e.getKey());
                BlockPos pos = new BlockPos(cp.getCenterX(), 64, cp.getCenterZ());
                if (Ac.get().redstone.whitelisted(Mc.worldId(w), Mc.pos(pos))) {
                    continue;
                }
                List<ItemEntity> list = items.get(e.getKey());
                if (c[2] > r.maxItemsPerChunk && list != null) {
                    list.sort(Comparator.comparingInt(ItemEntity::getItemAge).reversed());
                    for (int i = 0; i < list.size() - r.maxItemsPerChunk; i++) {
                        list.get(i).discard();
                    }
                }
                alert(w, pos, "too many entities: " + problem);
            }
        }
    }

    /** /lag: the busiest chunks by entity and block-entity count. */
    public static List<String> lagReport(int top) {
        record Row(String world, ChunkPos pos, int entities, int blockEntities) {
        }
        List<Row> rows = new ArrayList<>();
        for (ServerWorld w : Ac.server().getWorlds()) {
            Map<Long, int[]> counts = new HashMap<>();
            for (Entity e : w.iterateEntities()) {
                counts.computeIfAbsent(e.getChunkPos().toLong(), k -> new int[2])[0]++;
            }
            for (var ticker : ((com.vylorq.anticheat.mixin.WorldAccessor) w).ac$blockEntityTickers()) {
                BlockPos p = ticker.getPos();
                if (p != null) {
                    counts.computeIfAbsent(ChunkPos.toLong(p.getX() >> 4, p.getZ() >> 4), k -> new int[2])[1]++;
                }
            }
            for (Map.Entry<Long, int[]> e : counts.entrySet()) {
                rows.add(new Row(Mc.worldId(w), new ChunkPos(e.getKey()), e.getValue()[0], e.getValue()[1]));
            }
        }
        rows.sort(Comparator.comparingInt((Row x) -> x.entities() * 2 + x.blockEntities()).reversed());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(top, rows.size()); i++) {
            Row x = rows.get(i);
            out.add(x.world() + " " + x.pos().getCenterX() + " " + x.pos().getCenterZ() + " " + x.entities() + " " + x.blockEntities());
        }
        return out;
    }
}
