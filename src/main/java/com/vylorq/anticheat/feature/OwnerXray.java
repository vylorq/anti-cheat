package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The owner's X-ray: every ore, spawner and chest near them glows through the walls, in its own colour. The glowing
 * copies are display entities only owners are ever sent (EntityTrackerMixin), so nobody else sees a thing.
 */
public final class OwnerXray {
    private OwnerXray() {
    }

    /** Entities with this tag are only ever shown to owners. */
    public static final String OWNER_ONLY = "vigil_owner_only";
    private static final int RADIUS = 20;
    private static final int MAX = 400;

    private static final Set<UUID> ON = new HashSet<>();
    /** Each owner's glowing copies, by the block they show. */
    private static final Map<UUID, Map<BlockPos, DisplayEntity.BlockDisplayEntity>> SHOWN = new HashMap<>();

    public static boolean on(ServerPlayerEntity p) {
        return ON.contains(p.getUuid());
    }

    public static void toggle(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (ON.remove(p.getUuid())) {
            clear(p.getUuid());
            Msg.send(p, "xray.off");
        } else {
            ON.add(p.getUuid());
            Msg.send(p, "xray.on");
            scan(p);
        }
    }

    /** The glow colour for a block, or -1 when it isn't shown. */
    static int colour(BlockState st) {
        if (st.isOf(Blocks.DIAMOND_ORE) || st.isOf(Blocks.DEEPSLATE_DIAMOND_ORE)) {
            return 0x4AEDD9;
        }
        if (st.isOf(Blocks.ANCIENT_DEBRIS)) {
            return 0x8B4A3C;
        }
        if (st.isOf(Blocks.EMERALD_ORE) || st.isOf(Blocks.DEEPSLATE_EMERALD_ORE)) {
            return 0x17DD62;
        }
        if (st.isOf(Blocks.GOLD_ORE) || st.isOf(Blocks.DEEPSLATE_GOLD_ORE) || st.isOf(Blocks.NETHER_GOLD_ORE)) {
            return 0xFCEE4B;
        }
        if (st.isOf(Blocks.IRON_ORE) || st.isOf(Blocks.DEEPSLATE_IRON_ORE)) {
            return 0xD8AF93;
        }
        if (st.isOf(Blocks.REDSTONE_ORE) || st.isOf(Blocks.DEEPSLATE_REDSTONE_ORE)) {
            return 0xFF2020;
        }
        if (st.isOf(Blocks.LAPIS_ORE) || st.isOf(Blocks.DEEPSLATE_LAPIS_ORE)) {
            return 0x345EC3;
        }
        if (st.isOf(Blocks.SPAWNER) || st.isOf(Blocks.TRIAL_SPAWNER) || st.isOf(Blocks.VAULT)) {
            return 0xFF55FF;
        }
        if (st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL)) {
            return 0xFFAA00;
        }
        if (st.isIn(net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags.ORES)) {
            return 0xFFFFFF;   // copper, coal, quartz and the mod's gem ores
        }
        return -1;
    }

    private static void scan(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Map<BlockPos, DisplayEntity.BlockDisplayEntity> shown = SHOWN.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
        // Gone (mined, out of range, another world): take them away.
        shown.entrySet().removeIf(e -> {
            DisplayEntity.BlockDisplayEntity d = e.getValue();
            boolean keep = !d.isRemoved() && d.getEntityWorld() == w && e.getKey().getSquaredDistance(p.getBlockPos()) <= (RADIUS + 4) * (RADIUS + 4)
                    && colour(w.getBlockState(e.getKey())) >= 0;
            if (!keep) {
                d.discard();
            }
            return !keep;
        });
        BlockPos c = p.getBlockPos();
        for (BlockPos q : BlockPos.iterate(c.add(-RADIUS, -RADIUS, -RADIUS), c.add(RADIUS, RADIUS, RADIUS))) {
            if (shown.size() >= MAX) {
                break;
            }
            if (shown.containsKey(q) || w.isOutOfHeightLimit(q)) {
                continue;
            }
            BlockState st = w.getBlockState(q);
            int col = colour(st);
            if (col < 0) {
                continue;
            }
            DisplayEntity.BlockDisplayEntity d = EntityType.BLOCK_DISPLAY.create(w, SpawnReason.COMMAND);
            if (d == null) {
                continue;
            }
            BlockPos at = q.toImmutable();
            d.refreshPositionAndAngles(at.getX(), at.getY(), at.getZ(), 0, 0);
            d.setBlockState(st);
            d.setGlowing(true);
            d.setGlowColorOverride(col);
            d.addCommandTag(OWNER_ONLY);
            d.setNoGravity(true);
            shown.put(at, d);   // before it spawns: the load event must know it's one of ours
            w.spawnEntity(d);
        }
    }

    private static void clear(UUID id) {
        Map<BlockPos, DisplayEntity.BlockDisplayEntity> shown = SHOWN.remove(id);
        if (shown != null) {
            shown.values().forEach(net.minecraft.entity.Entity::discard);
        }
    }

    public static void tick(long ticks) {
        if (ticks % 40 != 0 || ON.isEmpty()) {
            return;
        }
        for (UUID id : new HashSet<>(ON)) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
            if (p == null) {
                clear(id);
                continue;
            }
            scan(p);
        }
    }

    /** Leftover copies (a restart while it was on) go when their chunk loads. */
    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e.getCommandTags().contains(OWNER_ONLY) && SHOWN.values().stream().noneMatch(m -> m.containsValue(e))) {
                OwnerPowers.later(1, e::discard);
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            clear(h.player.getUuid());
            ON.remove(h.player.getUuid());
        });
    }
}
