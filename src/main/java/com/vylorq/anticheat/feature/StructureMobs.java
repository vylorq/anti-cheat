package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;

import java.util.Map;

/**
 * Mobs from the secret structures' spawners come armed: each structure dresses its mobs in its own armour and gives
 * them its own weapons (skeletons and strays keep a bow). The spawners tag their mobs "vigil_structure_mob:<name>".
 */
public final class StructureMobs {
    private StructureMobs() {
    }

    public static final String TAG = "vigil_structure_mob:";
    private static final String ARMED = "vigil_armed";

    /** The structure's melee weapon (skeletons and strays keep a bow). */
    private static final Map<String, Item> WEAPONS = Map.of(
            "sunken_vault", Items.TRIDENT,
            "buried_vault", Items.DIAMOND_SWORD,
            "sky_citadel", Items.IRON_SWORD,
            "nether_forge", Items.DIAMOND_AXE,
            "desert_tomb", Items.IRON_SWORD,
            "frozen_bastion", Items.IRON_AXE,
            "overgrown_labyrinth", Items.IRON_SWORD,
            "watchers_hollow", Items.DIAMOND_SWORD,
            "tempest", Items.DIAMOND_SWORD);

    private static final Item[][] SETS = {
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}};

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (Ac.running() && e instanceof MobEntity m && !m.getCommandTags().contains(ARMED)) {
                for (String t : m.getCommandTags()) {
                    if (t.startsWith(TAG)) {
                        String structure = t.substring(TAG.length());
                        if (w instanceof net.minecraft.server.world.ServerWorld sw && Bosses.resting(sw, structure, m.getBlockPos())) {
                            // Its boss was beaten: the structure rests and its spawners stay empty for now.
                            m.addCommandTag(ARMED);
                            OwnerPowers.later(1, m::discard);
                            break;
                        }
                        arm(m, structure);
                        break;
                    }
                }
            }
        });
    }

    private static final net.minecraft.util.Identifier TOUGH = net.minecraft.util.Identifier.of("vigil", "structure_tough");

    /** Every structure mob is tougher than a normal one: more health, harder hits, sees players from further away. */
    private static void toughen(MobEntity m) {
        add(m, EntityAttributes.MAX_HEALTH, 0.6);
        add(m, EntityAttributes.ATTACK_DAMAGE, 0.35);
        add(m, EntityAttributes.FOLLOW_RANGE, 0.5);
        var kb = m.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE);
        if (kb != null && kb.getModifier(TOUGH) == null) {
            kb.addPersistentModifier(new EntityAttributeModifier(TOUGH, 0.3, EntityAttributeModifier.Operation.ADD_VALUE));
        }
        m.setHealth(m.getMaxHealth());
    }

    private static void add(MobEntity m, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> a, double frac) {
        var i = m.getAttributeInstance(a);
        if (i != null && frac != 0 && i.getModifier(TOUGH) == null) {
            i.addPersistentModifier(new EntityAttributeModifier(TOUGH, frac, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** The mob drops nothing when it dies: no loot and none of what it wears or holds. */
    public static void noDrops(MobEntity m) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            m.setEquipmentDropChance(slot, 0f);
        }
        OwnerPowers.later(1, () -> {
            if (!m.isRemoved()) {
                Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withSilent(),
                        "data merge entity " + m.getUuidAsString() + " {DeathLootTable:\"minecraft:empty\"}");
            }
        });
    }

    /** One room mob in this many is an elite. */
    static final float ELITE_CHANCE = 1f / 20;
    public static final String ELITE_TAG = "vigil_elite";
    private static final net.minecraft.util.Identifier ELITE = net.minecraft.util.Identifier.of("vigil", "elite");

    /** An elite: glows, has a name, double the health and much harder hits, and is faster. */
    static void elite(MobEntity m) {
        m.addCommandTag(ELITE_TAG);
        eliteBoost(m, EntityAttributes.MAX_HEALTH, 1.0);
        eliteBoost(m, EntityAttributes.ATTACK_DAMAGE, 0.6);
        eliteBoost(m, EntityAttributes.MOVEMENT_SPEED, 0.15);
        m.setHealth(m.getMaxHealth());
        m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.GLOWING,
                net.minecraft.entity.effect.StatusEffectInstance.INFINITE, 0, false, false));
        m.setCustomName(net.minecraft.text.Text.literal("§6§lElite §e" + m.getType().getName().getString()));
        m.setCustomNameVisible(true);
        m.setPersistent();
    }

    private static void eliteBoost(MobEntity m, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null && i.getModifier(ELITE) == null) {
            i.addPersistentModifier(new EntityAttributeModifier(ELITE, v, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static final Item[] DIAMOND = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    private static final net.minecraft.util.Identifier GUARD = net.minecraft.util.Identifier.of("vigil", "boss_guard");

    /**
     * A boss's guard: stronger than any room mob. Full diamond (on top of its own gear's slots), a diamond sword
     * if it has nothing in hand and can use one, two and a half times the health, nearly double the damage, faster,
     * and hard to knock back. It drops nothing.
     */
    public static void guard(MobEntity m) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            if (m.getEquippedStack(slots[i]).isEmpty()) {
                m.equipStack(slots[i], new ItemStack(DIAMOND[i]));
            }
        }
        if (m.getMainHandStack().isEmpty() && m instanceof ZombieEntity) {
            m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        }
        boost(m, EntityAttributes.MAX_HEALTH, 1.5, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        boost(m, EntityAttributes.ATTACK_DAMAGE, 0.9, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        boost(m, EntityAttributes.MOVEMENT_SPEED, 0.15, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        boost(m, EntityAttributes.FOLLOW_RANGE, 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        boost(m, EntityAttributes.KNOCKBACK_RESISTANCE, 0.6, EntityAttributeModifier.Operation.ADD_VALUE);
        boost(m, EntityAttributes.ARMOR_TOUGHNESS, 4, EntityAttributeModifier.Operation.ADD_VALUE);
        m.setHealth(m.getMaxHealth());
        m.addCommandTag(ARMED);
        noDrops(m);
    }

    private static void boost(MobEntity m, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> a,
                              double v, EntityAttributeModifier.Operation op) {
        var i = m.getAttributeInstance(a);
        if (i != null && i.getModifier(GUARD) == null) {
            i.addPersistentModifier(new EntityAttributeModifier(GUARD, v, op));
        }
    }

    /** Dresses one mob (once): a full set of iron or better; zombies and skeletons also get a weapon (skeletons keep their bow). */
    static void arm(MobEntity m, String structure) {
        m.addCommandTag(ARMED);
        toughen(m);
        noDrops(m);
        if (m.getRandom().nextFloat() < ELITE_CHANCE) {
            elite(m);
        }
        Item weapon = WEAPONS.get(structure);
        if (weapon == null) {
            return;
        }
        Random r = m.getRandom();
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            // Always a full set: iron mostly, diamond now and then, piece by piece (never netherite).
            Item[] set = SETS[r.nextFloat() < 0.3f ? 1 : 0];
            m.equipStack(slots[i], new ItemStack(set[i]));
            m.setEquipmentDropChance(slots[i], 0f);
        }
        // Every mob wears it (spiders and blazes too: it doesn't show on them, but it still protects them).
        if (!(m instanceof ZombieEntity || m instanceof AbstractSkeletonEntity)) {
            return;
        }
        boolean skeleton = m instanceof AbstractSkeletonEntity && !(m instanceof WitherSkeletonEntity);
        m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(skeleton ? Items.BOW : weapon));
        m.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
    }
}
