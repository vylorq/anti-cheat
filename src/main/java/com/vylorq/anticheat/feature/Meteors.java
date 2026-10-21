package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Weather events. Meteor showers: some nights stars streak down and a few land near players as small meteorites of
 * magma and rare ore (never on claims, the lobby or builds, and they don't break anything). Thunderstorms charge the
 * bosses: harder hits and tougher hides while the storm lasts (Bosses asks {@link #stormCharged}).
 */
public final class Meteors {
    private Meteors() {
    }

    /** One night in this many has a shower. */
    static final int NIGHTS = 4;
    /** How long a shower lasts (ticks). */
    static final int LENGTH = 20 * 150;

    private static long showerUntil;
    private static long lastNight = -1;

    public static boolean showering() {
        return Ac.server() != null && Ac.server().getOverworld().getTime() < showerUntil;
    }

    /** Starts a shower now (the owner's command, or a night that rolled one). */
    public static void start() {
        ServerWorld w = Ac.server().getOverworld();
        showerUntil = w.getTime() + LENGTH;
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Mc.title(p, "§6§l" + Msg.trFor(p, "meteor.title"), "§7" + Msg.trFor(p, "meteor.sub"), 10, 60, 20);
        }
        Ac.LOG.info("A meteor shower began");
    }

    public static void stop() {
        showerUntil = 0;
    }

    /** Thunder makes the bosses stronger while it lasts. */
    public static boolean stormCharged(World w) {
        return w != null && w.isThundering();
    }

    public static void tick(long ticks) {
        if (ticks % 20 != 0) {
            return;
        }
        ServerWorld w = Ac.server().getOverworld();
        long day = w.getTimeOfDay() / 24000L;
        long time = w.getTimeOfDay() % 24000L;
        // Each night, once, at dusk: maybe a shower.
        if (time >= 13000 && time < 13100 && lastNight != day) {
            lastNight = day;
            if (w.getRandom().nextInt(NIGHTS) == 0 && !w.isRaining()) {
                start();
            }
        }
        if (!showering()) {
            return;
        }
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (!p.isSpectator() && w.isSkyVisible(p.getBlockPos().up())) {
                out.add(p);
            }
        }
        for (ServerPlayerEntity p : out) {
            // Shooting stars across the sky for everyone outside.
            if (w.getRandom().nextInt(2) == 0) {
                streak(w, p, false);
            }
        }
        // Now and then one comes down.
        if (!out.isEmpty() && w.getRandom().nextInt(12) == 0) {
            streak(w, out.get(w.getRandom().nextInt(out.size())), true);
        }
    }

    /** A burning streak across the sky; if it lands, a meteorite where it hits. */
    private static void streak(ServerWorld w, ServerPlayerEntity p, boolean lands) {
        var r = w.getRandom();
        double a = r.nextDouble() * Math.PI * 2;
        double dist = lands ? 30 + r.nextInt(40) : 60 + r.nextInt(60);
        Vec3d end = new Vec3d(p.getX() + Math.cos(a) * dist, 0, p.getZ() + Math.sin(a) * dist);
        BlockPos ground = w.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(end));
        Vec3d to = lands ? Vec3d.ofBottomCenter(ground) : new Vec3d(end.x, p.getY() + 60, end.z);
        Vec3d from = to.add((r.nextDouble() - 0.5) * 60, 90, (r.nextDouble() - 0.5) * 60);
        int steps = 30;
        for (int i = 0; i <= steps; i++) {
            int k = i;
            OwnerPowers.later(k, () -> {
                Vec3d at = from.lerp(to, k / (double) steps);
                w.spawnParticles(p, ParticleTypes.FLAME, true, false, at.x, at.y, at.z, 4, 0.2, 0.2, 0.2, 0.01);
                w.spawnParticles(p, ParticleTypes.LARGE_SMOKE, true, false, at.x, at.y, at.z, 1, 0.1, 0.1, 0.1, 0);
                if (k % 3 == 0) {
                    w.spawnParticles(p, ParticleTypes.FIREWORK, true, false, at.x, at.y, at.z, 2, 0.3, 0.3, 0.3, 0.02);
                }
            });
        }
        if (lands) {
            w.playSound(null, to.x, to.y + 20, to.z, SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR, SoundCategory.AMBIENT, 6f, 0.5f);
            OwnerPowers.later(steps, () -> land(w, ground));
        }
    }

    private static boolean free(ServerWorld w, BlockPos q) {
        BlockState st = w.getBlockState(q);
        return (st.isAir() || st.isReplaceable()) && st.getFluidState().isEmpty();
    }

    public static void landForTest(ServerWorld w, BlockPos ground) {
        land(w, ground);
    }

    /** A small meteorite on the ground: magma round a heart of rare ore (a gem, now and then ancient debris). */
    static void land(ServerWorld w, BlockPos ground) {
        if (WorldGuard.claimedOrLobby(w, ground) || Builds.protects(w, ground) || TempestKeep.protects(w, ground) || EdgeKeep.protects(w, ground)
                || !free(w, ground) || w.getBlockState(ground.down()).isAir()) {
            return;
        }
        var r = w.getRandom();
        var gems = Gems.all();
        BlockState heart = r.nextInt(6) == 0 ? Blocks.ANCIENT_DEBRIS.getDefaultState()
                : Gems.oreState(gems.get(r.nextInt(gems.size())));
        w.setBlockState(ground, heart);
        for (BlockPos q : List.of(ground.east(), ground.west(), ground.north(), ground.south(), ground.up())) {
            if (free(w, q) && r.nextInt(4) != 0) {
                w.setBlockState(q, r.nextInt(3) == 0 ? Blocks.OBSIDIAN.getDefaultState() : Blocks.MAGMA_BLOCK.getDefaultState());
            }
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5, 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.LAVA, ground.getX() + 0.5, ground.getY() + 1, ground.getZ() + 0.5, 30, 1, 0.5, 1, 0);
        w.playSound(null, ground, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.AMBIENT, 3f, 0.6f);
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(Vec3d.ofCenter(ground)) < 160 * 160) {
                Msg.send(p, "meteor.landed", ground.getX(), ground.getZ());
            }
        }
        Ac.LOG.info("A meteorite landed at {}", ground.toShortString());
    }
}
