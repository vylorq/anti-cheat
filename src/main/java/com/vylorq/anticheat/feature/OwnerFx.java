package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * The owner's effects: a sound for each power, effects while powers are on, and the bigger tool effects
 * (shockwave, wind spiral, heart ring, freeze circle). Anything that could give the owner away (ghost mode,
 * vanish) is shown to the owner only.
 */
public final class OwnerFx {
    private OwnerFx() {
    }

    private static final DustParticleEffect GOLD = new DustParticleEffect(0xFFD54A, 1.0f);
    private static final DustParticleEffect ICE = new DustParticleEffect(0x9FE8FF, 1.2f);

    /** True when effects others can see would give the owner away. */
    private static boolean hidden(ServerPlayerEntity p) {
        return p.isSpectator() || StaffTools.isVanished(p.getUuid());
    }

    /** A particle everyone nearby sees, or only the owner while hidden. */
    private static void fx(ServerPlayerEntity p, ParticleEffect e, double x, double y, double z) {
        if (hidden(p)) {
            Mc.particle(p, e, x, y, z);
        } else {
            p.getEntityWorld().spawnParticles(e, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    // ---------------------------------------------------------------- sounds

    public static void powerSound(ServerPlayerEntity p, OwnerPowers.Power power, boolean on) {
        String base = switch (power) {
            case FLY -> "fly";
            case GOD -> "god";
            case SPEED -> "speed";
            case NIGHT_VISION -> "night";
            case INSTA_BREAK -> "break";
            case RADAR -> "radar";
            case GHOST -> "ghost";
        };
        OwnerPowers.sfx(p, base + (on ? "_on" : "_off"), on ? SoundEvents.BLOCK_BEACON_ACTIVATE : SoundEvents.BLOCK_BEACON_DEACTIVATE, on ? 1.4f : 1.2f);
    }

    // ---------------------------------------------------------------- while powers are on

    public static void tick(long ticks) {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (Perms.isOwner(p.getUuid()) && OwnerPowers.owner(p)) {
                ongoing(p, ticks);
            }
        }
        if (ticks % 2 == 0) {
            frozenLook();
        }
    }

    private static void ongoing(ServerPlayerEntity p, long ticks) {
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        // Flying: a glowing trail under the feet.
        if (OwnerPowers.on(p, OwnerPowers.Power.FLY) && p.getAbilities().flying && ticks % 2 == 0) {
            fx(p, ParticleTypes.END_ROD, x + (Math.random() - 0.5) * 0.4, y + 0.1, z + (Math.random() - 0.5) * 0.4);
            fx(p, ParticleTypes.CLOUD, x, y, z);
        }
        // God mode: a golden halo turning above the head.
        if (OwnerPowers.on(p, OwnerPowers.Power.GOD) && ticks % 3 == 0) {
            double a0 = ticks * 0.15;
            for (int i = 0; i < 6; i++) {
                double a = a0 + i * Math.PI / 3;
                fx(p, GOLD, x + Math.cos(a) * 0.45, y + p.getHeight() + 0.35, z + Math.sin(a) * 0.45);
            }
        }
        // Speed: wind lines behind when moving fast.
        if (OwnerPowers.on(p, OwnerPowers.Power.SPEED)) {
            Vec3d v = p.getVelocity();
            double h = Math.sqrt(v.x * v.x + v.z * v.z);
            if (h > 0.18 && ticks % 2 == 0) {
                for (int i = 0; i < 2; i++) {
                    fx(p, ParticleTypes.CLOUD, x - v.x * 2 + (Math.random() - 0.5) * 0.5, y + 0.2 + Math.random() * 1.2,
                            z - v.z * 2 + (Math.random() - 0.5) * 0.5);
                }
                fx(p, ParticleTypes.CRIT, x - v.x, y + 0.1, z - v.z);
            }
        }
        // Ghost mode: drifting souls and smoke (only the owner sees them).
        if (OwnerPowers.on(p, OwnerPowers.Power.GHOST) && ticks % 4 == 0) {
            Mc.particle(p, ParticleTypes.SOUL, x + (Math.random() - 0.5), y + Math.random() * 1.8, z + (Math.random() - 0.5));
            Mc.particle(p, ParticleTypes.SMOKE, x + (Math.random() - 0.5), y + Math.random() * 1.8, z + (Math.random() - 0.5));
        }
        // Night vision: a faint glimmer at the eyes (owner only).
        if (OwnerPowers.on(p, OwnerPowers.Power.NIGHT_VISION) && ticks % 20 == 0) {
            Vec3d look = p.getRotationVec(1f);
            Vec3d eye = p.getEyePos().add(look.multiply(0.6));
            Mc.particle(p, ParticleTypes.GLOW, eye.x, eye.y, eye.z);
        }
        // Radar: a soft sonar pulse every two seconds (owner only).
        if (OwnerPowers.on(p, OwnerPowers.Power.RADAR) && ticks % 40 == 0) {
            UUID id = p.getUuid();
            for (int step = 0; step < 6; step++) {
                int s = step;
                OwnerPowers.later(step * 2, () -> {
                    ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                    if (o != null) {
                        ring(o, ParticleTypes.ELECTRIC_SPARK, o.getEntityPos().add(0, 0.2, 0), 1.0 + s * 1.2, 16, true);
                    }
                });
            }
            OwnerPowers.sfx(p, "radar_ping", null, 1f);
        }
        // Holding the freeze wand: preview of the circle on the ground (owner only).
        if (ticks % 10 == 0 && OwnerTools.FREEZE.equals(OwnerTools.toolOf(p.getMainHandStack()))) {
            int r = OwnerPowers.state().freezeRadius;
            ring(p, ICE, p.getEntityPos().add(0, 0.15, 0), r, Math.min(120, 12 + r * 4), true);
            com.vylorq.anticheat.util.Msg.actionBar(p, "§b❄ " + com.vylorq.anticheat.util.Msg.trFor(p, "owner.freeze-radius", r));
        }
    }

    /** A flat ring of particles. */
    static void ring(ServerPlayerEntity p, ParticleEffect e, Vec3d c, double radius, int points, boolean ownerOnly) {
        for (int i = 0; i < points; i++) {
            double a = i * 2 * Math.PI / points;
            double x = c.x + Math.cos(a) * radius;
            double z = c.z + Math.sin(a) * radius;
            if (ownerOnly) {
                Mc.particle(p, e, x, c.y, z);
            } else {
                fx(p, e, x, c.y, z);
            }
        }
    }

    // ---------------------------------------------------------------- tool effects

    /** Lightning: an expanding shockwave ring of sparks and smoke. */
    public static void shockwave(ServerPlayerEntity p, Vec3d at) {
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
        UUID id = p.getUuid();
        for (int step = 0; step < 8; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o != null && o.getEntityWorld() == w) {
                    ring(o, ParticleTypes.ELECTRIC_SPARK, at.add(0, 0.2, 0), 0.6 + s * 0.7, 18 + s * 4, false);
                    ring(o, ParticleTypes.CAMPFIRE_COSY_SMOKE, at.add(0, 0.1, 0), 0.4 + s * 0.7, 6 + s, false);
                }
            });
        }
    }

    /** Launch: a spiral of wind following the launched one up. */
    public static void windSpiral(ServerPlayerEntity p, Entity target) {
        UUID id = p.getUuid();
        for (int step = 0; step < 14; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o == null || target.isRemoved()) {
                    return;
                }
                for (int k = 0; k < 3; k++) {
                    double a = s * 0.8 + k * 2 * Math.PI / 3;
                    fx(o, ParticleTypes.CLOUD, target.getX() + Math.cos(a) * 0.9, target.getY() + 0.2 + s * 0.12, target.getZ() + Math.sin(a) * 0.9);
                }
                if (s % 3 == 0) {
                    fx(o, ParticleTypes.GUST, target.getX(), target.getY(), target.getZ());
                }
            });
        }
    }

    /** Heal: a ring of hearts rising around the healed one. */
    public static void heartRing(ServerPlayerEntity p, LivingEntity e) {
        UUID id = p.getUuid();
        for (int step = 0; step < 10; step++) {
            int s = step;
            OwnerPowers.later(step * 2, () -> {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o == null || e.isRemoved()) {
                    return;
                }
                for (int k = 0; k < 6; k++) {
                    double a = s * 0.5 + k * Math.PI / 3;
                    fx(o, k % 2 == 0 ? ParticleTypes.HEART : ParticleTypes.HAPPY_VILLAGER,
                            e.getX() + Math.cos(a) * 0.8, e.getY() + 0.1 + s * 0.2, e.getZ() + Math.sin(a) * 0.8);
                }
            });
        }
    }

    /** Instant break: a crisp burst where the block was. */
    public static void breakBurst(ServerPlayerEntity p, net.minecraft.util.math.BlockPos pos) {
        ServerWorld w = p.getEntityWorld();
        if (!hidden(p)) {
            w.spawnParticles(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 14, 0.3, 0.3, 0.3, 0.3);
        }
        OwnerPowers.sfx(p, "insta", null, 1f);
    }

    /** Freeze wand: an icy circle racing out to the edge. */
    public static void freezeWave(ServerPlayerEntity p, int radius) {
        Vec3d c = p.getEntityPos().add(0, 0.2, 0);
        ServerWorld w = p.getEntityWorld();
        UUID id = p.getUuid();
        int steps = 10;
        for (int step = 1; step <= steps; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o != null && o.getEntityWorld() == w) {
                    double r = radius * s / (double) steps;
                    ring(o, ParticleTypes.SNOWFLAKE, c, r, (int) Math.min(160, 10 + r * 5), false);
                    ring(o, ICE, c.add(0, 0.3, 0), r, (int) Math.min(80, 6 + r * 3), false);
                }
            });
        }
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1f, 0.6f);
    }

    /** Players frozen by the wand look frozen: frost on their screen and snowflakes around them. */
    private static void frozenLook() {
        var st = OwnerPowers.state();
        if (st.wandFrozen.isEmpty()) {
            return;
        }
        for (String s : st.wandFrozen) {
            ServerPlayerEntity f;
            try {
                f = Ac.server().getPlayerManager().getPlayer(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (f == null || !StaffTools.isFrozen(f)) {
                continue;
            }
            // Just below the point where freezing starts to hurt.
            f.setFrozenTicks(f.getMinFreezeDamageTicks() - 1);
            f.getEntityWorld().spawnParticles(ParticleTypes.SNOWFLAKE, f.getX(), f.getY() + 1, f.getZ(), 2, 0.35, 0.6, 0.35, 0.01);
        }
    }
}
