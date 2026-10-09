package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.StructureKeys;

/**
 * Trial chambers are rare and hard, and the mace is a real prize:
 * <ul>
 *   <li>They generate about twenty times further apart in new land
 *   (data/minecraft/worldgen/structure_set/trial_chambers.json).</li>
 *   <li>Every hostile mob inside one has far more health, hits harder and moves faster.</li>
 *   <li>A Heavy Core (what a mace is made from) is kept only one time in {@link #CORE_ODDS} that a vault or chest
 *   would give one; otherwise it's a few diamonds instead.</li>
 * </ul>
 */
public final class TrialChambers {
    private TrialChambers() {
    }

    static final int CORE_ODDS = 25;
    static final String TAG = "vigil_trial_buffed";
    static final double HEALTH = 1.0;     // +100%
    static final double DAMAGE = 0.75;    // +75%
    static final double SPEED = 0.2;      // +20%

    private static void buff(MobEntity m) {
        m.addCommandTag(TAG);
        add(m, EntityAttributes.MAX_HEALTH, "health", HEALTH);
        add(m, EntityAttributes.ATTACK_DAMAGE, "damage", DAMAGE);
        add(m, EntityAttributes.MOVEMENT_SPEED, "speed", SPEED);
        add(m, EntityAttributes.ARMOR, "armor", 0);
        var armor = m.getAttributeInstance(EntityAttributes.ARMOR);
        if (armor != null) {
            armor.setBaseValue(armor.getBaseValue() + 6);
        }
        var kb = m.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE);
        if (kb != null) {
            kb.setBaseValue(Math.max(kb.getBaseValue(), 0.4));
        }
        m.setHealth(m.getMaxHealth());
    }

    private static void add(MobEntity m, RegistryEntry<EntityAttribute> a, String id, double amount) {
        var inst = m.getAttributeInstance(a);
        Identifier key = Identifier.of("vigil", "trial_" + id);
        if (inst != null && amount != 0 && !inst.hasModifier(key)) {
            inst.addPersistentModifier(new EntityAttributeModifier(key, amount, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    /** Whether this spot is inside a trial chamber. */
    static boolean inChamber(ServerWorld w, net.minecraft.util.math.BlockPos pos) {
        if (pos.getY() > 10 || !World.OVERWORLD.equals(w.getRegistryKey())) {
            return false;
        }
        var structure = w.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.STRUCTURE).get(StructureKeys.TRIAL_CHAMBERS);
        return structure != null && w.getStructureAccessor().getStructureContaining(pos, structure).hasChildren();
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (Ac.running() && e instanceof MobEntity m && (e instanceof HostileEntity || e.getType() == net.minecraft.entity.EntityType.BREEZE
                    || e.getType() == net.minecraft.entity.EntityType.SLIME) && !e.getCommandTags().contains(TAG)
                    && inChamber(w, e.getBlockPos())) {
                buff(m);
            }
        });
        // Heavy Cores: almost never.
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            if (!Ac.running()) {
                return;
            }
            for (int i = 0; i < drops.size(); i++) {
                if (drops.get(i).isOf(Items.HEAVY_CORE) && context.getRandom().nextInt(CORE_ODDS) != 0) {
                    drops.set(i, new ItemStack(Items.DIAMOND, 2 + context.getRandom().nextInt(3)));
                }
            }
        });
    }
}
