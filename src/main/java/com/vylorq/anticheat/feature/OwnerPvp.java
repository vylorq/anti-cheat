package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The owner's PvP powers (their own menu):
 * <ul>
 *   <li>Undying: a killing blow is always a totem instead (animation, sound, effects), forever.</li>
 *   <li>PvP Buffs: the effects that matter in a fight, always on.</li>
 *   <li>No Knockback, Always Crit, Damage Boost (2x / 3x), Auto Repair, No Item Cooldowns (pearls, apples, wind
 *   charges, chorus), Kill Effects, Keep Inventory, Enemy Glow (players near you glow, for you only) and Second Wind
 *   (low on health: a burst of speed and healing, once a minute).</li>
 * </ul>
 */
public final class OwnerPvp {
    private OwnerPvp() {
    }

    /** Effect and level (0 = I). */
    static final List<Map.Entry<RegistryEntry<StatusEffect>, Integer>> BUFFS = List.of(
            Map.entry(StatusEffects.STRENGTH, 1), Map.entry(StatusEffects.REGENERATION, 1), Map.entry(StatusEffects.RESISTANCE, 1),
            Map.entry(StatusEffects.FIRE_RESISTANCE, 0), Map.entry(StatusEffects.SPEED, 1), Map.entry(StatusEffects.HASTE, 1),
            Map.entry(StatusEffects.ABSORPTION, 3), Map.entry(StatusEffects.SATURATION, 0), Map.entry(StatusEffects.WATER_BREATHING, 0));
    private static final Identifier NO_KB = Identifier.of("vigil", "owner_no_knockback");
    private static final ItemStack[] COOLDOWN_ITEMS = {new ItemStack(Items.ENDER_PEARL), new ItemStack(Items.GOLDEN_APPLE),
            new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), new ItemStack(Items.WIND_CHARGE), new ItemStack(Items.CHORUS_FRUIT)};

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

    /** Every half second, while the owner's online (from OwnerPowers.apply). */
    static void apply(ServerPlayerEntity p, boolean ok, OwnerPowers.State s) {
        // No knockback
        var kb = p.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE);
        boolean noKb = ok && s.noKnockback;
        if (kb != null && kb.hasModifier(NO_KB) != noKb) {
            kb.removeModifier(NO_KB);
            if (noKb) {
                kb.addTemporaryModifier(new EntityAttributeModifier(NO_KB, 1.0, EntityAttributeModifier.Operation.ADD_VALUE));
            }
        }
        if (!ok) {
            return;
        }
        // Auto repair: armour and both hands
        if (s.autoRepair) {
            for (ItemStack st : List.of(p.getMainHandStack(), p.getOffHandStack(), p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD),
                    p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST), p.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS),
                    p.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET))) {
                if (st.isDamageable() && st.getDamage() > 0) {
                    st.setDamage(0);
                }
            }
        }
        // No item cooldowns
        if (s.noItemCooldown) {
            var mgr = p.getItemCooldownManager();
            for (ItemStack st : COOLDOWN_ITEMS) {
                if (mgr.isCoolingDown(st)) {
                    mgr.remove(mgr.getGroup(st));
                }
            }
        }
        // Enemy glow: every player near the owner glows (for the owner only)
        if (s.enemyGlow) {
            glow(p);
        }
    }

    private static final Map<UUID, Set<Integer>> GLOWING = new ConcurrentHashMap<>();

    private static void glow(ServerPlayerEntity owner) {
        Set<Integer> was = GLOWING.computeIfAbsent(owner.getUuid(), k -> new HashSet<>());
        Set<Integer> now = new HashSet<>();
        for (ServerPlayerEntity o : owner.getEntityWorld().getPlayers()) {
            if (o != owner && !o.isSpectator() && o.squaredDistanceTo(owner) < 48 * 48) {
                now.add(o.getId());
                send(owner, o, true);
            }
        }
        for (Integer id : was) {
            if (!now.contains(id)) {
                var e = owner.getEntityWorld().getEntityById(id);
                if (e != null) {
                    send(owner, e, false);
                }
            }
        }
        GLOWING.put(owner.getUuid(), now);
    }

    static void clearGlow(ServerPlayerEntity owner) {
        Set<Integer> was = GLOWING.remove(owner.getUuid());
        if (was == null) {
            return;
        }
        for (Integer id : was) {
            var e = owner.getEntityWorld().getEntityById(id);
            if (e != null) {
                send(owner, e, false);
            }
        }
    }

    private static void send(ServerPlayerEntity to, net.minecraft.entity.Entity e, boolean glow) {
        to.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(e.getId(), List.of(new net.minecraft.entity.data.DataTracker.SerializedEntry<>(0,
                net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE, OwnerPowers.flags(e, glow)))));
    }

    // ---------------------------------------------------------------- hits

    /** The owner's melee hits: damage boost and always-crit (called from LivingEntityMixin before the hit lands). */
    public static float outgoing(LivingEntity target, DamageSource src, float amount) {
        if (!(src.getAttacker() instanceof ServerPlayerEntity p) || src.getSource() != p || target == p || !OwnerPowers.owner(p)) {
            return amount;
        }
        var s = OwnerPowers.state();
        float out = amount;
        if (s.damageBoost > 0) {
            out *= s.damageBoost + 1;
        }
        if (s.alwaysCrit) {
            out *= 1.5f;
            if (target.getEntityWorld() instanceof ServerWorld w) {
                w.spawnParticles(ParticleTypes.CRIT, target.getX(), target.getY() + target.getHeight() / 2, target.getZ(), 15, 0.3, 0.3, 0.3, 0.3);
                w.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 1f);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- dying (or not)

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

    private static final Map<UUID, List<ItemStack>> KEPT = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]> KEPT_XP = new ConcurrentHashMap<>();

    /** Keep inventory (the owner only): everything is taken off them before death drops it, and given back after. */
    private static void keep(ServerPlayerEntity p) {
        var inv = p.getInventory();
        List<ItemStack> all = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            all.add(inv.getStack(i).copy());
            inv.setStack(i, ItemStack.EMPTY);
        }
        KEPT.put(p.getUuid(), all);
        KEPT_XP.put(p.getUuid(), new int[]{p.experienceLevel, Math.round(p.experienceProgress * 1000)});
        p.setExperienceLevel(0);
        p.setExperiencePoints(0);
        OwnerPowers.later(1, () -> {
            if (!p.isRemoved() && p.isAlive()) {
                giveBack(p);        // didn't die after all
            }
        });
    }

    private static void giveBack(ServerPlayerEntity p) {
        List<ItemStack> all = KEPT.remove(p.getUuid());
        if (all != null) {
            var inv = p.getInventory();
            for (int i = 0; i < all.size() && i < inv.size(); i++) {
                if (!all.get(i).isEmpty()) {
                    if (inv.getStack(i).isEmpty()) {
                        inv.setStack(i, all.get(i));
                    } else {
                        inv.offerOrDrop(all.get(i));
                    }
                }
            }
        }
        int[] xp = KEPT_XP.remove(p.getUuid());
        if (xp != null) {
            p.setExperienceLevel(xp[0]);
            p.experienceProgress = xp[1] / 1000f;
        }
    }

    // ---------------------------------------------------------------- kill effects, second wind

    private static void killFx(ServerPlayerEntity p, LivingEntity dead) {
        ServerWorld w = p.getEntityWorld();
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.refreshPositionAndAngles(dead.getX(), dead.getY(), dead.getZ(), 0, 0);
            bolt.setCosmetic(true);
            w.spawnEntity(bolt);
        }
        w.spawnParticles(ParticleTypes.FIREWORK, dead.getX(), dead.getY() + 1, dead.getZ(), 80, 0.4, 0.8, 0.4, 0.25);
        w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, dead.getX(), dead.getY() + 1, dead.getZ(), 60, 0.3, 0.6, 0.3, 0.6);
        w.playSound(null, dead.getX(), dead.getY(), dead.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 2f, 1f);
        w.playSound(null, dead.getX(), dead.getY(), dead.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 2f, 1f);
    }

    private static final Map<UUID, Long> WIND = new ConcurrentHashMap<>();

    private static void secondWind(ServerPlayerEntity p) {
        long t = System.currentTimeMillis();
        if (p.getHealth() > 6f || t < WIND.getOrDefault(p.getUuid(), 0L)) {
            return;
        }
        WIND.put(p.getUuid(), t + 60_000);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 120, 2, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 120, 2, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 80, 1, false, false, true));
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 30, 0.5, 0.8, 0.5, 0.1);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.5f, 1.6f);
        com.vylorq.anticheat.util.Msg.actionBar(p, "§a" + com.vylorq.anticheat.util.Msg.trFor(p, "owner.second-wind"));
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (Ac.running() && entity instanceof ServerPlayerEntity p && OwnerPowers.owner(p)) {
                if (OwnerPowers.on(p, OwnerPowers.Power.UNDYING)) {
                    return undying(p);
                }
                if (OwnerPowers.on(p, OwnerPowers.Power.KEEP_INVENTORY)) {
                    keep(p);
                }
            }
            return true;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> giveBack(now));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && source.getAttacker() instanceof ServerPlayerEntity p && OwnerPowers.on(p, OwnerPowers.Power.KILL_FX)) {
                killFx(p, e);
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((e, source, base, taken, blocked) -> {
            if (Ac.running() && e instanceof ServerPlayerEntity p && p.isAlive() && OwnerPowers.on(p, OwnerPowers.Power.SECOND_WIND)) {
                secondWind(p);
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> GLOWING.remove(h.player.getUuid()));
    }
}
