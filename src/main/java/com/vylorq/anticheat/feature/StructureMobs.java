package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EquipmentSlot;
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

    /** Helmet, chestplate, leggings, boots (null = none, an int = leather dyed that colour), then the melee weapon. */
    private record Kit(Object head, Object chest, Object legs, Object feet, Item weapon) {
    }

    private static final Map<String, Kit> KITS = Map.of(
            "sunken_vault", new Kit(Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, 0x2A6560, Items.IRON_BOOTS, Items.TRIDENT),
            "buried_vault", new Kit(Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.IRON_SWORD),
            "sky_citadel", new Kit(Items.GOLDEN_HELMET, Items.CHAINMAIL_CHESTPLATE, 0xECE6DF, Items.IRON_BOOTS, Items.IRON_SWORD),
            "nether_forge", new Kit(Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.IRON_LEGGINGS, Items.GOLDEN_BOOTS, Items.IRON_AXE),
            "desert_tomb", new Kit(Items.GOLDEN_HELMET, 0xC4AD80, 0xC4AD80, Items.CHAINMAIL_BOOTS, Items.GOLDEN_SWORD),
            "frozen_bastion", new Kit(Items.IRON_HELMET, 0x8FD8F0, 0x6FB8E0, 0xBFF4FF, Items.IRON_AXE),
            "overgrown_labyrinth", new Kit(0x2F6E20, 0x3F6328, 0x2F6E20, Items.CHAINMAIL_BOOTS, Items.STONE_SWORD),
            "watchers_hollow", new Kit(Items.CHAINMAIL_HELMET, 0x15101C, 0x15101C, 0x15101C, Items.IRON_SWORD));

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (Ac.running() && e instanceof MobEntity m && !m.getCommandTags().contains(ARMED)) {
                for (String t : m.getCommandTags()) {
                    if (t.startsWith(TAG)) {
                        arm(m, t.substring(TAG.length()));
                        break;
                    }
                }
            }
        });
    }

    private static ItemStack piece(Object o, Item leather) {
        if (o instanceof Item i) {
            return new ItemStack(i);
        }
        ItemStack s = new ItemStack(leather);
        s.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent((Integer) o));
        return s;
    }

    /** Dresses one mob (once). Only mobs that show armour get it; skeletons keep their bow. */
    static void arm(MobEntity m, String structure) {
        m.addCommandTag(ARMED);
        Kit k = KITS.get(structure);
        boolean skeleton = m instanceof AbstractSkeletonEntity && !(m instanceof WitherSkeletonEntity);
        if (k == null || !(m instanceof ZombieEntity || m instanceof AbstractSkeletonEntity)) {
            return;
        }
        Random r = m.getRandom();
        Object[] parts = {k.head(), k.chest(), k.legs(), k.feet()};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        Item[] leather = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
        for (int i = 0; i < 4; i++) {
            // Most pieces, not always all of them: every mob looks a little different.
            if (parts[i] != null && r.nextFloat() < 0.8f) {
                m.equipStack(slots[i], piece(parts[i], leather[i]));
                m.setEquipmentDropChance(slots[i], 0.04f);
            }
        }
        m.equipStack(EquipmentSlot.MAINHAND, new ItemStack(skeleton ? Items.BOW : k.weapon()));
        m.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.04f);
    }
}
