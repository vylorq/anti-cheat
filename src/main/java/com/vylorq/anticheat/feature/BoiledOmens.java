package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What comes with The Boiled One, before and after it shows itself: footsteps behind you, knocking on your door,
 * doors creaking open, whispers in chat, torches going out, animals panicking, static when it's near, bloody
 * footprints into your base; and the ways to live through it: hiding, the Lantern of Dawn, and a trophy for anyone
 * who gets through The Boiling Night without it noticing them.
 */
public final class BoiledOmens {
    private BoiledOmens() {
    }

    /** Players the Lantern of Dawn keeps it away from, until this world time. */
    private static final Map<UUID, Long> DAWN = new ConcurrentHashMap<>();
    /** Players who died or were marked during The Boiling Night (no trophy for them). */
    static final Set<UUID> FAILED = ConcurrentHashMap.newKeySet();
    /** Players it actually came for during The Boiling Night (only they can earn the trophy). */
    static final Set<UUID> FACED = ConcurrentHashMap.newKeySet();
    /** Even then, one survivor in this many gets the Boiled Tooth. */
    static final int TOOTH_ODDS = 10;

    // ---------------------------------------------------------------- now and then, an omen

    /** Every second: someone out at night or in a cave may hear, see or feel something. */
    static void tick(long now) {
        var s = BoiledOne.state();
        if (!s.enabled) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (!BoiledOne.huntable(p) || isProtected(p) || BoiledFight.wearsFullSet(p)) {
                continue;
            }
            boolean cave = BoiledOne.inCave(p);
            boolean night = BoiledOne.night(p.getEntityWorld());
            if (!cave && !night) {
                continue;
            }
            boolean focus = s.hunted.containsKey(p.getUuidAsString()) || BoiledOne.eventOn();
            if (p.getRandom().nextInt(focus ? 120 : 420) != 0) {
                continue;
            }
            omen(p, cave, BoiledOne.inBase(p));
        }
    }

    /** One omen that fits where they are. */
    static void omen(ServerPlayerEntity p, boolean cave, boolean base) {
        List<Runnable> can = new ArrayList<>();
        can.add(() -> footsteps(p));
        can.add(() -> BoiledOne.glimpse(p));
        can.add(() -> BoiledOne.glimpse(p));
        if (p.getRandom().nextInt(3) == 0) {
            can.add(() -> whisper(p));
        }
        if (p.getRandom().nextInt(3) == 0) {
            can.add(() -> {
                if (!BoiledHaunts.impersonate(p)) {
                    whisper(p);
                }
            });
        }
        if (!base) {
            can.add(() -> BoiledHaunts.trail(p));
        }
        if (BoiledHaunts.dark(p)) {
            can.add(() -> {
                if (!BoiledHaunts.mimic(p, null)) {
                    BoiledOne.glimpse(p);
                }
            });
        }
        if (cave) {
            can.add(() -> snuff(p, null));
        }
        if (base) {
            can.add(() -> knock(p));
            can.add(() -> door(p));
        }
        can.get(p.getRandom().nextInt(can.size())).run();
    }

    // ---------------------------------------------------------------- sounds only they hear

    static void sound(ServerPlayerEntity p, SoundEvent s, Vec3d at, float volume, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(s), SoundCategory.HOSTILE, at.x, at.y, at.z, volume,
                pitch, p.getRandom().nextLong()));
    }

    static void pack(ServerPlayerEntity p, String name, Vec3d at, float volume, float pitch) {
        sound(p, SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound(name)), at, volume, pitch);
    }

    /** Heavy, slow footsteps somewhere behind them, coming closer, then stopping. */
    static void footsteps(ServerPlayerEntity p) {
        Vec3d back = p.getRotationVec(1f).multiply(-1, 0, -1);
        if (back.lengthSquared() < 1e-4) {
            back = new Vec3d(1, 0, 0);
        }
        Vec3d dir = back.normalize();
        int steps = 4 + p.getRandom().nextInt(3);
        for (int i = 0; i < steps; i++) {
            int k = i;
            OwnerPowers.later(14 * i, () -> {
                if (p.isRemoved()) {
                    return;
                }
                double d = 11 - k * 1.6;
                Vec3d at = p.getEntityPos().add(dir.multiply(d)).add((k % 2 == 0 ? 0.4 : -0.4) * dir.z, 0, (k % 2 == 0 ? -0.4 : 0.4) * dir.x);
                sound(p, SoundEvents.ENTITY_WARDEN_STEP, at, 0.9f, 0.55f);
                sound(p, SoundEvents.BLOCK_GRAVEL_STEP, at, 0.6f, 0.5f);
            });
        }
    }

    /** Slow knocks on the nearest door of their base. */
    static void knock(ServerPlayerEntity p) {
        BlockPos door = nearestDoor(p, 14, true);
        if (door == null) {
            footsteps(p);
            return;
        }
        Vec3d at = Vec3d.ofCenter(door);
        BoiledHaunts.knocked(p, door);
        int[] when = {0, 14, 28, 70, 80};
        for (int i = 0; i < (p.getRandom().nextBoolean() ? 3 : 5); i++) {
            OwnerPowers.later(when[i], () -> sound(p, SoundEvents.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, at, 0.55f, 0.6f));
        }
    }

    /** A door of their base creaks slowly open by itself, and later swings shut. */
    static void door(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        BlockPos at = nearestDoor(p, 14, true);
        if (at == null) {
            knock(p);
            return;
        }
        BlockState st = w.getBlockState(at);
        if (!(st.getBlock() instanceof DoorBlock d) || st.get(DoorBlock.OPEN)) {
            return;
        }
        d.setOpen(null, w, st, at, true);
        Vec3d c = Vec3d.ofCenter(at);
        sound(p, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, c, 1f, 0.45f);
        OwnerPowers.later(20 * 8, () -> {
            BlockState now = w.getBlockState(at);
            if (now.getBlock() instanceof DoorBlock dd && now.get(DoorBlock.OPEN)) {
                dd.setOpen(null, w, now, at, false);
                sound(p, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, c, 1f, 0.5f);
            }
        });
    }

    private static BlockPos nearestDoor(ServerPlayerEntity p, int r, boolean wooden) {
        ServerWorld w = p.getEntityWorld();
        BlockPos c = p.getBlockPos();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.iterate(c.add(-r, -3, -r), c.add(r, 3, r))) {
            BlockState st = w.getBlockState(b);
            if (st.getBlock() instanceof DoorBlock && (!wooden || st.isIn(BlockTags.WOODEN_DOORS))
                    && st.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
                double d = b.getSquaredDistance(c);
                if (d < bd && d > 4) {
                    bd = d;
                    best = b.toImmutable();
                }
            }
        }
        return best;
    }

    private static final String[] WHISPERS = {"boiled.whisper.see", "boiled.whisper.behind", "boiled.whisper.found",
            "boiled.whisper.close", "boiled.whisper.door", "boiled.whisper.look", "boiled.whisper.alone", "boiled.whisper.hungry"};

    /** A message in chat, from it, with their name. Only they see it. */
    static void whisper(ServerPlayerEntity p) {
        String line = Msg.trFor(p, WHISPERS[p.getRandom().nextInt(WHISPERS.length)], p.getGameProfile().name());
        p.sendMessage(Text.literal("<").formatted(Formatting.DARK_RED)
                .append(Text.literal("The Boiled One").formatted(Formatting.DARK_RED, Formatting.OBFUSCATED))
                .append(Text.literal("> ").formatted(Formatting.DARK_RED))
                .append(Text.literal(line).formatted(Formatting.RED, Formatting.ITALIC)), false);
        sound(p, SoundEvents.AMBIENT_CAVE.value(), p.getEntityPos(), 0.6f, 0.5f);
    }

    // ---------------------------------------------------------------- before it comes

    /** The torches around them go out, one by one; then (if given) it comes. */
    static void snuff(ServerPlayerEntity p, Runnable then) {
        ServerWorld w = p.getEntityWorld();
        BlockPos c = p.getBlockPos();
        List<BlockPos> lights = new ArrayList<>();
        for (BlockPos b : BlockPos.iterate(c.add(-10, -4, -10), c.add(10, 5, 10))) {
            BlockState st = w.getBlockState(b);
            if (st.isOf(Blocks.TORCH) || st.isOf(Blocks.WALL_TORCH) || st.isOf(Blocks.SOUL_TORCH) || st.isOf(Blocks.SOUL_WALL_TORCH)) {
                lights.add(b.toImmutable());
            }
        }
        lights.sort((a, b) -> Double.compare(b.getSquaredDistance(c), a.getSquaredDistance(c)));   // farthest first
        int n = Math.min(lights.size(), 8);
        for (int i = 0; i < n; i++) {
            BlockPos b = lights.get(i);
            OwnerPowers.later(8 + i * 9, () -> {
                BlockState st = w.getBlockState(b);
                if (st.isOf(Blocks.TORCH) || st.isOf(Blocks.WALL_TORCH) || st.isOf(Blocks.SOUL_TORCH) || st.isOf(Blocks.SOUL_WALL_TORCH)) {
                    w.breakBlock(b, true);          // knocked off the wall (the torch drops)
                    Vec3d at = Vec3d.ofCenter(b);
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.SMOKE, at.x, at.y, at.z, 8, 0.1, 0.1, 0.1, 0.01);
                    sound(p, SoundEvents.BLOCK_FIRE_EXTINGUISH, at, 0.7f, 0.6f);
                }
            });
        }
        if (then != null) {
            OwnerPowers.later(14 + n * 9, then);
        }
    }

    /** Animals near where it's about to stand bolt away from it; dogs whine and cower. */
    static void panic(ServerWorld w, Vec3d at) {
        var box = new net.minecraft.util.math.Box(at.add(-24, -8, -24), at.add(24, 8, 24));
        for (AnimalEntity a : w.getEntitiesByClass(AnimalEntity.class, box, e -> e.isAlive())) {
            Vec3d away = a.getEntityPos().subtract(at).multiply(1, 0, 1);
            if (away.lengthSquared() < 1e-3) {
                away = new Vec3d(1, 0, 0);
            }
            Vec3d to = a.getEntityPos().add(away.normalize().multiply(16));
            if (a instanceof WolfEntity wolf) {
                // Dogs cower where they are.
                wolf.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 160, 4, false, false));
                wolf.setSitting(true);
                continue;
            }
            a.getNavigation().startMovingTo(to.x, to.y, to.z, 2.0);
            a.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 100, 1, false, false));
        }
    }

    // ---------------------------------------------------------------- when it's near

    /** It's close: the world goes wrong around them (static, a lurch, the dark closing in). */
    static void interference(ServerPlayerEntity p, MobEntity m, double dist, long now) {
        if (dist > 14 || now % 40 != 0) {
            return;
        }
        float near = (float) (1 - dist / 14);
        pack(p, "boiled_static", p.getEyePos(), 0.3f + near * 0.8f, 0.8f + near * 0.4f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, (int) (40 + near * 80), 0, false, false));
        if (near > 0.5f) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 50, 0, false, false));
        }
    }

    /** A bloody footprint where it stepped (fades after a while). */
    static void footprint(ServerWorld w, MobEntity m, boolean left) {
        BlockPos feet = m.getBlockPos();
        if (!w.getBlockState(feet.down()).isSolidBlock(w, feet.down())) {
            return;
        }
        footprintAt(w, new Vec3d(m.getX(), feet.getY(), m.getZ()), m.getYaw(), left);
    }

    /** A bloody footprint at these feet (y = the top of the ground), walking this way. */
    static void footprintAt(ServerWorld w, Vec3d feet, float yaw, boolean left) {
        Vec3d fwd = Vec3d.fromPolar(0, yaw);
        Vec3d side = new Vec3d(-fwd.z, 0, fwd.x).multiply(left ? 0.28 : -0.28);
        Vec3d at = new Vec3d(feet.x + side.x, Math.floor(feet.y) + 0.015, feet.z + side.z);
        UUID id = OwnerCombat.Display.summon(w, at, "minecraft:nautilus_shell", "boiled_print__body",
                "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.7f,0.7f,0.7f]}");
        if (id == null) {
            return;
        }
        var d = w.getEntity(id);
        if (d != null) {
            d.refreshPositionAndAngles(at.x, at.y, at.z, yaw + 180, 0);
            d.addCommandTag(ModelMobs.DISPLAY_TAG);
            try {
                Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(),
                        "data merge entity " + id + " {brightness:{sky:7,block:7}}");
            } catch (Exception ignored) {
                // it still shows
            }
            OwnerPowers.later(20 * 60 * 10, () -> {
                var e = w.getEntity(id);
                if (e != null) {
                    e.discard();
                }
            });
        }
    }

    // ---------------------------------------------------------------- surviving it

    /**
     * Hiding: crouched and still in a tight, dark spot (walled in on most sides, a roof over their head) or behind a
     * closed door, it can't find them.
     */
    static boolean hiding(ServerPlayerEntity p) {
        // Crouched, or standing perfectly still for a few seconds.
        Vec3d pos = p.getEntityPos();
        Still st0 = STILL.get(p.getUuid());
        if (st0 == null || st0.at.squaredDistanceTo(pos) > 0.0025) {
            STILL.put(p.getUuid(), st0 = new Still(pos));
        } else {
            st0.checks++;
        }
        if (!p.isSneaking() && st0.checks < 4) {
            return false;
        }
        ServerWorld w = p.getEntityWorld();
        BlockPos head = p.getBlockPos().up();
        if (w.getLightLevel(LightType.BLOCK, head) > 6) {
            return false;
        }
        int walls = 0;
        boolean door = false;
        for (Direction d : Direction.Type.HORIZONTAL) {
            for (BlockPos b : new BlockPos[]{head.offset(d), head.down().offset(d)}) {
                BlockState st = w.getBlockState(b);
                if (st.getBlock() instanceof DoorBlock && !st.get(DoorBlock.OPEN)) {
                    door = true;
                }
            }
            BlockState st = w.getBlockState(head.offset(d));
            if (st.isSolidBlock(w, head.offset(d)) || st.getBlock() instanceof DoorBlock && !st.get(DoorBlock.OPEN) || closet(st)) {
                walls++;
            }
        }
        boolean roof = false;
        for (int up = 1; up <= 2; up++) {
            BlockState st = w.getBlockState(head.up(up));
            if (st.isSolidBlock(w, head.up(up)) || closet(st)) {
                roof = true;
            }
        }
        return roof && (walls >= 3 || door && walls >= 2);
    }

    private static final class Still {
        final Vec3d at;
        int checks;

        Still(Vec3d at) {
            this.at = at;
        }
    }

    private static final Map<UUID, Still> STILL = new ConcurrentHashMap<>();

    /** What a closet is made of: barrels, chests, shelves, closed trapdoors. */
    private static boolean closet(BlockState st) {
        var b = st.getBlock();
        return b instanceof net.minecraft.block.BarrelBlock || b instanceof net.minecraft.block.ChestBlock
                || b instanceof net.minecraft.block.ChiseledBookshelfBlock || st.isOf(Blocks.BOOKSHELF)
                || b instanceof net.minecraft.block.TrapdoorBlock && !st.get(net.minecraft.block.TrapdoorBlock.OPEN);
    }

    // The Lantern of Dawn: keeps it away for one night.
    static final String LANTERN = "vigil_dawn_lantern";

    public static ItemStack lantern() {
        ItemStack s = new ItemStack(Items.LANTERN);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§6§lLantern of Dawn").styled(st -> st.withItalic(false)));
        s.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("§7Light it, and The Boiled One").styled(st -> st.withItalic(false)),
                Text.literal("§7can't come near you until morning.").styled(st -> st.withItalic(false)),
                Text.literal("§8Burns out after one night.").styled(st -> st.withItalic(false)))));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        com.vylorq.anticheat.util.ItemConv.setTag(s, LANTERN, "1");
        return s;
    }

    public static boolean isLantern(ItemStack s) {
        return s.isOf(Items.LANTERN) && "1".equals(com.vylorq.anticheat.util.ItemConv.tag(s, LANTERN));
    }

    /** Whether the Lantern of Dawn keeps it away from them right now. */
    public static boolean isProtected(ServerPlayerEntity p) {
        Long until = DAWN.get(p.getUuid());
        return until != null && Ac.server().getOverworld().getTimeOfDay() < until;
    }

    private static ActionResult light(ServerPlayerEntity p, ItemStack held) {
        long t = Ac.server().getOverworld().getTimeOfDay();
        long tod = t % 24000L;
        // Until the next sunrise (if it's daytime now, until the end of the coming night).
        long until = t - tod + (tod < 23000 ? 23000 : 47000);
        DAWN.put(p.getUuid(), until);
        if (BoiledOne.eventOn()) {
            FAILED.add(p.getUuid());       // hiding behind the lantern doesn't count as getting through it
        }
        held.decrement(1);
        BoiledOne.protect(p);
        Mc.title(p, "§6" + Msg.trFor(p, "boiled.dawn"), "§7" + Msg.trFor(p, "boiled.dawn-sub"), 10, 50, 20);
        Mc.sound(p, SoundEvents.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(net.minecraft.particle.ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.6, 0.8, 0.6, 0.03);
        return ActionResult.SUCCESS;
    }

    /**
     * The Boiling Night is over. The Boiled Tooth is very rare: only someone it actually came for, who lived, was
     * never marked, never hid behind a Lantern of Dawn and is still out there at sunrise, has a chance at it (1 in
     * {@link #TOOTH_ODDS}).
     */
    static void survivors() {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (FAILED.contains(p.getUuid()) || !FACED.contains(p.getUuid()) || p.isCreative() || p.isSpectator()
                    || p.getRandom().nextInt(TOOTH_ODDS) != 0) {
                continue;
            }
            p.getInventory().offerOrDrop(tooth(p));
            if (p.getRandom().nextInt(4) == 0) {
                p.getInventory().offerOrDrop(lantern());
            }
            Msg.send(p, "boiled.survived");
            Mc.sound(p, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
        }
        FAILED.clear();
        FACED.clear();
    }

    static ItemStack tooth(ServerPlayerEntity p) {
        ItemStack s = new ItemStack(Items.BONE);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§4§lBoiled Tooth").styled(st -> st.withItalic(false)));
        s.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("§7Survived The Boiling Night").styled(st -> st.withItalic(false)),
                Text.literal("§7without it ever seeing them.").styled(st -> st.withItalic(false)),
                Text.literal("§8" + p.getGameProfile().name() + " · " + java.time.LocalDate.now()).styled(st -> st.withItalic(false)))));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && isLantern(p.getStackInHand(hand))) {
                return light(p, p.getStackInHand(hand));
            }
            return ActionResult.PASS;
        });
        // Lighting it while looking at a block shouldn't place it.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && isLantern(p.getStackInHand(hand))) {
                return light(p, p.getStackInHand(hand));
            }
            return ActionResult.PASS;
        });
    }
}
