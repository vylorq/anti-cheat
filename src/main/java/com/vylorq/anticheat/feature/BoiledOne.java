package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Boiled One: not a boss, a thing that hunts players.
 *
 * <ul>
 *   <li>At night on the surface, and any time down in caves, it now and then shows up far away and stares at one
 *   player, creeping closer while they aren't looking. Look at it and it screams and rushes you to kill you.</li>
 *   <li>In caves it may only scare (gone once seen), lean out from behind a corner and watch (silent, or breathing
 *   heavily), or, while you're mining, stand right behind you, leaning over you: look up and it's looking down at
 *   you; turn around and it's gone.</li>
 *   <li>In your base you're safe, except one time in a hundred (or always, if the owner says so): it breaks in through
 *   the walls (nothing is put back) and goes for you. Every break-in is remembered: whose base it was and where.</li>
 *   <li>The owner can set it on one player: it follows them, breaks into their base whenever they're in it, and only
 *   stops once it has killed them.</li>
 *   <li>The Boiling Night (now and then at nightfall, or started by the owner): it stalks everyone, and anyone who
 *   looks at it while it's stalking someone is marked: if they're in their base, it breaks in. Always.</li>
 * </ul>
 * It can't be hurt. Java and Bedrock players see its 3D painted figure (Bedrock through the Geyser pack).
 */
public final class BoiledOne {
    private BoiledOne() {
    }

    public static final String TAG = "vigil_boiled_one";
    static final String MODEL = "boiled_one";

    enum Mode { STALK, SCARE, PEEK, BEHIND, RUSH, BREAK_IN }

    /** One break-in, remembered: who it came for and whose base it was. */
    public static final class BreakIn {
        public String target;
        public String baseOwner;
        public String claim;
        public String world;
        public int x;
        public int y;
        public int z;
        public long at;
    }

    public static final class State {
        public boolean enabled = true;
        /** On average one sighting per this many minutes a player spends out at night or down in caves. */
        public int minutes = 90;
        /** One sighting in this many, for someone in their base, is a break-in (1 = always). */
        public int breakInOdds = 100;
        /** Players it follows until it kills them (uuid -> name). */
        public Map<String, String> hunted = new HashMap<>();
        /** The last break-ins, newest last. */
        public List<BreakIn> breakIns = new ArrayList<>();
        /** One night in this many starts The Boiling Night by itself (0 = never). */
        public int eventNights = 20;
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("boiled_one.json");
    }

    static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read boiled_one.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.hunted == null) {
                state.hunted = new HashMap<>();
            }
            if (state.breakIns == null) {
                state.breakIns = new ArrayList<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save boiled_one.json", e);
        }
    }

    private static final class Hunt {
        final MobEntity mob;
        final UUID victim;
        Mode mode;
        long born;
        long modeSince;
        int seen;
        long lastSeen = -1;
        long lastMove;
        long nextHit;
        boolean breathes;

        Hunt(MobEntity mob, UUID victim, Mode mode, long now) {
            this.mob = mob;
            this.victim = victim;
            this.mode = mode;
            this.born = now;
            this.modeSince = now;
            this.lastMove = now;
        }
    }

    private static final Map<UUID, Hunt> HUNTS = new ConcurrentHashMap<>();
    /** When it may next come for each player (followed players, the event, mining scares). */
    private static final Map<UUID, Long> NEXT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_BEHIND = new ConcurrentHashMap<>();
    /** The Boiling Night: players who looked at it while it was stalking someone. */
    private static final Set<UUID> MARKED = ConcurrentHashMap.newKeySet();
    private static long eventUntil = -1;
    private static long lastNightRolled = -1;
    private static long now;

    static final double RUSH_SPEED = 0.46;
    static final float RUSH_DAMAGE = 16f;
    static final float BREAK_IN_DAMAGE = 40f;

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e.getCommandTags().contains(TAG) && !HUNTS.containsKey(e.getUuid()) && e instanceof MobEntity m) {
                // Left over from before a restart: gone.
                OwnerPowers.later(1, () -> {
                    if (!m.isRemoved() && !HUNTS.containsKey(m.getUuid())) {
                        ModelMobs.remove(m);
                    }
                });
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (e instanceof ServerPlayerEntity p && Ac.running()) {
                killed(p);
            }
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p) {
                mining(p);
            }
        });
    }

    // ---------------------------------------------------------------- where and when it shows up

    static boolean night(World w) {
        long t = w.getTimeOfDay() % 24000L;
        return t >= 13000 && t <= 23000;
    }

    /** Down in a cave: no sky above, below the surface, and not a lit room. */
    static boolean inCave(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        BlockPos at = p.getBlockPos();
        return !w.isSkyVisible(at.up()) && at.getY() < w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) - 8
                && w.getLightLevel(LightType.BLOCK, at) < 8;
    }

    /** Inside their base: in a claim, or under a roof in a lit room up at the surface. */
    static boolean inBase(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        BlockPos at = p.getBlockPos();
        if (Claims.at(w, at) != null) {
            return true;
        }
        return !w.isSkyVisible(at.up()) && !inCave(p) && w.getLightLevel(LightType.BLOCK, at) >= 8;
    }

    private static boolean busy(ServerPlayerEntity p) {
        return HUNTS.values().stream().anyMatch(h -> h.victim.equals(p.getUuid()));
    }

    private static boolean canHunt(ServerPlayerEntity p) {
        return p.isAlive() && !p.isCreative() && !p.isSpectator() && World.OVERWORLD.equals(p.getEntityWorld().getRegistryKey())
                && !Arenas.inMatch(p) && !busy(p);
    }

    public static boolean eventOn() {
        return eventUntil > now;
    }

    /** Once a minute, for everyone out at night or down in a cave: maybe it's their turn. */
    private static void roll() {
        State s = state();
        if (!s.enabled) {
            return;
        }
        var rng = Ac.server().getOverworld().getRandom();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (!canHunt(p) || s.hunted.containsKey(p.getUuidAsString())) {
                continue;
            }
            boolean cave = inCave(p);
            if (!cave && !night(p.getEntityWorld())) {
                continue;
            }
            if (rng.nextInt(Math.max(1, s.minutes)) != 0) {
                continue;
            }
            if (!cave && inBase(p)) {
                // Safe at home... nearly always.
                if (rng.nextInt(Math.max(1, s.breakInOdds)) == 0) {
                    appear(p, Mode.BREAK_IN);
                }
                continue;
            }
            Mode m = Mode.STALK;
            if (cave) {
                int r = rng.nextInt(3);
                m = r == 0 ? Mode.SCARE : r == 1 ? Mode.PEEK : Mode.STALK;
            }
            appear(p, m);
        }
    }

    /**
     * Every second: the players it follows (until they die), everyone during The Boiling Night, and those marked
     * that night. In their base, it breaks in; anywhere else, it stalks them.
     */
    private static void pursue() {
        State s = state();
        if (!s.enabled) {
            return;
        }
        boolean event = eventOn();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            boolean hunted = s.hunted.containsKey(p.getUuidAsString());
            boolean marked = event && MARKED.contains(p.getUuid());
            if (!(hunted || event) || !canHunt(p) || now < NEXT.getOrDefault(p.getUuid(), 0L)) {
                continue;
            }
            NEXT.put(p.getUuid(), now + 20L * (marked ? 20 : hunted ? 60 : 90));
            if (inBase(p)) {
                if (hunted || marked) {
                    appear(p, Mode.BREAK_IN);
                }
                continue;
            }
            appear(p, inCave(p) && p.getRandom().nextInt(3) == 0 ? Mode.PEEK : Mode.STALK);
        }
    }

    /** The Boiling Night starts by itself now and then, as night falls. */
    private static void nightfall() {
        State s = state();
        ServerWorld ow = Ac.server().getOverworld();
        long t = ow.getTimeOfDay() % 24000L;
        long day = ow.getTimeOfDay() / 24000L;
        if (eventOn()) {
            if (t >= 23000 || t < 12000) {
                stopEvent(false);
            }
            return;
        }
        if (s.enabled && s.eventNights > 0 && t >= 13000 && t < 13400 && day != lastNightRolled) {
            lastNightRolled = day;
            if (ow.getRandom().nextInt(s.eventNights) == 0) {
                startEvent();
            }
        }
    }

    static void startEvent() {
        long t = Ac.server().getOverworld().getTimeOfDay() % 24000L;
        // Until sunrise (or ten minutes, if it's started in the day).
        eventUntil = now + (t >= 13000 && t < 23000 ? 23000 - t : 12000);
        MARKED.clear();
        NEXT.clear();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Mc.title(p, "§4§l" + Msg.trFor(p, "boiled.event"), "§7" + Msg.trFor(p, "boiled.event-sub"), 20, 80, 30);
            Mc.sound(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f);
            Mc.sound(p, SoundEvents.AMBIENT_CAVE.value(), 1f, 0.5f);
            NEXT.put(p.getUuid(), now + 20L * (20 + p.getRandom().nextInt(60)));
        }
        Ac.LOG.info("The Boiling Night began");
    }

    static void stopEvent(boolean early) {
        eventUntil = -1;
        MARKED.clear();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Msg.send(p, early ? "boiled.event-stopped" : "boiled.event-over");
        }
        Ac.LOG.info("The Boiling Night ended");
    }

    /** A spot this far from the player where it can stand (on the ground, room for it, not in water). */
    private static Vec3d spot(ServerPlayerEntity p, double min, double max, boolean cave, boolean ahead) {
        ServerWorld w = p.getEntityWorld();
        var rng = w.getRandom();
        for (int tries = 0; tries < 40; tries++) {
            double ang = ahead ? Math.toRadians(p.getYaw() + 90 + (rng.nextDouble() - 0.5) * 140) : rng.nextDouble() * Math.PI * 2;
            double d = min + rng.nextDouble() * (max - min);
            int x = MathHelper.floor(p.getX() + Math.cos(ang) * d);
            int z = MathHelper.floor(p.getZ() + Math.sin(ang) * d);
            if (cave) {
                for (int dy = 6; dy >= -6; dy--) {
                    BlockPos at = new BlockPos(x, p.getBlockY() + dy, z);
                    if (standable(w, at, 4)) {
                        return Vec3d.ofBottomCenter(at);
                    }
                }
            } else {
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos at = new BlockPos(x, y, z);
                if (Math.abs(y - p.getBlockY()) <= 12 && standable(w, at, 4)) {
                    return Vec3d.ofBottomCenter(at);
                }
            }
        }
        return null;
    }

    /** A spot at a corner in a cave: room to stand, with rock right beside it to lean out from. */
    private static Vec3d corner(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        for (int tries = 0; tries < 12; tries++) {
            Vec3d at = spot(p, 8, 18, true, true);
            if (at == null) {
                continue;
            }
            BlockPos b = BlockPos.ofFloored(at);
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos side = b.offset(d).up();
                if (w.getBlockState(side).isSolidBlock(w, side) && w.getBlockState(side.up()).isSolidBlock(w, side.up())) {
                    return at;
                }
            }
        }
        return null;
    }

    /** A spot right behind the player (opposite where they look), with room for it to stand over them. */
    private static Vec3d behind(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) {
            return null;
        }
        look = look.normalize();
        for (double d : new double[]{2.2, 2.8, 3.4}) {
            for (double side : new double[]{0, 0.8, -0.8}) {
                Vec3d c = p.getEntityPos().subtract(look.multiply(d)).add(-look.z * side, 0, look.x * side);
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos at = BlockPos.ofFloored(c.x, p.getY() + dy, c.z);
                    if (standable(w, at, 3)) {
                        return Vec3d.ofBottomCenter(at);
                    }
                }
            }
        }
        return null;
    }

    private static boolean standable(ServerWorld w, BlockPos at, int headroom) {
        if (!w.getBlockState(at.down()).isSolidBlock(w, at.down()) || !w.getFluidState(at).isEmpty()) {
            return false;
        }
        for (int i = 0; i < headroom; i++) {
            if (!w.getBlockState(at.up(i)).getCollisionShape(w, at.up(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** It shows up for this player. @return the mob, or null if there was nowhere for it to stand */
    static MobEntity appear(ServerPlayerEntity p, Mode mode) {
        boolean cave = inCave(p);
        Vec3d at = switch (mode) {
            case BREAK_IN -> spot(p, 10, 16, false, false);
            case SCARE -> spot(p, 12, 22, true, true);
            case PEEK -> corner(p);
            case BEHIND -> behind(p);
            default -> cave ? spot(p, 16, 30, true, true) : spot(p, 30, 46, false, true);
        };
        if (at == null && mode == Mode.BREAK_IN) {
            at = spot(p, 6, 14, true, false);
        }
        if (at == null) {
            return null;
        }
        return spawn(p.getEntityWorld(), at, p, mode);
    }

    static MobEntity spawn(ServerWorld w, Vec3d at, ServerPlayerEntity victim, Mode mode) {
        MobEntity m = EntityType.WITHER_SKELETON.create(w, SpawnReason.EVENT);
        if (m == null) {
            return null;
        }
        m.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        m.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.item.ItemStack.EMPTY);
        set(m, EntityAttributes.SCALE, 1.5);
        set(m, EntityAttributes.MOVEMENT_SPEED, RUSH_SPEED);
        set(m, EntityAttributes.FOLLOW_RANGE, 96);
        set(m, EntityAttributes.STEP_HEIGHT, 1.6);
        set(m, EntityAttributes.KNOCKBACK_RESISTANCE, 1.0);
        set(m, EntityAttributes.ATTACK_DAMAGE, 0.5);
        m.setInvulnerable(true);
        m.setSilent(true);
        m.setAiDisabled(mode != Mode.BREAK_IN);
        m.setCanPickUpLoot(false);
        m.setCustomName(Text.literal("The Boiled One"));
        m.setCustomNameVisible(false);
        m.addCommandTag(TAG);
        StructureMobs.noDrops(m);
        ModelMobs.attach(m, MODEL);
        face(m, victim);
        w.spawnEntity(m);
        Hunt h = new Hunt(m, victim.getUuid(), mode, now);
        h.breathes = mode == Mode.BEHIND || w.getRandom().nextBoolean();
        HUNTS.put(m.getUuid(), h);
        switch (mode) {
            case BREAK_IN -> {
                remember(victim, BlockPos.ofFloored(at));
                rush(h, victim, true);
            }
            case PEEK -> {
                // Leaning out from behind the rock.
                ModelMobs.lean(m, 4, w.getRandom().nextBoolean() ? 18 : -18);
                if (h.breathes) {
                    breathe(victim, m, 0.8f);
                }
            }
            case BEHIND -> {
                // Bent over them, looking down.
                ModelMobs.lean(m, 26, 0);
                breathe(victim, m, 0.5f);
            }
            default -> Mc.sound(victim, SoundEvents.AMBIENT_CAVE.value(), 0.7f, 0.5f);
        }
        Ac.LOG.info("The Boiled One ({}) came for {} at {}", mode, victim.getGameProfile().name(), BlockPos.ofFloored(at).toShortString());
        return m;
    }

    private static void set(MobEntity m, RegistryEntry<EntityAttribute> a, double v) {
        var i = m.getAttributeInstance(a);
        if (i != null) {
            i.setBaseValue(v);
        }
    }

    private static void face(MobEntity m, ServerPlayerEntity p) {
        Vec3d d = p.getEyePos().subtract(m.getEyePos());
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 57.2958) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, d.horizontalLength()) * 57.2958);
        m.setYaw(yaw);
        m.setBodyYaw(yaw);
        m.setHeadYaw(yaw);
        m.setPitch(pitch);
    }

    /** Whether the player is looking right at it (and nothing's in the way). */
    static boolean looking(ServerPlayerEntity p, MobEntity m) {
        Vec3d to = m.getBoundingBox().getCenter().subtract(p.getEyePos());
        double dist = to.length();
        if (dist > 96 || dist < 0.01) {
            return false;
        }
        double tight = Math.max(0.94, 1 - 2.5 / dist);          // a big figure is easier to look at up close
        return p.getRotationVec(1f).dotProduct(to.multiply(1 / dist)) > tight && p.canSee(m);
    }

    /** Turned round toward it (not just looking up at it). */
    static boolean turnedAround(ServerPlayerEntity p, MobEntity m) {
        Vec3d to = m.getEntityPos().subtract(p.getEntityPos()).multiply(1, 0, 1);
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1);
        if (to.lengthSquared() < 1e-4 || look.lengthSquared() < 1e-4) {
            return false;
        }
        return p.getPitch() > -40 && look.normalize().dotProduct(to.normalize()) > 0.3;
    }

    private static void pack(ServerPlayerEntity p, String sound, Vec3d at, float volume, float pitch) {
        var entry = RegistryEntry.of(SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound(sound)));
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(entry, SoundCategory.HOSTILE, at.x, at.y, at.z, volume, pitch,
                p.getRandom().nextLong()));
    }

    private static void breathe(ServerPlayerEntity p, MobEntity m, float volume) {
        pack(p, "boiled_breath", m.getEyePos(), volume, 0.95f + p.getRandom().nextFloat() * 0.1f);
    }

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        now = ticks;
        if (ticks % 1200 == 600) {
            roll();
        }
        if (ticks % 20 == 0) {
            pursue();
            nightfall();
        }
        for (Hunt h : HUNTS.values()) {
            step(h);
        }
    }

    private static void vanish(Hunt h) {
        HUNTS.remove(h.mob.getUuid());
        if (!h.mob.isRemoved()) {
            ServerWorld w = (ServerWorld) h.mob.getEntityWorld();
            Vec3d c = h.mob.getBoundingBox().getCenter();
            if (h.mode != Mode.BEHIND) {
                w.spawnParticles(net.minecraft.particle.ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 30, 0.4, 1.2, 0.4, 0.01);
            }
            ModelMobs.remove(h.mob);
        }
    }

    private static void step(Hunt h) {
        MobEntity m = h.mob;
        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(h.victim);
        if (m.isRemoved() || p == null || !p.isAlive() || p.getEntityWorld() != m.getEntityWorld() || p.isSpectator()) {
            vanish(h);
            return;
        }
        ServerWorld w = p.getEntityWorld();
        double dist = m.distanceTo(p);
        switch (h.mode) {
            case STALK, SCARE, PEEK -> {
                face(m, p);
                boolean seen = looking(p, m);
                if (seen) {
                    h.seen++;
                    h.lastSeen = now;
                } else {
                    h.seen = 0;
                }
                if (eventOn() && h.mode == Mode.STALK && now % 5 == 0) {
                    markWatchers(w, m, p);
                }
                if (h.mode == Mode.SCARE && h.seen >= 3) {
                    jumpscare(p);
                    vanish(h);
                    return;
                }
                if (h.mode == Mode.PEEK && h.seen >= 30) {
                    // It slips back behind the rock.
                    vanish(h);
                    return;
                }
                if (h.mode == Mode.STALK && h.seen >= 8) {
                    rush(h, p, false);
                    return;
                }
                if (dist < (h.mode == Mode.PEEK ? 6 : 7) || now - h.born > 20 * 150
                        || (h.lastSeen >= 0 && now - h.lastSeen > 20 * 6 && dist > 50)) {
                    vanish(h);
                    return;
                }
                if (h.breathes && now % 90 == 0 && dist < 30) {
                    breathe(p, m, (float) Math.max(0.3, 1.2 - dist / 30));
                } else if (h.mode == Mode.STALK && now % 30 == 0 && dist < 48) {
                    Mc.sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, (float) Math.max(0.15, 1 - dist / 48), 0.6f);
                }
                // While nobody's looking, it creeps closer.
                if (!seen && h.mode == Mode.STALK && now - h.lastMove > 20 * 15 && dist > 14) {
                    h.lastMove = now;
                    Vec3d to = spot(p, Math.max(10, dist - 12), Math.max(12, dist - 6), inCave(p), true);
                    if (to != null) {
                        m.refreshPositionAndAngles(to.x, to.y, to.z, m.getYaw(), 0);
                    }
                }
            }
            case BEHIND -> {
                face(m, p);
                if (turnedAround(p, m) || dist > 7 || now - h.born > 20 * 30) {
                    // Turn round and nothing's there.
                    vanish(h);
                    return;
                }
                if (now % 90 == 45) {
                    breathe(p, m, 0.45f);
                }
            }
            case RUSH, BREAK_IN -> {
                m.setTarget(p);
                face(m, p);
                if (h.mode == Mode.BREAK_IN) {
                    // Straight at them, through whatever is in the way.
                    Vec3d d = p.getEntityPos().subtract(m.getEntityPos()).multiply(1, 0, 1);
                    if (d.lengthSquared() > 1) {
                        Vec3d v = d.normalize().multiply(0.32);
                        m.setVelocity(v.x, m.getVelocity().y, v.z);
                    }
                    if (now % 3 == 0) {
                        smash(w, m, d);
                    }
                }
                if (dist < 2.2 && now >= h.nextHit) {
                    h.nextHit = now + 14;
                    m.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    jumpscare(p);
                    p.damage(w, m.getDamageSources().mobAttack(m), h.mode == Mode.BREAK_IN ? BREAK_IN_DAMAGE : RUSH_DAMAGE);
                }
                long limit = h.mode == Mode.BREAK_IN ? 20 * 40 : 20 * 9;
                if (now - h.modeSince > limit || dist > 80) {
                    vanish(h);
                }
            }
        }
    }

    /** The Boiling Night: anyone else looking at it while it stalks is marked, and it will come for them. */
    private static void markWatchers(ServerWorld w, MobEntity m, ServerPlayerEntity victim) {
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o != victim && !o.isCreative() && !o.isSpectator() && !MARKED.contains(o.getUuid()) && looking(o, m)) {
                MARKED.add(o.getUuid());
                NEXT.put(o.getUuid(), now + 20L * (10 + o.getRandom().nextInt(20)));
                Msg.actionBar(o, "§4" + Msg.trFor(o, "boiled.marked"));
                Mc.sound(o, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);
            }
        }
    }

    /** Its face (a glyph from the pack) filling the screen, and its scream, as loud as the game plays anything. */
    static final char SCARE = '';

    static void jumpscare(ServerPlayerEntity p) {
        Mc.title(p, "§f" + SCARE, "", 0, 16, 6);
        for (float pitch : new float[]{1.0f, 0.85f, 1.15f}) {
            pack(p, "boiled_scream", p.getEntityPos(), 1f, pitch);
        }
        Mc.sound(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.7f);
        Mc.sound(p, SoundEvents.ENTITY_GHAST_SCREAM, 1f, 0.55f);
        Mc.sound(p, SoundEvents.ENTITY_WARDEN_ROAR, 1f, 1.3f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 50, 0, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0, false, false));
    }

    /** It sees you: a scream, and it comes for you. */
    private static void rush(Hunt h, ServerPlayerEntity p, boolean breakIn) {
        MobEntity m = h.mob;
        h.mode = breakIn ? Mode.BREAK_IN : Mode.RUSH;
        h.modeSince = now;
        ModelMobs.lean(m, 0, 0);
        m.setAiDisabled(false);
        m.setTarget(p);
        ServerWorld w = p.getEntityWorld();
        Vec3d c = m.getEntityPos();
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 3f, 0.45f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 3f, 0.7f);
        jumpscare(p);
        if (breakIn) {
            OwnerPowers.later(20, () -> Mc.title(p, "§4§l" + Msg.trFor(p, "boiled.breakin"), "", 0, 30, 10));
        }
    }

    /** Breaks the blocks in front of it, up to its full height (anything but the unbreakable). Nothing is put back. */
    private static void smash(ServerWorld w, MobEntity m, Vec3d dir) {
        if (dir.lengthSquared() < 0.01) {
            return;
        }
        Vec3d f = dir.normalize();
        BlockPos base = BlockPos.ofFloored(m.getX() + f.x * 1.1, m.getY() + 0.1, m.getZ() + f.z * 1.1);
        int broke = 0;
        for (int dy = 0; dy < 4; dy++) {
            BlockPos at = base.up(dy);
            BlockState st = w.getBlockState(at);
            float hard = st.getHardness(w, at);
            if (st.isAir() || hard < 0 || hard > 50 || st.getCollisionShape(w, at).isEmpty()) {
                continue;
            }
            w.breakBlock(at, false, m);
            broke++;
        }
        if (broke > 0) {
            w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 1.5f, 0.6f);
        }
    }

    /** Remembers a break-in: who it came for, whose base it was and where. */
    private static void remember(ServerPlayerEntity target, BlockPos from) {
        BreakIn b = new BreakIn();
        b.target = target.getGameProfile().name();
        ServerWorld w = target.getEntityWorld();
        BlockPos at = target.getBlockPos();
        var claim = Claims.at(w, at);
        if (claim != null) {
            b.claim = claim.name;
            if (claim.createdBy != null) {
                b.baseOwner = com.vylorq.anticheat.command.Args.nameOf(claim.createdBy, claim.createdBy.toString());
            }
        }
        if (b.baseOwner == null) {
            b.baseOwner = b.target;                     // no claim: their own place
        }
        b.world = com.vylorq.anticheat.util.Mc.worldId(w);
        b.x = at.getX();
        b.y = at.getY();
        b.z = at.getZ();
        b.at = System.currentTimeMillis();
        State s = state();
        s.breakIns.add(b);
        while (s.breakIns.size() > 30) {
            s.breakIns.remove(0);
        }
        save();
        Ac.LOG.info("The Boiled One broke into {}'s base ({}) for {} at {} {} {}", b.baseOwner, b.claim, b.target, b.x, b.y, b.z);
    }

    /** A player died: if it was following them, it's done. */
    private static void killed(ServerPlayerEntity p) {
        State s = state();
        MARKED.remove(p.getUuid());
        if (s.hunted.remove(p.getUuidAsString()) != null) {
            save();
            for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
                if (com.vylorq.anticheat.perm.Perms.isOwner(o.getUuid())) {
                    Msg.send(o, "boiled.hunt-done", p.getGameProfile().name());
                }
            }
        }
        for (Hunt h : HUNTS.values().toArray(new Hunt[0])) {
            if (h.victim.equals(p.getUuid())) {
                vanish(h);
            }
        }
    }

    /** Mining underground: now and then it's right behind you. */
    private static void mining(ServerPlayerEntity p) {
        State s = state();
        if (!s.enabled || !canHunt(p) || !inCave(p) || now < NEXT_BEHIND.getOrDefault(p.getUuid(), 0L)) {
            return;
        }
        boolean focus = s.hunted.containsKey(p.getUuidAsString()) || eventOn();
        if (p.getRandom().nextInt(focus ? 60 : 300) != 0) {
            return;
        }
        NEXT_BEHIND.put(p.getUuid(), now + 20L * 60 * 6);
        appear(p, Mode.BEHIND);
    }

    // ---------------------------------------------------------------- owner

    public static void ownerToggle(ServerPlayerEntity p, boolean on) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        state().enabled = on;
        save();
        Msg.send(p, on ? "boiled.on" : "boiled.off");
    }

    public static void ownerMinutes(ServerPlayerEntity p, int minutes) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        state().minutes = Math.max(1, minutes);
        save();
        Msg.send(p, "boiled.minutes", state().minutes);
    }

    /** Break-ins always (anyone in a base it comes for) or rare (1 in 100). */
    public static void ownerBreakIns(ServerPlayerEntity p, boolean always) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        state().breakInOdds = always ? 1 : 100;
        save();
        Msg.send(p, always ? "boiled.breakins-always" : "boiled.breakins-rare");
    }

    public static void ownerHunt(ServerPlayerEntity owner, ServerPlayerEntity target, boolean on) {
        if (!OwnerPowers.require(owner)) {
            return;
        }
        if (target == null) {
            Msg.send(owner, "boiled.no-player");
            return;
        }
        State s = state();
        if (on) {
            s.hunted.put(target.getUuidAsString(), target.getGameProfile().name());
            NEXT.put(target.getUuid(), now + 20L * 5);
        } else {
            s.hunted.remove(target.getUuidAsString());
            for (Hunt h : HUNTS.values().toArray(new Hunt[0])) {
                if (h.victim.equals(target.getUuid())) {
                    vanish(h);
                }
            }
        }
        save();
        Msg.send(owner, on ? "boiled.hunting" : "boiled.unhunted", target.getGameProfile().name());
        Staff.log(owner, on ? "boiled-hunt" : "boiled-unhunt", target.getUuid(), target.getGameProfile().name(), "");
    }

    public static void ownerHunted(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        var names = state().hunted.values();
        Msg.send(p, "boiled.hunted-list", names.isEmpty() ? "-" : String.join(", ", names));
    }

    public static void ownerBases(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        var list = state().breakIns;
        if (list.isEmpty()) {
            Msg.send(p, "boiled.bases-none");
            return;
        }
        for (int i = Math.max(0, list.size() - 10); i < list.size(); i++) {
            BreakIn b = list.get(i);
            String when = java.time.Instant.ofEpochMilli(b.at).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            Msg.send(p, "boiled.base-entry", when, b.baseOwner, b.claim == null ? "-" : b.claim, b.target, b.x, b.y, b.z);
        }
    }

    public static void ownerEvent(ServerPlayerEntity p, boolean on) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (on) {
            startEvent();
        } else if (eventOn()) {
            stopEvent(true);
        }
        Staff.log(p, on ? "boiled-event" : "boiled-event-stop", null, null, "");
    }

    public static void ownerInfo(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        Msg.send(p, "boiled.info", s.enabled ? "on" : "off", s.minutes, s.breakInOdds == 1 ? "always" : "1 in " + s.breakInOdds,
                HUNTS.size(), s.hunted.size(), eventOn() ? "on" : "off");
    }

    /** Sends it after a player now: watch (stalks), scare, peek (from a corner), behind, or breakin. */
    public static void ownerSend(ServerPlayerEntity owner, ServerPlayerEntity target, String how) {
        if (!OwnerPowers.require(owner)) {
            return;
        }
        if (target == null) {
            Msg.send(owner, "boiled.no-player");
            return;
        }
        Mode mode = switch (how) {
            case "scare" -> Mode.SCARE;
            case "peek" -> Mode.PEEK;
            case "behind" -> Mode.BEHIND;
            case "breakin" -> Mode.BREAK_IN;
            default -> Mode.STALK;
        };
        MobEntity m = appear(target, mode);
        Msg.send(owner, m == null ? "boiled.nowhere" : "boiled.sent", target.getGameProfile().name());
        if (m != null) {
            Staff.log(owner, "boiled-one", target.getUuid(), target.getGameProfile().name(), how);
        }
    }

    public static void ownerRemoveAll(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        int n = HUNTS.size();
        for (Hunt h : HUNTS.values().toArray(new Hunt[0])) {
            vanish(h);
        }
        Msg.send(p, "boiled.removed", n);
    }

    // ---------------------------------------------------------------- tests

    public static MobEntity spawnForTest(ServerWorld w, Vec3d at, ServerPlayerEntity victim) {
        return spawn(w, at, victim, Mode.STALK);
    }

    public static boolean rushingForTest(MobEntity m) {
        Hunt h = HUNTS.get(m.getUuid());
        return h != null && (h.mode == Mode.RUSH || h.mode == Mode.BREAK_IN);
    }

    public static void rushForTest(MobEntity m, ServerPlayerEntity p) {
        Hunt h = HUNTS.get(m.getUuid());
        if (h != null) {
            rush(h, p, false);
        }
    }

    public static void removeForTest(MobEntity m) {
        Hunt h = HUNTS.get(m.getUuid());
        if (h != null) {
            vanish(h);
        }
    }

    public static void eventForTest(boolean on) {
        if (on) {
            eventUntil = now + 2400;
        } else {
            eventUntil = -1;
        }
    }

    /** Whether someone it's following loses their place when killed (the hunt ends). */
    public static boolean huntEndsOnDeathForTest(ServerPlayerEntity p) {
        state().hunted.put(p.getUuidAsString(), p.getGameProfile().name());
        killed(p);
        return !state().hunted.containsKey(p.getUuidAsString());
    }
}
