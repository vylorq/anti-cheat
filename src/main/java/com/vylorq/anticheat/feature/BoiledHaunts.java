package com.vylorq.anticheat.feature;

import com.mojang.authlib.GameProfile;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * More of The Boiled One: friends calling for help in chat (it isn't them), footprints walking up to you, a glitched
 * name in the tab list while it hunts you, the door you open after the knocking, things in the dark that only look
 * like a cow or a friend, lights dying around it, the torn pages of its story, and how it kills.
 */
public final class BoiledHaunts {
    private BoiledHaunts() {
    }

    // ---------------------------------------------------------------- 1. a friend in chat (it isn't them)

    private static final String[] FAKE = {"boiled.fake.help", "boiled.fake.helpme", "boiled.fake.where", "boiled.fake.behind",
            "boiled.fake.come", "boiled.fake.here", "boiled.fake.dontlook", "boiled.fake.run"};

    /** Someone else on the server "says" something in chat. Only this player sees it, and the other never said it. */
    static boolean impersonate(ServerPlayerEntity p) {
        List<ServerPlayerEntity> others = new ArrayList<>();
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            if (o != p && !StaffTools.isVanished(o.getUuid())) {
                others.add(o);
            }
        }
        if (others.isEmpty()) {
            return false;
        }
        String name = others.get(p.getRandom().nextInt(others.size())).getGameProfile().name();
        fakeChat(p, name, FAKE[p.getRandom().nextInt(FAKE.length)]);
        return true;
    }

    /** A chat line that looks like it came from this player. */
    static void fakeChat(ServerPlayerEntity p, String name, String key) {
        p.sendMessage(Text.literal("<" + name + "> " + Msg.trFor(p, key)), false);
    }

    // ---------------------------------------------------------------- 2. footprints walking up to you

    /** Bloody footprints appear one after another, from far behind them, walking right up to their back. */
    static void trail(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d back = p.getRotationVec(1f).multiply(-1, 0, -1);
        if (back.lengthSquared() < 1e-4) {
            back = new Vec3d(1, 0, 0);
        }
        Vec3d dir = back.normalize();
        Vec3d start = p.getEntityPos();
        float yaw = (float) (MathHelper.atan2(-dir.z, -dir.x) * 57.2958) - 90f;   // walking toward them
        int steps = 22;
        for (int i = 0; i < steps; i++) {
            int k = i;
            OwnerPowers.later(7 * i, () -> {
                if (p.isRemoved()) {
                    return;
                }
                double d = 16 - k * 0.65;
                Vec3d at = start.add(dir.multiply(d));
                BlockPos ground = ground(w, BlockPos.ofFloored(at.x, start.y, at.z));
                if (ground == null) {
                    return;
                }
                BoiledOmens.footprintAt(w, new Vec3d(at.x, ground.getY(), at.z), yaw, k % 2 == 0);
                BoiledOmens.sound(p, SoundEvents.BLOCK_MUD_STEP, Vec3d.ofCenter(ground), 0.5f, 0.6f);
            });
        }
    }

    /** The open spot right above solid ground near here (feet height), or null. */
    private static BlockPos ground(ServerWorld w, BlockPos near) {
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos at = near.up(dy);
            if (w.getBlockState(at.down()).isSolidBlock(w, at.down()) && w.getBlockState(at).getCollisionShape(w, at).isEmpty()) {
                return at;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 3. its name in the tab list

    private static final UUID GHOST_ID = UUID.nameUUIDFromBytes("vigil-boiled-one-tab".getBytes(StandardCharsets.UTF_8));
    /** Players who see it in their tab list right now. */
    private static final Set<UUID> LISTED = ConcurrentHashMap.newKeySet();
    private static FakePlayer ghost;

    private static FakePlayer ghost() {
        if (ghost == null) {
            ghost = new FakePlayer(Ac.server().getOverworld(), new GameProfile(GHOST_ID, "TheBoiledOne")) {
                @Override
                public Text getPlayerListName() {
                    return Text.literal("Th").formatted(Formatting.DARK_RED)
                            .append(Text.literal("e B").formatted(Formatting.DARK_RED, Formatting.OBFUSCATED))
                            .append(Text.literal("oiled ").formatted(Formatting.DARK_RED))
                            .append(Text.literal("On").formatted(Formatting.DARK_RED, Formatting.OBFUSCATED))
                            .append(Text.literal("e").formatted(Formatting.DARK_RED));
                }
            };
        }
        return ghost;
    }

    private static void listFor(ServerPlayerEntity p, boolean on) {
        try {
            if (on) {
                p.networkHandler.sendPacket(new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER,
                        PlayerListS2CPacket.Action.UPDATE_LISTED, PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME,
                        PlayerListS2CPacket.Action.UPDATE_LATENCY), List.<ServerPlayerEntity>of(ghost())));
            } else {
                p.networkHandler.sendPacket(new PlayerRemoveS2CPacket(List.of(GHOST_ID)));
            }
        } catch (Exception e) {
            Ac.LOG.debug("tab ghost", e);
        }
    }

    // ---------------------------------------------------------------- 4. the door, after the knocking

    private record Knock(BlockPos door, long until) {
    }

    private static final Map<UUID, Knock> KNOCKED = new ConcurrentHashMap<>();

    /** They heard knocking on this door: if they open it soon, it may be standing right there. */
    static void knocked(ServerPlayerEntity p, BlockPos door) {
        KNOCKED.put(p.getUuid(), new Knock(door.toImmutable(), p.getEntityWorld().getTime() + 20 * 25));
    }

    /** One in this many opened doors (after knocking) has it behind. */
    static final int DOOR_ODDS = 3;

    private static void opened(ServerPlayerEntity p, BlockPos at, BlockState st) {
        Knock k = KNOCKED.get(p.getUuid());
        if (k == null || p.getEntityWorld().getTime() > k.until) {
            KNOCKED.remove(p.getUuid());
            return;
        }
        BlockPos lower = st.get(DoorBlock.HALF) == net.minecraft.block.enums.DoubleBlockHalf.UPPER ? at.down() : at;
        if (lower.getManhattanDistance(k.door) > 1) {
            return;
        }
        KNOCKED.remove(p.getUuid());
        if (p.getRandom().nextInt(DOOR_ODDS) != 0) {
            // Nothing there. Just the wind.
            OwnerPowers.later(4, () -> BoiledOmens.sound(p, SoundEvents.WEATHER_RAIN_ABOVE, Vec3d.ofCenter(lower), 0.4f, 0.5f));
            return;
        }
        OwnerPowers.later(3, () -> BoiledOne.atDoor(p, lower));
    }

    // ---------------------------------------------------------------- 6. things in the dark that only look like something

    static final String MIMIC_TAG = "vigil_boiled_mimic";
    private static final EntityType<?>[] DISGUISES = {EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.VILLAGER,
            EntityType.CHICKEN};
    private static final String[] CALLS = {"boiled.fake.come", "boiled.fake.here", "boiled.fake.follow"};

    private static final class Mimic {
        final UUID victim;
        final ServerWorld world;
        final Vec3d at;
        final long born;
        /** A real mob dressed as an animal, or... */
        MobEntity mob;
        /** ...a figure only the victim sees, wearing someone's skin and name. */
        WatcherFigure figure;

        Mimic(UUID victim, ServerWorld world, Vec3d at, long born) {
            this.victim = victim;
            this.world = world;
            this.at = at;
            this.born = born;
        }
    }

    private static final List<Mimic> MIMICS = new ArrayList<>();

    /** Dark enough around them for it to pass as something else. */
    static boolean dark(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        return w.getLightLevel(LightType.BLOCK, p.getBlockPos()) < 6 && (BoiledOne.night(w) || BoiledOne.inCave(p));
    }

    /**
     * Something in the dark ahead of them: a cow, a villager... or another player, their name over their head. It
     * just stands there, watching. Get close (or hit it) and it shows what it really is.
     *
     * @param asPlayer true: always another player's shape (or their own, if nobody else is on); false: an animal;
     *                 null: either
     */
    static boolean mimic(ServerPlayerEntity p, Boolean asPlayer) {
        if (!BoiledOne.huntable(p)) {
            return false;
        }
        ServerWorld w = p.getEntityWorld();
        Vec3d at = BoiledOne.spotNear(p, 12, 22);
        if (at == null) {
            return false;
        }
        List<ServerPlayerEntity> others = new ArrayList<>();
        for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
            if (o != p && !StaffTools.isVanished(o.getUuid())) {
                others.add(o);
            }
        }
        boolean player = asPlayer != null ? asPlayer : !others.isEmpty() && p.getRandom().nextBoolean();
        Mimic mm = new Mimic(p.getUuid(), w, at, w.getTime());
        if (player) {
            ServerPlayerEntity as = others.isEmpty() ? p : others.get(p.getRandom().nextInt(others.size()));
            String name = as.getGameProfile().name();
            mm.figure = WatcherFigure.ghost(w, as.getUuid(), name).named();
            mm.figure.at(at, 0, 0);
            mm.figure.show(p);
            mm.figure.lookAt(p, p.getEyePos());
            if (p.getRandom().nextBoolean() && as != p) {
                OwnerPowers.later(40, () -> {
                    if (MIMICS.contains(mm) && !p.isRemoved()) {
                        fakeChat(p, name, CALLS[p.getRandom().nextInt(CALLS.length)]);
                    }
                });
            }
        } else {
            Entity e = DISGUISES[p.getRandom().nextInt(DISGUISES.length)].create(w, SpawnReason.EVENT);
            if (!(e instanceof MobEntity m)) {
                return false;
            }
            m.refreshPositionAndAngles(at.x, at.y, at.z, p.getRandom().nextFloat() * 360, 0);
            m.setAiDisabled(true);
            m.setInvulnerable(true);
            m.setSilent(true);
            m.addCommandTag(MIMIC_TAG);
            StructureMobs.noDrops(m);
            w.spawnEntity(m);
            mm.mob = m;
        }
        MIMICS.add(mm);
        Ac.LOG.info("The Boiled One mimics {} for {}", player ? "a player" : "an animal", p.getGameProfile().name());
        return true;
    }

    private static void mimics(long now) {
        for (Mimic mm : MIMICS.toArray(new Mimic[0])) {
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(mm.victim);
            if (p == null || !p.isAlive() || p.getEntityWorld() != mm.world || now - mm.born > 20 * 120
                    || (mm.mob != null && mm.mob.isRemoved())) {
                drop(mm, p);
                continue;
            }
            double d = p.getEntityPos().distanceTo(mm.at);
            if (d > 64) {
                drop(mm, p);
                continue;
            }
            if (mm.figure != null && now % 10 == 0) {
                mm.figure.lookAt(p, p.getEyePos());
            } else if (mm.mob != null && now % 10 == 0 && mm.mob instanceof net.minecraft.entity.passive.VillagerEntity v) {
                v.getLookControl().lookAt(p, 30, 30);
                v.setHeadYaw(yawTo(v.getEntityPos(), p.getEntityPos()));
            }
            if (d < 4.5) {
                reveal(mm, p);
            }
        }
    }

    private static float yawTo(Vec3d from, Vec3d to) {
        Vec3d d = to.subtract(from);
        return (float) (MathHelper.atan2(d.z, d.x) * 57.2958) - 90f;
    }

    private static void drop(Mimic mm, ServerPlayerEntity p) {
        MIMICS.remove(mm);
        if (mm.figure != null && p != null) {
            mm.figure.hide(p);
        }
        if (mm.mob != null && !mm.mob.isRemoved()) {
            mm.mob.discard();
        }
    }

    /** It was never a cow. */
    private static void reveal(Mimic mm, ServerPlayerEntity p) {
        Vec3d at = mm.mob != null ? mm.mob.getEntityPos() : mm.at;
        drop(mm, p);
        mm.world.spawnParticles(net.minecraft.particle.ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 30, 0.4, 0.9, 0.4, 0.02);
        BoiledOne.transform(p, at);
    }

    // ---------------------------------------------------------------- 8. torn pages of its story

    static final String PAGE = "vigil_boiled_page";
    static final String EYE = "vigil_boiled_eye";
    static final int PAGES = 8;
    /** One escape in this many leaves a page behind where it stood. */
    static final int PAGE_ODDS = 6;
    /** One hostile mob in this many, killed at night, drops a page. */
    static final int MOB_PAGE_ODDS = 600;

    public static ItemStack page(ServerPlayerEntity p, int n) {
        ItemStack s = new ItemStack(Items.PAPER);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§c" + Msg.trFor(p, "boiled.page.name") + " §7(" + n + "/" + PAGES + ")")
                .styled(st -> st.withItalic(false)));
        List<Text> lore = new ArrayList<>();
        for (String line : wrap(Msg.trFor(p, "boiled.page." + n), 34)) {
            lore.add(Text.literal("§7§o" + line).styled(st -> st.withItalic(false)));
        }
        lore.add(Text.literal("§8" + Msg.trFor(p, "boiled.page.use")).styled(st -> st.withItalic(false)));
        s.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(lore));
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        com.vylorq.anticheat.util.ItemConv.setTag(s, PAGE, String.valueOf(n));
        return s;
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    static int pageOf(ItemStack s) {
        if (!s.isOf(Items.PAPER)) {
            return 0;
        }
        try {
            String t = com.vylorq.anticheat.util.ItemConv.tag(s, PAGE);
            return t == null ? 0 : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Which pages they carry. */
    private static Set<Integer> pagesHeld(ServerPlayerEntity p) {
        Set<Integer> have = new HashSet<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            int n = pageOf(inv.getStack(i));
            if (n > 0) {
                have.add(n);
            }
        }
        return have;
    }

    /** A page they don't have yet (most of the time). */
    private static int nextPage(ServerPlayerEntity p) {
        Set<Integer> have = pagesHeld(p);
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= PAGES; i++) {
            if (!have.contains(i)) {
                missing.add(i);
            }
        }
        if (missing.isEmpty() || p.getRandom().nextInt(4) == 0) {
            return 1 + p.getRandom().nextInt(PAGES);
        }
        return missing.get(p.getRandom().nextInt(missing.size()));
    }

    /** A torn page into their inventory (one they don't have yet, most of the time). */
    static void givePage(ServerPlayerEntity p) {
        p.getInventory().offerOrDrop(page(p, nextPage(p)));
    }

    /** They got away from it: sometimes it leaves a torn page behind. */
    static void escaped(ServerPlayerEntity p, Vec3d at) {
        if (p.getRandom().nextInt(PAGE_ODDS) != 0) {
            return;
        }
        dropPage(p, at);
    }

    private static void dropPage(ServerPlayerEntity p, Vec3d at) {
        ServerWorld w = p.getEntityWorld();
        var item = new net.minecraft.entity.ItemEntity(w, at.x, at.y + 0.5, at.z, page(p, nextPage(p)));
        item.setPickupDelay(20);
        w.spawnEntity(item);
        Msg.actionBar(p, "§8" + Msg.trFor(p, "boiled.page.found"));
    }

    private static ActionResult read(ServerPlayerEntity p, ItemStack held, int n) {
        Set<Integer> have = pagesHeld(p);
        if (have.size() >= PAGES) {
            ending(p);
            return ActionResult.SUCCESS;
        }
        p.sendMessage(Text.literal("§c" + Msg.trFor(p, "boiled.page.name") + " " + n + "/" + PAGES + ": §7§o"
                + Msg.trFor(p, "boiled.page." + n)), false);
        Msg.send(p, "boiled.page.count", have.size(), PAGES);
        Mc.sound(p, SoundEvents.ITEM_BOOK_PAGE_TURN, 1f, 0.7f);
        return ActionResult.SUCCESS;
    }

    /** All eight pages: the whole story, and it notices you. */
    private static void ending(ServerPlayerEntity p) {
        var inv = p.getInventory();
        Set<Integer> taken = new HashSet<>();
        for (int i = 0; i < inv.size(); i++) {
            int n = pageOf(inv.getStack(i));
            if (n > 0 && taken.add(n)) {
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.DARKNESS,
                140, 0, false, false));
        p.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(net.minecraft.entity.effect.StatusEffects.BLINDNESS,
                60, 0, false, false));
        Mc.sound(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.4f);
        for (int i = 1; i <= PAGES; i++) {
            int n = i;
            OwnerPowers.later(n * 12, () -> {
                if (!p.isRemoved()) {
                    p.sendMessage(Text.literal("§8§o" + Msg.trFor(p, "boiled.page." + n)), false);
                    Mc.sound(p, SoundEvents.ITEM_BOOK_PAGE_TURN, 0.8f, 0.5f);
                }
            });
        }
        OwnerPowers.later(PAGES * 12 + 30, () -> {
            if (p.isRemoved()) {
                return;
            }
            BoiledOne.jumpscare(p);
            OwnerPowers.later(20, () -> {
                Mc.title(p, "§4" + Msg.trFor(p, "boiled.ending.title"), "§7" + Msg.trFor(p, "boiled.ending.sub"), 10, 80, 30);
                p.getInventory().offerOrDrop(eye(p));
                for (ServerPlayerEntity o : Ac.server().getPlayerManager().getPlayerList()) {
                    Msg.send(o, "boiled.ending.done", p.getGameProfile().name());
                }
                Ac.LOG.info("{} read the whole story of The Boiled One", p.getGameProfile().name());
            });
        });
    }

    /** The Boiled Eye: the reward for the whole story. It shows which way it is. */
    public static ItemStack eye(ServerPlayerEntity p) {
        ItemStack s = new ItemStack(Items.ENDER_EYE);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§4§l" + Msg.trFor(p, "boiled.eye.name")).styled(st -> st.withItalic(false)));
        s.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("§7" + Msg.trFor(p, "boiled.eye.lore")).styled(st -> st.withItalic(false)))));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        com.vylorq.anticheat.util.ItemConv.setTag(s, EYE, "1");
        return s;
    }

    static boolean isEye(ItemStack s) {
        return s.isOf(Items.ENDER_EYE) && "1".equals(com.vylorq.anticheat.util.ItemConv.tag(s, EYE));
    }

    private static final String[] DIRS = {"boiled.dir.s", "boiled.dir.sw", "boiled.dir.w", "boiled.dir.nw", "boiled.dir.n",
            "boiled.dir.ne", "boiled.dir.e", "boiled.dir.se"};

    private static ActionResult gaze(ServerPlayerEntity p, ItemStack held) {
        if (p.getItemCooldownManager().isCoolingDown(held)) {
            return ActionResult.FAIL;
        }
        p.getItemCooldownManager().set(held, 20 * 60);
        Entity near = null;
        double best = 256 * 256;
        for (Entity e : p.getEntityWorld().iterateEntities()) {
            if ((e.getCommandTags().contains(BoiledOne.TAG) || e.getCommandTags().contains(MIMIC_TAG)) && e.isAlive()) {
                double d = e.squaredDistanceTo(p);
                if (d < best) {
                    best = d;
                    near = e;
                }
            }
        }
        Mc.sound(p, SoundEvents.BLOCK_END_PORTAL_FRAME_FILL, 1f, 0.5f);
        if (near == null) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "boiled.eye.none"));
            return ActionResult.SUCCESS;
        }
        Vec3d d = near.getEntityPos().subtract(p.getEntityPos());
        // Minecraft yaw: 0 is south, 90 west, 180 north, 270 east.
        float yaw = MathHelper.wrapDegrees((float) (MathHelper.atan2(d.z, d.x) * 57.2958) - 90f);
        int dir = Math.floorMod(Math.round(yaw / 45f), 8);
        Msg.actionBar(p, "§4" + Msg.trFor(p, "boiled.eye.near", (int) Math.sqrt(best), Msg.trFor(p, DIRS[dir])));
        return ActionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- 9. lights die around it

    /** Lights each victim's client shows as out right now (the real blocks are untouched). */
    private static final Map<UUID, Set<BlockPos>> DARKENED = new ConcurrentHashMap<>();

    private static BlockState unlit(BlockState st) {
        if (st.isOf(Blocks.TORCH) || st.isOf(Blocks.WALL_TORCH) || st.isOf(Blocks.SOUL_TORCH) || st.isOf(Blocks.SOUL_WALL_TORCH)
                || st.isOf(Blocks.REDSTONE_TORCH) || st.isOf(Blocks.REDSTONE_WALL_TORCH) || st.isOf(Blocks.COPPER_TORCH)
                || st.isOf(Blocks.COPPER_WALL_TORCH) || st.isOf(Blocks.LANTERN) || st.isOf(Blocks.SOUL_LANTERN)) {
            return Blocks.AIR.getDefaultState();
        }
        if (st.isOf(Blocks.JACK_O_LANTERN)) {
            return Blocks.CARVED_PUMPKIN.getDefaultState().with(net.minecraft.block.CarvedPumpkinBlock.FACING,
                    st.get(net.minecraft.block.CarvedPumpkinBlock.FACING));
        }
        if (st.getBlock() instanceof RedstoneLampBlock && st.get(RedstoneLampBlock.LIT)) {
            return st.with(RedstoneLampBlock.LIT, false);
        }
        if (st.getBlock() instanceof AbstractCandleBlock && st.contains(AbstractCandleBlock.LIT) && st.get(AbstractCandleBlock.LIT)) {
            return st.with(AbstractCandleBlock.LIT, false);
        }
        if (st.getBlock() instanceof CampfireBlock && st.get(CampfireBlock.LIT)) {
            return st.with(CampfireBlock.LIT, false);
        }
        return null;
    }

    /** Lights near it flicker; the closest go out (for the one it's after, until it's gone). */
    private static void flicker(ServerPlayerEntity p, Entity it) {
        ServerWorld w = p.getEntityWorld();
        Set<BlockPos> dark = DARKENED.computeIfAbsent(p.getUuid(), k -> ConcurrentHashMap.newKeySet());
        BlockPos c = it.getBlockPos();
        Set<BlockPos> want = new HashSet<>();
        for (BlockPos b : BlockPos.iterate(c.add(-7, -3, -7), c.add(7, 5, 7))) {
            BlockState st = w.getBlockState(b);
            BlockState off = unlit(st);
            if (off == null) {
                continue;
            }
            double d = Math.sqrt(b.getSquaredDistance(c));
            boolean out = d < 4 || p.getRandom().nextFloat() < (d < 8 ? 0.35f : 0f);
            if (out) {
                BlockPos at = b.toImmutable();
                want.add(at);
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(at, off));
                if (!dark.contains(at)) {
                    BoiledOmens.sound(p, SoundEvents.BLOCK_FIRE_EXTINGUISH, Vec3d.ofCenter(at), 0.15f, 1.6f);
                }
            }
        }
        for (BlockPos b : dark) {
            if (!want.contains(b)) {
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(w, b));
            }
        }
        dark.clear();
        dark.addAll(want);
    }

    private static void relight(ServerPlayerEntity p) {
        Set<BlockPos> dark = DARKENED.remove(p.getUuid());
        if (dark == null || p == null) {
            return;
        }
        for (BlockPos b : dark) {
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(p.getEntityWorld(), b));
        }
    }

    // ---------------------------------------------------------------- 12. how it kills

    /** Players it is killing right now (their death message is its own). */
    private static final Set<UUID> TAKEN = ConcurrentHashMap.newKeySet();
    private static final String[] DEATHS = {"boiled.death.1", "boiled.death.2", "boiled.death.3", "boiled.death.4"};

    public static void taking(ServerPlayerEntity p, boolean on) {
        if (on) {
            TAKEN.add(p.getUuid());
        } else {
            TAKEN.remove(p.getUuid());
        }
    }

    /** The death message for someone it just killed, or null for anyone else. */
    public static Text deathMessage(net.minecraft.entity.LivingEntity e) {
        if (!(e instanceof ServerPlayerEntity p) || !TAKEN.contains(p.getUuid())) {
            return null;
        }
        String key = DEATHS[Math.floorMod(p.getUuid().hashCode() + (int) (p.getEntityWorld().getTime() / 20), DEATHS.length)];
        return Text.literal(Msg.tr(key, p.getGameProfile().name())).formatted(Formatting.DARK_RED);
    }

    // ---------------------------------------------------------------- every tick

    static void tick(long now) {
        if (!MIMICS.isEmpty() && now % 2 == 0) {
            mimics(now);
        }
        if (now % 4 == 0) {
            Map<UUID, Entity> hunting = BoiledOne.hunting();
            for (UUID id : new ArrayList<>(DARKENED.keySet())) {
                if (!hunting.containsKey(id)) {
                    ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
                    if (p != null) {
                        relight(p);
                    } else {
                        DARKENED.remove(id);
                    }
                }
            }
            for (var e : hunting.entrySet()) {
                ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(e.getKey());
                if (p != null && p.getEntityWorld() == e.getValue().getEntityWorld() && p.squaredDistanceTo(e.getValue()) < 40 * 40) {
                    flicker(p, e.getValue());
                }
            }
            if (now % 20 == 0) {
                for (UUID id : hunting.keySet()) {
                    ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
                    if (p != null && LISTED.add(id)) {
                        listFor(p, true);
                    }
                }
                for (UUID id : new ArrayList<>(LISTED)) {
                    if (!hunting.containsKey(id)) {
                        LISTED.remove(id);
                        ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(id);
                        if (p != null) {
                            listFor(p, false);
                        }
                    }
                }
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e.getCommandTags().contains(MIMIC_TAG) && MIMICS.stream().noneMatch(m -> m.mob == e)) {
                OwnerPowers.later(1, () -> {
                    if (!e.isRemoved() && MIMICS.stream().noneMatch(m -> m.mob == e)) {
                        e.discard();        // left over from before a restart
                    }
                });
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, source, amount) -> {
            if (!Ac.running() || !e.getCommandTags().contains(MIMIC_TAG)) {
                return true;
            }
            for (Mimic mm : MIMICS.toArray(new Mimic[0])) {
                if (mm.mob == e) {
                    ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(mm.victim);
                    if (p != null) {
                        reveal(mm, p);
                    } else {
                        drop(mm, null);
                    }
                }
            }
            return false;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && e instanceof HostileEntity && source.getAttacker() instanceof ServerPlayerEntity p
                    && World.OVERWORLD.equals(e.getEntityWorld().getRegistryKey()) && BoiledOne.night(e.getEntityWorld())
                    && p.getRandom().nextInt(MOB_PAGE_ODDS) == 0) {
                dropPage(p, e.getEntityPos());
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            LISTED.remove(h.player.getUuid());
            DARKENED.remove(h.player.getUuid());
            KNOCKED.remove(h.player.getUuid());
            TAKEN.remove(h.player.getUuid());
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p)) {
                return ActionResult.PASS;
            }
            ItemStack held = p.getStackInHand(hand);
            int n = pageOf(held);
            if (n > 0) {
                return read(p, held, n);
            }
            if (isEye(held)) {
                return gaze(p, held);
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p)) {
                return ActionResult.PASS;
            }
            if (isEye(p.getStackInHand(hand))) {
                return gaze(p, p.getStackInHand(hand));        // not into an end portal frame
            }
            BlockState st = world.getBlockState(hit.getBlockPos());
            if (!KNOCKED.isEmpty() && st.getBlock() instanceof DoorBlock && !st.get(DoorBlock.OPEN)) {
                opened(p, hit.getBlockPos(), st);
            }
            return ActionResult.PASS;
        });
    }

    // ---------------------------------------------------------------- owner

    static void ownerPages(ServerPlayerEntity p) {
        for (int i = 1; i <= PAGES; i++) {
            p.getInventory().offerOrDrop(page(p, i));
        }
    }

    public static int pageOfForTest(ItemStack s) {
        return pageOf(s);
    }

    public static boolean hidingForTest(ServerPlayerEntity p) {
        return BoiledOmens.hiding(p);
    }
}
