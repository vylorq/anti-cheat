package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.PiglinBruteEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Structure bosses. Each secret structure has one: it rises the first time a player reaches its arena (the piece in
 * the middle, past the rooms around it), never comes back once spawned there, and fights in three phases (new attacks unlock as its health drops, its guards join at each phase).
 *
 * <p>Every boss is a real, scaled-up mob with its own gear, so Java and Bedrock players both see it and its real
 * animations. A boss with a custom 3D model (the Drowned Warden) wears it on Java (with sway / lean animation);
 * Bedrock players, who can't see pack models, see the mob itself. Health shows in the name tag.
 */
public final class Bosses {
    private Bosses() {
    }

    public static final String TAG = "vigil_boss";
    public static final String MODEL_TAG = "vigil_boss_model";
    public static final String GUARD_TAG = "vigil_guard";
    public static final String DROWNED_WARDEN = "drowned_warden";

    enum Ability { SHOCKWAVE, TRIDENTS, ROCKS, LIGHTNING, FIRE_RING, SANDSTORM, ICE_SHARDS, FREEZE_AURA, THORNS, DARKNESS, LEAP }

    record Guard(EntityType<? extends MobEntity> type, String name, int count, Consumer<MobEntity> gear) {
    }

    /**
     * One boss. It has three lives (phases), each a full health bar of {@code health} (so three times that in all; the
     * game allows a mob 1024 health at most, so the real bar is {@link #BAR} and hits on it are scaled down to match):
     * when one runs out it rises again, stronger, and phase N unlocks the first N abilities.
     */
    record Kind(String id, String name, String color, String structure, int floor, EntityType<? extends MobEntity> type,
                double health, double damage, double armor, float scale, List<Ability> abilities, int aura,
                List<Guard> guards, Consumer<MobEntity> gear, String weapon,
                String model, float modelScale, float modelLift) {
        boolean hasModel() {
            return model != null;
        }
    }

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    private static ItemStack dyed(Item item, int rgb) {
        ItemStack s = new ItemStack(item);
        s.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(rgb));
        return s;
    }

    private static void wear(MobEntity m, EquipmentSlot slot, ItemStack s) {
        m.equipStack(slot, s);
        m.setEquipmentDropChance(slot, 0f);
    }

    private static void add(Kind k) {
        KINDS.put(k.id(), k);
    }

    static {
        add(new Kind(DROWNED_WARDEN, "Drowned Warden", "§3", "sunken_vault", 2, EntityType.DROWNED, 5000, 24, 26, 1.7f,
                List.of(Ability.SHOCKWAVE, Ability.TRIDENTS, Ability.LEAP), 0x3FA8FF,
                List.of(new Guard(EntityType.DROWNED, "§3Vault Drowned", 3, m -> wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.TRIDENT)))),
                m -> { }, "tide_trident", "drowned_warden", 1.15f, 1.6f));
        add(new Kind("deepslate_colossus", "Deepslate Colossus", "§8", "buried_vault", 1, EntityType.IRON_GOLEM, 6500, 30, 30, 1.6f,
                List.of(Ability.ROCKS, Ability.LEAP, Ability.SHOCKWAVE), 0x6C7684,
                List.of(new Guard(EntityType.SILVERFISH, "§7Stone Crawler", 4, m -> { }),
                        new Guard(EntityType.CAVE_SPIDER, "§2Sculk Lurker", 2, m -> { })),
                m -> { }, "colossus_maul", null, 0, 0));
        add(new Kind("storm_phantom", "Storm Phantom", "§b", "sky_citadel", 7, EntityType.PHANTOM, 5000, 22, 22, 2.0f,
                List.of(Ability.LIGHTNING, Ability.SHOCKWAVE, Ability.LIGHTNING), 0xB8E8FF,
                List.of(new Guard(EntityType.PHANTOM, "§bGale Phantom", 3, m -> { }),
                        new Guard(EntityType.SKELETON, "§fSky Sentry", 2, m -> wear(m, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)))),
                m -> {
                    if (m instanceof PhantomEntity ph) {
                        ph.setPhantomSize(6);
                    }
                }, "storm_fang", null, 0, 0));
        add(new Kind("forgemaster", "Forgemaster", "§6", "nether_forge", 1, EntityType.PIGLIN_BRUTE, 6000, 28, 28, 1.9f,
                List.of(Ability.FIRE_RING, Ability.LEAP, Ability.SHOCKWAVE), 0xFF8A2E,
                List.of(new Guard(EntityType.PIGLIN_BRUTE, "§6Forge Brute", 2, m -> wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_AXE))),
                        new Guard(EntityType.MAGMA_CUBE, "§cEmber Imp", 3, m -> { })),
                m -> {
                    wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_AXE));
                    wear(m, EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    wear(m, EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
                    if (m instanceof PiglinBruteEntity b) {
                        b.setImmuneToZombification(true);
                    }
                }, "forge_cleaver", null, 0, 0));
        add(new Kind("sand_colossus", "Sand Colossus", "§e", "desert_tomb", 1, EntityType.HUSK, 5200, 24, 24, 2.4f,
                List.of(Ability.SANDSTORM, Ability.LEAP, Ability.SHOCKWAVE), 0xE8C77A,
                List.of(new Guard(EntityType.HUSK, "§eTomb Husk", 3, m -> wear(m, EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET))),
                        new Guard(EntityType.SILVERFISH, "§6Scarab", 4, m -> { })),
                m -> {
                    wear(m, EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
                    wear(m, EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
                    wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
                }, "dune_blade", null, 0, 0));
        add(new Kind("frost_titan", "Frost Titan", "§b", "frozen_bastion", 1, EntityType.STRAY, 5200, 22, 24, 2.4f,
                List.of(Ability.ICE_SHARDS, Ability.FREEZE_AURA, Ability.SHOCKWAVE), 0xBFF4FF,
                List.of(new Guard(EntityType.STRAY, "§bIce Stray", 3, m -> wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW))),
                        new Guard(EntityType.POLAR_BEAR, "§fFrostbite Bear", 1, m -> { })),
                m -> {
                    wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                    wear(m, EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, 0xBFF4FF));
                    wear(m, EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x8FD8F0));
                    wear(m, EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x6FB8E0));
                }, "glacier_axe", null, 0, 0));
        add(new Kind("thornback_beast", "Thornback Beast", "§2", "overgrown_labyrinth", 1, EntityType.RAVAGER, 6000, 28, 26, 1.4f,
                List.of(Ability.THORNS, Ability.LEAP, Ability.SHOCKWAVE), 0x6FD05A,
                List.of(new Guard(EntityType.SPIDER, "§2Jungle Stalker", 2, m -> { }),
                        new Guard(EntityType.CAVE_SPIDER, "§aVine Creeper", 3, m -> { })),
                m -> { }, "thornspine", null, 0, 0));
        add(new Kind("hollow_watcher", "The Hollow Watcher", "§5", "watchers_hollow", 1, EntityType.WITHER_SKELETON, 7000, 30, 30, 2.0f,
                List.of(Ability.DARKNESS, Ability.SHOCKWAVE, Ability.LEAP), 0x6A2BD6,
                List.of(new Guard(EntityType.VEX, "§5Shadow Echo", 3, m -> { })),
                m -> {
                    wear(m, EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
                    wear(m, EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, 0x15101C));
                    wear(m, EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, 0x15101C));
                    wear(m, EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, 0x15101C));
                }, "hollow_edge", null, 0, 0));
    }

    // ---------------------------------------------------------------- saved state

    public static final class State {
        /** Structures that already had their boss ("sunken_vault@12,-40"). */
        public Set<String> spawned = new HashSet<>();
        /** Structure mobs players killed in each structure ("sunken_vault@12,-40" -> kills). */
        public Map<String, Integer> kills = new HashMap<>();
        /** How many of a structure's mobs must die before its boss wakes. */
        public int killsToWake = 30;
        /** Beaten structures rest until then (millis): no spawner mobs, and then the boss can rise again. */
        public Map<String, Long> restUntil = new HashMap<>();
        /** How many days a structure rests after its boss is beaten. */
        public int restDays = 3;
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
        final DustParticleEffect aura;
        /** The health bar at the top of the screen (Java and Bedrock). */
        final net.minecraft.entity.boss.ServerBossBar bar;
        /** The players this fight belongs to: only they can hurt it until it rests again. */
        final Set<UUID> group = new HashSet<>();
        /** The structure it guards ("forgemaster@12,-40"), or null when the owner spawned it. */
        String key;
        /** 1-3: which of its three health bars it is on. Kept on the mob as a tag, so a reload doesn't reset it. */
        int phase = 1;
        Vec3d home;
        long[] next = new long[Ability.values().length];
        long lastPlayerNear;
        long leapLand = -1;

        Live(MobEntity mob, Kind kind) {
            this.mob = mob;
            this.kind = kind;
            this.aura = new DustParticleEffect(kind.aura(), 1.6f);
            this.home = mob.getEntityPos();
            this.bar = new net.minecraft.entity.boss.ServerBossBar(Text.literal(kind.color() + "§l" + kind.name()),
                    net.minecraft.entity.boss.BossBar.Color.RED, net.minecraft.entity.boss.BossBar.Style.NOTCHED_10);
        }
    }

    private static final Map<UUID, Live> LIVE = new ConcurrentHashMap<>();
    private static final Set<Integer> LIVE_IDS = ConcurrentHashMap.newKeySet();
    private static long now;

    static String kindOf(Entity e) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith(TAG + ":")) {
                return t.substring(TAG.length() + 1);
            }
        }
        return null;
    }

    /** Which of its health bars a boss is on (1-3), or 0 for anything else. */
    public static int phaseOf(Entity e) {
        Live l = LIVE.get(e.getUuid());
        return l == null ? 0 : l.phase;
    }

    public static boolean isBoss(Entity e) {
        return LIVE.containsKey(e.getUuid());
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (!Ac.running()) {
                return;
            }
            String k = kindOf(e);
            if (k != null && KINDS.containsKey(k) && e instanceof MobEntity mob && mob.isAlive()) {
                Live l = new Live(mob, KINDS.get(k));
                for (String t : e.getCommandTags()) {
                    if (t.startsWith(PHASE_TAG)) {
                        try {
                            l.phase = Math.max(1, Math.min(PHASES, Integer.parseInt(t.substring(PHASE_TAG.length()))));
                        } catch (NumberFormatException ignored) {
                            // phase 1
                        }
                    }
                    if (t.startsWith(KEY_TAG)) {
                        l.key = t.substring(KEY_TAG.length());
                    }
                    if (t.startsWith("vigil_home:")) {
                        String[] p = t.substring(11).split(",");
                        try {
                            l.home = new Vec3d(Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2]));
                        } catch (RuntimeException ignored) {
                            // keep where it is
                        }
                    }
                }
                l.lastPlayerNear = now;
                LIVE.put(e.getUuid(), l);
                LIVE_IDS.add(e.getId());
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((e, w) -> {
            Live gone = LIVE.remove(e.getUuid());
            if (gone != null) {
                LIVE_IDS.remove(e.getId());
                gone.bar.clearPlayers();
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) -> {
            Live l = LIVE.get(e.getUuid());
            if (l == null || l.group.isEmpty() || !(source.getAttacker() instanceof ServerPlayerEntity p) || l.group.contains(p.getUuid())) {
                return true;
            }
            // Someone else's fight: they can't steal it.
            Msg.actionBar(p, "§c" + Msg.trFor(p, "boss.locked"));
            return false;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((e, source, amount) -> {
            Live l = LIVE.get(e.getUuid());
            if (l == null || l.phase >= PHASES || source.isOf(net.minecraft.entity.damage.DamageTypes.GENERIC_KILL)
                    || source.isOf(net.minecraft.entity.damage.DamageTypes.OUT_OF_WORLD)) {
                return true;
            }
            // Not dead yet: it rises on its next health bar.
            nextPhase(l, l.phase + 1);
            return false;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && e.getCommandTags().stream().anyMatch(t -> t.startsWith(StructureMobs.TAG))) {
                structureMobDied(e, source);
            }
            Live l = LIVE.remove(e.getUuid());
            if (l != null) {
                LIVE_IDS.remove(e.getId());
                l.bar.clearPlayers();
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
        MobEntity mob = k.type().create(w, SpawnReason.EVENT);
        if (mob == null) {
            return null;
        }
        mob.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        k.gear().accept(mob);
        set(mob, EntityAttributes.MAX_HEALTH, BAR);
        set(mob, EntityAttributes.ATTACK_DAMAGE, k.damage());
        set(mob, EntityAttributes.ARMOR, k.armor());
        set(mob, EntityAttributes.ARMOR_TOUGHNESS, 12);
        set(mob, EntityAttributes.KNOCKBACK_RESISTANCE, 1.0);
        set(mob, EntityAttributes.FOLLOW_RANGE, 40);
        set(mob, EntityAttributes.SCALE, k.scale());
        mob.setHealth((float) BAR);
        mob.setPersistent();
        mob.setCanPickUpLoot(false);
        ModelMobs.attach(mob, k.id());
        mob.addCommandTag(TAG + ":" + k.id());
        mob.addCommandTag(String.format(java.util.Locale.ROOT, "vigil_home:%.1f,%.1f,%.1f", at.x, at.y, at.z));
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
        mob.setCustomNameVisible(true);
        nameTag(mob, k);
        w.spawnEntity(mob);
        Live l = LIVE.get(mob.getUuid());
        if (l != null) {
            l.home = at;
            l.lastPlayerNear = now;
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
        w.spawnParticles(new DustParticleEffect(k.aura(), 2f), at.x, at.y + 1.5, at.z, 120, 1.5, 2, 1.5, 0);
        SecretItems.sound(w, at, "boss_rise", 3f);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 1.5f, 0.6f);
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) < 48 * 48) {
                Mc.title(p, k.color() + "§l" + k.name(), "§7" + Msg.trFor(p, "boss.awakens"), 10, 60, 20);
            }
        }
        guards(w, mob, k, k.guards().size() > 1 ? 1 : 2);
        return mob;
    }

    private static void set(MobEntity m, RegistryEntry<EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null) {
            i.setBaseValue(v);
        }
    }

    static final int PHASES = 3;
    private static final String KEY_TAG = "vigil_key:";

    private static void updateBar(Live l, ServerWorld w, Vec3d c) {
        Kind k = l.kind;
        double scale = k.health() / BAR;
        double max = k.health() * PHASES;
        double total = l.mob.getHealth() * scale + (PHASES - l.phase) * k.health();
        l.bar.setPercent((float) Math.max(0, Math.min(1, total / max)));
        l.bar.setName(Text.literal(k.color() + "§l" + k.name() + " §7- " + (l.phase == 1 ? "§a" : l.phase == 2 ? "§e" : "§c")
                + "Phase " + l.phase + "/" + PHASES));
        l.bar.setColor(l.phase == 1 ? net.minecraft.entity.boss.BossBar.Color.GREEN
                : l.phase == 2 ? net.minecraft.entity.boss.BossBar.Color.YELLOW : net.minecraft.entity.boss.BossBar.Color.RED);
        for (ServerPlayerEntity p : List.copyOf(l.bar.getPlayers())) {
            if (p.isRemoved() || p.getEntityWorld() != w || p.squaredDistanceTo(c) > 56 * 56) {
                l.bar.removePlayer(p);
            }
        }
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(c) < 48 * 48 && !l.bar.getPlayers().contains(p)) {
                l.bar.addPlayer(p);
            }
        }
    }

    /** Whether the structure at this spot is resting after its boss was beaten (its spawners stay quiet). */
    public static boolean resting(ServerWorld w, String structure, BlockPos pos) {
        var rest = state().restUntil;
        if (rest == null || rest.isEmpty()) {
            return false;
        }
        Structure s = w.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE).get(Identifier.of("vigil", structure));
        if (s == null) {
            return false;
        }
        var start = w.getStructureAccessor().getStructureAt(pos, s);
        if (start == null || !start.hasChildren()) {
            return false;
        }
        Long until = rest.get(structure + "@" + start.getPos().x + "," + start.getPos().z);
        return until != null && System.currentTimeMillis() < until;
    }
    /** The real health bar (the game's limit is 1024); a boss's own bar is bigger and its hits count for less. */
    static final double BAR = 1000;

    /** How much less a hit counts on this entity: 1 for anything that isn't a boss. */
    public static float damageScale(Entity e) {
        Live l = LIVE.get(e.getUuid());
        return l == null ? 1f : (float) (l.kind.health() / BAR);
    }
    private static final String PHASE_TAG = "vigil_phase:";

    private static void setPhase(Live l, int phase) {
        l.phase = phase;
        l.mob.getCommandTags().removeIf(t -> t.startsWith(PHASE_TAG));
        l.mob.addCommandTag(PHASE_TAG + phase);
    }

    /** Its health bar ran out: it rises again on full health, stronger, faster and with more guards. */
    private static void nextPhase(Live l, int phase) {
        MobEntity m = l.mob;
        ServerWorld w = (ServerWorld) m.getEntityWorld();
        Vec3d c = m.getEntityPos();
        setPhase(l, phase);
        m.setHealth(m.getMaxHealth());
        m.clearStatusEffects();
        m.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
        int lvl = phase - 2; // phase 2: level I, phase 3: level II
        m.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, lvl, false, false));
        m.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, StatusEffectInstance.INFINITE, lvl, false, false));
        m.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, StatusEffectInstance.INFINITE, lvl, false, false));
        // A moment to breathe while it rises (it can't be hurt for 3 seconds).
        m.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 4, false, false));
        for (ServerPlayerEntity p : fighters(w, c, 48)) {
            Mc.title(p, l.kind.color() + "§l" + l.kind.name(), "§c" + Msg.trFor(p, phase == 2 ? "boss.phase2" : "boss.phase3"), 5, 50, 10);
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, c.x, c.y + 1, c.z, 2, 0.5, 0.5, 0.5, 0);
        w.spawnParticles(new DustParticleEffect(l.kind.aura(), 2f), c.x, c.y + 1.5, c.z, 160, 2, 2.5, 2, 0);
        sound(w, c, "boss_rise", SoundEvents.ENTITY_RAVAGER_ROAR, 0.6f);
        // Pushes everyone near away as it rises.
        shockwave(l, w, c, 7, 6, 1.6);
        guards(w, m, l.kind, phase);
        nameTag(m, l.kind);
    }

    private static void nameTag(MobEntity m, Kind k) {
        Live l = LIVE.get(m.getUuid());
        int phase = l == null ? 1 : l.phase;
        double scale = k.health() / BAR;
        int hp = (int) Math.ceil(m.getHealth() * scale);
        int max = (int) Math.round(k.health());
        int total = hp + (PHASES - phase) * max;
        String color = phase == 1 ? "§a" : phase == 2 ? "§e" : "§c";
        m.setCustomName(Text.literal(k.color() + "§l" + k.name() + " §7[" + phase + "/" + PHASES + "] " + color + "❤ " + total + "/" + (max * PHASES)));
    }

    /** The structure's own mobs come out to guard their boss. */
    private static void guards(ServerWorld w, MobEntity boss, Kind k, int perType) {
        LivingEntity target = boss.getTarget() != null ? boss.getTarget() : w.getClosestPlayer(boss, 32);
        for (Guard g : k.guards()) {
            for (int i = 0; i < Math.max(1, g.count() * perType / 2); i++) {
                MobEntity m = g.type().create(w, SpawnReason.EVENT);
                if (m == null) {
                    continue;
                }
                double a = w.getRandom().nextDouble() * Math.PI * 2;
                double r = 2.5 + w.getRandom().nextDouble() * 2.5;
                Vec3d at = boss.getEntityPos().add(Math.cos(a) * r, 0.1, Math.sin(a) * r);
                m.refreshPositionAndAngles(at.x, at.y, at.z, w.getRandom().nextFloat() * 360, 0);
                g.gear().accept(m);
                ModelMobs.attach(m, modelName(g.name()));
                m.setCustomName(Text.literal(g.name()));
                m.setPersistent();
                m.addCommandTag(GUARD_TAG);
                StructureMobs.guard(m);
                if (m instanceof PiglinBruteEntity b) {
                    b.setImmuneToZombification(true);
                }
                w.spawnEntity(m);
                if (target != null && !(target instanceof ServerPlayerEntity sp && (sp.isCreative() || sp.isSpectator()))) {
                    m.setTarget(target);
                }
                w.spawnParticles(new DustParticleEffect(k.aura(), 1.3f), at.x, at.y + 1, at.z, 20, 0.3, 0.6, 0.3, 0);
            }
        }
    }

    /** "§3Vault Drowned" -> "vault_drowned" (the guard's model). */
    static String modelName(String name) {
        return name.replaceAll("§.", "").trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }

    /** A player walked into a structure: its boss rises (once per structure). */
    private static int killsLeft(String key) {
        var m = state().kills;
        return Math.max(0, state().killsToWake - (m == null ? 0 : m.getOrDefault(key, 0)));
    }

    /** A structure's mob died: a player's kill counts toward waking that structure's boss. */
    static void structureMobDied(net.minecraft.entity.LivingEntity e, net.minecraft.entity.damage.DamageSource source) {
        if (!(source.getAttacker() instanceof ServerPlayerEntity killer) || !(e.getEntityWorld() instanceof ServerWorld w)) {
            return;
        }
        String structure = null;
        for (String t : e.getCommandTags()) {
            if (t.startsWith(StructureMobs.TAG)) {
                structure = t.substring(StructureMobs.TAG.length());
            }
        }
        if (structure == null) {
            return;
        }
        Structure s = w.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE).get(Identifier.of("vigil", structure));
        if (s == null) {
            return;
        }
        var start = w.getStructureAccessor().getStructureAt(e.getBlockPos(), s);
        if (start == null || !start.hasChildren()) {
            return;
        }
        String key = structure + "@" + start.getPos().x + "," + start.getPos().z;
        if (state().spawned.contains(key)) {
            return;
        }
        if (state().kills == null) {
            state().kills = new HashMap<>();
        }
        int n = state().kills.merge(key, 1, Integer::sum);
        save();
        int need = state().killsToWake;
        if (n == need) {
            for (ServerPlayerEntity p : fighters(w, e.getEntityPos(), 120)) {
                Msg.send(p, "boss.awake");
                com.vylorq.anticheat.util.Mc.sound(p, net.minecraft.sound.SoundEvents.ENTITY_WITHER_SPAWN, 0.6f, 0.7f);
            }
        } else if (n < need) {
            Msg.actionBar(killer, "§6" + Msg.trFor(killer, "boss.kills", n, need));
        }
    }

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
                // The boss waits in the arena (the first piece, in the middle): the rooms around it come first.
                var box = start.getChildren().get(0).getBoundingBox();
                if (!box.expand(1).contains(p.getBlockPos())) {
                    continue;
                }
                String key = k.structure() + "@" + start.getPos().x + "," + start.getPos().z;
                Long rest = state().restUntil == null ? null : state().restUntil.get(key);
                if (rest != null && System.currentTimeMillis() >= rest) {
                    // Rested long enough: it fills up again and its boss can rise once more.
                    state().restUntil.remove(key);
                    state().spawned.remove(key);
                    state().kills.remove(key);
                    save();
                }
                if (state().spawned.contains(key)) {
                    continue;
                }
                int left = killsLeft(key);
                if (left > 0) {
                    // Asleep until enough of the structure's mobs are dead.
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "boss.asleep", left));
                    continue;
                }
                state().spawned.add(key);
                save();
                Vec3d at = new Vec3d(box.getCenter().getX() + 0.5, box.getMinY() + k.floor(), box.getCenter().getZ() + 0.5);
                MobEntity risen = spawn(w, at, k.id());
                if (risen != null) {
                    risen.addCommandTag(KEY_TAG + key);
                    Live rl = LIVE.get(risen.getUuid());
                    if (rl != null) {
                        rl.key = key;
                    }
                }
                Ac.LOG.info("{} rose at {} ({})", k.name(), BlockPos.ofFloored(at).toShortString(), key);
            }
        }
    }

    // ---------------------------------------------------------------- fighting

    private static List<ServerPlayerEntity> fighters(ServerWorld w, Vec3d c, double r) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (!p.isSpectator() && !p.isCreative() && p.isAlive() && p.squaredDistanceTo(c) < r * r) {
                out.add(p);
            }
        }
        return out;
    }

    private static void push(Entity e, Vec3d v) {
        e.setVelocity(Vec3d.ZERO);
        e.addVelocity(v);
        if (e instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
        }
    }

    private static int cooldown(Live l, int base) {
        return (int) (base * (l.phase == 1 ? 1.0 : l.phase == 2 ? 0.75 : 0.55));
    }

    private static boolean ready(Live l, Ability a, int base) {
        if (now < l.next[a.ordinal()]) {
            return false;
        }
        l.next[a.ordinal()] = now + cooldown(l, base);
        return true;
    }

    private static void ring(ServerWorld w, ParticleEffect e, Vec3d c, double r, int points) {
        for (int i = 0; i < points; i++) {
            double a = i * 2 * Math.PI / points;
            w.spawnParticles(e, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private static void sound(ServerWorld w, Vec3d at, String custom, SoundEvent vanilla, float pitch) {
        SecretItems.sound(w, at, custom, 2.5f);
        if (vanilla != null) {
            w.playSound(null, at.x, at.y, at.z, vanilla, SoundCategory.HOSTILE, 1.4f, pitch);
        }
    }

    private static void fight(Live l) {
        MobEntity m = l.mob;
        ServerWorld w = (ServerWorld) m.getEntityWorld();
        Vec3d c = m.getEntityPos();
        List<ServerPlayerEntity> near = fighters(w, c, 40);
        if (now % 10 == 0) {
            updateBar(l, w, c);
        }
        if (!near.isEmpty()) {
            l.lastPlayerNear = now;
            if (l.group.isEmpty()) {
                // Whoever is here when the fight starts owns it.
                near.forEach(p -> l.group.add(p.getUuid()));
            }
        } else if (now - l.lastPlayerNear > 600 && (m.getHealth() < m.getMaxHealth() || l.phase > 1 || !l.group.isEmpty())) {
            // Everyone left: it recovers and starts over, open to anyone again.
            l.group.clear();
            setPhase(l, 1);
            m.removeStatusEffect(StatusEffects.SPEED);
            m.removeStatusEffect(StatusEffects.STRENGTH);
            m.removeStatusEffect(StatusEffects.RESISTANCE);
            m.setHealth(m.getMaxHealth());
            return;
        }
        // It stays in its lair.
        if (l.home != null && c.squaredDistanceTo(l.home) > 30 * 30) {
            m.refreshPositionAndAngles(l.home.x, l.home.y, l.home.z, m.getYaw(), 0);
            m.heal((float) (m.getMaxHealth() * 0.05));
            w.spawnParticles(l.aura, l.home.x, l.home.y + 1, l.home.z, 60, 1, 1.5, 1, 0);
        }
        // Keep it on the nearest fighter (iron golems, for one, wouldn't go after players by themselves).
        LivingEntity target = m.getTarget();
        if (target == null || !target.isAlive() || (target instanceof ServerPlayerEntity sp && (sp.isCreative() || sp.isSpectator()))) {
            target = near.isEmpty() ? null : near.stream().min((a, b) -> Double.compare(a.squaredDistanceTo(m), b.squaredDistanceTo(m))).get();
            m.setTarget(target);
        }
        // A low growl now and then while players are near.
        if (!near.isEmpty() && now % 140 == 0) {
            sound(w, c, "boss_growl", SoundEvents.ENTITY_WARDEN_AMBIENT, 0.5f);
        }
        // Aura so it reads as a boss on both editions.
        if (now % 4 == 0) {
            double a = now * 0.2;
            for (int k = 0; k < 3; k++) {
                double b = a + k * 2 * Math.PI / 3;
                double r = 0.8 * l.kind.scale();
                w.spawnParticles(l.aura, c.x + Math.cos(b) * r, c.y + 0.2 + (now % 40) / 40.0 * m.getHeight(), c.z + Math.sin(b) * r, 1, 0, 0, 0, 0);
            }
        }
        // Landing from a leap
        if (l.leapLand > 0 && (m.isOnGround() && now > l.leapLand - 30 || now > l.leapLand)) {
            l.leapLand = -1;
            shockwave(l, w, m.getEntityPos(), 6, 10, 1.4);
        }
        if (target == null || target.squaredDistanceTo(m) > 36 * 36) {
            return;
        }
        List<Ability> unlocked = l.kind.abilities().subList(0, Math.min(l.phase, l.kind.abilities().size()));
        for (Ability a : unlocked) {
            use(l, a, w, m, target, near);
        }
    }

    private static void use(Live l, Ability a, ServerWorld w, MobEntity m, LivingEntity target, List<ServerPlayerEntity> near) {
        Vec3d c = m.getEntityPos();
        double dist = Math.sqrt(target.squaredDistanceTo(m));
        switch (a) {
            case SHOCKWAVE -> {
                if (dist < 7 && ready(l, a, 160)) {
                    shockwave(l, w, c, 7, 8, 1.6);
                }
            }
            case LEAP -> {
                if (dist > 6 && dist < 24 && m.isOnGround() && ready(l, a, 200)) {
                    Vec3d d = target.getEntityPos().subtract(c);
                    push(m, new Vec3d(d.x * 0.12, 0.9 + d.y * 0.04, d.z * 0.12));
                    l.leapLand = now + 60;
                    w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 0.2, c.z, 30, 1, 0.1, 1, 0.1);
                    sound(w, c, "boss_throw", SoundEvents.ENTITY_RAVAGER_ROAR, 1.2f);
                }
            }
            case TRIDENTS -> {
                if (dist > 5 && m.canSee(target) && ready(l, a, 100)) {
                    for (int i = 0; i < l.phase; i++) {
                        TridentEntity t = new TridentEntity(w, m, new ItemStack(Items.TRIDENT));
                        Vec3d from = m.getEyePos();
                        Vec3d d = target.getEyePos().subtract(0, 0.4, 0).subtract(from);
                        t.setPosition(from.x, from.y, from.z);
                        t.setVelocity(d.x, d.y + d.horizontalLength() * 0.08, d.z, 2.0f, 4f + i * 6f);
                        t.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
                        w.spawnEntity(t);
                        OwnerPowers.later(100, t::discard);
                    }
                    sound(w, c, "boss_throw", null, 1f);
                }
            }
            case ROCKS -> {
                if (dist > 4 && ready(l, a, 120)) {
                    throwItems(l, w, m, target, Items.COBBLED_DEEPSLATE, 2 + l.phase, 1.3f, Shard.ROCK);
                    sound(w, c, "boss_throw", SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 0.6f);
                }
            }
            case ICE_SHARDS -> {
                if (dist > 3 && ready(l, a, 100)) {
                    throwItems(l, w, m, target, Items.BLUE_ICE, 3 + l.phase, 1.5f, Shard.ICE);
                    sound(w, c, "frost_shot", SoundEvents.BLOCK_GLASS_BREAK, 1.4f);
                }
            }
            case LIGHTNING -> {
                if (ready(l, a, 140)) {
                    for (ServerPlayerEntity p : near) {
                        Vec3d spot = p.getEntityPos().add((w.getRandom().nextDouble() - 0.5) * 2, 0, (w.getRandom().nextDouble() - 0.5) * 2);
                        // Warning circle, then the strike a second later.
                        for (int s = 0; s < 20; s += 4) {
                            OwnerPowers.later(s, () -> ring(w, ParticleTypes.ELECTRIC_SPARK, spot.add(0, 0.1, 0), 1.6, 18));
                        }
                        OwnerPowers.later(20, () -> {
                            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.EVENT);
                            if (bolt != null) {
                                bolt.refreshPositionAfterTeleport(spot);
                                bolt.setCosmetic(true);
                                w.spawnEntity(bolt);
                            }
                            for (ServerPlayerEntity hit : fighters(w, spot, 2.2)) {
                                hit.damage(w, m.getDamageSources().mobAttack(m), 10f);
                            }
                        });
                    }
                }
            }
            case FIRE_RING -> {
                if (dist < 12 && ready(l, a, 180)) {
                    Set<UUID> burned = new HashSet<>();
                    for (int s = 0; s < 12; s++) {
                        int k = s;
                        OwnerPowers.later(s, () -> {
                            double r = 1 + k * 0.75;
                            ring(w, ParticleTypes.FLAME, c.add(0, 0.2, 0), r, (int) (16 + r * 6));
                            ring(w, ParticleTypes.LAVA, c.add(0, 0.3, 0), r, 4);
                            for (ServerPlayerEntity p : fighters(w, c, r + 0.8)) {
                                if (p.getEntityPos().distanceTo(c) > r - 0.8 && burned.add(p.getUuid())) {
                                    p.damage(w, m.getDamageSources().mobAttack(m), 7f);
                                    p.setFireTicks(Math.max(p.getFireTicks(), 100));
                                }
                            }
                        });
                    }
                    sound(w, c, "flame_wave", SoundEvents.ENTITY_BLAZE_SHOOT, 0.5f);
                }
            }
            case SANDSTORM -> {
                if (dist < 16 && ready(l, a, 260)) {
                    for (ServerPlayerEntity p : fighters(w, c, 14)) {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0));
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 1));
                    }
                    DustParticleEffect sand = new DustParticleEffect(0xE8C77A, 2f);
                    for (int s = 0; s < 40; s += 2) {
                        int k = s;
                        OwnerPowers.later(s, () -> {
                            for (int i = 0; i < 30; i++) {
                                double ang = k * 0.3 + i * 0.21;
                                double r = 2 + (i % 10);
                                w.spawnParticles(sand, c.x + Math.cos(ang) * r, c.y + 0.5 + (i % 5) * 0.6, c.z + Math.sin(ang) * r, 1, 0, 0, 0, 0);
                            }
                        });
                    }
                    sound(w, c, "tidecaller", SoundEvents.WEATHER_RAIN_ABOVE, 0.5f);
                }
            }
            case FREEZE_AURA -> {
                if (now % 20 == 0) {
                    for (ServerPlayerEntity p : fighters(w, c, 7)) {
                        p.setFrozenTicks(Math.min(p.getFrozenTicks() + 50, p.getMinFreezeDamageTicks() + 20));
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 0));
                    }
                    ring(w, ParticleTypes.SNOWFLAKE, c.add(0, 0.3, 0), 7, 40);
                }
            }
            case THORNS -> {
                if (dist < 8 && ready(l, a, 140)) {
                    for (ServerPlayerEntity p : fighters(w, c, 7)) {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 1));
                        p.damage(w, m.getDamageSources().mobAttack(m), 4f);
                    }
                    DustParticleEffect thorn = new DustParticleEffect(0x6FD05A, 1.5f);
                    for (int i = 0; i < 60; i++) {
                        double ang = i * 0.105;
                        for (double r = 1; r < 7; r += 1.5) {
                            w.spawnParticles(thorn, c.x + Math.cos(ang) * r, c.y + 0.3 + r * 0.05, c.z + Math.sin(ang) * r, 1, 0, 0, 0, 0);
                        }
                    }
                    sound(w, c, "voidblade", SoundEvents.ENTITY_RAVAGER_ATTACK, 0.7f);
                }
            }
            case DARKNESS -> {
                if (ready(l, a, 220)) {
                    for (ServerPlayerEntity p : fighters(w, c, 20)) {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 120, 0));
                    }
                    // Steps out of the shadows behind its target.
                    Vec3d behind = target.getEntityPos().subtract(target.getRotationVec(1f).multiply(1, 0, 1).normalize().multiply(2.5));
                    if (w.isSpaceEmpty(m, m.getBoundingBox().offset(behind.subtract(c)))) {
                        w.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 1, c.z, 40, 0.5, 1, 0.5, 0.02);
                        m.refreshPositionAndAngles(behind.x, behind.y, behind.z, m.getYaw(), 0);
                        w.spawnParticles(ParticleTypes.REVERSE_PORTAL, behind.x, behind.y + 1, behind.z, 40, 0.5, 1, 0.5, 0.1);
                    }
                    sound(w, c, "shadow_on", SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 0.8f);
                }
            }
            default -> {
            }
        }
    }

    private static void shockwave(Live l, ServerWorld w, Vec3d c, double radius, float damage, double force) {
        for (ServerPlayerEntity p : fighters(w, c, radius)) {
            Vec3d d = p.getEntityPos().subtract(c).multiply(1, 0, 1);
            push(p, (d.lengthSquared() < 1e-3 ? new Vec3d(1, 0, 0) : d.normalize()).multiply(force).add(0, 0.55, 0));
            p.damage(w, l.mob.getDamageSources().mobAttack(l.mob), damage);
        }
        for (int s = 0; s < 8; s++) {
            int k = s;
            OwnerPowers.later(s, () -> {
                double r = 0.8 + k * radius / 8;
                ring(w, l.aura, c.add(0, 0.3, 0), r, (int) (20 + r * 6));
                ring(w, ParticleTypes.CLOUD, c.add(0, 0.1, 0), r, (int) (8 + r * 2));
            });
        }
        w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 2, 1, 0.1, 1, 0);
        sound(w, c, "boss_shock", SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.6f);
    }

    // ---------------------------------------------------------------- thrown rocks and ice

    enum Shard { ROCK, ICE }

    private record Thrown(SnowballEntity ball, MobEntity boss, Shard kind) {
    }

    private static final List<Thrown> THROWN = new CopyOnWriteArrayList<>();

    private static void throwItems(Live l, ServerWorld w, MobEntity m, LivingEntity target, Item item, int count, float speed, Shard kind) {
        for (int i = 0; i < count; i++) {
            SnowballEntity ball = new SnowballEntity(w, m, new ItemStack(item));
            Vec3d from = m.getEyePos();
            Vec3d d = target.getEyePos().subtract(from);
            ball.setPosition(from.x, from.y, from.z);
            ball.setVelocity(d.x, d.y + d.horizontalLength() * 0.12, d.z, speed, 6f + i * 3f);
            w.spawnEntity(ball);
            THROWN.add(new Thrown(ball, m, kind));
        }
    }

    private static void thrown() {
        Iterator<Thrown> it = THROWN.iterator();
        while (it.hasNext()) {
            Thrown t = it.next();
            SnowballEntity b = t.ball();
            ServerWorld w = (ServerWorld) b.getEntityWorld();
            if (!b.isRemoved() && b.age < 120) {
                w.spawnParticles(t.kind() == Shard.ICE ? ParticleTypes.SNOWFLAKE : ParticleTypes.SMOKE, b.getX(), b.getY(), b.getZ(), 1, 0, 0, 0, 0);
                continue;
            }
            THROWN.remove(t);
            if (b.age >= 120) {
                b.discard();
                continue;
            }
            Vec3d at = b.getEntityPos();
            for (ServerPlayerEntity p : fighters(w, at, 2.2)) {
                p.damage(w, t.boss().getDamageSources().mobAttack(t.boss()), t.kind() == Shard.ICE ? 6f : 8f);
                if (t.kind() == Shard.ICE) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 1));
                    p.setFrozenTicks(Math.max(p.getFrozenTicks(), p.getMinFreezeDamageTicks() - 1));
                }
            }
            if (t.kind() == Shard.ICE) {
                w.spawnParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.3, at.z, 20, 0.5, 0.3, 0.5, 0.05);
            } else {
                w.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
                w.spawnParticles(new DustParticleEffect(0x6C7684, 1.8f), at.x, at.y + 0.3, at.z, 20, 0.6, 0.3, 0.6, 0);
            }
        }
    }

    // ---------------------------------------------------------------- defeat

    static final int MAX_UPGRADE = 10;

    /** Raises the boss weapon the player carries by one level (up to {@link #MAX_UPGRADE}). @return false if they have none */
    static boolean upgradeWeapon(ServerPlayerEntity p, String weapon) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!weapon.equals(SecretItems.idOf(s))) {
                continue;
            }
            String ench = s.isOf(Items.TRIDENT) ? "minecraft:impaling" : s.isOf(Items.MACE) ? "minecraft:density" : "minecraft:sharpness";
            var entry = com.vylorq.anticheat.util.ItemConv.enchantment(ench);
            if (entry.isEmpty()) {
                return false;
            }
            int level = net.minecraft.enchantment.EnchantmentHelper.getLevel(entry.get(), s);
            if (level >= MAX_UPGRADE) {
                Msg.send(p, "boss.weapon-max", s.getName().getString());
                return true;
            }
            s.addEnchantment(entry.get(), level + 1);
            Msg.send(p, "boss.weapon-upgraded", s.getName().getString(), level + 1);
            Mc.sound(p, SoundEvents.BLOCK_ANVIL_USE, 1f, 1.2f);
            return true;
        }
        return false;
    }

    private static void defeated(ServerWorld w, Live l, Entity killer) {
        state().killed++;
        save();
        Vec3d at = l.mob.getEntityPos();
        ExperienceOrbEntity.spawn(w, at, 500);
        List<ItemStack> drops = List.of(new ItemStack(Items.DIAMOND, 4 + w.getRandom().nextInt(5)),
                new ItemStack(Items.NETHERITE_SCRAP, 1 + w.getRandom().nextInt(2)), new ItemStack(Items.EMERALD, 6 + w.getRandom().nextInt(7)),
                new ItemStack(Items.GOLDEN_APPLE, 2));
        for (ItemStack s : drops) {
            ItemEntity it = new ItemEntity(w, at.x, at.y + 1, at.z, s);
            it.setVelocity((w.getRandom().nextDouble() - 0.5) * 0.3, 0.3, (w.getRandom().nextDouble() - 0.5) * 0.3);
            w.spawnEntity(it);
        }
        if (l.key != null) {
            if (state().restUntil == null) {
                state().restUntil = new HashMap<>();
            }
            state().restUntil.put(l.key, System.currentTimeMillis() + state().restDays * 86_400_000L);
            save();
        }
        // Its own weapon, through the same loot table the mod uses everywhere; the killer who has one already gets
        // it upgraded instead of a copy.
        if (!(killer instanceof ServerPlayerEntity kp && upgradeWeapon(kp, l.kind.weapon()))) {
            Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(),
                    String.format(java.util.Locale.ROOT, "loot spawn %.2f %.2f %.2f loot vigil:items/%s", at.x, at.y + 1, at.z, l.kind.weapon()));
        }
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1.5, at.z, 2, 1, 1, 1, 0);
        w.spawnParticles(l.aura, at.x, at.y + 1.5, at.z, 150, 1.5, 2, 1.5, 0);
        SecretItems.sound(w, at, "boss_defeated", 3f);
        String by = killer instanceof ServerPlayerEntity p ? p.getGameProfile().name() : null;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) < 64 * 64) {
                Mc.title(p, l.kind.color() + "§l" + l.kind.name(), "§7" + (by == null ? Msg.trFor(p, "boss.defeated")
                        : Msg.trFor(p, "boss.defeated-by", by)), 10, 60, 20);
            }
        }
        Ac.LOG.info("{} was defeated{}", l.kind.name(), by == null ? "" : " by " + by);
    }

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        now = ticks;
        if (ticks % 40 == 0) {
            checkPlayers();
        }
        if (!THROWN.isEmpty()) {
            thrown();
        }
        for (Live l : LIVE.values()) {
            MobEntity m = l.mob;
            if (m.isRemoved() || !m.isAlive()) {
                continue;
            }
            if (ticks % 10 == 0) {
                nameTag(m, l.kind);
            }
            fight(l);
        }
    }

    // ---------------------------------------------------------------- owner commands

    public static List<String> kinds() {
        return List.copyOf(KINDS.keySet());
    }

    public static void ownerSpawn(ServerPlayerEntity p, String kind) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        Vec3d at = p.getEntityPos().add(p.getRotationVec(1f).multiply(1, 0, 1).normalize().multiply(6));
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
        ModelMobs.clearAll();
        for (Live l : new ArrayList<>(LIVE.values())) {
            l.mob.discard();
            n++;
        }
        LIVE.clear();
        LIVE_IDS.clear();
        for (ServerWorld w : Ac.server().getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (e.getCommandTags().contains(GUARD_TAG)) {
                    e.discard();
                }
            }
        }
        Msg.send(p, "boss.removed", n);
    }
}
