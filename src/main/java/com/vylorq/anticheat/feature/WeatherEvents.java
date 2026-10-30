package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * More weather. Crystal storms: during a thunderstorm, now and then a bolt (only a flash: nothing burns) strikes near a
 * player out in the open and leaves a crystal behind: an amethyst shard, or now and then one of the gems. Fog nights:
 * some nights a thick fog rolls in; out in it you can't see far, but every monster beaten in the fog drops emeralds.
 */
public final class WeatherEvents {
    private WeatherEvents() {
    }

    /** One night in this many is foggy. */
    static final int FOG_NIGHTS = 5;
    private static long lastNight = -1;
    private static boolean fog;

    public static boolean foggy() {
        if (!fog) {
            return false;
        }
        long time = Ac.server().getOverworld().getTimeOfDay() % 24000L;
        return time >= 13000 && time < 23000;
    }

    public static void startFog() {
        fog = true;
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Mc.title(p, "§7§l" + Msg.trFor(p, "fog.title"), "§8" + Msg.trFor(p, "fog.sub"), 10, 60, 20);
        }
    }

    private static boolean outside(ServerWorld w, ServerPlayerEntity p) {
        return w == Ac.server().getOverworld() && !p.isSpectator() && w.isSkyVisible(p.getBlockPos().up())
                && !LobbyFeature.in(w, p.getBlockPos());
    }

    public static void tick(long ticks) {
        if (ticks % 20 != 0) {
            return;
        }
        ServerWorld w = Ac.server().getOverworld();
        long day = w.getTimeOfDay() / 24000L;
        long time = w.getTimeOfDay() % 24000L;
        if (time >= 12900 && time < 13000 && lastNight != day) {
            lastNight = day;
            fog = false;
            if (w.getRandom().nextInt(FOG_NIGHTS) == 0 && !Meteors.showering()) {
                startFog();
            }
        }
        if (fog && time >= 23000) {
            fog = false;
        }
        if (foggy()) {
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (outside(w, p)) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, true, false, false));
                }
            }
        }
        // Crystal storm: about once a minute for each player out in the thunder.
        if (w.isThundering()) {
            List<ServerPlayerEntity> out = new ArrayList<>();
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (outside(w, p)) {
                    out.add(p);
                }
            }
            for (ServerPlayerEntity p : out) {
                if (w.getRandom().nextInt(60) != 0) {
                    continue;
                }
                int dx = w.getRandom().nextInt(17) - 8, dz = w.getRandom().nextInt(17) - 8;
                BlockPos at = w.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getBlockPos().add(dx, 0, dz));
                if (WorldGuard.claimedOrLobby(w, at) || Builds.protects(w, at)) {
                    continue;
                }
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.setCosmetic(true);
                    bolt.refreshPositionAfterTeleport(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                    w.spawnEntity(bolt);
                }
                ItemStack crystal;
                if (w.getRandom().nextInt(5) == 0) {
                    var gems = Gems.all().stream().filter(g -> !g.id().equals("bloodstone")).toList();
                    crystal = Gems.gem(gems.get(w.getRandom().nextInt(gems.size())), 1);
                } else {
                    crystal = new ItemStack(Items.AMETHYST_SHARD, 1 + w.getRandom().nextInt(3));
                }
                ItemEntity drop = new ItemEntity(w, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, crystal);
                drop.setGlowing(true);
                w.spawnEntity(drop);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 20, 0.4, 0.4, 0.4, 0.1);
                Msg.actionBar(p, "§b" + Msg.trFor(p, "storm.crystal"));
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && foggy() && e instanceof Monster && source.getAttacker() instanceof ServerPlayerEntity p
                    && e.getEntityWorld() == Ac.server().getOverworld() && e.getEntityWorld() instanceof ServerWorld w) {
                w.spawnEntity(new ItemEntity(w, e.getX(), e.getY() + 0.3, e.getZ(), new ItemStack(Items.EMERALD, 1 + w.getRandom().nextInt(2))));
            }
        });
    }
}
