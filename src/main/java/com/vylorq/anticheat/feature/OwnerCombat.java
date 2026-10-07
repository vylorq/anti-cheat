package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The owner's combat side: six weapons (Thor's hammer, flame sword, frost bow, blast bow, meteor staff, disarm
 * gloves), the combat toggles (one-punch, lifesteal, mega knockback, no cooldown, force field) and three instant
 * actions (berserk, mob wipe, smite). All of it is owner-only; it hits real players too.
 */
public final class OwnerCombat {
    private OwnerCombat() {
    }

    public static final String THOR = "thor_hammer";
    public static final String FLAME = "flame_sword";
    public static final String FROST_BOW = "frost_bow";
    public static final String BLAST_BOW = "blast_bow";
    public static final String METEOR = "meteor_staff";
    public static final String DISARM = "disarm_gloves";
    public static final String GODSLAYER = "godslayer";

    private static final DustParticleEffect STORM = new DustParticleEffect(0x7FD8FF, 1.6f);
    private static final DustParticleEffect STORM_GOLD = new DustParticleEffect(0xFFE27A, 1.3f);
    private static final DustParticleEffect EMBER = new DustParticleEffect(0xFF7A1A, 1.4f);
    private static final DustParticleEffect EMBER_DARK = new DustParticleEffect(0xB0200C, 1.8f);
    private static final DustParticleEffect FROST = new DustParticleEffect(0xBFF4FF, 1.3f);
    private static final DustParticleEffect BLAST = new DustParticleEffect(0xFFB02E, 2.0f);
    private static final DustParticleEffect BLOOD = new DustParticleEffect(0xE0123A, 1.3f);
    private static final DustParticleEffect FIELD = new DustParticleEffect(0xB76BFF, 1.1f);
    private static final DustParticleEffect RAGE = new DustParticleEffect(0xFF1E1E, 1.5f);
    private static final DustParticleEffect PUNCH = new DustParticleEffect(0xFFFFFF, 2.0f);

    // ---------------------------------------------------------------- the weapons

    public static ItemStack thorHammer() {
        return OwnerTools.make(Items.MACE, THOR, "§b⚒ Thor's Hammer",
                "Right-click: smash the ground (shockwave + lightning)", "Hit: a thunder strike on the target", "§8Owner only");
    }

    public static ItemStack flameSword() {
        return OwnerTools.make(Items.GOLDEN_SWORD, FLAME, "§6🔥 Flame Sword",
                "Hit: heavy damage and sets them ablaze", "Right-click: breathe a wave of fire ahead", "§8Owner only");
    }

    public static ItemStack frostBow() {
        return OwnerTools.make(Items.BOW, FROST_BOW, "§b❄ Frost Bow",
                "Right-click: shoot a frost arrow (no arrows needed)", "Whoever it hits is frozen for 3 seconds", "§8Owner only");
    }

    public static ItemStack blastBow() {
        return OwnerTools.make(Items.BOW, BLAST_BOW, "§c✹ Blast Bow",
                "Right-click: shoot an exploding arrow (no arrows needed)", "Blasts everyone near where it lands", "(never breaks blocks)", "§8Owner only");
    }

    public static ItemStack meteorStaff() {
        return OwnerTools.make(Items.MAGMA_CREAM, METEOR, "§4☄ Meteor Staff",
                "Right-click: call a meteor down where you look", "Random size: small, big, huge... or colossal",
                "Breaks blocks (never in the lobby or protected claims)", "§8Owner only");
    }

    public static ItemStack disarmGloves() {
        return OwnerTools.make(Items.LEATHER, DISARM, "§f✋ Disarm Gloves",
                "Hit a player or mob: knock the item out of their hand", "§8Owner only");
    }

    public static ItemStack godslayer() {
        return OwnerTools.make(Items.NETHERITE_SWORD, GODSLAYER, "§4☠ Doom Blade",
                "Hit: kills anything in one blow", "Players (even in creative), mobs and bosses", "§8Owner only");
    }

    public static List<ItemStack> weapons() {
        return List.of(thorHammer(), flameSword(), frostBow(), blastBow(), meteorStaff(), disarmGloves(), OrbitalStrike.item(), godslayer());
    }

    // ---------------------------------------------------------------- helpers

    /** Set while owner abilities deal damage, so claim / team PvP rules let it through. */
    private static int dealing;

    public static boolean dealing() {
        return dealing > 0;
    }

    /** Whether an entity can be hit by owner abilities (not the owner, traders, shops, armor stands or spectators). */
    static boolean target(ServerPlayerEntity owner, Entity e) {
        if (!(e instanceof LivingEntity l) || e == owner || !l.isAlive() || e instanceof ArmorStandEntity) {
            return false;
        }
        if (Traders.isTrader(e) || Shops.isShop(e) || Booths.isBooth(e)) {
            return false;
        }
        return !(e instanceof ServerPlayerEntity sp) || (!sp.isSpectator() && !sp.isCreative()
                && !com.vylorq.anticheat.perm.Perms.isOwner(sp.getUuid()));
    }

    /** For the game tests: one of the owner's ability hits. */
    public static void hurtForTest(ServerPlayerEntity owner, LivingEntity e, float amount) {
        hurt(owner, e, amount);
    }

    static void hurt(ServerPlayerEntity owner, LivingEntity e, float amount) {
        dealing++;
        try {
            // Bosses: the owner's weapons go straight through their armour and their scaled-down hits.
            var source = Bosses.isBoss(e) ? owner.getDamageSources().indirectMagic(owner, owner)
                    : owner.getDamageSources().playerAttack(owner);
            e.damage(owner.getEntityWorld(), source, amount);
        } finally {
            dealing--;
        }
    }

    /** Sets an entity's motion (and tells a player's game right away). */
    static void push(Entity e, Vec3d v) {
        e.setVelocity(Vec3d.ZERO);
        e.addVelocity(v);
        if (e instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
        }
    }

    static Vec3d away(Vec3d from, Entity e, double strength, double up) {
        Vec3d d = e.getEntityPos().subtract(from).multiply(1, 0, 1);
        if (d.lengthSquared() < 1e-4) {
            d = new Vec3d(Math.random() - 0.5, 0, Math.random() - 0.5);
        }
        return d.normalize().multiply(strength).add(0, up, 0);
    }

    static List<LivingEntity> around(ServerPlayerEntity owner, Vec3d c, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : owner.getEntityWorld().getOtherEntities(owner, new Box(c, c).expand(r))) {
            if (target(owner, e) && e.getEntityPos().squaredDistanceTo(c) <= r * r) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    static void bolt(ServerWorld w, Vec3d at, boolean real, ServerPlayerEntity by) {
        LightningEntity b = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
        if (b == null) {
            return;
        }
        b.refreshPositionAfterTeleport(at);
        b.setCosmetic(!real);
        if (real) {
            b.setChanneler(by);
        }
        w.spawnEntity(b);
    }

    /** A custom (owner pack) sound everyone nearby hears if they have the pack. */
    static void sound(ServerWorld w, Vec3d at, String name, float volume) {
        var entry = RegistryEntry.of(SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound(name)));
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o.squaredDistanceTo(at) < 96 * 96) {
                o.networkHandler.sendPacket(new PlaySoundS2CPacket(entry, SoundCategory.PLAYERS, at.x, at.y, at.z, volume, 1f, o.getRandom().nextLong()));
            }
        }
    }

    static void ring(ServerWorld w, DustParticleEffect e, Vec3d c, double r, int points) {
        for (int i = 0; i < points; i++) {
            double a = i * 2 * Math.PI / points;
            w.spawnParticles(e, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private static long lastUse;

    // ---------------------------------------------------------------- right-click

    /** Right-click with a combat weapon. @return true when it was one */
    static boolean use(ServerPlayerEntity p, String tool) {
        switch (tool) {
            case THOR, FLAME, FROST_BOW, BLAST_BOW, METEOR, DISARM -> {
            }
            case OrbitalStrike.TOOL -> {
                OrbitalStrike.use(p);
                return true;
            }
            default -> {
                return false;
            }
        }
        // A click on a block sends "used on a block" and "used the item": only act once.
        long now = System.currentTimeMillis();
        if (now - lastUse < 180) {
            return true;
        }
        lastUse = now;
        switch (tool) {
            case THOR -> smash(p);
            case FLAME -> flameWave(p);
            case FROST_BOW -> shoot(p, Shot.FROST);
            case BLAST_BOW -> shoot(p, Shot.BLAST);
            case METEOR -> meteor(p);
            case DISARM -> Msg.actionBar(p, "§7" + Msg.trFor(p, "owner.disarm-hit"));
            default -> {
            }
        }
        return true;
    }

    /** Hitting with a combat weapon. @return true when it was one (the vanilla hit is replaced) */
    static boolean attack(ServerPlayerEntity p, String tool, Entity target) {
        switch (tool) {
            case THOR -> thorHit(p, target);
            case FLAME -> flameHit(p, target);
            case DISARM -> disarm(p, target);
            case GODSLAYER -> slay(p, target);
            case FROST_BOW, BLAST_BOW, METEOR -> {
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- the Doom Blade

    /** For the game tests. */
    public static void slayForTest(ServerPlayerEntity p, Entity target) {
        slay(p, target);
    }

    /** One blow, and whatever it hits is dead (creative players too): a boss skips straight past its phases, a totem doesn't save anyone. */
    private static void slay(ServerPlayerEntity p, Entity target) {
        Entity t = target instanceof net.minecraft.entity.boss.dragon.EnderDragonPart part ? part.owner : target;
        if (!(t instanceof LivingEntity e) || !e.isAlive() || (e instanceof ServerPlayerEntity sp && sp.isSpectator())) {
            return;
        }
        if (e instanceof ServerPlayerEntity victim && LobbyFeature.in(victim) && com.vylorq.anticheat.Ac.config().lobby.noPvp) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        double x = e.getX();
        double y = e.getY() + e.getHeight() / 2;
        double z = e.getZ();
        dealing++;
        try {
            e.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
            if (e.isAlive()) {
                e.kill(w);
            }
        } finally {
            dealing--;
        }
        w.spawnParticles(BLOOD, x, y, z, 60, 0.4, 0.6, 0.4, 0);
        w.spawnParticles(ParticleTypes.SOUL, x, y, z, 20, 0.3, 0.5, 0.3, 0.05);
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 1, 0, 0, 0, 0);
        w.playSound(null, x, y, z, SoundEvents.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.PLAYERS, 0.8f, 1.4f);
        w.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 1f, 0.6f);
    }

    // ---------------------------------------------------------------- Thor's hammer

    private static void smash(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d c = p.getEntityPos();
        int hit = 0;
        for (LivingEntity e : around(p, c, 7)) {
            double d = Math.sqrt(e.getEntityPos().squaredDistanceTo(c));
            hurt(p, e, (float) Math.max(4, 14 - d * 1.4));
            push(e, away(c, e, 2.4 - d * 0.18, 0.9));
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1, e.getZ(), 14, 0.3, 0.6, 0.3, 0.3);
            hit++;
        }
        bolt(w, c, false, p);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.random();
            bolt(w, c.add(Math.cos(a) * 4.5, 0, Math.sin(a) * 4.5), false, p);
        }
        w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.3, c.z, 3, 1.2, 0.1, 1.2, 0);
        UUID id = p.getUuid();
        for (int step = 0; step < 9; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                double r = 0.8 + s * 0.85;
                ring(w, STORM, c.add(0, 0.15, 0), r, 18 + s * 6);
                ring(w, STORM_GOLD, c.add(0, 0.45, 0), r * 0.85, 10 + s * 3);
                if (s % 2 == 0) {
                    w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 0.1, c.z, 10, r * 0.5, 0.05, r * 0.5, 0.05);
                }
            });
        }
        Display.flat(w, c.add(0, 0.06, 0), "rune_circle", 9f, 26);
        sound(w, c, "thor_smash", 2f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.2f, 0.7f);
        Msg.actionBar(p, "§b⚒ " + Msg.trFor(p, "owner.smash", hit));
    }

    private static void thorHit(ServerPlayerEntity p, Entity t) {
        if (!target(p, t)) {
            return;
        }
        LivingEntity e = (LivingEntity) t;
        ServerWorld w = p.getEntityWorld();
        hurt(p, e, 14);
        push(e, away(p.getEntityPos(), e, 1.6, 0.6));
        bolt(w, e.getEntityPos(), false, p);
        w.spawnParticles(STORM, e.getX(), e.getY() + 1, e.getZ(), 30, 0.4, 0.8, 0.4, 0);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1, e.getZ(), 30, 0.4, 0.8, 0.4, 0.4);
        sound(w, e.getEntityPos(), "thor_hit", 1.5f);
    }

    // ---------------------------------------------------------------- flame sword

    private static void flameHit(ServerPlayerEntity p, Entity t) {
        if (!target(p, t)) {
            return;
        }
        LivingEntity e = (LivingEntity) t;
        ServerWorld w = p.getEntityWorld();
        hurt(p, e, 12);
        e.setFireTicks(Math.max(e.getFireTicks(), 160));
        push(e, away(p.getEntityPos(), e, 0.6, 0.25));
        flameArc(p);
        w.spawnParticles(ParticleTypes.FLAME, e.getX(), e.getY() + 1, e.getZ(), 30, 0.35, 0.6, 0.35, 0.06);
        w.spawnParticles(EMBER_DARK, e.getX(), e.getY() + 1, e.getZ(), 16, 0.35, 0.6, 0.35, 0);
        sound(w, e.getEntityPos(), "flame_hit", 1.3f);
        w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.8f, 1.3f);
    }

    /** A crescent of fire in front of the owner (every swing with the flame sword). */
    public static void flameArc(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d eye = p.getEyePos().add(0, -0.35, 0);
        float yaw = p.getYaw();
        for (int i = -6; i <= 6; i++) {
            double a = Math.toRadians(yaw + i * 9 + 90);
            double x = eye.x + Math.cos(a) * 1.7;
            double z = eye.z + Math.sin(a) * 1.7;
            double y = eye.y + i * 0.04;
            w.spawnParticles(i % 2 == 0 ? ParticleTypes.FLAME : EMBER, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    /** Right-click: a cone of fire up to 9 blocks ahead. */
    private static void flameWave(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f);
        int hit = 0;
        for (LivingEntity e : around(p, eye, 9)) {
            Vec3d to = e.getEntityPos().add(0, e.getHeight() / 2, 0).subtract(eye);
            if (to.normalize().dotProduct(look) > 0.8) {
                hurt(p, e, 6);
                e.setFireTicks(Math.max(e.getFireTicks(), 120));
                hit++;
            }
        }
        for (int step = 0; step < 7; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                for (int k = 0; k < 10; k++) {
                    double dist = 1.2 + s * 1.2 + Math.random();
                    Vec3d at = eye.add(look.multiply(dist)).add((Math.random() - 0.5) * dist * 0.5, (Math.random() - 0.5) * dist * 0.35,
                            (Math.random() - 0.5) * dist * 0.5);
                    w.spawnParticles(k % 3 == 0 ? EMBER : ParticleTypes.FLAME, at.x, at.y, at.z, 1, 0, 0, 0, 0.02);
                }
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, eye.x + look.x * (2 + s), eye.y + look.y * (2 + s), eye.z + look.z * (2 + s),
                        2, 0.2, 0.2, 0.2, 0.01);
            });
        }
        sound(w, eye, "flame_wave", 1.4f);
        w.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.9f, 0.8f);
        if (hit > 0) {
            Msg.actionBar(p, "§6🔥 " + Msg.trFor(p, "owner.burned", hit));
        }
    }

    // ---------------------------------------------------------------- bows

    enum Shot { FROST, BLAST }

    private static final class Flying {
        final UUID owner;
        final Shot kind;
        Vec3d last;
        int still;
        int age;

        Flying(UUID owner, Shot kind, Vec3d at) {
            this.owner = owner;
            this.kind = kind;
            this.last = at;
        }
    }

    private static final Map<ArrowEntity, Flying> ARROWS = new ConcurrentHashMap<>();

    private static void shoot(ServerPlayerEntity p, Shot kind) {
        ServerWorld w = p.getEntityWorld();
        ItemStack bow = p.getMainHandStack();
        ArrowEntity a = new ArrowEntity(w, p, new ItemStack(Items.ARROW), bow.copy());
        a.setVelocity(p, p.getPitch(), p.getYaw(), 0f, 3.4f, 0.2f);
        a.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        a.setCritical(true);
        if (kind == Shot.BLAST) {
            a.setFireTicks(2000);
        }
        w.spawnEntity(a);
        ARROWS.put(a, new Flying(p.getUuid(), kind, a.getEntityPos()));
        Vec3d m = p.getEyePos().add(p.getRotationVec(1f).multiply(0.8));
        w.spawnParticles(kind == Shot.FROST ? FROST : BLAST, m.x, m.y, m.z, 10, 0.12, 0.12, 0.12, 0);
        sound(w, p.getEntityPos(), kind == Shot.FROST ? "frost_shot" : "blast_shot", 1f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 0.8f, kind == Shot.FROST ? 1.6f : 0.8f);
    }

    private static void arrows() {
        Iterator<Map.Entry<ArrowEntity, Flying>> it = ARROWS.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            ArrowEntity a = en.getKey();
            Flying f = en.getValue();
            f.age++;
            ServerWorld w = (ServerWorld) a.getEntityWorld();
            ServerPlayerEntity owner = Ac.server().getPlayerManager().getPlayer(f.owner);
            if (owner == null || f.age > 200) {
                it.remove();
                a.discard();
                continue;
            }
            if (a.isRemoved()) {
                // Hit someone: the arrow is gone. Land where it was heading.
                it.remove();
                impact(owner, w, f.last.add(a.getVelocity().multiply(0.5)), f.kind, true);
                continue;
            }
            Vec3d now = a.getEntityPos();
            if (now.squaredDistanceTo(f.last) < 1e-4 && f.age > 1) {
                if (++f.still >= 1) {
                    it.remove();
                    a.discard();
                    impact(owner, w, now, f.kind, false);
                    continue;
                }
            } else {
                f.still = 0;
            }
            f.last = now;
            // Trail
            w.spawnParticles(f.kind == Shot.FROST ? FROST : BLAST, now.x, now.y, now.z, 2, 0.05, 0.05, 0.05, 0);
            w.spawnParticles(f.kind == Shot.FROST ? ParticleTypes.SNOWFLAKE : ParticleTypes.SMALL_FLAME, now.x, now.y, now.z, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    private static void impact(ServerPlayerEntity owner, ServerWorld w, Vec3d at, Shot kind, boolean hitEntity) {
        if (owner.getEntityWorld() != w) {
            return;
        }
        if (kind == Shot.FROST) {
            for (LivingEntity e : around(owner, at, hitEntity ? 2.2 : 2.8)) {
                frostbite(owner, e);
            }
            w.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.5, at.z, 50, 0.8, 0.8, 0.8, 0.05);
            w.spawnParticles(FROST, at.x, at.y + 0.5, at.z, 40, 0.9, 0.7, 0.9, 0);
            ring(w, FROST, at.add(0, 0.1, 0), 2.4, 28);
            sound(w, at, "frost_hit", 1.3f);
            w.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1f, 1.4f);
        } else {
            for (LivingEntity e : around(owner, at, 4.5)) {
                double d = Math.sqrt(e.getEntityPos().squaredDistanceTo(at));
                hurt(owner, e, (float) Math.max(3, 12 - d * 2));
                push(e, away(at, e, 1.8 - d * 0.25, 0.7));
                e.setFireTicks(Math.max(e.getFireTicks(), 60));
            }
            w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 1, 0, 0, 0, 0);
            w.spawnParticles(BLAST, at.x, at.y + 0.5, at.z, 60, 1.4, 1.0, 1.4, 0);
            w.spawnParticles(ParticleTypes.FLAME, at.x, at.y + 0.5, at.z, 40, 1.0, 0.6, 1.0, 0.15);
            for (int step = 0; step < 6; step++) {
                int s = step;
                OwnerPowers.later(step, () -> ring(w, BLAST, at.add(0, 0.2, 0), 0.6 + s * 0.8, 14 + s * 5));
            }
            sound(w, at, "blast_boom", 2f);
            w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1f, 1.2f);
        }
    }

    private static final Identifier FROST_ID = Identifier.of("vigil", "frost_bow");

    /** Can't walk or jump for 3 seconds (temporary modifiers: never saved, so a restart can't leave anyone stuck). */
    public static void frostbite(ServerPlayerEntity owner, LivingEntity e) {
        hurt(owner, e, 3);
        EntityAttributeInstance spd = e.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        EntityAttributeInstance jump = e.getAttributeInstance(EntityAttributes.JUMP_STRENGTH);
        for (EntityAttributeInstance i : new EntityAttributeInstance[]{spd, jump}) {
            if (i != null) {
                i.removeModifier(FROST_ID);
                i.addTemporaryModifier(new EntityAttributeModifier(FROST_ID, -1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
        }
        push(e, Vec3d.ZERO);
        e.setFrozenTicks(e.getMinFreezeDamageTicks() - 1);
        OwnerPowers.later(60, () -> thaw(e));
    }

    private static void thaw(LivingEntity e) {
        for (var attr : List.of(EntityAttributes.MOVEMENT_SPEED, EntityAttributes.JUMP_STRENGTH)) {
            EntityAttributeInstance i = e.getAttributeInstance(attr);
            if (i != null) {
                i.removeModifier(FROST_ID);
            }
        }
        e.setFrozenTicks(0);
        if (!e.isRemoved() && e.getEntityWorld() instanceof ServerWorld w) {
            w.spawnParticles(ParticleTypes.ITEM_SNOWBALL, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.6, 0.3, 0.1);
        }
    }

    // ---------------------------------------------------------------- meteor

    private record Size(String name, float power, float scale, double weight) {
    }

    private static final Size[] SIZES = {
            new Size("small", 3f, 1.4f, 30), new Size("big", 5f, 2.4f, 32), new Size("huge", 7.5f, 3.6f, 25), new Size("colossal", 11f, 5.5f, 13)};

    static Size randomSize() {
        double total = 0;
        for (Size s : SIZES) {
            total += s.weight();
        }
        double r = Math.random() * total;
        for (Size s : SIZES) {
            r -= s.weight();
            if (r <= 0) {
                return s;
            }
        }
        return SIZES[SIZES.length - 1];
    }

    private static void meteor(ServerPlayerEntity p) {
        HitResult hit = p.raycast(160, 1f, false);
        if (hit.getType() == HitResult.Type.MISS) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "owner.meteor-miss"));
            return;
        }
        ServerWorld w = p.getEntityWorld();
        Vec3d target = hit.getPos();
        Size size = randomSize();
        double ang = Math.random() * Math.PI * 2;
        Vec3d start = target.add(Math.cos(ang) * 22, 55, Math.sin(ang) * 22);
        int ticks = 34;
        UUID display = Display.cube(w, start, "meteor", size.scale());
        sound(w, p.getEntityPos(), "meteor_cast", 1.2f);
        sound(w, target, "meteor_fall", 3f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 1f, 0.5f);
        Msg.actionBar(p, "§4☄ " + Msg.trFor(p, "owner.meteor-size", Msg.trFor(p, "owner.meteor." + size.name())));
        // Warning circle where it will land.
        for (int s = 0; s < ticks; s += 4) {
            int k = s;
            OwnerPowers.later(s, () -> ring(w, EMBER_DARK, target.add(0, 0.15, 0), size.power() * 0.9 * (1 - k / (double) ticks * 0.5), 30));
        }
        for (int step = 1; step <= ticks; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                double f = s / (double) ticks;
                double ease = f * f;
                Vec3d at = start.lerp(target, ease);
                Display.move(w, display, at, s * 14f);
                double spread = size.scale() * 0.35;
                w.spawnParticles(ParticleTypes.FLAME, at.x, at.y, at.z, (int) (6 * size.scale()), spread, spread, spread, 0.04);
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.5, at.z, (int) (3 * size.scale()), spread, spread, spread, 0.02);
                w.spawnParticles(EMBER, at.x, at.y, at.z, (int) (4 * size.scale()), spread, spread, spread, 0);
                if (s % 3 == 0) {
                    w.spawnParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 2, spread, spread, spread, 0);
                }
                if (s == ticks) {
                    Display.remove(w, display);
                    land(p, w, target, size);
                }
            });
        }
        Staff.log(p, "owner-meteor", null, null, size.name() + " at " + net.minecraft.util.math.BlockPos.ofFloored(target).toShortString());
    }

    private static void land(ServerPlayerEntity p, ServerWorld w, Vec3d at, Size size) {
        WorldGuard.ownerBlast(true);
        try {
            w.createExplosion(p, at.x, at.y, at.z, size.power(), true, World.ExplosionSourceType.TNT);
        } finally {
            WorldGuard.ownerBlast(false);
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, (int) Math.max(1, size.scale() / 1.5), size.scale(), 0.5, size.scale(), 0);
        w.spawnParticles(ParticleTypes.LAVA, at.x, at.y + 1, at.z, (int) (20 * size.scale()), size.scale(), 1, size.scale(), 0);
        w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y + 1, at.z, (int) (10 * size.scale()), size.scale(), 1.5, size.scale(), 0.03);
        for (int step = 0; step < 10; step++) {
            int s = step;
            OwnerPowers.later(step, () -> {
                double r = 0.8 + s * size.power() * 0.25;
                ring(w, EMBER, at.add(0, 0.3, 0), r, (int) Math.min(120, 16 + r * 6));
                ring(w, EMBER_DARK, at.add(0, 0.8, 0), r * 0.8, (int) Math.min(80, 10 + r * 4));
            });
        }
        sound(w, at, "meteor_impact", 4f);
        // Everyone close by feels it.
        for (ServerPlayerEntity o : w.getPlayers()) {
            double d = o.squaredDistanceTo(at);
            if (d < 48 * 48) {
                o.networkHandler.sendPacket(new PlaySoundS2CPacket(SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS,
                        at.x, at.y, at.z, 2f, 0.55f, o.getRandom().nextLong()));
            }
        }
    }

    // ---------------------------------------------------------------- disarm

    private static void disarm(ServerPlayerEntity p, Entity t) {
        if (!target(p, t)) {
            return;
        }
        LivingEntity e = (LivingEntity) t;
        Hand hand = !e.getMainHandStack().isEmpty() ? Hand.MAIN_HAND : !e.getOffHandStack().isEmpty() ? Hand.OFF_HAND : null;
        ServerWorld w = p.getEntityWorld();
        if (hand == null) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "owner.disarm-empty", t.getName().getString()));
            return;
        }
        ItemStack s = e.getStackInHand(hand).copy();
        e.setStackInHand(hand, ItemStack.EMPTY);
        if (e instanceof MobEntity mob) {
            // Mobs don't pick it back up straight away either.
            mob.setCanPickUpLoot(false);
        }
        ItemEntity drop = new ItemEntity(w, e.getX(), e.getEyeY() - 0.3, e.getZ(), s);
        Vec3d v = away(p.getEntityPos(), e, 0.45, 0.35);
        drop.setVelocity(v);
        drop.setPickupDelay(50);
        w.spawnEntity(drop);
        if (e instanceof ServerPlayerEntity sp) {
            sp.playerScreenHandler.syncState();
            Msg.actionBar(sp, "§c" + Msg.trFor(sp, "owner.disarmed-you"));
        }
        w.spawnParticles(PUNCH, e.getX(), e.getEyeY() - 0.3, e.getZ(), 16, 0.25, 0.25, 0.25, 0);
        w.spawnParticles(ParticleTypes.CRIT, e.getX(), e.getEyeY() - 0.3, e.getZ(), 20, 0.3, 0.3, 0.3, 0.4);
        sound(w, e.getEntityPos(), "disarm", 1.3f);
        w.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.8f, 0.6f);
        Msg.actionBar(p, "§f✋ " + Msg.trFor(p, "owner.disarmed", t.getName().getString()));
    }

    // ---------------------------------------------------------------- toggles on normal hits

    /** The owner hit something with an ordinary weapon (or a fist): one-punch, lifesteal, mega knockback. */
    public static void onMelee(ServerPlayerEntity p, Entity t) {
        if (!OwnerPowers.owner(p) || !target(p, t)) {
            return;
        }
        var s = OwnerPowers.state();
        if (!s.onePunch && !s.lifesteal && !s.megaKnockback) {
            return;
        }
        LivingEntity e = (LivingEntity) t;
        OwnerPowers.usedTool();
        ServerWorld w = p.getEntityWorld();
        // Right after the normal hit lands.
        OwnerPowers.later(1, () -> {
            if (e.isRemoved()) {
                return;
            }
            if (s.onePunch && e.isAlive()) {
                hurt(p, e, 100000f);
                w.spawnParticles(PUNCH, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 30, 0.4, 0.5, 0.4, 0);
                w.spawnParticles(ParticleTypes.SONIC_BOOM, e.getX(), e.getY() + e.getHeight() / 2, e.getZ(), 1, 0, 0, 0, 0);
                sound(w, e.getEntityPos(), "onepunch_hit", 1.6f);
            }
            if (s.lifesteal) {
                p.heal(5f);
                Vec3d from = e.getEntityPos().add(0, e.getHeight() / 2, 0);
                Vec3d to = p.getEntityPos().add(0, 1, 0);
                for (int i = 0; i <= 10; i++) {
                    Vec3d at = from.lerp(to, i / 10.0);
                    w.spawnParticles(BLOOD, at.x, at.y + Math.sin(i / 10.0 * Math.PI) * 0.5, at.z, 1, 0, 0, 0, 0);
                }
                w.spawnParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2.1, p.getZ(), 2, 0.3, 0.1, 0.3, 0);
                OwnerPowers.sfx(p, "lifesteal_hit", null, 1f);
            }
            if (s.megaKnockback && e.isAlive()) {
                push(e, away(p.getEntityPos(), e, 3.6, 1.0));
                w.spawnParticles(ParticleTypes.GUST, e.getX(), e.getY() + 1, e.getZ(), 1, 0, 0, 0, 0);
                w.spawnParticles(ParticleTypes.CLOUD, e.getX(), e.getY() + 0.5, e.getZ(), 20, 0.3, 0.3, 0.3, 0.2);
                sound(w, e.getEntityPos(), "mega_hit", 1.4f);
            }
        });
    }

    // ---------------------------------------------------------------- instant actions

    private static final Map<UUID, Long> BERSERK = new ConcurrentHashMap<>();

    public static boolean berserk(UUID id) {
        Long until = BERSERK.get(id);
        return until != null && until > System.currentTimeMillis();
    }

    public static void berserk(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        BERSERK.put(p.getUuid(), System.currentTimeMillis() + 30_000);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 1, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600, 1, false, false, true));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 600, 1, false, false, true));
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(RAGE, p.getX(), p.getY() + 1, p.getZ(), 80, 0.6, 1.0, 0.6, 0);
        w.spawnParticles(ParticleTypes.ANGRY_VILLAGER, p.getX(), p.getY() + 2, p.getZ(), 8, 0.6, 0.3, 0.6, 0);
        sound(w, p.getEntityPos(), "berserk", 2f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1f, 1.2f);
        Msg.send(p, "owner.berserk-on");
        Staff.log(p, "owner-berserk", null, null, "");
    }

    public static void mobWipe(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        int r = OwnerPowers.state().wipeRadius;
        ServerWorld w = p.getEntityWorld();
        List<MobEntity> mobs = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(r),
                m -> m instanceof Monster && m.isAlive() && m.squaredDistanceTo(p) <= (double) r * r);
        int n = 0;
        for (MobEntity m : mobs) {
            Vec3d at = m.getEntityPos();
            if (n < 24) {
                int delay = n;
                OwnerPowers.later(delay, () -> bolt(w, at, false, p));
            }
            w.spawnParticles(STORM, at.x, at.y + 1, at.z, 12, 0.3, 0.6, 0.3, 0);
            hurt(p, m, 100000f);
            n++;
        }
        UUID id = p.getUuid();
        for (int step = 0; step < 10; step++) {
            int s = step;
            OwnerPowers.later(step * 2, () -> {
                ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(id);
                if (o != null) {
                    ring(w, STORM, o.getEntityPos().add(0, 0.2, 0), r * (s + 1) / 10.0, (int) Math.min(160, 12 + r * (s + 1) / 2.0));
                }
            });
        }
        sound(w, p.getEntityPos(), "mobwipe", 2f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1f, 0.6f);
        Msg.send(p, "owner.wiped", n, r);
        Staff.log(p, "owner-mobwipe", null, null, n + " mobs, radius " + r);
    }

    public static void smite(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        LivingEntity t = lookedAt(p, 80);
        Vec3d at;
        if (t != null) {
            at = t.getEntityPos();
        } else {
            HitResult hit = p.raycast(120, 1f, false);
            if (hit.getType() == HitResult.Type.MISS) {
                Msg.actionBar(p, "§7" + Msg.trFor(p, "owner.smite-miss"));
                return;
            }
            at = hit.getPos();
        }
        bolt(w, at, true, p);
        bolt(w, at, false, p);
        if (t != null) {
            hurt(p, t, 10);
            Msg.actionBar(p, "§e⚡ " + Msg.trFor(p, "owner.smote", t.getName().getString()));
            Staff.log(p, "owner-smite", t instanceof ServerPlayerEntity sp ? sp.getUuid() : null, t.getName().getString(), "");
        }
        w.spawnParticles(STORM_GOLD, at.x, at.y + 1, at.z, 50, 0.5, 1.5, 0.5, 0);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.3, at.z, 60, 0.8, 0.3, 0.8, 0.5);
        sound(w, at, "smite", 2.5f);
        OwnerPowers.usedTool();
    }

    /** The living thing (player or mob) the owner is looking at. */
    static LivingEntity lookedAt(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos();
        Vec3d end = eye.add(p.getRotationVec(1f).multiply(range));
        LivingEntity best = null;
        double bestDist = range;
        for (Entity e : p.getEntityWorld().getOtherEntities(p, p.getBoundingBox().stretch(end.subtract(eye)).expand(1), x -> target(p, x))) {
            var hit = e.getBoundingBox().expand(0.3).raycast(eye, end);
            if (hit.isPresent()) {
                double d = hit.get().distanceTo(eye);
                if (d < bestDist) {
                    bestDist = d;
                    best = (LivingEntity) e;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        if (!ARROWS.isEmpty()) {
            arrows();
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (!com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid()) || !OwnerPowers.owner(p)) {
                continue;
            }
            if (ticks % 2 == 0 && OwnerPowers.state().forceField && !p.isSpectator()) {
                forceField(p, ticks);
            }
            if (berserk(p.getUuid()) && ticks % 3 == 0) {
                ServerWorld w = p.getEntityWorld();
                double a = ticks * 0.3;
                for (int k = 0; k < 3; k++) {
                    double b = a + k * 2 * Math.PI / 3;
                    w.spawnParticles(RAGE, p.getX() + Math.cos(b) * 0.7, p.getY() + 0.2 + (ticks % 20) / 10.0, p.getZ() + Math.sin(b) * 0.7, 1, 0, 0, 0, 0);
                }
                if (ticks % 30 == 0) {
                    long left = (BERSERK.get(p.getUuid()) - System.currentTimeMillis()) / 1000;
                    Msg.actionBar(p, "§c🔥 " + Msg.trFor(p, "owner.berserk-left", left));
                }
            }
            if (ticks % 4 == 0 && FLAME.equals(OwnerTools.toolOf(p.getMainHandStack()))) {
                p.getEntityWorld().spawnParticles(ParticleTypes.SMALL_FLAME, p.getX(), p.getY() + 1.1, p.getZ(), 1, 0.4, 0.3, 0.4, 0.01);
            }
        }
        if (ticks % 20 == 0) {
            BERSERK.values().removeIf(t -> t < System.currentTimeMillis());
        }
    }

    /** Pushes away everything (players too) within 5 blocks and turns arrows around. */
    private static void forceField(ServerPlayerEntity p, long ticks) {
        ServerWorld w = p.getEntityWorld();
        Vec3d c = p.getEntityPos();
        boolean pushed = false;
        for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(5))) {
            if (e.getEntityPos().squaredDistanceTo(c) > 25) {
                continue;
            }
            if (e instanceof ProjectileEntity pr && pr.getOwner() != p) {
                push(pr, pr.getVelocity().multiply(-0.6));
                continue;
            }
            if (!target(p, e) || (e instanceof net.minecraft.entity.passive.TameableEntity tame && tame.isOwner(p))) {
                continue;
            }
            push(e, away(c, e, 0.75, 0.25));
            w.spawnParticles(FIELD, e.getX(), e.getY() + 1, e.getZ(), 6, 0.3, 0.5, 0.3, 0);
            pushed = true;
        }
        if (ticks % 4 == 0) {
            double a0 = ticks * 0.12;
            for (int i = 0; i < 16; i++) {
                double a = a0 + i * Math.PI / 8;
                double y = c.y + 1 + Math.sin(a * 2) * 0.6;
                w.spawnParticles(FIELD, c.x + Math.cos(a) * 2.6, y, c.z + Math.sin(a) * 2.6, 1, 0, 0, 0, 0);
            }
        }
        if (pushed && ticks % 10 == 0) {
            sound(w, c, "field_push", 1f);
        }
    }

    // ---------------------------------------------------------------- display entities (custom models)

    /** Item display entities carrying owner-pack models (others see the plain base item). */
    static final class Display {
        private Display() {
        }

        private static String uuidNbt(UUID u) {
            long m = u.getMostSignificantBits();
            long l = u.getLeastSignificantBits();
            return "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
        }

        static UUID summon(ServerWorld w, Vec3d at, String base, String model, String transform) {
            UUID id = UUID.randomUUID();
            String cmd = String.format(java.util.Locale.ROOT,
                    "summon minecraft:item_display %.3f %.3f %.3f {UUID:%s,teleport_duration:1,brightness:{sky:15,block:15},"
                            + "item:{id:\"%s\",count:1,components:{\"minecraft:custom_model_data\":{strings:[\"%s\"]},"
                            + "\"minecraft:item_model\":\"%s\"}},transformation:%s}",
                    at.x, at.y, at.z, uuidNbt(id), base, com.vylorq.anticheat.util.PackIds.model(model),
                    com.vylorq.anticheat.util.PackIds.itemModel(model), transform);
            try {
                var src = Ac.server().getCommandSource().withWorld(w).withSilent();
                Ac.server().getCommandManager().parseAndExecute(src, cmd);
            } catch (Exception e) {
                Ac.LOG.debug("display summon failed", e);
                return null;
            }
            return id;
        }

        static UUID cube(ServerWorld w, Vec3d at, String model, float scale) {
            return summon(w, at, "minecraft:magma_block", model, String.format(java.util.Locale.ROOT,
                    "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]}", scale, scale, scale));
        }

        /** A flat picture lying on the ground, gone after {@code life} ticks. */
        static void flat(ServerWorld w, Vec3d at, String model, float scale, int life) {
            UUID id = summon(w, at, "minecraft:light_blue_stained_glass_pane", model, String.format(java.util.Locale.ROOT,
                    "{left_rotation:[0.7071f,0f,0f,0.7071f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[%.2ff,%.2ff,%.2ff]}",
                    scale, scale, scale));
            if (id != null) {
                for (int s = 1; s < life; s++) {
                    float yaw = s * 6f;
                    OwnerPowers.later(s, () -> {
                        Entity e = w.getEntity(id);
                        if (e != null) {
                            e.setYaw(yaw);
                        }
                    });
                }
                OwnerPowers.later(life, () -> remove(w, id));
            }
        }

        static void move(ServerWorld w, UUID id, Vec3d at, float yaw) {
            Entity e = id == null ? null : w.getEntity(id);
            if (e != null) {
                e.setPosition(at.x, at.y, at.z);
                e.setYaw(yaw);
            }
        }

        static void remove(ServerWorld w, UUID id) {
            Entity e = id == null ? null : w.getEntity(id);
            if (e != null) {
                e.discard();
            }
        }
    }
}
