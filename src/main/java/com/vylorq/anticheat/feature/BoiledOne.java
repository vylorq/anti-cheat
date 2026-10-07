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

    enum Mode { STALK, SCARE, PEEK, BEHIND, RUSH, BREAK_IN, GRAB, GLIMPSE }

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
        /** Standing (4+ blocks of room), crouching (3) or crawling (2) to fit where it is. */
        int room = 4;
        /** Its hand pressed against the tunnel roof (a separate model), when it crouches over someone. */
        UUID hand;
        /** Seconds-ish its victim has spent hiding from it. */
        int hidden;
        boolean leftFoot;
        /** Stalking: it stands frozen until this tick after being looked at. */
        long frozenUntil;

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

    /** Someone it could come for at all (alive, playing or in creative, in the overworld, not in an arena). */
    static boolean huntable(ServerPlayerEntity p) {
        return p.isAlive() && !p.isSpectator() && World.OVERWORLD.equals(p.getEntityWorld().getRegistryKey())
                && !Arenas.inMatch(p);
    }

    /** The owner is never picked at random (only when sent or hunted on purpose). */
    private static boolean owner(ServerPlayerEntity p) {
        return com.vylorq.anticheat.perm.Perms.isOwner(p.getUuid());
    }

    private static boolean canHunt(ServerPlayerEntity p) {
        return huntable(p) && !busy(p) && !BoiledOmens.isProtected(p);
    }

    /** The Lantern of Dawn was lit: whatever is after them now is gone. */
    static void protect(ServerPlayerEntity p) {
        for (Hunt h : HUNTS.values().toArray(new Hunt[0])) {
            if (h.victim.equals(p.getUuid()) && h.mode != Mode.GRAB) {
                vanish(h);
            }
        }
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
            if (!canHunt(p) || s.hunted.containsKey(p.getUuidAsString()) || owner(p)) {
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
                if (rng.nextBoolean()) {
                    // The torches go out first.
                    Mode then = m;
                    BoiledOmens.snuff(p, () -> {
                        if (canHunt(p)) {
                            appear(p, then);
                        }
                    });
                    continue;
                }
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
        if (eventUntil >= 0) {
            // It runs its 5-20 minutes, then ends (and survivors get their reward).
            if (now >= eventUntil) {
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
        BoiledOmens.FAILED.clear();
        BoiledOmens.FACED.clear();
        // Lasts between 5 and 20 minutes.
        eventUntil = now + 20L * 60 * (5 + Ac.server().getOverworld().getRandom().nextInt(16));
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
        if (!early) {
            BoiledOmens.survivors();
        } else {
            BoiledOmens.FAILED.clear();
            BoiledOmens.FACED.clear();
        }
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
                    if (standable(w, at, 2)) {
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
        // Room to stand over them if there is any; in a tight tunnel it crouches in it.
        for (int room : new int[]{3, 2}) {
            for (double d : new double[]{1.8, 2.4, 3.0}) {
                for (double side : new double[]{0, 0.8, -0.8}) {
                    Vec3d c = p.getEntityPos().subtract(look.multiply(d)).add(-look.z * side, 0, look.x * side);
                    for (int dy = 1; dy >= -1; dy--) {
                        BlockPos at = BlockPos.ofFloored(c.x, p.getY() + dy, c.z);
                        if (standable(w, at, room)) {
                            return Vec3d.ofBottomCenter(at);
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Blocks of open space above its feet (up to 5). */
    static int room(ServerWorld w, BlockPos feet) {
        int n = 0;
        while (n < 5 && w.getBlockState(feet.up(n)).getCollisionShape(w, feet.up(n)).isEmpty()) {
            n++;
        }
        return n;
    }

    /** Stands tall in the open, crouches under a low roof, crawls through tunnels: and shrinks to fit through them. */
    private static void posture(Hunt h, boolean force) {
        MobEntity m = h.mob;
        int room = Math.min(4, room((ServerWorld) m.getEntityWorld(), m.getBlockPos()));
        if (room == h.room && !force) {
            return;
        }
        h.room = room;
        if (room >= 4) {
            set(m, EntityAttributes.SCALE, 1.5);
            if (h.mode == Mode.PEEK) {
                ModelMobs.lean(m, 4, h.breathes ? 18 : -18);
            } else if (h.mode == Mode.BEHIND) {
                ModelMobs.lean(m, 26, 0);
            } else {
                ModelMobs.lean(m, 0, 0);
            }
        } else if (room == 3) {
            set(m, EntityAttributes.SCALE, 1.0);
            ModelMobs.pose(m, h.mode == Mode.BEHIND ? 38 : 28, 0, 0.68f);
        } else {
            // Crawling: nearly flat, low to the ground.
            set(m, EntityAttributes.SCALE, 0.55);
            ModelMobs.pose(m, h.mode == Mode.BEHIND ? 48 : 72, 0, h.mode == Mode.BEHIND ? 0.52f : 0.48f);
        }
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
        if (eventOn() && mode != Mode.GLIMPSE) {
            BoiledOmens.FACED.add(victim.getUuid());
        }
        h.breathes = mode == Mode.BEHIND || w.getRandom().nextBoolean();
        HUNTS.put(m.getUuid(), h);
        switch (mode) {
            case BREAK_IN -> {
                remember(victim, BlockPos.ofFloored(at));
                rush(h, victim, true);
            }
            case PEEK -> {
                // Leaning out from behind the rock (posture() leans it).
                if (h.breathes) {
                    breathe(victim, m, 0.8f);
                }
            }
            case BEHIND -> {
                // Bent over them, looking down; in a tunnel, crouched, a hand pressed on the roof above them.
                breathe(victim, m, 0.5f);
            }
            case GLIMPSE -> {
                // Off to the side of their view; nothing to hear.
            }
            default -> Mc.sound(victim, SoundEvents.AMBIENT_CAVE.value(), 0.7f, 0.5f);
        }
        if (mode == Mode.STALK || mode == Mode.BREAK_IN) {
            BoiledOmens.panic(w, at);
        }
        posture(h, true);
        if (mode == Mode.BEHIND && h.room < 4) {
            h.hand = handOnRoof(w, m, victim);
        }
        Ac.LOG.info("The Boiled One ({}) came for {} at {}", mode, victim.getGameProfile().name(), BlockPos.ofFloored(at).toShortString());
        return m;
    }

    /** Its clawed hand (its own model), flat against the roof just above and behind the player's head. */
    private static UUID handOnRoof(ServerWorld w, MobEntity m, ServerPlayerEntity p) {
        BlockPos head = p.getBlockPos().up();
        int roof = -1;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos b = head.up(dy);
            if (!w.getBlockState(b).getCollisionShape(w, b).isEmpty()) {
                roof = b.getY();
                break;
            }
        }
        if (roof < 0) {
            return null;
        }
        Vec3d toward = m.getEntityPos().subtract(p.getEntityPos()).multiply(1, 0, 1);
        Vec3d c = p.getEntityPos().add(toward.lengthSquared() > 1e-4 ? toward.normalize().multiply(0.5) : Vec3d.ZERO);
        Vec3d at = new Vec3d(c.x, roof - 0.06, c.z);
        // Palm up against the stone (its painted side facing down at the player), fingers toward them.
        UUID id = OwnerCombat.Display.summon(w, at, "minecraft:nautilus_shell", "boiled_hand__body",
                "{left_rotation:[-0.7071f,0f,0f,0.7071f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.9f,0.9f,0.9f]}");
        if (id != null) {
            var d = w.getEntity(id);
            if (d != null) {
                d.refreshPositionAndAngles(at.x, at.y, at.z, m.getYaw() + 180, 0);
                d.addCommandTag(ModelMobs.DISPLAY_TAG);
                try {
                    Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(),
                            "data merge entity " + id + " {brightness:{sky:5,block:5}}");
                } catch (Exception ignored) {
                    // it still shows
                }
            }
        }
        return id;
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

    // ---------------------------------------------------------------- glimpses

    /**
     * A glimpse: it stands off to the side, at the very edge of the screen (38-48 degrees from where they're looking;
     * the normal view reaches about 50 to each side), close enough to make out, with nothing in the way. The moment they start to turn toward it, it's gone,
     * so they're never sure they saw it.
     */
    static MobEntity glimpse(ServerPlayerEntity p) {
        if (!canHunt(p)) {
            return null;
        }
        ServerWorld w = p.getEntityWorld();
        boolean cave = inCave(p);
        var rng = w.getRandom();
        for (int tries = 0; tries < 30; tries++) {
            double side = (rng.nextBoolean() ? 1 : -1) * (38 + rng.nextDouble() * 10);
            double ang = Math.toRadians(p.getYaw() + 90 + side);
            double d = cave ? 8 + rng.nextDouble() * 8 : 12 + rng.nextDouble() * 12;
            int x = MathHelper.floor(p.getX() + Math.cos(ang) * d);
            int z = MathHelper.floor(p.getZ() + Math.sin(ang) * d);
            for (int dy = 4; dy >= -4; dy--) {
                BlockPos at = new BlockPos(x, p.getBlockY() + dy, z);
                if (!standable(w, at, cave ? 2 : 4)) {
                    continue;
                }
                Vec3d spot = Vec3d.ofBottomCenter(at);
                // It must be in plain sight from their eyes.
                var hit = w.raycast(new net.minecraft.world.RaycastContext(p.getEyePos(), spot.add(0, 1.5, 0),
                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
                if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                    continue;
                }
                return spawn(w, spot, p, Mode.GLIMPSE);
            }
        }
        return null;
    }

    /** How far (degrees) from where they look it stands. */
    static double offView(ServerPlayerEntity p, MobEntity m) {
        Vec3d to = m.getBoundingBox().getCenter().subtract(p.getEyePos());
        if (to.lengthSquared() < 1e-4) {
            return 0;
        }
        double c = p.getRotationVec(1f).dotProduct(to.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, c))));
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
            BoiledOmens.tick(ticks);
        }
        for (Hunt h : HUNTS.values()) {
            step(h);
        }
        if (ticks % 10 == 0 && !ESP.isEmpty()) {
            espTick();
        }
    }

    private static void vanish(Hunt h) {
        HUNTS.remove(h.mob.getUuid());
        if (h.hand != null) {
            var d = ((ServerWorld) h.mob.getEntityWorld()).getEntity(h.hand);
            if (d != null) {
                d.discard();
            }
        }
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
        if (now % 5 == 0 && h.mode != Mode.BEHIND) {
            posture(h, false);
        }
        if (h.mode != Mode.BEHIND && h.mode != Mode.GLIMPSE && h.mode != Mode.GRAB) {
            BoiledOmens.interference(p, m, dist, now);
            // Hiding (crouched, still, in a tight dark spot or behind a closed door): it loses them.
            if (now % 10 == 0) {
                h.hidden = BoiledOmens.hiding(p) && dist > 3 ? h.hidden + 1 : 0;
                if (h.hidden >= 6) {
                    breathe(p, m, 0.9f);
                    Msg.actionBar(p, "§8" + Msg.trFor(p, "boiled.lost-you"));
                    vanish(h);
                    return;
                }
            }
        }
        switch (h.mode) {
            case GLIMPSE -> {
                face(m, p);
                // Gone the moment they turn toward it.
                if (offView(p, m) < 24 || now - h.born > 50 || dist < 5) {
                    HUNTS.remove(m.getUuid());
                    ModelMobs.remove(m);
                    return;
                }
            }
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
                if (h.mode == Mode.STALK) {
                    // Looked at: it freezes for a few seconds, then keeps coming (slower while watched).
                    if (h.seen == 1) {
                        h.frozenUntil = now + 20 * (3 + p.getRandom().nextInt(3));
                    }
                    if (dist < 2.4) {
                        grab(h, p);
                        return;
                    }
                    if (now >= h.frozenUntil) {
                        walk(m, p, seen ? 0.09 : 0.16);
                        if (now % 9 == 0) {
                            h.leftFoot = !h.leftFoot;
                            BoiledOmens.footprint(w, m, h.leftFoot);
                        }
                    }
                }
                if ((h.mode != Mode.STALK && dist < (h.mode == Mode.PEEK ? 6 : 7)) || now - h.born > 20 * 150
                        || (h.lastSeen >= 0 && now - h.lastSeen > 20 * 6 && dist > 50)) {
                    vanish(h);
                    return;
                }
                if (h.breathes && now % 90 == 0 && dist < 30) {
                    breathe(p, m, (float) Math.max(0.3, 1.2 - dist / 30));
                } else if (h.mode == Mode.STALK && now % 30 == 0 && dist < 48) {
                    Mc.sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, (float) Math.max(0.15, 1 - dist / 48), 0.6f);
                }
                // Far off and unwatched, it closes the gap out of sight.
                if (!seen && h.mode == Mode.STALK && now - h.lastMove > 20 * 15 && dist > 40) {
                    h.lastMove = now;
                    Vec3d to = spot(p, Math.max(24, dist - 16), Math.max(28, dist - 10), inCave(p), true);
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
            case GRAB -> held(h, p, w);
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
                    if (now % 9 == 0 && m.getVelocity().horizontalLengthSquared() > 0.005) {
                        h.leftFoot = !h.leftFoot;
                        BoiledOmens.footprint(w, m, h.leftFoot);
                    }
                }
                if (dist < 2.4) {
                    grab(h, p);
                    return;
                }
                long limit = h.mode == Mode.BREAK_IN ? 20 * 40 : 20 * 9;
                if (now - h.modeSince > limit || dist > 80) {
                    vanish(h);
                }
            }
        }
    }

    /** It caught them: it grabs them and lifts them up to its face. */
    private static void grab(Hunt h, ServerPlayerEntity p) {
        MobEntity m = h.mob;
        h.mode = Mode.GRAB;
        h.modeSince = now;
        m.setTarget(null);
        m.setAiDisabled(true);
        m.setVelocity(Vec3d.ZERO);
        m.swingHand(net.minecraft.util.Hand.MAIN_HAND);
        ModelMobs.act(m, ModelMobs.GRAB);
        ServerWorld w = p.getEntityWorld();
        w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2f, 0.6f);
        breathe(p, m, 1.4f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 6, false, false));
    }

    /** Held up in front of its face, made to look at it; then it bites their head, and they die. */
    private static void held(Hunt h, ServerPlayerEntity p, ServerWorld w) {
        MobEntity m = h.mob;
        long t = now - h.modeSince;
        face(m, p);
        Vec3d fwd = Vec3d.fromPolar(0, m.getYaw());
        double lift = Math.min(m.getHeight() * 0.62, 2.6);
        Vec3d hold = m.getEntityPos().add(fwd.multiply(Math.max(0.9, m.getWidth() * 1.4))).add(0, lift, 0);
        Vec3d eye = hold.add(0, p.getStandingEyeHeight(), 0);
        Vec3d to = m.getEyePos().subtract(eye);
        float yaw = (float) (MathHelper.atan2(to.z, to.x) * 57.2958) - 90f;
        float pitch = (float) -(MathHelper.atan2(to.y, to.horizontalLength()) * 57.2958);
        // Struggling a little in its grip.
        double shake = Math.sin(t * 1.7) * 0.04;
        p.networkHandler.requestTeleport(hold.x + shake, hold.y, hold.z - shake, yaw, pitch);
        p.setVelocity(Vec3d.ZERO);
        p.onLanding();
        if (t % 8 == 0 && t < 28) {
            p.damage(w, m.getDamageSources().mobAttack(m), 1f);
        }
        if (t == 28) {
            ModelMobs.act(m, ModelMobs.BITE);
        }
        if (t == 32) {
            Vec3d head = p.getEyePos();
            w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(net.minecraft.particle.ParticleTypes.BLOCK,
                    net.minecraft.block.Blocks.REDSTONE_BLOCK.getDefaultState()), head.x, head.y, head.z, 80, 0.3, 0.3, 0.3, 0.2);
            w.spawnParticles(new net.minecraft.particle.DustParticleEffect(0x8A0000, 1.6f), head.x, head.y, head.z, 60, 0.4, 0.5, 0.4, 0);
            w.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.HOSTILE, 2f, 0.5f);
            w.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 2f, 1.4f);
            w.playSound(null, head.x, head.y, head.z, SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.HOSTILE, 2f, 0.6f);
            jumpscare(p);
            p.damage(w, m.getDamageSources().mobAttack(m), 1000f);
            if (p.isAlive()) {
                // Creative players can't be hurt: it kills them anyway.
                p.damage(w, w.getDamageSources().genericKill(), Float.MAX_VALUE);
            }
            if (p.isAlive()) {
                p.setHealth(0f);
                p.onDeath(m.getDamageSources().mobAttack(m));
            }
        }
        if (t > 50) {
            vanish(h);
        }
    }

    /** The Boiling Night: anyone else looking at it while it stalks is marked, and it will come for them. */
    private static void markWatchers(ServerWorld w, MobEntity m, ServerPlayerEntity victim) {
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o != victim && !o.isSpectator() && !MARKED.contains(o.getUuid()) && looking(o, m)) {
                MARKED.add(o.getUuid());
                BoiledOmens.FAILED.add(o.getUuid());
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
    /** One step toward the player with its AI off: climbs a block, falls with gravity. */
    private static void walk(MobEntity m, ServerPlayerEntity p, double speed) {
        Vec3d d = p.getEntityPos().subtract(m.getEntityPos()).multiply(1, 0, 1);
        if (d.lengthSquared() < 0.01) {
            return;
        }
        set(m, EntityAttributes.STEP_HEIGHT, 1.1);
        Vec3d v = d.normalize().multiply(speed);
        m.move(net.minecraft.entity.MovementType.SELF, new Vec3d(v.x, -0.5, v.z));
        face(m, p);
    }

    private static void rush(Hunt h, ServerPlayerEntity p, boolean breakIn) {
        MobEntity m = h.mob;
        h.mode = breakIn ? Mode.BREAK_IN : Mode.RUSH;
        h.modeSince = now;
        posture(h, true);
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
        if (eventOn()) {
            BoiledOmens.FAILED.add(p.getUuid());
        }
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
        if (!s.enabled || !canHunt(p) || owner(p) || !inCave(p) || now < NEXT_BEHIND.getOrDefault(p.getUuid(), 0L)) {
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

    // ---------------------------------------------------------------- locator (owner ESP)

    /** Owners with the locator on: every Boiled One glows for them (Java) and the nearest shows on the action bar. */
    private static final Set<UUID> ESP = new java.util.HashSet<>();

    /** /owner boiledone locate: lists every Boiled One out now and turns the locator on or off. */
    public static void ownerLocate(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (ESP.remove(p.getUuid())) {
            espPush(p, false);
            Msg.send(p, "boiled.locate-off");
            return;
        }
        ESP.add(p.getUuid());
        Msg.send(p, "boiled.locate-on", HUNTS.size());
        for (Hunt h : HUNTS.values()) {
            MobEntity m = h.mob;
            ServerPlayerEntity v = Ac.server().getPlayerManager().getPlayer(h.victim);
            Msg.send(p, "boiled.locate-entry", Mc.worldId(m.getEntityWorld()).replace("minecraft:", ""),
                    m.getBlockX(), m.getBlockY(), m.getBlockZ(), v == null ? "?" : v.getGameProfile().name(), h.mode.name().toLowerCase());
        }
        espPush(p, true);
    }

    private static void espPush(ServerPlayerEntity p, boolean glow) {
        for (Hunt h : HUNTS.values()) {
            if (h.mob.getEntityWorld() == p.getEntityWorld()) {
                p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket(h.mob.getId(), List.of(
                        new net.minecraft.entity.data.DataTracker.SerializedEntry<>(0,
                                net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE, OwnerPowers.flags(h.mob, glow)))));
            }
        }
    }

    private static void espTick() {
        for (UUID id : ESP.toArray(new UUID[0])) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
            if (p == null) {
                ESP.remove(id);
                continue;
            }
            espPush(p, true);
            Hunt near = null;
            double best = Double.MAX_VALUE;
            for (Hunt h : HUNTS.values()) {
                if (h.mob.getEntityWorld() == p.getEntityWorld() && h.mob.squaredDistanceTo(p) < best) {
                    best = h.mob.squaredDistanceTo(p);
                    near = h;
                }
            }
            if (near == null) {
                Msg.actionBar(p, "§8" + Msg.trFor(p, "boiled.locate-none"));
            } else {
                ServerPlayerEntity v = Ac.server().getPlayerManager().getPlayer(near.victim);
                MobEntity m = near.mob;
                Msg.actionBar(p, "§4☠ §f" + m.getBlockX() + " " + m.getBlockY() + " " + m.getBlockZ() + " §7(" + (int) Math.sqrt(best)
                        + "m) §c" + (v == null ? "?" : v.getGameProfile().name()) + " §8" + near.mode.name().toLowerCase());
            }
        }
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
        switch (how) {
            case "footsteps" -> {
                BoiledOmens.footsteps(target);
                Msg.send(owner, "boiled.sent", target.getGameProfile().name());
                return;
            }
            case "knock" -> {
                BoiledOmens.knock(target);
                Msg.send(owner, "boiled.sent", target.getGameProfile().name());
                return;
            }
            case "door" -> {
                BoiledOmens.door(target);
                Msg.send(owner, "boiled.sent", target.getGameProfile().name());
                return;
            }
            case "whisper" -> {
                BoiledOmens.whisper(target);
                Msg.send(owner, "boiled.sent", target.getGameProfile().name());
                return;
            }
            case "torches" -> {
                BoiledOmens.snuff(target, null);
                Msg.send(owner, "boiled.sent", target.getGameProfile().name());
                return;
            }
            case "glimpse" -> {
                MobEntity g = glimpse(target);
                Msg.send(owner, g == null ? "boiled.nowhere" : "boiled.sent", target.getGameProfile().name());
                return;
            }
            default -> {
            }
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

    public static void ownerLantern(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        p.getInventory().offerOrDrop(BoiledOmens.lantern());
        Msg.send(p, "boiled.lantern-given");
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

    public static boolean grabForTest(MobEntity m, ServerPlayerEntity p) {
        Hunt h = HUNTS.get(m.getUuid());
        if (h == null) {
            return false;
        }
        grab(h, p);
        return h.mode == Mode.GRAB && m.isAiDisabled();
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
