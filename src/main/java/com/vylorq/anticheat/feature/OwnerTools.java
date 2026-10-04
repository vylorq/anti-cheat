package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * The owner's tools: lightning wand, launch stick and heal wand. Ordinary items with the owner tag and a custom
 * model (the owner pack draws them; without it they look like a blaze rod, a stick and a ghast tear). Anyone else
 * who ends up with one loses it.
 */
public final class OwnerTools {
    private OwnerTools() {
    }

    public static final String KEY = "vigil_owner";
    public static final String LIGHTNING = "lightning_wand";
    public static final String LAUNCH = "launch_stick";
    public static final String HEAL = "heal_wand";

    private static ItemStack make(Item base, String id, String name, String... lore) {
        ItemStack s = Icons.glint(Icons.of(base, name, lore));
        ItemConv.setTag(s, KEY, id);
        s.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(List.of(), List.of(), List.of("vigil:" + id), List.of()));
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    public static ItemStack lightningWand() {
        return make(Items.BLAZE_ROD, LIGHTNING, "§e⚡ Lightning Wand",
                "Right-click: lightning where you look", "Sneak + right-click: harmless / real", "§8Owner only");
    }

    public static ItemStack launchStick() {
        return make(Items.STICK, LAUNCH, "§b➹ Launch Stick",
                "Hit a player or mob: launch them up", "(they float down gently)", "Sneak + hit: knock them far back", "§8Owner only");
    }

    public static ItemStack healWand() {
        return make(Items.GHAST_TEAR, HEAL, "§d❤ Heal Wand",
                "Right-click a player or mob: heal them", "Right-click the air: heal yourself",
                "Sneak + right-click: heal everyone near you", "§8Owner only");
    }

    public static List<ItemStack> all() {
        return List.of(lightningWand(), launchStick(), healWand());
    }

    public static String toolOf(ItemStack s) {
        return s.isEmpty() ? null : ItemConv.tag(s, KEY);
    }

    /** Anyone but the owner loses owner tools (checked on join and every few seconds). */
    public static void confiscate(ServerPlayerEntity p) {
        if (com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid())) {
            return;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (toolOf(inv.getStack(i)) != null) {
                Staff.log("System", null, "owner-tool-removed", p.getUuid(), p.getGameProfile().name(), toolOf(inv.getStack(i)));
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
    }

    /** Right-click with a tool in hand. @return true when it was an owner tool (handled) */
    public static boolean use(ServerPlayerEntity p, ItemStack stack) {
        String tool = toolOf(stack);
        if (tool == null) {
            return false;
        }
        if (!OwnerPowers.owner(p)) {
            if (!com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid())) {
                stack.setCount(0);
            }
            OwnerPowers.require(p);
            return true;
        }
        OwnerPowers.usedTool();
        switch (tool) {
            case LIGHTNING -> lightning(p);
            case HEAL -> {
                if (p.isSneaking()) {
                    int n = 0;
                    for (ServerPlayerEntity o : p.getEntityWorld().getPlayers()) {
                        if (o.squaredDistanceTo(p) <= 100) {
                            heal(o);
                            n++;
                        }
                    }
                    Msg.actionBar(p, "§d" + Msg.trFor(p, "owner.healed-near", n));
                } else {
                    heal(p);
                }
                OwnerPowers.sfx(p, "heal", SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            }
            default -> {
            }
        }
        return true;
    }

    /** Right-click on an entity with a tool. */
    public static boolean useOn(ServerPlayerEntity p, ItemStack stack, Entity target) {
        if (!HEAL.equals(toolOf(stack))) {
            return toolOf(stack) != null && use(p, stack);
        }
        if (!OwnerPowers.require(p)) {
            return true;
        }
        OwnerPowers.usedTool();
        if (target instanceof LivingEntity l) {
            heal(l);
            OwnerPowers.sfx(p, "heal", SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            Msg.actionBar(p, "§d" + Msg.trFor(p, "owner.healed", target.getName().getString()));
        }
        return true;
    }

    /** Hitting with a tool. @return true when it was an owner tool (the hit itself is cancelled) */
    public static boolean attack(ServerPlayerEntity p, ItemStack stack, Entity target) {
        String tool = toolOf(stack);
        if (tool == null) {
            return false;
        }
        if (!OwnerPowers.require(p)) {
            return true;
        }
        if (!LAUNCH.equals(tool)) {
            return true;
        }
        OwnerPowers.usedTool();
        ServerWorld w = p.getEntityWorld();
        Vec3d v;
        if (p.isSneaking()) {
            Vec3d away = target.getEntityPos().subtract(p.getEntityPos()).multiply(1, 0, 1).normalize();
            v = away.multiply(3.2).add(0, 0.7, 0);
        } else {
            v = new Vec3d(0, 2.4, 0);
            if (target instanceof LivingEntity l) {
                // Up, then a gentle float down instead of a deadly fall.
                l.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, true, true));
            }
        }
        target.setVelocity(v);
        target.velocityModified = true;
        if (target instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
        }
        w.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, target.getX(), target.getY() + 0.5, target.getZ(), 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.CLOUD, target.getX(), target.getY() + 0.2, target.getZ(), 30, 0.4, 0.2, 0.4, 0.15);
        w.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1f, 1f);
        OwnerPowers.sfx(p, "launch", null, 1f);
        return true;
    }

    private static void lightning(ServerPlayerEntity p) {
        var st = OwnerPowers.state();
        if (p.isSneaking()) {
            st.realLightning = !st.realLightning;
            OwnerPowers.save();
            Msg.actionBar(p, "§e" + Msg.trFor(p, st.realLightning ? "owner.lightning-real" : "owner.lightning-harmless"));
            OwnerPowers.sfx(p, "mode", SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), st.realLightning ? 0.8f : 1.6f);
            return;
        }
        ServerWorld w = p.getEntityWorld();
        HitResult hit = p.raycast(200, 1f, false);
        Vec3d at = hit.getPos();
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
        if (bolt == null) {
            return;
        }
        bolt.refreshPositionAfterTeleport(at);
        bolt.setCosmetic(!st.realLightning);
        w.spawnEntity(bolt);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.5, at.z, 40, 0.5, 1.0, 0.5, 0.2);
        OwnerPowers.sfx(p, "zap", null, 1f);
    }

    static void heal(LivingEntity e) {
        e.setHealth(e.getMaxHealth());
        e.extinguish();
        List<net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.effect.StatusEffect>> bad = new ArrayList<>();
        for (StatusEffectInstance i : e.getStatusEffects()) {
            if (i.getEffectType().value().getCategory() == net.minecraft.entity.effect.StatusEffectCategory.HARMFUL) {
                bad.add(i.getEffectType());
            }
        }
        bad.forEach(e::removeStatusEffect);
        if (e instanceof ServerPlayerEntity sp) {
            sp.getHungerManager().setFoodLevel(20);
            sp.getHungerManager().setSaturationLevel(10f);
        }
        if (e.getEntityWorld() instanceof ServerWorld w) {
            w.spawnParticles(ParticleTypes.HEART, e.getX(), e.getY() + e.getHeight() + 0.3, e.getZ(), 8, 0.4, 0.3, 0.4, 0.05);
            w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, e.getX(), e.getY() + 1, e.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
        }
    }

    // ---------------------------------------------------------------- repair and items

    public static void repair(ServerPlayerEntity p, boolean all) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        int n = 0;
        if (all) {
            var inv = p.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                n += fix(inv.getStack(i));
            }
        } else {
            n = fix(p.getMainHandStack());
        }
        Msg.send(p, "owner.repaired", n);
        OwnerPowers.sfx(p, "repair", SoundEvents.BLOCK_ANVIL_USE, 1.6f);
    }

    private static int fix(ItemStack s) {
        if (s.isEmpty() || !s.isDamageable() || s.getDamage() == 0) {
            return 0;
        }
        s.setDamage(0);
        return 1;
    }

    public static void give(ServerPlayerEntity p, ItemStack s) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        p.getInventory().offerOrDrop(s.copy());
        Staff.log(p, "owner-item", p.getUuid(), p.getGameProfile().name(), s.getCount() + "x " + Mc.itemId(s.getItem()));
        OwnerPowers.sfx(p, "give", SoundEvents.ENTITY_ITEM_PICKUP, 1f);
    }
}
