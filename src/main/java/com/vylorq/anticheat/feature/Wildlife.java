package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.BiomeKeys;

/**
 * Wild animals: deer graze in forests and plains, owls come out at night in the woods, and fireflies glow over
 * forests, swamps and meadows on warm nights. Deer and owls are vanilla mobs underneath (a sheared sheep, a parrot)
 * wearing their own 3D models on Java; Bedrock players see the plain mob. A few at a time, near players only, and
 * they go away again when nobody is around.
 */
public final class Wildlife {
    private Wildlife() {
    }

    static final String TAG = "vigil_wild";
    private static final int DEER_NEAR = 4;
    private static final int OWLS_NEAR = 2;

    private static boolean woods(ServerWorld w, BlockPos pos) {
        var b = w.getBiome(pos);
        return b.isIn(BiomeTags.IS_FOREST) || b.isIn(BiomeTags.IS_TAIGA) || b.matchesKey(BiomeKeys.DARK_FOREST);
    }

    private static boolean grassland(ServerWorld w, BlockPos pos) {
        var b = w.getBiome(pos);
        return woods(w, pos) || b.matchesKey(BiomeKeys.PLAINS) || b.matchesKey(BiomeKeys.SUNFLOWER_PLAINS) || b.matchesKey(BiomeKeys.MEADOW)
                || b.matchesKey(BiomeKeys.CHERRY_GROVE);
    }

    private static boolean night(ServerWorld w) {
        long t = w.getTimeOfDay() % 24000L;
        return t >= 13000 && t <= 23000;
    }

    private static int count(ServerWorld w, ServerPlayerEntity p, String kind) {
        return w.getEntitiesByClass(MobEntity.class, new Box(p.getBlockPos()).expand(48), m -> m.getCommandTags().contains(TAG + ":" + kind)).size();
    }

    private static BlockPos spot(ServerWorld w, ServerPlayerEntity p) {
        var r = w.getRandom();
        double a = r.nextDouble() * Math.PI * 2;
        double d = 20 + r.nextInt(16);
        return w.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.ofFloored(p.getX() + Math.cos(a) * d, p.getY(), p.getZ() + Math.sin(a) * d));
    }

    private static void spawn(ServerWorld w, BlockPos at, EntityType<? extends MobEntity> type, String kind) {
        MobEntity m = type.create(w, SpawnReason.NATURAL);
        if (m == null) {
            return;
        }
        m.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, w.getRandom().nextFloat() * 360, 0);
        if (m instanceof SheepEntity sheep) {
            sheep.setSheared(true);
        }
        m.addCommandTag(TAG);
        m.addCommandTag(TAG + ":" + kind);
        if (!w.isSpaceEmpty(m)) {
            return;
        }
        ModelMobs.attach(m, kind);
        w.spawnEntity(m);
    }

    public static void tick(long ticks) {
        ServerWorld w = Ac.server().getOverworld();
        if (ticks % 4 == 0 && night(w) && !w.isRaining()) {
            fireflies(w);
        }
        if (ticks % 200 != 0) {
            return;
        }
        var r = w.getRandom();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator()) {
                continue;
            }
            BlockPos at = spot(w, p);
            if (grassland(w, at) && w.getBlockState(at.down()).isOf(net.minecraft.block.Blocks.GRASS_BLOCK)
                    && count(w, p, "deer") < DEER_NEAR && r.nextInt(3) == 0) {
                spawn(w, at, EntityType.SHEEP, "deer");
            }
            if (night(w) && woods(w, at) && count(w, p, "owl") < OWLS_NEAR && r.nextInt(4) == 0) {
                spawn(w, at.up(3), EntityType.PARROT, "owl");
            }
        }
        // They wander off once nobody is near.
        for (var e : w.iterateEntities()) {
            if (e instanceof MobEntity m && m.getCommandTags().contains(TAG) && w.getClosestPlayer(m, 128) == null && !m.hasCustomName()) {
                m.discard();
            }
        }
    }

    /** Little glowing lights drifting over the grass near players on warm nights. */
    private static void fireflies(ServerWorld w) {
        var r = w.getRandom();
        for (ServerPlayerEntity p : w.getPlayers()) {
            BlockPos base = p.getBlockPos();
            var biome = w.getBiome(base);
            boolean warm = biome.isIn(BiomeTags.IS_FOREST) || biome.matchesKey(BiomeKeys.SWAMP) || biome.matchesKey(BiomeKeys.MANGROVE_SWAMP)
                    || biome.matchesKey(BiomeKeys.PLAINS) || biome.matchesKey(BiomeKeys.MEADOW) || biome.matchesKey(BiomeKeys.SUNFLOWER_PLAINS)
                    || biome.matchesKey(BiomeKeys.CHERRY_GROVE) || biome.matchesKey(BiomeKeys.DARK_FOREST);
            if (!warm || Seasons.now() == Seasons.Season.WINTER) {
                continue;
            }
            for (int i = 0; i < 3; i++) {
                double x = p.getX() + (r.nextDouble() - 0.5) * 24;
                double z = p.getZ() + (r.nextDouble() - 0.5) * 24;
                int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
                if (Math.abs(top - p.getY()) > 12) {
                    continue;
                }
                w.spawnParticles(p, ParticleTypes.FIREFLY, false, false, x, top + 0.5 + r.nextDouble() * 2, z, 1, 0.2, 0.2, 0.2, 0);
            }
        }
    }
}
