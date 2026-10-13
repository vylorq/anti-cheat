package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import java.util.Map;

/**
 * The owner's PvP powers: Undying (a killing blow is always a totem instead: the totem's animation, sound and
 * effects, every time, until it's turned off) and PvP Buffs (the effects that matter in a fight, always on).
 */
public final class OwnerPvp {
    private OwnerPvp() {
    }

    /** Effect and level (0 = I). */
    static final List<Map.Entry<RegistryEntry<StatusEffect>, Integer>> BUFFS = List.of(
            Map.entry(StatusEffects.STRENGTH, 1), Map.entry(StatusEffects.REGENERATION, 1), Map.entry(StatusEffects.RESISTANCE, 1),
            Map.entry(StatusEffects.FIRE_RESISTANCE, 0), Map.entry(StatusEffects.SPEED, 1), Map.entry(StatusEffects.HASTE, 1),
            Map.entry(StatusEffects.ABSORPTION, 3), Map.entry(StatusEffects.SATURATION, 0), Map.entry(StatusEffects.WATER_BREATHING, 0));

    static void buffs(ServerPlayerEntity p) {
        for (var e : BUFFS) {
            StatusEffectInstance have = p.getStatusEffect(e.getKey());
            if (have == null || have.getAmplifier() < e.getValue() || !have.isInfinite() && have.getDuration() < 100) {
                p.addStatusEffect(new StatusEffectInstance(e.getKey(), StatusEffectInstance.INFINITE, e.getValue(), true, false, true));
            }
        }
    }

    static void clearBuffs(ServerPlayerEntity p) {
        for (var e : BUFFS) {
            StatusEffectInstance have = p.getStatusEffect(e.getKey());
            if (have != null && have.isInfinite()) {
                p.removeStatusEffect(e.getKey());
            }
        }
    }

    /** A killing blow: the totem goes off instead (its animation and sound for everyone around), every time. */
    private static boolean undying(ServerPlayerEntity p) {
        p.setHealth(Math.max(1f, p.getMaxHealth() * 0.25f));
        p.clearStatusEffects();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 900, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 100, 1));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 800, 0));
        p.getEntityWorld().sendEntityStatus(p, EntityStatuses.USE_TOTEM_OF_UNDYING);
        if (OwnerPowers.on(p, OwnerPowers.Power.PVP_BUFFS)) {
            buffs(p);
        }
        return false;
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (Ac.running() && entity instanceof ServerPlayerEntity p && OwnerPowers.on(p, OwnerPowers.Power.UNDYING)) {
                return undying(p);
            }
            return true;
        });
    }
}
