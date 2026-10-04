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
    public static final String FREEZE = "freeze_wand";
    public static final String JUDGE = "judge_gavel";

    static ItemStack make(Item base, String id, String name, String... lore) {
        ItemStack s = Icons.glint(Icons.of(base, name, lore));
        ItemConv.setTag(s, KEY, id);
        s.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(List.of(), List.of(), List.of(com.vylorq.anticheat.util.PackIds.model(id)), List.of()));
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

    public static ItemStack freezeWand() {
        return make(Items.PRISMARINE_SHARD, FREEZE, "§b❄ Freeze Wand",
                "Right-click: freeze every player in your circle", "Sneak + right-click: thaw everyone it froze",
                "Change the circle size in /owner", "or with /owner freezeradius <blocks>", "§8Owner only");
    }

    public static ItemStack judgeGavel() {
        return make(Items.BREEZE_ROD, JUDGE, "§6⚖ Judge's Gavel",
                "Right-click a player: open their player menu", "Sneak + right-click a player: jail them",
                "(pick how long and why)", "§8Owner only");
    }

    public static List<ItemStack> all() {
        return List.of(lightningWand(), launchStick(), healWand(), freezeWand(), judgeGavel());
    }

    /** The gavel on a player: their menu, or (sneaking) the quick jail. */
    private static long lastJudge;

    static void judge(ServerPlayerEntity p, Entity target) {
        // Clicking a player sends both "used on a player" and "used the item": only act once.
        long now = System.currentTimeMillis();
        if (now - lastJudge < 400) {
            return;
        }
        lastJudge = now;
        if (!(target instanceof ServerPlayerEntity t)) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "owner.gavel-players"));
            return;
        }
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(ParticleTypes.CRIT, t.getX(), t.getY() + t.getHeight() + 0.3, t.getZ(), 12, 0.3, 0.2, 0.3, 0.1);
        if (p.isSneaking()) {
            OwnerPowers.sfx(p, "gavel", SoundEvents.BLOCK_ANVIL_LAND, 1.6f);
            com.vylorq.anticheat.gui.OwnerMenu.jail(p, t);
        } else {
            OwnerPowers.sfx(p, "gavel_soft", SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
            com.vylorq.anticheat.gui.InspectMenu.open(p, t.getUuid());
        }
    }

    /** Players (not the owner, not spectators) within {@code r} blocks around the owner, and not far above or below. */
    public static List<ServerPlayerEntity> inCircle(ServerPlayerEntity p, java.util.Collection<ServerPlayerEntity> all, int r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity o : all) {
            if (o == p || o.isSpectator() || com.vylorq.anticheat.perm.Perms.isOwner(o.getUuid()) || o.getEntityWorld() != p.getEntityWorld()) {
                continue;
            }
            double dx = o.getX() - p.getX();
            double dz = o.getZ() - p.getZ();
            if (dx * dx + dz * dz <= (double) r * r && Math.abs(o.getY() - p.getY()) <= Math.max(16, r)) {
                out.add(o);
            }
        }
        return out;
    }

    /** Freeze wand: everyone (but the owner) within the circle can't move until thawed. */
    static void freeze(ServerPlayerEntity p) {
        var st = OwnerPowers.state();
        if (p.isSneaking()) {
            int n = 0;
            for (String s : new java.util.ArrayList<>(st.wandFrozen)) {
                ServerPlayerEntity f = Ac.server().getPlayerManager().getPlayer(java.util.UUID.fromString(s));
                if (f != null) {
                    StaffTools.setFrozen(p, f, false);
                    f.setFrozenTicks(0);
                    f.getEntityWorld().spawnParticles(ParticleTypes.DRIPPING_WATER, f.getX(), f.getY() + 1, f.getZ(), 20, 0.4, 0.6, 0.4, 0);
                    f.getEntityWorld().playSound(null, f.getX(), f.getY(), f.getZ(), SoundEvents.BLOCK_POWDER_SNOW_BREAK, SoundCategory.PLAYERS, 1f, 1f);
                    n++;
                } else {
                    // Offline: thaw them in the saved list (they come back unfrozen).
                    Ac.get().staff.setFrozen(java.util.UUID.fromString(s), false);
                    Ac.markDirty("staff");
                }
                st.wandFrozen.remove(s);
            }
            OwnerPowers.save();
            Msg.actionBar(p, "§b" + Msg.trFor(p, "owner.thawed", n));
            OwnerPowers.sfx(p, "thaw", SoundEvents.BLOCK_POWDER_SNOW_BREAK, 1f);
            return;
        }
        int r = st.freezeRadius;
        int n = 0;
        for (ServerPlayerEntity o : inCircle(p, p.getEntityWorld().getPlayers(), r)) {
            if (!StaffTools.isFrozen(o)) {
                StaffTools.setFrozen(p, o, true);
                st.wandFrozen.add(o.getUuid().toString());
                o.getEntityWorld().playSound(null, o.getX(), o.getY(), o.getZ(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1f, 0.8f);
                o.getEntityWorld().spawnParticles(ParticleTypes.SNOWFLAKE, o.getX(), o.getY() + 1, o.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
                n++;
            }
        }
        OwnerPowers.save();
        OwnerFx.freezeWave(p, r);
        Msg.actionBar(p, "§b" + Msg.trFor(p, "owner.froze", n, r));
        OwnerPowers.sfx(p, "freeze", SoundEvents.BLOCK_GLASS_BREAK, 0.6f);
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
            case FREEZE -> freeze(p);
            case JUDGE -> {
                // Aimed at nobody: look for the player in front (up to 30 blocks), so it works from a distance too.
                Entity t = lookedAt(p, 30);
                if (t != null) {
                    judge(p, t);
                } else {
                    Msg.actionBar(p, "§7" + Msg.trFor(p, "owner.gavel-players"));
                }
            }
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
                    OwnerFx.heartRing(p, p);
                }
                OwnerPowers.sfx(p, "heal", SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
            }
            default -> OwnerCombat.use(p, tool);
        }
        return true;
    }

    /** Right-click on an entity with a tool. */
    public static boolean useOn(ServerPlayerEntity p, ItemStack stack, Entity target) {
        if (JUDGE.equals(toolOf(stack))) {
            if (OwnerPowers.require(p)) {
                OwnerPowers.usedTool();
                judge(p, target);
            }
            return true;
        }
        if (!HEAL.equals(toolOf(stack))) {
            return toolOf(stack) != null && use(p, stack);
        }
        if (!OwnerPowers.require(p)) {
            return true;
        }
        OwnerPowers.usedTool();
        if (target instanceof LivingEntity l) {
            heal(l);
            OwnerFx.heartRing(p, l);
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
        OwnerPowers.usedTool();
        if (OwnerCombat.attack(p, tool, target) || !LAUNCH.equals(tool)) {
            return true;
        }
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
        target.setVelocity(Vec3d.ZERO);
        target.addVelocity(v);
        if (target instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
        }
        w.spawnParticles(ParticleTypes.GUST_EMITTER_SMALL, target.getX(), target.getY() + 0.5, target.getZ(), 1, 0, 0, 0, 0);
        w.spawnParticles(ParticleTypes.CLOUD, target.getX(), target.getY() + 0.2, target.getZ(), 30, 0.4, 0.2, 0.4, 0.15);
        w.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1f, 1f);
        OwnerPowers.sfx(p, "launch", null, 1f);
        OwnerFx.windSpiral(p, target);
        return true;
    }

    /** The player the owner is looking at, within {@code range} blocks. */
    static ServerPlayerEntity lookedAt(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        ServerPlayerEntity best = null;
        double bestDist = range;
        for (ServerPlayerEntity o : p.getEntityWorld().getPlayers()) {
            if (o == p) {
                continue;
            }
            var hit = o.getBoundingBox().expand(0.3).raycast(eye, eye.add(look.multiply(range)));
            if (hit.isPresent()) {
                double d = hit.get().distanceTo(eye);
                if (d < bestDist && com.vylorq.anticheat.feature.Combat.canSee(p, o)) {
                    bestDist = d;
                    best = o;
                }
            }
        }
        return best;
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
        OwnerFx.shockwave(p, at);
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
