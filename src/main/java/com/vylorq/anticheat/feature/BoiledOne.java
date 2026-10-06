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
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Boiled One: not a boss, a thing that hunts players.
 *
 * <p>At night on the surface, and at any time down in caves, it now and then shows up far away and just stands there,
 * staring at one player, creeping closer while they aren't looking. If they look at it, it screams and rushes them,
 * very fast, trying to kill them; if they get away it's gone. In caves it sometimes only wants to scare: look at it and
 * it's gone. Someone hiding in their base is usually safe, but one time in a hundred it breaks in, straight through
 * the walls (nothing it breaks is put back), and goes for them.</p>
 *
 * <p>It can't be hurt. Java players see the painted figure (a picture that always faces them); Bedrock players see
 * the mob it's built on.</p>
 */
public final class BoiledOne {
    private BoiledOne() {
    }

    public static final String TAG = "vigil_boiled_one";
    static final String MODEL = "boiled_one";

    enum Mode { STALK, SCARE, RUSH, BREAK_IN }

    public static final class State {
        public boolean enabled = true;
        /** On average one sighting per this many minutes a player spends out at night or down in caves. */
        public int minutes = 90;
        /** One sighting in this many, for someone in their base, is a break-in. */
        public int breakInOdds = 100;
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

    private static boolean canHunt(ServerPlayerEntity p) {
        return p.isAlive() && !p.isCreative() && !p.isSpectator() && World.OVERWORLD.equals(p.getEntityWorld().getRegistryKey())
                && !Arenas.inMatch(p) && HUNTS.values().stream().noneMatch(h -> h.victim.equals(p.getUuid()));
    }

    /** Once a minute, for everyone out at night or down in a cave: maybe it's their turn. */
    private static void roll() {
        State s = state();
        if (!s.enabled) {
            return;
        }
        var rng = Ac.server().getOverworld().getRandom();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (!canHunt(p)) {
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
            appear(p, cave && rng.nextBoolean() ? Mode.SCARE : Mode.STALK);
        }
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
                    if (standable(w, at)) {
                        return Vec3d.ofBottomCenter(at);
                    }
                }
            } else {
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos at = new BlockPos(x, y, z);
                if (Math.abs(y - p.getBlockY()) <= 12 && standable(w, at)) {
                    return Vec3d.ofBottomCenter(at);
                }
            }
        }
        return null;
    }

    private static boolean standable(ServerWorld w, BlockPos at) {
        if (!w.getBlockState(at.down()).isSolidBlock(w, at.down()) || !w.getFluidState(at).isEmpty()) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
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
            default -> cave ? spot(p, 16, 30, true, true) : spot(p, 30, 46, false, true);
        };
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
        HUNTS.put(m.getUuid(), h);
        if (mode == Mode.BREAK_IN) {
            rush(h, victim, true);
        } else {
            Mc.sound(victim, SoundEvents.AMBIENT_CAVE.value(), 0.7f, 0.5f);
        }
        Ac.LOG.info("The Boiled One {} {} at {}", mode == Mode.BREAK_IN ? "broke in on" : "is watching",
                victim.getGameProfile().name(), BlockPos.ofFloored(at).toShortString());
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

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        now = ticks;
        if (ticks % 1200 == 600) {
            roll();
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
            w.spawnParticles(net.minecraft.particle.ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 30, 0.4, 1.2, 0.4, 0.01);
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
            case STALK, SCARE -> {
                face(m, p);
                boolean seen = looking(p, m);
                if (seen) {
                    h.seen++;
                    h.lastSeen = now;
                } else {
                    h.seen = 0;
                }
                if (h.mode == Mode.SCARE && h.seen >= 3) {
                    // Just wanted you to see it.
                    Mc.sound(p, SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, 0.9f, 0.5f);
                    vanish(h);
                    return;
                }
                if (h.mode == Mode.STALK && h.seen >= 8) {
                    rush(h, p, false);
                    return;
                }
                if (dist < 7 || now - h.born > 20 * 150 || (h.lastSeen >= 0 && now - h.lastSeen > 20 * 6 && dist > 50)) {
                    vanish(h);
                    return;
                }
                if (now % 30 == 0 && dist < 48) {
                    Mc.sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, (float) Math.max(0.15, 1 - dist / 48), 0.6f);
                }
                // While nobody's looking, it creeps closer.
                if (!seen && h.mode == Mode.STALK && now - h.lastMove > 20 * 15 && dist > 14 && !looking(p, m)) {
                    h.lastMove = now;
                    Vec3d to = spot(p, Math.max(10, dist - 12), Math.max(12, dist - 6), inCave(p), true);
                    if (to != null) {
                        m.refreshPositionAndAngles(to.x, to.y, to.z, m.getYaw(), 0);
                    }
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
                    p.damage(w, m.getDamageSources().mobAttack(m), h.mode == Mode.BREAK_IN ? BREAK_IN_DAMAGE : RUSH_DAMAGE);
                }
                long limit = h.mode == Mode.BREAK_IN ? 20 * 40 : 20 * 9;
                if (now - h.modeSince > limit || dist > 80) {
                    vanish(h);
                }
            }
        }
    }

    /** It sees you: a scream, and it comes for you. */
    private static void rush(Hunt h, ServerPlayerEntity p, boolean breakIn) {
        MobEntity m = h.mob;
        h.mode = breakIn ? Mode.BREAK_IN : Mode.RUSH;
        h.modeSince = now;
        m.setAiDisabled(false);
        m.setTarget(p);
        ServerWorld w = p.getEntityWorld();
        Vec3d c = m.getEntityPos();
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 3f, 0.45f);
        w.playSound(null, c.x, c.y, c.z, SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 3f, 0.7f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false));
        if (breakIn) {
            Mc.title(p, "§4§l" + Msg.trFor(p, "boiled.breakin"), "", 0, 30, 10);
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

    public static void ownerInfo(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        Msg.send(p, "boiled.info", s.enabled ? "on" : "off", s.minutes, s.breakInOdds, HUNTS.size());
    }

    /** Sends it after a player now: "watch" (stalks), "scare" (gone when seen) or "breakin". */
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
}
