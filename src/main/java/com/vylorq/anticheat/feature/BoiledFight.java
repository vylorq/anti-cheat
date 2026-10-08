package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.gui.Menu;
import com.vylorq.anticheat.ui.Btn;
import com.vylorq.anticheat.ui.Theme;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Boiling: The Boiled One's own dimension (data/vigil/dimension/boiling.json: a flat, crimson, fog-choked world)
 * and the fight against it there, five phases of {@link #PHASE_HP} health each.
 *
 * <p>Now and then, at night, a bleeding door stands somewhere near a player. Whoever opens it first chooses who comes:
 * the whole server, or the players they pick. Those who say yes are taken into The Boiling with them, where it waits.
 * Dying there doesn't kill you: you wake back where you were, out of the fight. Survivors of a win get its heart.</p>
 *
 * <p>Phases: 1 The Stalker (it lunges at you), 2 Boiling Blood (pools of blood burst under your feet), 3 Many of Him
 * (copies of it; only the real one bleeds), 4 Lights Out (darkness; it grabs and bites), 5 Rage (all of it, faster,
 * screaming in your face).</p>
 */
public final class BoiledFight {
    private BoiledFight() {
    }

    public static final RegistryKey<World> BOILING = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("vigil", "boiling"));
    public static final int PHASES = 5;
    public static final double PHASE_HP = 20_000;
    /** It's this many times harder to hurt than its health says: every hit counts this much less. */
    public static final double HARDNESS = 5;
    /** The door turns up at most once in this long (real time). */
    static final long DOOR_COOLDOWN = 7L * 24 * 60 * 60 * 1000;
    /** Unopened, the door crumbles after this long. */
    static final long DOOR_LIFE = 10L * 60 * 1000;
    public static final int FLOOR = 5;          // where you stand in The Boiling (bedrock, blackstone, nylium below)
    static final int ARENA = 30;         // the arena's radius (its world border is a little wider)
    private static final String TAG = "vigil_boiling";
    private static final String NAME = "The Boiled One";
    private static final String[] PHASE_NAMES = {"The Stalker", "Boiling Blood", "Many of Him", "Lights Out", "Rage"};

    // ---------------------------------------------------------------- saved state

    public static final class State {
        public long lastDoorAt;
        public int wins;
        public int fights;
        public boolean arenaBuilt;
        /** The door standing now (world, x, y, z, facing), or null. */
        public String doorWorld;
        public long door;
        public String doorFacing;
        public long doorAt;
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("boiled_fight.json");
    }

    static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read boiled_fight.json", e);
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
            Ac.LOG.warn("Could not save boiled_fight.json", e);
        }
    }

    // ---------------------------------------------------------------- the fight

    enum Stage { INVITE, ENTER, FIGHT, OVER }

    private record Back(RegistryKey<World> world, double x, double y, double z, float yaw, float pitch) {
    }

    private record Pool(Vec3d at, long burstAt) {
    }

    static final class Fight {
        Stage stage = Stage.INVITE;
        UUID finder;
        final Set<UUID> invited = new LinkedHashSet<>();
        final Set<UUID> joined = new LinkedHashSet<>();
        final Set<UUID> inside = new LinkedHashSet<>();
        final Set<UUID> fallen = new LinkedHashSet<>();
        final Map<UUID, Back> back = new HashMap<>();
        MobEntity boss;
        final List<MobEntity> decoys = new ArrayList<>();
        final List<Pool> pools = new ArrayList<>();
        int phase = 1;
        double hp = PHASE_HP;
        long stageUntil;
        long calmUntil;
        long nextLunge;
        long lungeHit = -1;
        long nextPools;
        long nextShuffle;
        long nextDecoys;
        long nextGrab;
        long grabbing = -1;
        UUID grabbed;
        long nextScare;
        boolean won;
        ServerBossBar bar;
        /** How much each player took off it, for the top damage reward. */
        final Map<UUID, Double> dealt = new HashMap<>();
    }

    private static Fight fight;
    private static long now;

    public static boolean active() {
        return fight != null;
    }

    public static ServerWorld boiling() {
        return Ac.server().getWorld(BOILING);
    }

    public static boolean inBoiling(Entity e) {
        return e.getEntityWorld().getRegistryKey() == BOILING;
    }

    // ---------------------------------------------------------------- events

    public static void register() {
        // Opening the door.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || world.isClient() || !isDoor(world, hit.getBlockPos())) {
                return ActionResult.PASS;
            }
            found(p);
            return ActionResult.FAIL;
        });
        // Its health is its own (five bars of 20,000): every hit is counted here, and the mob itself never gets hurt.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!Ac.running() || fight == null || !(entity instanceof MobEntity m)) {
                return true;
            }
            if (m == fight.boss) {
                hurt(source, amount);
                return false;
            }
            if (fight.decoys.contains(m)) {
                pop(m, source);
                return false;
            }
            return true;
        });
        // Dying in The Boiling: you wake back where you came from, out of the fight, with everything you had.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!Ac.running() || !(entity instanceof ServerPlayerEntity p) || !inBoiling(p)) {
                return true;
            }
            p.setHealth(p.getMaxHealth());
            p.extinguish();
            p.clearStatusEffects();
            p.getHungerManager().setFoodLevel(20);
            if (fight != null && fight.inside.remove(p.getUuid())) {
                fight.fallen.add(p.getUuid());
                Msg.send(p, "boilfight.fell");
                tellInside("boilfight.someone-fell", p.getGameProfile().name());
            }
            sendBack(p);
            return false;
        });
        // Nobody stays in The Boiling once the fight's over (logging out there, a restart...).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.player;
            server.execute(() -> {
                if (Ac.running() && inBoiling(p) && (fight == null || !fight.inside.contains(p.getUuid()))) {
                    sendBack(p);
                }
            });
        });
    }

    public static void tick(long ticks) {
        now = ticks;
        if (ticks % 1200 == 300) {
            rollDoor();
        }
        if (ticks % 20 == 0) {
            doorTick();
            sweep();
        }
        if (fight == null) {
            return;
        }
        switch (fight.stage) {
            case INVITE -> {
                if (ticks % 20 == 0) {
                    long left = (fight.stageUntil - now) / 20;
                    for (UUID id : fight.invited) {
                        ServerPlayerEntity p = online(id);
                        if (p != null && !fight.joined.contains(id)) {
                            Msg.actionBar(p, "§4" + Msg.trFor(p, "boilfight.invite-bar", left));
                        }
                    }
                }
                if (now >= fight.stageUntil || (!fight.invited.isEmpty() && fight.joined.containsAll(fight.invited))) {
                    enter();
                }
            }
            case ENTER -> {
                if (now >= fight.stageUntil) {
                    awaken();
                }
            }
            case FIGHT -> fightTick();
            case OVER -> {
                if (now >= fight.stageUntil) {
                    finish();
                }
            }
        }
    }

    // ---------------------------------------------------------------- the door

    private static boolean isDoor(World w, BlockPos pos) {
        State s = state();
        if (s.doorWorld == null || !s.doorWorld.equals(Mc.worldId(w))) {
            return false;
        }
        BlockPos d = BlockPos.fromLong(s.door);
        return pos.equals(d) || pos.equals(d.up());
    }

    /** Every minute at night: a small chance the door turns up near someone (once a week at most). */
    private static void rollDoor() {
        State s = state();
        if (fight != null || s.doorWorld != null || System.currentTimeMillis() - s.lastDoorAt < DOOR_COOLDOWN) {
            return;
        }
        ServerWorld ow = Ac.server().getOverworld();
        long t = ow.getTimeOfDay() % 24000;
        if (t < 13000 || t > 23000 || ow.getRandom().nextInt(30) != 0) {
            return;
        }
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : ow.getPlayers()) {
            if (!p.isSpectator() && !LobbyFeature.in(p) && !WaitingRoomFeature.waiting(p)) {
                out.add(p);
            }
        }
        if (!out.isEmpty()) {
            placeDoor(out.get(ow.getRandom().nextInt(out.size())), false);
        }
    }

    /** Puts the door on the ground near a player (right in front of them for the owner's command). */
    public static boolean placeDoor(ServerPlayerEntity p, boolean close) {
        ServerWorld w = p.getEntityWorld();
        var rng = w.getRandom();
        for (int tries = 0; tries < 40; tries++) {
            double ang = close ? Math.toRadians(p.getYaw() + 90) : rng.nextDouble() * Math.PI * 2;
            double dist = close ? 3 : 14 + rng.nextInt(10);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos base = new BlockPos(x, y, z);
            Direction facing = Direction.fromHorizontalDegrees(p.getYaw()).getOpposite();
            if (!fits(w, base, facing)) {
                continue;
            }
            build(w, base, facing);
            State s = state();
            s.doorWorld = Mc.worldId(w);
            s.door = base.asLong();
            s.doorFacing = facing.asString();
            s.doorAt = System.currentTimeMillis();
            s.lastDoorAt = s.doorAt;
            save();
            w.playSound(null, base, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, 1.5f, 0.5f);
            Ac.LOG.info("The Boiled Door stands at {} {}", s.doorWorld, base.toShortString());
            return true;
        }
        return false;
    }

    /** The door and its frame need air (or plants) to stand in, and solid ground under them. */
    private static boolean fits(ServerWorld w, BlockPos base, Direction facing) {
        Direction side = facing.rotateYClockwise();
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                BlockState st = w.getBlockState(base.offset(side, dx).up(dy));
                if (!st.isAir() && !st.isReplaceable()) {
                    return false;
                }
            }
        }
        return w.getBlockState(base.down()).isSolidBlock(w, base.down()) && !w.getFluidState(base).isStill();
    }

    /** A crimson door in a frame of black stone, dripping. */
    private static void build(ServerWorld w, BlockPos base, Direction facing) {
        Direction side = facing.rotateYClockwise();
        BlockState frame = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        for (int dy = 0; dy <= 3; dy++) {
            w.setBlockState(base.offset(side, -1).up(dy), frame, Block.NOTIFY_ALL);
            w.setBlockState(base.offset(side, 1).up(dy), frame, Block.NOTIFY_ALL);
        }
        w.setBlockState(base.up(3), Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockState door = Blocks.CRIMSON_DOOR.getDefaultState().with(DoorBlock.FACING, facing);
        w.setBlockState(base, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        w.setBlockState(base.up(), door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
    }

    /** Takes the door away (opened, crumbled, or the owner's stop). */
    static void removeDoor() {
        State s = state();
        if (s.doorWorld == null) {
            return;
        }
        ServerWorld w = Mc.world(Ac.server(), s.doorWorld);
        if (w != null) {
            BlockPos base = BlockPos.fromLong(s.door);
            Direction facing = Direction.NORTH;
            for (Direction d : Direction.values()) {
                if (d.asString().equals(s.doorFacing)) {
                    facing = d;
                }
            }
            Direction side = facing.rotateYClockwise();
            w.setBlockState(base.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            w.setBlockState(base, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            for (int dy = 0; dy <= 3; dy++) {
                w.setBlockState(base.offset(side, -1).up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                w.setBlockState(base.offset(side, 1).up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            w.setBlockState(base.up(3), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5, 40, 0.6, 1, 0.6, 0.02);
        }
        s.doorWorld = null;
        save();
    }

    /** Every second: the door drips and breathes, and crumbles if nobody opens it in time. */
    private static void doorTick() {
        State s = state();
        if (s.doorWorld == null) {
            return;
        }
        if (System.currentTimeMillis() - s.doorAt > DOOR_LIFE) {
            removeDoor();
            return;
        }
        ServerWorld w = Mc.world(Ac.server(), s.doorWorld);
        if (w == null) {
            return;
        }
        BlockPos base = BlockPos.fromLong(s.door);
        if (!w.isChunkLoaded(base)) {
            return;
        }
        if (!w.getBlockState(base).isOf(Blocks.CRIMSON_DOOR)) {
            // broken by someone: it's gone
            s.doorWorld = null;
            save();
            return;
        }
        w.spawnParticles(new DustParticleEffect(0x7A0000, 1.4f), base.getX() + 0.5, base.getY() + 1.6, base.getZ() + 0.5,
                6, 0.3, 0.9, 0.3, 0);
        w.spawnParticles(ParticleTypes.DRIPPING_LAVA, base.getX() + 0.5, base.getY() + 2.2, base.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0);
        if (now % 100 == 0) {
            w.playSound(null, base, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.2f, 0.6f);
        }
    }

    // ---------------------------------------------------------------- who comes

    /** Someone opened the door: they choose who comes with them. */
    public static void found(ServerPlayerEntity p) {
        if (fight != null) {
            Msg.send(p, "boilfight.busy");
            return;
        }
        Set<UUID> picked = new LinkedHashSet<>();
        p.getEntityWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 2f, 0.5f);
        choose(p, picked);
    }

    /** The menu: bring everyone, or pick players (click their heads), then open the door. */
    static void choose(ServerPlayerEntity p, Set<UUID> picked) {
        Menu m = Menu.std(Theme.Category.PLAYER, 6, Msg.trFor(p, "boilfight.menu-title"));
        m.renderer(menu -> {
            menu.set(Menu.INFO, Btn.of(Items.CRIMSON_DOOR).name("§4" + Msg.tr("boilfight.menu-title"))
                    .desc(Msg.tr("boilfight.menu-desc")).build(), null, null);
            int i = 0;
            for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
                if (o == p || i >= Menu.CONTENT.length) {
                    continue;
                }
                boolean on = picked.contains(o.getUuid());
                UUID id = o.getUuid();
                menu.set(Menu.CONTENT[i++], Btn.head(id, o.getGameProfile().name()).name((on ? "§a✔ " : "§7") + o.getGameProfile().name())
                        .glint(on).left(Msg.tr(on ? "boilfight.menu-unpick" : "boilfight.menu-pick")).build(), null, (pl, c) -> {
                    if (!picked.remove(id)) {
                        picked.add(id);
                    }
                    menu.refresh();
                });
            }
            menu.set(Menu.SEARCH - 2, Btn.of(Items.BEACON).name("§c" + Msg.tr("boilfight.menu-everyone"))
                    .desc(Msg.tr("boilfight.menu-everyone-desc")).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                Set<UUID> all = new LinkedHashSet<>();
                for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
                    if (o != pl) {
                        all.add(o.getUuid());
                    }
                }
                start(pl, all);
            });
            menu.set(Menu.SEARCH + 2, Btn.of(Items.LIME_DYE).name("§a" + Msg.tr("boilfight.menu-go"))
                    .desc(Msg.tr("boilfight.menu-go-desc", picked.size())).build(), null, (pl, c) -> {
                pl.closeHandledScreen();
                start(pl, picked);
            });
        });
        m.open(p);
    }

    /** The door opens: everyone chosen gets 30 seconds to say they're coming. */
    static void start(ServerPlayerEntity finder, Set<UUID> who) {
        if (fight != null) {
            Msg.send(finder, "boilfight.busy");
            return;
        }
        removeDoor();
        fight = new Fight();
        fight.finder = finder.getUuid();
        fight.joined.add(finder.getUuid());
        fight.invited.addAll(who);
        fight.invited.remove(finder.getUuid());
        fight.stageUntil = now + (fight.invited.isEmpty() ? 60 : 20 * 30);
        state().fights++;
        save();
        Msg.send(finder, fight.invited.isEmpty() ? "boilfight.alone" : "boilfight.waiting", fight.invited.size());
        String name = finder.getGameProfile().name();
        for (UUID id : fight.invited) {
            ServerPlayerEntity o = online(id);
            if (o == null) {
                continue;
            }
            Mc.title(o, "§4" + Msg.trFor(o, "boilfight.invite-title"), "§7" + Msg.trFor(o, "boilfight.invite-sub", name), 10, 80, 20);
            Mc.sound(o, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1f, 0.5f);
            Msg.sendRaw(o, Msg.prefixed("§c" + Msg.trFor(o, "boilfight.invite", name)).append(Text.literal(" "))
                    .append(Msg.button("§a[" + Msg.trFor(o, "boilfight.join-word") + "]", "/boiledfight join",
                            Msg.trFor(o, "boilfight.join-hover"))));
        }
        Ac.LOG.info("{} opened the Boiled Door ({} invited)", name, fight.invited.size());
    }

    /** /boiledfight join */
    public static void join(ServerPlayerEntity p) {
        if (fight == null || fight.stage != Stage.INVITE || !fight.invited.contains(p.getUuid())) {
            Msg.send(p, "boilfight.no-invite");
            return;
        }
        if (fight.joined.add(p.getUuid())) {
            Msg.send(p, "boilfight.joined");
            ServerPlayerEntity f = online(fight.finder);
            if (f != null) {
                Msg.send(f, "boilfight.someone-joined", p.getGameProfile().name());
            }
        }
    }

    /** /boiledfight leave: out of the fight (and out of The Boiling). */
    public static void leave(ServerPlayerEntity p) {
        if (fight == null || (!fight.joined.contains(p.getUuid()) && !fight.inside.contains(p.getUuid()))) {
            Msg.send(p, "boilfight.not-in");
            return;
        }
        fight.joined.remove(p.getUuid());
        if (fight.inside.remove(p.getUuid())) {
            sendBack(p);
            tellInside("boilfight.someone-left", p.getGameProfile().name());
        }
        Msg.send(p, "boilfight.left");
    }

    // ---------------------------------------------------------------- into The Boiling

    /** Everyone who said yes goes through, standing round the arena; it wakes a few seconds later. */
    private static void enter() {
        ServerWorld b = boiling();
        if (b == null) {
            Ac.LOG.warn("The Boiling dimension isn't loaded");
            fight = null;
            return;
        }
        buildArena(b);
        b.getWorldBorder().setCenter(0.5, 0.5);
        b.getWorldBorder().setSize(ARENA * 2 + 8);
        List<ServerPlayerEntity> going = new ArrayList<>();
        for (UUID id : fight.joined) {
            ServerPlayerEntity p = online(id);
            if (p != null && p.isAlive() && !Jail.isJailed(p) && !WaitingRoomFeature.waiting(p)) {
                going.add(p);
            }
        }
        if (going.isEmpty()) {
            fight = null;
            return;
        }
        for (int i = 0; i < going.size(); i++) {
            ServerPlayerEntity p = going.get(i);
            fight.back.put(p.getUuid(), new Back(p.getEntityWorld().getRegistryKey(), p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()));
            double a = Math.PI * 2 * i / going.size();
            double x = Math.cos(a) * (ARENA - 8) + 0.5;
            double z = Math.sin(a) * (ARENA - 8) + 0.5;
            float yaw = (float) Math.toDegrees(Math.atan2(-x, z)) + 180;
            Mc.teleport(p, b, x, FLOOR, z, yaw, 0);
            fight.inside.add(p.getUuid());
            Mc.title(p, "§4§l" + Msg.trFor(p, "boilfight.enter-title"), "§7" + Msg.trFor(p, "boilfight.enter-sub"), 10, 70, 20);
            Mc.sound(p, SoundEvents.AMBIENT_CAVE.value(), 1f, 0.5f);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false));
        }
        fight.stage = Stage.ENTER;
        fight.stageUntil = now + 20 * 6;
        Ac.LOG.info("{} players entered The Boiling", going.size());
    }

    /** The arena: a ring of black pillars with soul lanterns, blood on the ground, an altar in the middle. */
    static void buildArena(ServerWorld w) {
        State s = state();
        if (s.arenaBuilt && w.getBlockState(new BlockPos(0, FLOOR, 0)).isOf(Blocks.CHISELED_POLISHED_BLACKSTONE)) {
            return;
        }
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                w.getChunk(cx, cz);
            }
        }
        var rng = w.getRandom();
        BlockState pillar = Blocks.POLISHED_BLACKSTONE_BRICKS.getDefaultState();
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12;
            int x = (int) Math.round(Math.cos(a) * ARENA);
            int z = (int) Math.round(Math.sin(a) * ARENA);
            int h = 5 + rng.nextInt(4);
            for (int y = 0; y < h; y++) {
                w.setBlockState(new BlockPos(x, FLOOR + y, z), pillar, Block.NOTIFY_LISTENERS);
            }
            w.setBlockState(new BlockPos(x, FLOOR + h, z), Blocks.SOUL_LANTERN.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        // Blood: dark red splashes on the ground.
        for (int i = 0; i < 160; i++) {
            int x = rng.nextInt(ARENA * 2) - ARENA;
            int z = rng.nextInt(ARENA * 2) - ARENA;
            if (x * x + z * z > (ARENA - 2) * (ARENA - 2)) {
                continue;
            }
            BlockState blood = rng.nextInt(3) == 0 ? Blocks.REDSTONE_BLOCK.getDefaultState() : Blocks.RED_CONCRETE.getDefaultState();
            w.setBlockState(new BlockPos(x, FLOOR - 1, z), blood, Block.NOTIFY_LISTENERS);
        }
        // The altar it rises from.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                w.setBlockState(new BlockPos(x, FLOOR - 1, z), Blocks.POLISHED_BLACKSTONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        w.setBlockState(new BlockPos(0, FLOOR, 0), Blocks.CHISELED_POLISHED_BLACKSTONE.getDefaultState(), Block.NOTIFY_LISTENERS);
        s.arenaBuilt = true;
        save();
    }

    /** It rises from the altar. */
    private static void awaken() {
        ServerWorld w = boiling();
        MobEntity m = body(w, new Vec3d(0.5, FLOOR + 1, 0.5), false);
        if (m == null) {
            fail();
            return;
        }
        fight.boss = m;
        fight.stage = Stage.FIGHT;
        fight.calmUntil = now + 60;
        fight.nextLunge = now + 120;
        fight.nextPools = now + 200;
        fight.nextGrab = now + 200;
        fight.nextScare = now + 300;
        fight.bar = new ServerBossBar(Text.literal("§4" + NAME), BossBar.Color.RED, BossBar.Style.NOTCHED_10);
        bar();
        for (ServerPlayerEntity p : insidePlayers()) {
            fight.bar.addPlayer(p);
            BoiledOne.jumpscare(p);
        }
        phaseTitle();
    }

    /** One of its bodies: the real one (it moves and fights) or a copy (it only stands there). */
    private static MobEntity body(ServerWorld w, Vec3d at, boolean copy) {
        MobEntity m = EntityType.WITHER_SKELETON.create(w, SpawnReason.EVENT);
        if (m == null) {
            return null;
        }
        m.refreshPositionAndAngles(at.x, at.y, at.z, 0, 0);
        m.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        set(m, EntityAttributes.SCALE, 1.8);
        set(m, EntityAttributes.MAX_HEALTH, 1000);
        m.setHealth(1000);
        set(m, EntityAttributes.MOVEMENT_SPEED, 0.3);
        set(m, EntityAttributes.FOLLOW_RANGE, 96);
        set(m, EntityAttributes.KNOCKBACK_RESISTANCE, 1.0);
        set(m, EntityAttributes.ATTACK_DAMAGE, 9);
        set(m, EntityAttributes.STEP_HEIGHT, 1.6);
        m.setPersistent();
        m.setSilent(true);
        m.setCanPickUpLoot(false);
        m.setAiDisabled(copy);
        m.setCustomName(Text.literal(NAME));
        m.setCustomNameVisible(false);
        m.addCommandTag(TAG);
        StructureMobs.noDrops(m);
        ModelMobs.attach(m, "boiled_one");
        w.spawnEntity(m);
        return m;
    }

    private static void set(MobEntity m, RegistryEntry<EntityAttribute> a, double v) {
        var inst = m.getAttributeInstance(a);
        if (inst != null) {
            inst.setBaseValue(v);
        }
    }

    // ---------------------------------------------------------------- the fight itself

    private static void fightTick() {
        Fight f = fight;
        // Who's still in it?
        f.inside.removeIf(id -> {
            ServerPlayerEntity p = online(id);
            return p == null || !inBoiling(p) || !p.isAlive();
        });
        if (f.inside.isEmpty()) {
            fail();
            return;
        }
        MobEntity m = f.boss;
        if (m == null || m.isRemoved()) {
            fail();
            return;
        }
        ServerWorld w = (ServerWorld) m.getEntityWorld();
        List<ServerPlayerEntity> in = insidePlayers();
        if (now % 20 == 0) {
            ServerPlayerEntity near = null;
            double best = Double.MAX_VALUE;
            for (ServerPlayerEntity p : in) {
                double d = p.squaredDistanceTo(m);
                if (d < best && !p.isCreative() && !p.isSpectator()) {
                    best = d;
                    near = p;
                }
            }
            m.setTarget(near);
            if (m.getX() * m.getX() + m.getZ() * m.getZ() > (ARENA + 2) * (ARENA + 2)) {
                m.refreshPositionAndAngles(0.5, FLOOR + 1, 0.5, m.getYaw(), 0);
            }
            bar();
        }
        if (now < f.calmUntil) {
            return;
        }
        int p = f.phase;
        double pace = p >= 5 ? 0.5 : 1.0;
        // 1+: it lunges at someone, roaring; anyone close when it lands is torn.
        if (now >= f.nextLunge && !in.isEmpty()) {
            ServerPlayerEntity t = in.get(w.getRandom().nextInt(in.size()));
            Vec3d d = t.getEntityPos().subtract(m.getEntityPos());
            Vec3d v = d.multiply(1, 0, 1).normalize().multiply(Math.min(2.2, 0.25 + d.horizontalLength() * 0.11));
            OwnerCombat.push(m, new Vec3d(v.x, 0.45, v.z));
            w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2.5f, 0.6f);
            f.lungeHit = now + 14;
            f.nextLunge = now + (long) ((p >= 2 ? 120 : 160) * pace);
        }
        if (f.lungeHit >= 0 && now >= f.lungeHit) {
            f.lungeHit = -1;
            for (ServerPlayerEntity t : in) {
                if (t.squaredDistanceTo(m) < 3.5 * 3.5) {
                    t.damage(w, m.getDamageSources().mobAttack(m), 7f);
                    OwnerCombat.push(t, t.getEntityPos().subtract(m.getEntityPos()).normalize().multiply(0.9).add(0, 0.4, 0));
                }
            }
            w.spawnParticles(ParticleTypes.SWEEP_ATTACK, m.getX(), m.getY() + 1, m.getZ(), 6, 1.2, 0.5, 1.2, 0);
        }
        // 2+: blood boils up under people's feet: a red ring, then it bursts.
        if (p >= 2 && now >= f.nextPools) {
            List<ServerPlayerEntity> pick = new ArrayList<>(in);
            java.util.Collections.shuffle(pick, new java.util.Random(now));
            for (ServerPlayerEntity t : pick.subList(0, Math.min(p >= 5 ? 5 : 3, pick.size()))) {
                f.pools.add(new Pool(t.getEntityPos(), now + 40));
                Mc.sound(t, SoundEvents.BLOCK_LAVA_POP, 1f, 0.5f);
            }
            f.nextPools = now + (long) (140 * pace);
        }
        DustParticleEffect blood = new DustParticleEffect(0x9A0000, 1.8f);
        f.pools.removeIf(pool -> {
            if (now % 4 == 0) {
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * 2 * i / 16;
                    w.spawnParticles(blood, pool.at().x + Math.cos(a) * 2.5, FLOOR + 0.1, pool.at().z + Math.sin(a) * 2.5, 1, 0, 0, 0, 0);
                }
            }
            if (now < pool.burstAt()) {
                return false;
            }
            w.spawnParticles(blood, pool.at().x, FLOOR + 0.5, pool.at().z, 120, 1.5, 0.8, 1.5, 0);
            w.playSound(null, pool.at().x, FLOOR, pool.at().z, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, SoundCategory.HOSTILE, 1.5f, 0.5f);
            for (ServerPlayerEntity t : in) {
                if (t.getEntityPos().squaredDistanceTo(pool.at()) < 2.5 * 2.5) {
                    t.damage(w, m.getDamageSources().mobAttack(m), 9f);
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1, false, true));
                }
            }
            return true;
        });
        // 3+: copies of it stand round the arena; now and then they all swap places.
        if (p >= 3) {
            f.decoys.removeIf(d -> d.isRemoved() || !d.isAlive());
            if (now >= f.nextDecoys && f.decoys.size() < 4) {
                while (f.decoys.size() < 4) {
                    double a = w.getRandom().nextDouble() * Math.PI * 2;
                    MobEntity d = body(w, new Vec3d(Math.cos(a) * 14 + 0.5, FLOOR, Math.sin(a) * 14 + 0.5), true);
                    if (d == null) {
                        break;
                    }
                    f.decoys.add(d);
                }
                f.nextDecoys = now + 400;
            }
            if (now >= f.nextShuffle && !f.decoys.isEmpty()) {
                MobEntity d = f.decoys.get(w.getRandom().nextInt(f.decoys.size()));
                Vec3d a = m.getEntityPos();
                Vec3d b = d.getEntityPos();
                m.refreshPositionAndAngles(b.x, b.y, b.z, m.getYaw(), 0);
                d.refreshPositionAndAngles(a.x, a.y, a.z, d.getYaw(), 0);
                for (Vec3d at : new Vec3d[]{a, b}) {
                    w.spawnParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.5, at.z, 40, 0.5, 1.2, 0.5, 0.02);
                }
                f.nextShuffle = now + (long) (140 * pace);
            }
            for (MobEntity d : f.decoys) {
                ServerPlayerEntity t = nearest(in, d);
                if (t != null) {
                    face(d, t);
                }
            }
        }
        // 4+: the lights go out; it shows itself only now and then; it grabs whoever's close and bites.
        if (p >= 4) {
            if (now % 40 == 0) {
                for (ServerPlayerEntity t : in) {
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 80, 0, false, false));
                }
            }
            m.setGlowing(now % 100 < 25);
            if (f.grabbing < 0 && now >= f.nextGrab) {
                ServerPlayerEntity t = nearest(in, m);
                if (t != null && t.squaredDistanceTo(m) < 2.8 * 2.8) {
                    f.grabbing = now;
                    f.grabbed = t.getUuid();
                    ModelMobs.act(m, ModelMobs.GRAB);
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 6, false, false));
                    OwnerCombat.push(t, new Vec3d(0, 0.6, 0));
                }
            }
            if (f.grabbing >= 0) {
                ServerPlayerEntity t = online(f.grabbed);
                if (now - f.grabbing == 18) {
                    ModelMobs.act(m, ModelMobs.BITE);
                }
                if (now - f.grabbing >= 24) {
                    if (t != null && f.inside.contains(t.getUuid())) {
                        BoiledOne.jumpscare(t);
                        t.damage(w, m.getDamageSources().mobAttack(m), 12f);
                        w.spawnParticles(blood, t.getX(), t.getEyeY(), t.getZ(), 60, 0.3, 0.3, 0.3, 0);
                    }
                    ModelMobs.act(m, 0);
                    f.grabbing = -1;
                    f.nextGrab = now + (long) (200 * pace);
                }
            }
        }
        // 5: rage. It screams in someone's face now and then.
        if (p >= 5) {
            if (now >= f.nextScare && !in.isEmpty()) {
                BoiledOne.jumpscare(in.get(w.getRandom().nextInt(in.size())));
                f.nextScare = now + 300;
            }
            if (now % 5 == 0) {
                w.spawnParticles(blood, m.getX(), m.getY() + 2, m.getZ(), 6, 0.7, 1.4, 0.7, 0);
            }
        }
    }

    private static void face(MobEntity m, ServerPlayerEntity p) {
        Vec3d d = p.getEntityPos().subtract(m.getEntityPos());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        m.setYaw(yaw);
        m.setBodyYaw(yaw);
        m.setHeadYaw(yaw);
    }

    private static ServerPlayerEntity nearest(List<ServerPlayerEntity> in, Entity to) {
        ServerPlayerEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayerEntity p : in) {
            double d = p.squaredDistanceTo(to);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** A hit on the real one: off its health (secret weapons hit for 20-40, like on any boss). */
    private static void hurt(DamageSource source, float amount) {
        Fight f = fight;
        if (f.stage != Stage.FIGHT || now < f.calmUntil || !(source.getAttacker() instanceof ServerPlayerEntity p)) {
            return;
        }
        double hit = amount;
        if (SecretItems.weaponOf(source) != null) {
            hit = SecretItems.bossHit(source);
        }
        hit /= HARDNESS;
        if (amount >= 100_000) {
            // The Doom Blade: every phase at once.
            f.phase = PHASES;
            hit = f.hp;
        }
        f.dealt.merge(p.getUuid(), Math.min(hit, Math.max(0, f.hp)), Double::sum);
        f.hp -= hit;
        ServerWorld w = (ServerWorld) f.boss.getEntityWorld();
        w.spawnParticles(new DustParticleEffect(0x9A0000, 1.5f), f.boss.getX(), f.boss.getY() + 2.5, f.boss.getZ(), 12, 0.4, 0.8, 0.4, 0);
        w.playSound(null, f.boss.getX(), f.boss.getY(), f.boss.getZ(), SoundEvents.ENTITY_WARDEN_HURT, SoundCategory.HOSTILE, 1f, 0.6f);
        if (f.hp <= 0) {
            if (f.phase >= PHASES) {
                win();
            } else {
                nextPhase();
            }
        }
        bar();
    }

    /** A copy was hit: it bursts, screaming, and the one who hit it pays. */
    private static void pop(MobEntity d, DamageSource source) {
        fight.decoys.remove(d);
        ServerWorld w = (ServerWorld) d.getEntityWorld();
        w.spawnParticles(ParticleTypes.LARGE_SMOKE, d.getX(), d.getY() + 2, d.getZ(), 60, 0.6, 1.6, 0.6, 0.03);
        w.playSound(null, d.getX(), d.getY(), d.getZ(), SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 2f, 0.5f);
        ModelMobs.remove(d);
        if (source.getAttacker() instanceof ServerPlayerEntity p) {
            p.damage(w, w.getDamageSources().magic(), 4f);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false));
            Msg.actionBar(p, "§8" + Msg.trFor(p, "boilfight.decoy"));
        }
    }

    private static void nextPhase() {
        Fight f = fight;
        f.phase++;
        f.hp = PHASE_HP;
        f.calmUntil = now + 60;
        f.pools.clear();
        f.nextDecoys = now;
        f.nextShuffle = now + 160;
        f.boss.refreshPositionAndAngles(0.5, FLOOR + 1, 0.5, f.boss.getYaw(), 0);
        if (f.phase >= 5) {
            set(f.boss, EntityAttributes.MOVEMENT_SPEED, 0.38);
        }
        for (ServerPlayerEntity p : insidePlayers()) {
            BoiledOne.jumpscare(p);
        }
        phaseTitle();
    }

    private static void phaseTitle() {
        int ph = fight.phase;
        for (ServerPlayerEntity p : insidePlayers()) {
            Mc.title(p, "§4" + Msg.trFor(p, "boilfight.phase", ph, PHASES), "§7" + Msg.trFor(p, "boilfight.phase-" + ph), 5, 50, 15);
        }
    }

    private static void bar() {
        Fight f = fight;
        if (f == null || f.bar == null) {
            return;
        }
        f.bar.setName(Text.literal("§4" + NAME + " §7· " + PHASE_NAMES[f.phase - 1] + " §8(" + f.phase + "/" + PHASES + ")"));
        f.bar.setPercent((float) Math.max(0, Math.min(1, f.hp / PHASE_HP)));
        for (ServerPlayerEntity p : insidePlayers()) {
            if (!f.bar.getPlayers().contains(p)) {
                f.bar.addPlayer(p);
            }
        }
        for (ServerPlayerEntity p : new ArrayList<>(f.bar.getPlayers())) {
            if (!f.inside.contains(p.getUuid())) {
                f.bar.removePlayer(p);
            }
        }
    }

    // ---------------------------------------------------------------- the end

    private static void win() {
        Fight f = fight;
        f.won = true;
        f.stage = Stage.OVER;
        f.stageUntil = now + 20 * 15;
        clearBodies();
        state().wins++;
        save();
        List<String> names = new ArrayList<>();
        for (ServerPlayerEntity p : insidePlayers()) {
            names.add(p.getGameProfile().name());
            Mc.title(p, "§c§l" + Msg.trFor(p, "boilfight.won-title"), "§7" + Msg.trFor(p, "boilfight.won-sub"), 10, 100, 30);
            Mc.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
            give(p, heart(p));
            give(p, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
            give(p, new ItemStack(Items.TOTEM_OF_UNDYING));
            give(p, new ItemStack(Items.DIAMOND, 8));
            p.addExperienceLevels(30);
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Msg.send(p, "boilfight.won-all", String.join(", ", names));
        }
        // Whoever hurt it most takes one of the secret weapons home.
        UUID top = null;
        for (var e : f.dealt.entrySet()) {
            if (online(e.getKey()) != null && (top == null || e.getValue() > f.dealt.get(top))) {
                top = e.getKey();
            }
        }
        ServerPlayerEntity best = online(top);
        if (best != null) {
            List<String> weapons = new ArrayList<>(SecretItems.BOSS_WEAPONS);
            weapons.add(SecretItems.VOIDBLADE);
            weapons.add(SecretItems.STORMBREAKER);
            weapons.add(SecretItems.TIDECALLER);
            String weapon = weapons.get(best.getRandom().nextInt(weapons.size()));
            SecretItems.give(best, weapon);
            int dealt = (int) Math.round(f.dealt.get(top));
            for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                Msg.send(p, "boilfight.top", best.getGameProfile().name(), dealt);
            }
            Ac.LOG.info("The Boiling: {} did the most damage ({}) and got {}", best.getGameProfile().name(), dealt, weapon);
        }
        Ac.LOG.info("The Boiled One was killed in The Boiling by {}", names);
    }

    private static void fail() {
        Fight f = fight;
        if (f == null) {
            return;
        }
        f.stage = Stage.OVER;
        f.stageUntil = now + 60;
        clearBodies();
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (f.joined.contains(p.getUuid()) || f.fallen.contains(p.getUuid())) {
                Msg.send(p, "boilfight.lost");
            }
        }
        Ac.LOG.info("The Boiling: nobody's left standing");
    }

    /** Everyone still inside goes back where they came from. */
    private static void finish() {
        Fight f = fight;
        for (ServerPlayerEntity p : insidePlayers()) {
            sendBack(p);
        }
        if (f.bar != null) {
            f.bar.clearPlayers();
        }
        fight = null;
    }

    private static void clearBodies() {
        Fight f = fight;
        if (f.boss != null) {
            ServerWorld w = (ServerWorld) f.boss.getEntityWorld();
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, f.boss.getX(), f.boss.getY() + 2, f.boss.getZ(), 120, 0.8, 2, 0.8, 0.04);
            w.playSound(null, f.boss.getX(), f.boss.getY(), f.boss.getZ(), SoundEvents.ENTITY_GHAST_DEATH, SoundCategory.HOSTILE, 3f, 0.4f);
            ModelMobs.remove(f.boss);
            f.boss = null;
        }
        for (MobEntity d : f.decoys) {
            ModelMobs.remove(d);
        }
        f.decoys.clear();
        if (f.bar != null) {
            f.bar.clearPlayers();
        }
    }

    /** The trophy: its heart, still warm. */
    public static ItemStack heart(ServerPlayerEntity p) {
        ItemStack s = new ItemStack(Items.NETHER_STAR);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§4§lHeart of The Boiled One").styled(st -> st.withItalic(false)));
        s.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("§7Torn out of it in The Boiling.").styled(st -> st.withItalic(false)),
                Text.literal("§8" + p.getGameProfile().name() + " · " + java.time.LocalDate.now()).styled(st -> st.withItalic(false)))));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    private static void give(ServerPlayerEntity p, ItemStack s) {
        if (!p.getInventory().insertStack(s)) {
            p.dropItem(s, false);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void sendBack(ServerPlayerEntity p) {
        Back b = fight == null ? null : fight.back.get(p.getUuid());
        ServerWorld w = b == null ? null : Ac.server().getWorld(b.world());
        if (w != null && w.getRegistryKey() != BOILING) {
            Mc.teleport(p, w, b.x(), b.y(), b.z(), b.yaw(), b.pitch());
            return;
        }
        BlockPos spawn = Mc.worldSpawn(Ac.server());
        Mc.teleport(p, Ac.server().getOverworld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
    }

    private static ServerPlayerEntity online(UUID id) {
        return id == null ? null : Ac.server().getPlayerManager().getPlayer(id);
    }

    private static List<ServerPlayerEntity> insidePlayers() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        if (fight == null) {
            return out;
        }
        for (UUID id : fight.inside) {
            ServerPlayerEntity p = online(id);
            if (p != null && inBoiling(p)) {
                out.add(p);
            }
        }
        return out;
    }

    private static void tellInside(String key, Object... args) {
        for (ServerPlayerEntity p : insidePlayers()) {
            Msg.send(p, key, args);
        }
    }

    /** Nothing else lives in The Boiling: whatever the crimson forest spawns there is taken away. */
    private static void sweep() {
        ServerWorld w = boiling();
        if (w == null) {
            return;
        }
        List<Entity> gone = new ArrayList<>();
        for (Entity e : w.iterateEntities()) {
            if (e instanceof MobEntity m && !m.getCommandTags().contains(TAG) && !ModelMobs.wears(m)) {
                gone.add(e);
            }
        }
        for (Entity e : gone) {
            e.discard();
        }
    }

    // ---------------------------------------------------------------- owner

    /** /owner boiledone fight: as if the owner had found the door. */
    public static void ownerFight(ServerPlayerEntity p) {
        if (OwnerPowers.require(p)) {
            found(p);
        }
    }

    /** /owner boiledone door: the door, right in front of you. */
    public static void ownerDoor(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        if (state().doorWorld != null) {
            removeDoor();
        }
        Msg.send(p, placeDoor(p, true) ? "boilfight.door-placed" : "boilfight.door-no-room");
    }

    /** /owner boiledone fightstop: ends a fight (everyone comes back) and takes away a door. */
    public static void ownerStop(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        removeDoor();
        if (fight != null) {
            clearBodies();
            finish();
        }
        Msg.send(p, "boilfight.stopped");
    }

    public static void ownerInfo(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        long wait = Math.max(0, DOOR_COOLDOWN - (System.currentTimeMillis() - s.lastDoorAt));
        String stage = fight == null ? "-" : fight.stage + " phase " + fight.phase + " hp " + (int) Math.max(0, fight.hp)
                + " inside " + fight.inside.size();
        Msg.send(p, "boilfight.info", s.fights, s.wins, s.doorWorld == null ? "-" : BlockPos.fromLong(s.door).toShortString(),
                com.vylorq.anticheat.core.util.Durations.format(wait), stage);
    }

    // ---------------------------------------------------------------- tests

    public static boolean dimensionLoadedForTest() {
        return boiling() != null;
    }

    public static MobEntity bodyForTest(ServerWorld w, Vec3d at) {
        return body(w, at, false);
    }
}
