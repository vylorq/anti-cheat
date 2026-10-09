package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fear. Everyone has a hidden fear level (0-100) that rises in the dark, alone, underground, and whenever they see it,
 * and falls in the light with other people around. The more afraid they are, the more their body gives them away
 * (loud breathing, shaking hands, a shaking screen) and the more often it comes. At the very top, not even home is
 * safe. Also here: it learns where you like to be and waits there, red eyes in the dark, its whisper in your ear, and
 * the silence before it comes.
 */
public final class BoiledFear {
    private BoiledFear() {
    }

    public static final float MAX = 100;
    private static final Map<UUID, Float> FEAR = new ConcurrentHashMap<>();
    /** Fear thresholds already told about (so each message comes once per rise). */
    private static final Map<UUID, Integer> TOLD = new ConcurrentHashMap<>();

    public static float fear(ServerPlayerEntity p) {
        return FEAR.getOrDefault(p.getUuid(), 0f);
    }

    public static void scare(ServerPlayerEntity p, float amount) {
        if (exempt(p)) {
            return;
        }
        FEAR.put(p.getUuid(), MathHelper.clamp(fear(p) + amount, 0, MAX));
    }

    private static boolean exempt(ServerPlayerEntity p) {
        return p.isCreative() || p.isSpectator() || com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid());
    }

    static boolean alone(ServerPlayerEntity p) {
        for (ServerPlayerEntity o : p.getEntityWorld().getPlayers()) {
            if (o != p && !o.isSpectator() && o.squaredDistanceTo(p) < 32 * 32) {
                return false;
            }
        }
        return true;
    }

    static boolean dark(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        BlockPos at = p.getBlockPos();
        int block = w.getLightLevel(LightType.BLOCK, at);
        int sky = BoiledOne.night(w) ? 0 : w.getLightLevel(LightType.SKY, at);
        return Math.max(block, sky) < 6;
    }

    /** How much likelier it is to come for them now (alone, in the dark, underground, afraid): 1 to about 4. */
    static double pull(ServerPlayerEntity p) {
        double f = 1;
        if (alone(p)) {
            f *= 1.4;
        }
        if (dark(p) || BoiledOne.inCave(p)) {
            f *= 1.45;
        }
        return f * (1 + fear(p) / 100.0);
    }

    // ---------------------------------------------------------------- every second

    private static void second(ServerPlayerEntity p) {
        if (exempt(p) || !World.OVERWORLD.equals(p.getEntityWorld().getRegistryKey())) {
            FEAR.remove(p.getUuid());
            return;
        }
        boolean dark = dark(p);
        boolean alone = alone(p);
        boolean cave = BoiledOne.inCave(p);
        boolean night = BoiledOne.night(p.getEntityWorld());
        float d = 0;
        if (dark) {
            d += 0.5f;
        }
        if (alone && (dark || night || cave)) {
            d += 0.35f;
        }
        if (cave) {
            d += 0.35f;
        }
        if (BoiledOne.hunting().containsKey(p.getUuid())) {
            d += 1.2f;
        }
        if (BoiledOne.eventOn()) {
            d += 0.4f;
        }
        if (!dark && !alone) {
            d -= 2f;                               // light, and people around: it fades
        } else if (!dark && !night && !cave) {
            d -= 1.2f;
        } else if (d == 0) {
            d -= 0.3f;
        }
        if (BoiledOmens.isProtected(p) || BoiledFight.wearsFullSet(p)) {
            d = Math.min(d, -1.5f);
        }
        float f = MathHelper.clamp(fear(p) + d, 0, MAX);
        FEAR.put(p.getUuid(), f);
        body(p, f);
    }

    /** What fear does to them. */
    private static void body(ServerPlayerEntity p, float f) {
        int level = f >= 90 ? 4 : f >= 70 ? 3 : f >= 50 ? 2 : f >= 30 ? 1 : 0;
        int told = TOLD.getOrDefault(p.getUuid(), 0);
        if (level > told) {
            TOLD.put(p.getUuid(), level);
            Msg.actionBar(p, "§8§o" + Msg.trFor(p, "fear." + level));
        } else if (level < told - 1 || level == 0) {
            TOLD.put(p.getUuid(), level);
        }
        var rng = p.getRandom();
        // Breathing: faster and louder the more afraid.
        if (level >= 1 && rng.nextInt(level >= 3 ? 2 : 5) == 0) {
            BoiledOmens.pack(p, "boiled_breath", p.getEyePos(), 0.12f + 0.08f * level, 1.45f + 0.1f * level);
        }
        // Shaking hands: slower to mine and to swing.
        if (level >= 2) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 40, 0, true, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 0, true, false, false));
        }
        // A shaking screen.
        if (level >= 3 && rng.nextInt(level >= 4 ? 2 : 4) == 0) {
            shake(p, level >= 4 ? 2.2f : 1.2f, 6);
        }
        if (level >= 4 && rng.nextInt(6) == 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 80, 0, true, false, false));
        }
    }

    /** The camera trembles for a moment (their feet stay where they are). */
    static void shake(ServerPlayerEntity p, float strength, int ticks) {
        for (int i = 0; i < ticks; i++) {
            OwnerPowers.later(i, () -> {
                if (p.isRemoved()) {
                    return;
                }
                var r = p.getRandom();
                p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(),
                        p.getYaw() + (r.nextFloat() - 0.5f) * strength * 2, MathHelper.clamp(p.getPitch() + (r.nextFloat() - 0.5f) * strength * 2, -90, 90));
            });
        }
    }

    // ---------------------------------------------------------------- the silence before it comes

    private static final SoundCategory[] HUSH = {SoundCategory.MUSIC, SoundCategory.RECORDS, SoundCategory.WEATHER, SoundCategory.BLOCKS,
            SoundCategory.NEUTRAL, SoundCategory.PLAYERS, SoundCategory.AMBIENT, SoundCategory.VOICE};

    /** Every sound around them stops, and keeps stopping, for a few seconds (only its own sounds get through). */
    static void silence(ServerPlayerEntity p, int ticks) {
        for (int i = 0; i < ticks; i += 4) {
            OwnerPowers.later(i, () -> {
                if (!p.isRemoved()) {
                    for (SoundCategory c : HUSH) {
                        p.networkHandler.sendPacket(new StopSoundS2CPacket(null, c));
                    }
                }
            });
        }
    }

    // ---------------------------------------------------------------- red eyes in the dark

    /** Two red eyes, far off in the dark; they go out the moment you look straight at them. */
    static boolean eyes(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        var rng = w.getRandom();
        for (int tries = 0; tries < 30; tries++) {
            double side = (rng.nextBoolean() ? 1 : -1) * (15 + rng.nextDouble() * 35);
            double ang = Math.toRadians(p.getYaw() + 90 + side);
            double d = 16 + rng.nextDouble() * 16;
            int x = MathHelper.floor(p.getX() + Math.cos(ang) * d);
            int z = MathHelper.floor(p.getZ() + Math.sin(ang) * d);
            for (int dy = 4; dy >= -4; dy--) {
                BlockPos at = new BlockPos(x, p.getBlockY() + dy, z);
                if (!w.getBlockState(at.down()).isSolidBlock(w, at.down()) || !w.getBlockState(at).getCollisionShape(w, at).isEmpty()
                        || !w.getBlockState(at.up()).getCollisionShape(w, at.up()).isEmpty()
                        || w.getLightLevel(LightType.BLOCK, at.up()) > 4) {
                    continue;
                }
                Vec3d head = Vec3d.ofBottomCenter(at).add(0, 1.65, 0);
                Vec3d toMe = p.getEyePos().subtract(head).multiply(1, 0, 1).normalize();
                Vec3d side2 = new Vec3d(-toMe.z, 0, toMe.x).multiply(0.14);
                Vec3d left = head.add(side2);
                Vec3d right = head.subtract(side2);
                long until = w.getTime() + 20 * 12;
                glow(p, left, right, until);
                return true;
            }
        }
        return false;
    }

    private static void glow(ServerPlayerEntity p, Vec3d l, Vec3d r, long until) {
        ServerWorld w = p.getEntityWorld();
        if (p.isRemoved() || w.getTime() > until || p.getEntityWorld() != w) {
            return;
        }
        Vec3d mid = l.add(r).multiply(0.5);
        Vec3d to = mid.subtract(p.getEyePos());
        if (to.lengthSquared() < 1e-3 || to.length() < 6 || p.getRotationVec(1f).dotProduct(to.normalize()) > 0.985) {
            return;     // looked straight at: gone
        }
        DustParticleEffect red = new DustParticleEffect(0xFF1010, 1.3f);
        w.spawnParticles(p, red, true, true, l.x, l.y, l.z, 1, 0, 0, 0, 0);
        w.spawnParticles(p, red, true, true, r.x, r.y, r.z, 1, 0, 0, 0, 0);
        OwnerPowers.later(3, () -> glow(p, l, r, until));
    }

    // ---------------------------------------------------------------- its whisper in your ear

    /** Its breath right beside one ear, and your name. */
    static void earWhisper(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) {
            look = new Vec3d(0, 0, 1);
        }
        look = look.normalize();
        boolean leftSide = p.getRandom().nextBoolean();
        Vec3d side = new Vec3d(-look.z, 0, look.x).multiply(leftSide ? -0.7 : 0.7);
        Vec3d at = p.getEyePos().add(side).subtract(look.multiply(0.3));
        BoiledOmens.pack(p, "boiled_breath", at, 0.9f, 1.25f);
        OwnerPowers.later(18, () -> {
            if (!p.isRemoved()) {
                Msg.actionBar(p, "§8§o" + (leftSide ? "« " : "") + "..." + p.getGameProfile().name() + "..." + (leftSide ? "" : " »"));
                BoiledOmens.sound(p, net.minecraft.sound.SoundEvents.ENTITY_VEX_AMBIENT, at, 0.35f, 0.5f);
            }
        });
        scare(p, 8);
    }

    // ---------------------------------------------------------------- it learns where you go

    /** Per player: how many minutes they've spent in each 16-block cell (overworld). */
    private static final Map<UUID, Map<Long, Integer>> HABITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CELL = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_WAIT = new ConcurrentHashMap<>();

    private static long cell(BlockPos b) {
        return BlockPos.asLong(b.getX() >> 4, b.getY() >> 4, b.getZ() >> 4);
    }

    private static void habit(ServerPlayerEntity p, long now) {
        if (!World.OVERWORLD.equals(p.getEntityWorld().getRegistryKey()) || exempt(p)) {
            return;
        }
        long c = cell(p.getBlockPos());
        Map<Long, Integer> h = HABITS.computeIfAbsent(p.getUuid(), k -> new HashMap<>());
        if (now % 1200 == 0) {
            h.merge(c, 1, Integer::sum);
            if (h.size() > 64) {
                // Forget the least-visited places.
                h.entrySet().stream().min(Map.Entry.comparingByValue()).ifPresent(e -> h.remove(e.getKey()));
            }
        }
        Long was = CELL.put(p.getUuid(), c);
        if (was == null || was == c) {
            return;
        }
        // Just arrived somewhere they go a lot: it may already be waiting there.
        int visits = h.getOrDefault(c, 0);
        if (visits < 15 || now < NEXT_WAIT.getOrDefault(p.getUuid(), 0L) || !BoiledOne.enabled()) {
            return;
        }
        boolean cave = BoiledOne.inCave(p);
        if (!cave && !BoiledOne.night(p.getEntityWorld())) {
            return;
        }
        if (p.getRandom().nextInt(10) != 0) {
            return;
        }
        NEXT_WAIT.put(p.getUuid(), now + 20L * 60 * 15);
        BoiledOne.waitingAt(p);
    }

    public static void tick(long now) {
        if (now % 20 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            second(p);
            habit(p, now);
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            FEAR.remove(h.player.getUuid());
            TOLD.remove(h.player.getUuid());
            CELL.remove(h.player.getUuid());
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> {
            FEAR.put(now.getUuid(), 20f);     // shaken, but alive
            TOLD.remove(now.getUuid());
        });
    }

    public static void setForTest(ServerPlayerEntity p, float f) {
        FEAR.put(p.getUuid(), f);
    }
}
