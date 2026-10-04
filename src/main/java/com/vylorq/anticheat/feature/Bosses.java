package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.DrownedEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.structure.Structure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure bosses. A boss appears the first time a player walks into its structure and never comes back once
 * spawned there. It's a real mob (made invisible for Java players) wearing a custom 3D model from the pack, which
 * follows it around; Bedrock players, who can't see pack models, see the plain mob instead. Health shows in its
 * name tag.
 */
public final class Bosses {
    private Bosses() {
    }

    public static final String TAG = "vigil_boss";
    public static final String MODEL_TAG = "vigil_boss_model";
    public static final String DROWNED_WARDEN = "drowned_warden";

    /** One boss: where it lives and how it looks. */
    record Kind(String id, String name, String structure, double health, double damage, double armor, float scale,
                String model, float modelScale, float modelLift) {
    }

    static final Map<String, Kind> KINDS = Map.of(
            DROWNED_WARDEN, new Kind(DROWNED_WARDEN, "Drowned Warden", "sunken_vault", 160, 9, 10, 1.7f,
                    "drowned_warden", 1.3f, 1.3f));

    private static final DustParticleEffect TIDE = new DustParticleEffect(0x3FA8FF, 1.6f);

    // ---------------------------------------------------------------- saved state

    public static final class State {
        /** Structures that already had their boss ("sunken_vault@12,-40"). */
        public Set<String> spawned = new HashSet<>();
        public int killed;
        /** Turn the models around if they face backwards. */
        public float yawOffset = 180f;
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("bosses.json");
    }

    static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read bosses.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.spawned == null) {
                state.spawned = new HashSet<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save bosses.json", e);
        }
    }

    // ---------------------------------------------------------------- live bosses

    private static final class Live {
        final MobEntity mob;
        final Kind kind;
        UUID model;
        boolean helpersCalled;
        long nextShock;
        long nextThrow;

        Live(MobEntity mob, Kind kind) {
            this.mob = mob;
            this.kind = kind;
        }
    }

    private static final Map<UUID, Live> LIVE = new ConcurrentHashMap<>();
    private static final Set<Integer> LIVE_IDS = ConcurrentHashMap.newKeySet();

    static String kindOf(Entity e) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith(TAG + ":")) {
                return t.substring(TAG.length() + 1);
            }
        }
        return null;
    }

    public static boolean isBoss(Entity e) {
        return LIVE.containsKey(e.getUuid());
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (!Ac.running()) {
                return;
            }
            if (e.getCommandTags().contains(MODEL_TAG)) {
                // A model left over from before a restart: a fresh one is made for its boss.
                boolean owned = LIVE.values().stream().anyMatch(l -> e.getUuid().equals(l.model));
                if (!owned) {
                    e.discard();
                }
                return;
            }
            String k = kindOf(e);
            if (k != null && KINDS.containsKey(k) && e instanceof MobEntity mob && mob.isAlive()) {
                LIVE.put(e.getUuid(), new Live(mob, KINDS.get(k)));
                LIVE_IDS.add(e.getId());
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((e, w) -> {
            Live l = LIVE.remove(e.getUuid());
            if (l != null) {
                LIVE_IDS.remove(e.getId());
                removeModel(w, l);
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            Live l = LIVE.remove(e.getUuid());
            if (l != null) {
                LIVE_IDS.remove(e.getId());
                defeated((ServerWorld) e.getEntityWorld(), l, source.getAttacker());
            }
        });
    }

    // ---------------------------------------------------------------- spawning

    /** Spawns a boss at a spot. @return the mob, or null */
    public static MobEntity spawn(ServerWorld w, Vec3d at, String kindId) {
        Kind k = KINDS.get(kindId);
        if (k == null) {
            return null;
        }
        DrownedEntity mob = EntityType.DROWNED.create(w, SpawnReason.EVENT);
        if (mob == null) {
            return null;
        }
        mob.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        set(mob, EntityAttributes.MAX_HEALTH, k.health());
        set(mob, EntityAttributes.ATTACK_DAMAGE, k.damage());
        set(mob, EntityAttributes.ARMOR, k.armor());
        set(mob, EntityAttributes.KNOCKBACK_RESISTANCE, 0.8);
        set(mob, EntityAttributes.FOLLOW_RANGE, 32);
        set(mob, EntityAttributes.SCALE, k.scale());
        mob.setHealth((float) k.health());
        mob.setPersistent();
        mob.setCanPickUpLoot(false);
        mob.setInvisible(true);
        mob.addCommandTag(TAG + ":" + k.id());
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
        mob.setCustomNameVisible(true);
        w.spawnEntity(mob);
        nameTag(mob, k);
        w.spawnParticles(ParticleTypes.SPLASH, at.x, at.y + 1, at.z, 80, 1, 1.5, 1, 0.3);
        w.spawnParticles(TIDE, at.x, at.y + 1.5, at.z, 60, 1, 1.5, 1, 0);
        SecretItems.sound(w, at, "boss_rise", 3f);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 1f, 0.6f);
        return mob;
    }

    private static void set(MobEntity m, RegistryEntry<EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null) {
            i.setBaseValue(v);
        }
    }

    private static void nameTag(MobEntity m, Kind k) {
        int hp = (int) Math.ceil(m.getHealth());
        int max = (int) Math.ceil(m.getMaxHealth());
        String color = hp > max / 2 ? "§a" : hp > max / 4 ? "§e" : "§c";
        m.setCustomName(Text.literal("§3§l" + k.name() + " " + color + "❤ " + hp + "/" + max));
    }

    /** A player walked into a structure: its boss rises (once per structure). */
    private static void checkPlayers() {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (p.isSpectator() || p.isCreative()) {
                continue;
            }
            ServerWorld w = p.getEntityWorld();
            var structures = w.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
            for (Kind k : KINDS.values()) {
                Structure s = structures.get(Identifier.of("vigil", k.structure()));
                if (s == null) {
                    continue;
                }
                var start = w.getStructureAccessor().getStructureAt(p.getBlockPos(), s);
                if (start == null || !start.hasChildren()) {
                    continue;
                }
                String key = k.structure() + "@" + start.getPos().x + "," + start.getPos().z;
                if (state().spawned.contains(key)) {
                    continue;
                }
                state().spawned.add(key);
                save();
                var box = start.getBoundingBox();
                Vec3d at = new Vec3d(box.getCenter().getX() + 0.5, box.getMinY() + 1, box.getCenter().getZ() + 0.5);
                spawn(w, at, k.id());
                Ac.LOG.info("{} rose at {} ({})", k.name(), BlockPos.ofFloored(at).toShortString(), key);
            }
        }
    }

    // ---------------------------------------------------------------- the model

    private static String transform(Kind k) {
        float s = k.modelScale();
        return String.format(java.util.Locale.ROOT,
                "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,%.3ff,0f],scale:[%.3ff,%.3ff,%.3ff]}",
                k.modelLift(), s, s, s);
    }

    private static void keepModel(ServerWorld w, Live l) {
        Entity model = l.model == null ? null : w.getEntity(l.model);
        if (model == null || model.isRemoved()) {
            l.model = OwnerCombat.Display.summon(w, l.mob.getEntityPos(), "minecraft:nautilus_shell", l.kind.model(), transform(l.kind));
            model = l.model == null ? null : w.getEntity(l.model);
            if (model == null) {
                return;
            }
            model.addCommandTag(MODEL_TAG);
        }
        model.refreshPositionAndAngles(l.mob.getX(), l.mob.getY(), l.mob.getZ(), l.mob.getBodyYaw() + state().yawOffset, 0);
    }

    private static void removeModel(ServerWorld w, Live l) {
        Entity model = l.model == null ? null : w.getEntity(l.model);
        if (model != null) {
            model.discard();
        }
        l.model = null;
    }

    /** Java players see the model, so the mob itself is invisible; Bedrock players can't see models: show them the mob. */
    public static Packet<?> forViewer(ServerPlayerEntity to, Packet<?> packet) {
        if (!(packet instanceof EntityTrackerUpdateS2CPacket u) || !LIVE_IDS.contains(u.id()) || !com.vylorq.anticheat.ui.Viewer.isBedrock(to)) {
            return packet;
        }
        List<net.minecraft.entity.data.DataTracker.SerializedEntry<?>> out = new ArrayList<>(u.trackedValues().size());
        boolean changed = false;
        for (var e : u.trackedValues()) {
            if (e.id() == 0 && e.value() instanceof Byte b) {
                out.add(new net.minecraft.entity.data.DataTracker.SerializedEntry<>(0,
                        net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE, (byte) (b & ~0x20)));
                changed = true;
            } else {
                out.add(e);
            }
        }
        return changed ? new EntityTrackerUpdateS2CPacket(u.id(), out) : packet;
    }

    // ---------------------------------------------------------------- fighting

    private static void abilities(Live l, long ticks) {
        MobEntity m = l.mob;
        ServerWorld w = (ServerWorld) m.getEntityWorld();
        LivingEntity target = m.getTarget();
        if (target == null || !target.isAlive() || target.squaredDistanceTo(m) > 32 * 32) {
            return;
        }
        // Tidal shockwave when players crowd it.
        if (ticks >= l.nextShock && target.squaredDistanceTo(m) < 6 * 6) {
            l.nextShock = ticks + 160;
            Vec3d c = m.getEntityPos();
            for (ServerPlayerEntity p : w.getPlayers()) {
                if (p.squaredDistanceTo(c) < 7 * 7 && !p.isSpectator() && !p.isCreative()) {
                    Vec3d d = p.getEntityPos().subtract(c).multiply(1, 0, 1);
                    Vec3d v = (d.lengthSquared() < 1e-3 ? new Vec3d(1, 0, 0) : d.normalize()).multiply(1.4).add(0, 0.5, 0);
                    p.setVelocity(Vec3d.ZERO);
                    p.addVelocity(v);
                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(p));
                    p.damage(w, m.getDamageSources().mobAttack(m), 6f);
                }
            }
            for (int s = 0; s < 8; s++) {
                int k = s;
                OwnerPowers.later(s, () -> {
                    double r = 0.8 + k * 0.85;
                    for (int i = 0; i < 24 + k * 6; i++) {
                        double a = i * 2 * Math.PI / (24 + k * 6);
                        w.spawnParticles(ParticleTypes.SPLASH, c.x + Math.cos(a) * r, c.y + 0.3, c.z + Math.sin(a) * r, 2, 0.05, 0.1, 0.05, 0.1);
                        w.spawnParticles(TIDE, c.x + Math.cos(a) * r, c.y + 0.6, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                    }
                });
            }
            SecretItems.sound(w, c, "boss_shock", 2.5f);
            w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, SoundCategory.HOSTILE, 1.2f, 0.6f);
        }
        // Trident throw at targets keeping their distance.
        if (ticks >= l.nextThrow && target.squaredDistanceTo(m) > 5 * 5 && m.canSee(target)) {
            l.nextThrow = ticks + 100;
            TridentEntity t = new TridentEntity(w, m, new ItemStack(Items.TRIDENT));
            Vec3d from = m.getEyePos();
            Vec3d to = target.getEyePos().subtract(0, 0.4, 0);
            Vec3d d = to.subtract(from);
            t.setPosition(from.x, from.y, from.z);
            t.setVelocity(d.x, d.y + d.horizontalLength() * 0.08, d.z, 2.0f, 2f);
            t.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
            w.spawnEntity(t);
            OwnerPowers.later(100, t::discard);
            SecretItems.sound(w, m.getEntityPos(), "boss_throw", 1.5f);
        }
        // Half health: calls two drowned to help (once).
        if (!l.helpersCalled && m.getHealth() < m.getMaxHealth() / 2) {
            l.helpersCalled = true;
            for (int i = 0; i < 2; i++) {
                DrownedEntity h = EntityType.DROWNED.create(w, SpawnReason.EVENT);
                if (h != null) {
                    h.refreshPositionAndAngles(m.getX() + (i == 0 ? 2 : -2), m.getY(), m.getZ(), 0, 0);
                    h.setCustomName(Text.literal("§3Vault Drowned"));
                    h.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    h.setPersistent();
                    w.spawnEntity(h);
                    h.setTarget(target);
                    w.spawnParticles(ParticleTypes.SPLASH, h.getX(), h.getY() + 1, h.getZ(), 30, 0.4, 0.8, 0.4, 0.2);
                }
            }
            SecretItems.sound(w, m.getEntityPos(), "boss_rise", 2f);
        }
        // Last quarter: it gets faster.
        if (m.getHealth() < m.getMaxHealth() / 4 && !m.hasStatusEffect(StatusEffects.SPEED)) {
            m.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
        }
    }

    private static void defeated(ServerWorld w, Live l, Entity killer) {
        removeModel(w, l);
        state().killed++;
        save();
        Vec3d at = l.mob.getEntityPos();
        ExperienceOrbEntity.spawn(w, at, 160);
        List<ItemStack> drops = List.of(new ItemStack(Items.HEART_OF_THE_SEA), new ItemStack(Items.DIAMOND, 2 + w.getRandom().nextInt(3)),
                new ItemStack(Items.PRISMARINE_SHARD, 8 + w.getRandom().nextInt(9)), new ItemStack(Items.EMERALD, 3 + w.getRandom().nextInt(4)));
        for (ItemStack s : drops) {
            ItemEntity it = new ItemEntity(w, at.x, at.y + 1, at.z, s);
            it.setVelocity((w.getRandom().nextDouble() - 0.5) * 0.3, 0.3, (w.getRandom().nextDouble() - 0.5) * 0.3);
            w.spawnEntity(it);
        }
        w.spawnParticles(ParticleTypes.SPLASH, at.x, at.y + 1.5, at.z, 150, 1.2, 1.5, 1.2, 0.4);
        w.spawnParticles(TIDE, at.x, at.y + 1.5, at.z, 100, 1.2, 1.5, 1.2, 0);
        SecretItems.sound(w, at, "boss_defeated", 3f);
        String by = killer instanceof ServerPlayerEntity p ? p.getGameProfile().name() : null;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) < 64 * 64) {
                com.vylorq.anticheat.util.Mc.title(p, "§3" + l.kind.name(), "§7" + (by == null ? Msg.trFor(p, "boss.defeated")
                        : Msg.trFor(p, "boss.defeated-by", by)), 10, 60, 20);
            }
        }
    }

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        if (ticks % 40 == 0) {
            checkPlayers();
        }
        for (Live l : LIVE.values()) {
            MobEntity m = l.mob;
            if (m.isRemoved() || !m.isAlive()) {
                continue;
            }
            ServerWorld w = (ServerWorld) m.getEntityWorld();
            keepModel(w, l);
            if (ticks % 10 == 0) {
                nameTag(m, l.kind);
            }
            if (ticks % 5 == 0) {
                abilities(l, ticks);
            }
        }
    }

    // ---------------------------------------------------------------- owner commands

    public static void ownerSpawn(ServerPlayerEntity p, String kind) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Vec3d at = p.getEntityPos().add(p.getRotationVec(1f).multiply(1, 0, 1).normalize().multiply(5));
        if (spawn(p.getEntityWorld(), at, kind) != null) {
            Staff.log(p, "boss-spawn", null, null, kind);
        }
    }

    public static void ownerRotate(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        state().yawOffset = (state().yawOffset + 180f) % 360f;
        save();
        Msg.send(p, "boss.rotated");
    }

    public static void ownerKillAll(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        int n = 0;
        for (Live l : new ArrayList<>(LIVE.values())) {
            removeModel((ServerWorld) l.mob.getEntityWorld(), l);
            l.mob.discard();
            n++;
        }
        LIVE.clear();
        LIVE_IDS.clear();
        Msg.send(p, "boss.removed", n);
    }
}
