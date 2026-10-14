package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.structure.Structure;

import java.util.HashSet;
import java.util.Set;

/**
 * The volcanoes (data/vigil/worldgen/structure/volcano) are alive: smoke rises from the crater while players are
 * near, and now and then it erupts in a fountain of sparks and a rumble. Nothing is set on fire or broken.
 */
public final class Volcanoes {
    private Volcanoes() {
    }

    public static void tick(long ticks) {
        if (ticks % 40 != 0) {
            return;
        }
        ServerWorld w = Ac.server().getOverworld();
        Structure volcano = w.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE).get(Identifier.of("vigil", "volcano"));
        if (volcano == null) {
            return;
        }
        Set<Long> done = new HashSet<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator()) {
                continue;
            }
            ChunkPos at = p.getChunkPos();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    ChunkPos c = new ChunkPos(at.x + dx, at.z + dz);
                    if (!w.isChunkLoaded(c.x, c.z)) {
                        continue;
                    }
                    for (var start : w.getStructureAccessor().getStructureStarts(c, s -> s == volcano)) {
                        if (!start.hasChildren() || !done.add(start.getPos().toLong())) {
                            continue;
                        }
                        var box = start.getBoundingBox();
                        smoke(w, new Vec3d(box.getCenter().getX() + 0.5, box.getMaxY() + 1, box.getCenter().getZ() + 0.5));
                    }
                }
            }
        }
    }

    private static void smoke(ServerWorld w, Vec3d top) {
        var r = w.getRandom();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(top) < 160 * 160) {
                w.spawnParticles(p, ParticleTypes.CAMPFIRE_COSY_SMOKE, true, false, top.x, top.y, top.z, 12, 2.5, 1, 2.5, 0.03);
                w.spawnParticles(p, ParticleTypes.LAVA, true, false, top.x, top.y - 1, top.z, 4, 2, 0.5, 2, 0);
            }
        }
        if (r.nextInt(20) != 0) {
            return;
        }
        // An eruption: a fountain of fire and sparks, and a rumble everyone near hears.
        for (int i = 0; i < 40; i += 2) {
            int k = i;
            OwnerPowers.later(k, () -> {
                for (ServerPlayerEntity p : w.getPlayers()) {
                    if (p.squaredDistanceTo(top) < 200 * 200) {
                        w.spawnParticles(p, ParticleTypes.LAVA, true, false, top.x, top.y + k * 0.4, top.z, 25, 2.5, 2, 2.5, 0);
                        w.spawnParticles(p, ParticleTypes.FLAME, true, false, top.x, top.y + k * 0.5, top.z, 30, 1.5, 3, 1.5, 0.08);
                        w.spawnParticles(p, ParticleTypes.LARGE_SMOKE, true, false, top.x, top.y + k * 0.6, top.z, 15, 3, 3, 3, 0.05);
                    }
                }
            });
        }
        w.playSound(null, top.x, top.y, top.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.AMBIENT, 8f, 0.4f);
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(top) < 96 * 96) {
                Msg.actionBar(p, "§6" + Msg.trFor(p, "volcano.erupts"));
            }
        }
    }
}
