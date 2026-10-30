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

import java.util.List;
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
    private static final Map<String, Item> WEAPONS = Map.ofEntries(
            Map.entry("sunken_vault", Items.TRIDENT),
            Map.entry("buried_vault", Items.DIAMOND_SWORD),
            Map.entry("sky_citadel", Items.IRON_SWORD),
            Map.entry("nether_forge", Items.DIAMOND_AXE),
            Map.entry("desert_tomb", Items.IRON_SWORD),
            Map.entry("frozen_bastion", Items.IRON_AXE),
            Map.entry("overgrown_labyrinth", Items.IRON_SWORD),
            Map.entry("watchers_hollow", Items.DIAMOND_SWORD),
            Map.entry("tempest", Items.DIAMOND_SWORD),
            Map.entry("edge", Items.NETHERITE_SWORD),
            Map.entry("sky_island", Items.IRON_SWORD),
            Map.entry("mushroom_cave", Items.IRON_AXE),
            Map.entry("sunken_ship", Items.IRON_SWORD),
            Map.entry("soul_sanctum", Items.DIAMOND_SWORD),
            Map.entry("volcano_forge", Items.DIAMOND_AXE),
            Map.entry("frozen_ship", Items.IRON_SWORD),
            Map.entry("sky_temple", Items.GOLDEN_SWORD),
            Map.entry("mine_town", Items.IRON_PICKAXE),
            Map.entry("pyramid_maze", Items.GOLDEN_SWORD),
            Map.entry("witch_tower", Items.IRON_SWORD),
            Map.entry("warped_shrine", Items.GOLDEN_SWORD),
            Map.entry("cinder_keep", Items.STONE_SWORD),
            Map.entry("chorus_garden", Items.DIAMOND_SWORD),
            Map.entry("crimson_citadel", Items.GOLDEN_AXE),
            Map.entry("void_spire", Items.DIAMOND_SWORD),
            Map.entry("ender_crypt", Items.DIAMOND_AXE));

    private static final Item[][] SETS = {
            {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS},
            {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS}};

    /** Where a structure mob belongs ("vigil_home:x,y,z"), and how far it may stray from there ("vigil_leash:r"). */
    public static final String HOME = "vigil_home:";
    public static final String LEASH_TAG = "vigil_leash:";
    /** How far a structure's mobs may go from where they were spawned before they're pulled back. */
    static final int LEASH = 24;

    private record Home(MobEntity mob, double x, double y, double z, int range) {}

    private static final Map<java.util.UUID, Home> HOMES = new java.util.HashMap<>();

    /** Keeps this mob near where it stands now: it is pulled back whenever it strays more than {@code range} blocks. */
    public static void leash(MobEntity m, int range) {
        m.getCommandTags().removeIf(t -> t.startsWith(HOME) || t.startsWith(LEASH_TAG));
        m.addCommandTag(HOME + m.getBlockX() + "," + m.getBlockY() + "," + m.getBlockZ());
        m.addCommandTag(LEASH_TAG + range);
        track(m);
    }

    private static void track(MobEntity m) {
        double[] h = null;
        int range = LEASH;
        for (String t : m.getCommandTags()) {
            if (t.startsWith(HOME)) {
                String[] p = t.substring(HOME.length()).split(",");
                if (p.length == 3) {
                    try {
                        h = new double[]{Integer.parseInt(p[0]) + 0.5, Integer.parseInt(p[1]), Integer.parseInt(p[2]) + 0.5};
                    } catch (NumberFormatException ignored) {
                        // a hand-made tag: no home
                    }
                }
            } else if (t.startsWith(LEASH_TAG)) {
                try {
                    range = Integer.parseInt(t.substring(LEASH_TAG.length()));
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
        }
        if (h != null) {
            HOMES.put(m.getUuid(), new Home(m, h[0], h[1], h[2], range));
        }
    }

    /** Once a second: every leashed mob that has strayed too far is pulled back home and forgets its target. */
    public static void tick(long ticks) {
        if (ticks % 20 != 0 || HOMES.isEmpty()) {
            return;
        }
        var it = HOMES.values().iterator();
        while (it.hasNext()) {
            Home h = it.next();
            MobEntity m = h.mob();
            if (m.isRemoved() || !m.isAlive()) {
                it.remove();
                continue;
            }
            double dx = m.getX() - h.x();
            double dz = m.getZ() - h.z();
            double dy = m.getY() - h.y();
            if (dx * dx + dz * dz > (double) h.range() * h.range() || Math.abs(dy) > h.range()) {
                if (m.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld w) {
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, m.getX(), m.getY() + 1, m.getZ(), 20, 0.3, 0.6, 0.3, 0.2);
                }
                m.setTarget(null);
                m.getNavigation().stop();
                m.requestTeleport(h.x(), h.y(), h.z());
                m.fallDistance = 0;
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && e instanceof MobEntity m && m.getCommandTags().contains(GUARDIAN_TAG)) {
                guardianDrop(m);
                if (source.getAttacker() instanceof net.minecraft.server.network.ServerPlayerEntity p) {
                    Achievements.unlock(p, "feat:guardian");
                    Skills.add(p, "combat", 40);
                }
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((e, w) -> HOMES.remove(e.getUuid()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (Ac.running() && e instanceof MobEntity m && m.getCommandTags().stream().anyMatch(t -> t.startsWith(HOME))) {
                track(m);
            }
        });
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
                        leash(m, LEASH);
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

    /** One room mob in this many is the rooms' Guardian, a mini-boss (rarer than an elite, much tougher). */
    static final float GUARDIAN_CHANCE = 1f / 60;
    public static final String GUARDIAN_TAG = "vigil_guardian";
    private static final net.minecraft.util.Identifier GUARDIAN = net.minecraft.util.Identifier.of("vigil", "room_guardian");

    /** A Guardian: bigger, four times the health, hard hits, a red name; it drops a Dungeon Key or an artifact. */
    static void guardian(MobEntity m, String structure) {
        m.addCommandTag(GUARDIAN_TAG);
        guardianBoost(m, EntityAttributes.MAX_HEALTH, 3.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        guardianBoost(m, EntityAttributes.ATTACK_DAMAGE, 0.8, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        guardianBoost(m, EntityAttributes.MOVEMENT_SPEED, 0.1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        guardianBoost(m, EntityAttributes.SCALE, 0.45, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        guardianBoost(m, EntityAttributes.KNOCKBACK_RESISTANCE, 0.6, EntityAttributeModifier.Operation.ADD_VALUE);
        m.setHealth(m.getMaxHealth());
        m.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE,
                net.minecraft.entity.effect.StatusEffectInstance.INFINITE, 0, false, false));
        StringBuilder where = new StringBuilder();
        for (String w : structure.split("_")) {
            where.append(where.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        m.setCustomName(net.minecraft.text.Text.literal("§4§l☠ §cGuardian of the " + where));
        m.setCustomNameVisible(true);
        m.setPersistent();
    }

    private static void guardianBoost(MobEntity m, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> a,
                                      double v, EntityAttributeModifier.Operation op) {
        var i = m.getAttributeInstance(a);
        if (i != null && i.getModifier(GUARDIAN) == null) {
            i.addPersistentModifier(new EntityAttributeModifier(GUARDIAN, v, op));
        }
    }

    /** What a fallen Guardian leaves: a Dungeon Key mostly, an artifact now and then. */
    static void guardianDrop(MobEntity m) {
        if (!(m.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld w)) {
            return;
        }
        ItemStack loot;
        if (m.getRandom().nextFloat() < 0.35f) {
            var ids = new java.util.ArrayList<>(Artifacts.DEFS.keySet());
            loot = Artifacts.item(ids.get(m.getRandom().nextInt(ids.size())));
        } else {
            loot = DungeonKeys.key();
        }
        w.spawnEntity(new net.minecraft.entity.ItemEntity(w, m.getX(), m.getY() + 0.5, m.getZ(), loot));
        w.playSound(null, m.getX(), m.getY(), m.getZ(), net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                net.minecraft.sound.SoundCategory.HOSTILE, 0.8f, 1.2f);
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

    /** One room mob in this many is one of the new kinds (see {@link #variant}). */
    static final float VARIANT_CHANCE = 1f / 4;
    public static final String VARIANT_TAG = "vigil_variant";
    private static final net.minecraft.util.Identifier VARIANT = net.minecraft.util.Identifier.of("vigil", "variant");

    /**
     * The structures' own kinds of mob, made from the game's (so they work for Bedrock players too): giant Brutes and
     * quick little Runts among the zombies, Broodmothers among the spiders, Sharpshooters among the skeletons, shielded
     * Knights among the wither skeletons, Infernos among the blazes and Hulks of anything else.
     */
    static void variant(MobEntity m) {
        Random r = m.getRandom();
        String name;
        if (m instanceof net.minecraft.entity.mob.SpiderEntity) {
            name = "§2Broodmother";
            vary(m, 1.6, 1.2, 0.4, 0.05);
        } else if (m instanceof ZombieEntity && r.nextBoolean()) {
            name = "§4Brute";
            vary(m, 1.4, 1.3, 0.6, -0.1);
        } else if (m instanceof ZombieEntity) {
            name = "§eRunt";
            vary(m, 0.6, -0.4, 0, 0.4);
        } else if (m instanceof WitherSkeletonEntity) {
            name = "§8Knight";
            vary(m, 1.2, 0.9, 0.3, 0);
            m.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            m.setEquipmentDropChance(EquipmentSlot.OFFHAND, 0f);
        } else if (m instanceof AbstractSkeletonEntity) {
            name = "§bSharpshooter";
            vary(m, 1.0, 0.4, 0.5, 0.2);
            boost(m, EntityAttributes.FOLLOW_RANGE, 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        } else if (m instanceof net.minecraft.entity.mob.BlazeEntity) {
            name = "§6Inferno";
            vary(m, 1.4, 1.0, 0.5, 0);
        } else {
            name = "§5Hulk";
            vary(m, 1.3, 0.8, 0.4, -0.05);
        }
        m.addCommandTag(VARIANT_TAG);
        m.setCustomName(net.minecraft.text.Text.literal(name + " §7" + m.getType().getName().getString()));
        m.setCustomNameVisible(true);
        m.setHealth(m.getMaxHealth());
    }

    private static void vary(MobEntity m, double scale, double health, double damage, double speed) {
        for (var e : List.of(Map.entry(EntityAttributes.SCALE, scale - 1), Map.entry(EntityAttributes.MAX_HEALTH, health),
                Map.entry(EntityAttributes.ATTACK_DAMAGE, damage), Map.entry(EntityAttributes.MOVEMENT_SPEED, speed))) {
            var i = m.getAttributeInstance(e.getKey());
            if (i != null && e.getValue() != 0 && i.getModifier(VARIANT) == null) {
                i.addPersistentModifier(new EntityAttributeModifier(VARIANT, e.getValue(), EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
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
        if (m.getRandom().nextFloat() < GUARDIAN_CHANCE) {
            guardian(m, structure);
        } else if (m.getRandom().nextFloat() < ELITE_CHANCE) {
            elite(m);
        } else if (m.getRandom().nextFloat() < VARIANT_CHANCE) {
            variant(m);
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
