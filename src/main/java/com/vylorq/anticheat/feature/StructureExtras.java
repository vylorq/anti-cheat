package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The secret structures' extras: the Boss Compass (points to the nearest structure whose boss hasn't risen yet), the
 * arena's altar chest sealed until its boss is beaten, floors that cave in under you, and lever puzzles that open a
 * hidden wall.
 */
public final class StructureExtras {
    private StructureExtras() {
    }

    public static final String COMPASS_TAG = "vigil_boss_compass";

    // ---------------------------------------------------------------- the Boss Compass

    public static ItemStack compass() {
        ItemStack s = new ItemStack(Items.COMPASS);
        ItemConv.setTag(s, COMPASS_TAG, "1");
        s.set(DataComponentTypes.ITEM_NAME, Text.literal("Boss Compass").styled(st -> st.withColor(0xE0533D)));
        s.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("Right-click: points to the nearest boss").styled(st -> st.withColor(0xAAAAAA).withItalic(false)),
                Text.literal("that hasn't been beaten yet").styled(st -> st.withColor(0xAAAAAA).withItalic(false)))));
        return s;
    }

    public static boolean isCompass(ItemStack s) {
        return !s.isEmpty() && ItemConv.tag(s, COMPASS_TAG) != null;
    }

    private record Candidate(RegistryEntry<Structure> structure, String name, ChunkPos chunk, double dist) {
    }

    /** The nearest secret structure (with a boss) whose boss hasn't risen yet, or null. */
    static BlockPos nearestUnbeaten(ServerWorld w, BlockPos from, String[] nameOut) {
        var cm = w.getChunkManager();
        long seed = cm.getStructurePlacementCalculator().getStructureSeed();
        var gen = cm.getChunkGenerator();
        var biomes = gen.getBiomeSource().getBiomes();
        int cx = from.getX() >> 4;
        int cz = from.getZ() >> 4;
        List<Candidate> all = new ArrayList<>();
        for (var set : w.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE_SET).streamEntries().toList()) {
            if (!set.getKey().map(k -> "vigil".equals(k.getValue().getNamespace())).orElse(false)
                    || !(set.value().placement() instanceof RandomSpreadStructurePlacement rs)) {
                continue;
            }
            for (var sw : set.value().structures()) {
                var st = sw.structure();
                String name = st.getKey().map(k -> k.getValue().getPath()).orElse("");
                if (!Bosses.hasBoss(name) || st.value().getValidBiomes().stream().noneMatch(biomes::contains)) {
                    continue;
                }
                int sp = rs.getSpacing();
                int rx = Math.floorDiv(cx, sp);
                int rz = Math.floorDiv(cz, sp);
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        ChunkPos c = rs.getStartChunk(seed, (rx + dx) * sp, (rz + dz) * sp);
                        if (Bosses.spawnedAt(name + "@" + c.x + "," + c.z)) {
                            continue;
                        }
                        double ddx = c.x - cx;
                        double ddz = c.z - cz;
                        all.add(new Candidate(st, name, c, ddx * ddx + ddz * ddz));
                    }
                }
            }
        }
        all.sort(Comparator.comparingDouble(Candidate::dist));
        int checks = 0;
        for (Candidate c : all) {
            if (checks++ >= 24) {
                break;
            }
            // Is one really there (right biome, room to fit)? The game's own check, for just that spot.
            var found = gen.locateStructure(w, RegistryEntryList.of(c.structure()), c.chunk().getStartPos(), 0, false);
            if (found != null && new ChunkPos(found.getFirst()).equals(c.chunk())) {
                nameOut[0] = c.name();
                return found.getFirst();
            }
        }
        return null;
    }

    private static void useCompass(ServerPlayerEntity p, ItemStack s) {
        ServerWorld w = p.getEntityWorld();
        String[] name = new String[1];
        BlockPos at = nearestUnbeaten(w, p.getBlockPos(), name);
        p.getItemCooldownManager().set(s, 600);
        if (at == null) {
            Msg.send(p, "bosscompass.none");
            return;
        }
        s.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(Optional.of(GlobalPos.create(w.getRegistryKey(), at)), false));
        int dist = (int) Math.sqrt(at.getSquaredDistance(p.getX(), at.getY(), p.getZ()));
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_LODESTONE_COMPASS_LOCK, SoundCategory.PLAYERS, 1f, 0.8f);
        w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, p.getX(), p.getY() + 1.2, p.getZ(), 20, 0.4, 0.4, 0.4, 0.02);
        Msg.send(p, "bosscompass.found", Bosses.bossOf(name[0]) + "§r", dist);
    }

    // ---------------------------------------------------------------- the trader's structure map

    public static final int MAP_PRICE = 32;
    private static final long MAP_COOLDOWN = 24L * 3600_000L;

    /** The trader's map button: a map to a random secret structure, once a day each. */
    public static ItemStack mapButton(ServerPlayerEntity p) {
        long left = Bosses.mapCooldown(p.getUuid(), MAP_COOLDOWN);
        var b = com.vylorq.anticheat.ui.Btn.of(Items.FILLED_MAP).color(0xE0533D).name(Msg.trFor(p, "smap.name"))
                .desc(Msg.trFor(p, "smap.desc")).line(Msg.trFor(p, "smap.price", MAP_PRICE));
        if (left > 0) {
            b.status(com.vylorq.anticheat.ui.Theme.RED, Msg.trFor(p, "smap.wait", com.vylorq.anticheat.core.util.Durations.format(left)));
        } else {
            b.left(Msg.trFor(p, "tr.action.buy"));
        }
        return b.build();
    }

    public static void buyMap(ServerPlayerEntity p) {
        if (Bosses.mapCooldown(p.getUuid(), MAP_COOLDOWN) > 0) {
            Msg.send(p, "smap.too-soon");
            return;
        }
        if (Markets.countItem(p, Items.EMERALD) < MAP_PRICE) {
            Msg.send(p, "smap.cant-afford", MAP_PRICE);
            return;
        }
        ServerWorld w = p.getEntityWorld();
        BlockPos found = w.locateStructure(SecretItems.SECRET_STRUCTURES, p.getBlockPos(), 60, false);
        if (found == null) {
            Msg.send(p, "smap.none");
            return;
        }
        if (!Markets.takeItem(p, Items.EMERALD, MAP_PRICE)) {
            return;
        }
        Bosses.mapBought(p.getUuid());
        ItemStack map = net.minecraft.item.FilledMapItem.createMap(w, found.getX(), found.getZ(), (byte) 2, true, true);
        net.minecraft.item.FilledMapItem.fillExplorationMap(w, map);
        net.minecraft.item.map.MapState.addDecorationsNbt(map, found, "+", net.minecraft.item.map.MapDecorationTypes.RED_X);
        map.set(DataComponentTypes.ITEM_NAME, Text.literal("Secret Structure Map").styled(st -> st.withColor(0xE0533D)));
        p.getInventory().offerOrDrop(map);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.PLAYERS, 1f, 1f);
        Msg.send(p, "smap.bought");
    }

    // ---------------------------------------------------------------- the sealed altar chest

    private static boolean container(BlockState st) {
        return st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL);
    }

    /** The arena's chest stays shut until its boss is beaten. @return the arena's key when sealed, else null */
    static String sealed(ServerWorld w, BlockPos pos, ServerPlayerEntity p) {
        if (p.isCreative() || Perms.isOwner(p.getUuid())) {
            return null;
        }
        String key = Bosses.arenaAt(w, pos);
        return key == null || Bosses.beaten(key) ? null : key;
    }

    private static void tellSealed(ServerPlayerEntity p, String key) {
        Msg.actionBar(p, "§c" + Msg.trFor(p, "boss.sealed", Bosses.bossOf(key.substring(0, key.indexOf('@'))) + "§c"));
        p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_CHEST_LOCKED, SoundCategory.BLOCKS, 1f, 1f);
    }

    // ---------------------------------------------------------------- lever puzzles

    /** The levers sit on this block; the answer is hidden behind it (glowstone = on), the door is made of WALL. */
    private static final net.minecraft.block.Block PANEL = Blocks.WAXED_CHISELED_COPPER;
    private static final net.minecraft.block.Block WALL = Blocks.WAXED_CUT_COPPER;

    private static BlockPos attached(BlockState lever, BlockPos pos) {
        return pos.offset(lever.get(LeverBlock.FACING).getOpposite());
    }

    private static boolean puzzleLever(ServerWorld w, BlockPos pos) {
        BlockState st = w.getBlockState(pos);
        return st.isOf(Blocks.LEVER) && st.get(LeverBlock.FACE) == BlockFace.WALL && w.getBlockState(attached(st, pos)).isOf(PANEL);
    }

    static void leverPulled(ServerWorld w, BlockPos pos, ServerPlayerEntity p) {
        if (!puzzleLever(w, pos)) {
            return;
        }
        Direction facing = w.getBlockState(pos).get(LeverBlock.FACING);
        Direction along = facing.rotateYClockwise();
        List<BlockPos> levers = new ArrayList<>();
        levers.add(pos);
        for (Direction d : List.of(along, along.getOpposite())) {
            BlockPos q = pos.offset(d);
            for (int i = 0; i < 6 && puzzleLever(w, q); i++) {
                levers.add(q);
                q = q.offset(d);
            }
        }
        if (levers.size() < 3) {
            return;
        }
        for (BlockPos l : levers) {
            BlockState st = w.getBlockState(l);
            boolean want = w.getBlockState(attached(st, l).offset(facing.getOpposite())).isOf(Blocks.GLOWSTONE);
            if (st.get(Properties.POWERED) != want) {
                return;
            }
        }
        // Solved: the copper wall nearby slides away.
        int opened = 0;
        for (BlockPos q : BlockPos.iterate(pos.add(-10, -3, -10), pos.add(10, 6, 10))) {
            if (w.getBlockState(q).isOf(WALL)) {
                w.setBlockState(q, Blocks.AIR.getDefaultState());
                w.spawnParticles(ParticleTypes.WAX_OFF, q.getX() + 0.5, q.getY() + 0.5, q.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0);
                opened++;
            }
        }
        if (opened > 0) {
            w.playSound(null, pos, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundCategory.BLOCKS, 1.5f, 0.6f);
            w.playSound(null, pos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.7f, 1.2f);
            Msg.actionBar(p, "§a" + Msg.trFor(p, "puzzle.solved"));
        }
    }

    public static void leverPulledForTest(ServerWorld w, BlockPos pos, ServerPlayerEntity p) {
        leverPulled(w, pos, p);
    }

    // ---------------------------------------------------------------- collapsing floors

    /** Gravel and sand left hanging over a drop give way when someone steps on them. */
    public static void tick(long ticks) {
        if (ticks % 5 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (p.isSpectator() || !p.isOnGround()) {
                continue;
            }
            ServerWorld w = p.getEntityWorld();
            BlockPos under = p.getBlockPos().down();
            for (BlockPos q : List.of(under, under.east(), under.west(), under.north(), under.south())) {
                BlockState st = w.getBlockState(q);
                if ((st.isOf(Blocks.GRAVEL) || st.isOf(Blocks.SAND))
                        && w.getBlockState(q.down()).isAir()) {
                    w.scheduleBlockTick(q, st.getBlock(), 6 + w.getRandom().nextInt(6));
                }
            }
        }
    }

    // ---------------------------------------------------------------- hooks

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && world instanceof ServerWorld && player instanceof ServerPlayerEntity p) {
                ItemStack held = p.getStackInHand(hand);
                if (isCompass(held)) {
                    if (!p.getItemCooldownManager().isCoolingDown(held)) {
                        useCompass(p, held);
                    }
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || !(world instanceof ServerWorld w) || !(player instanceof ServerPlayerEntity p)) {
                return ActionResult.PASS;
            }
            BlockPos pos = hit.getBlockPos();
            BlockState st = w.getBlockState(pos);
            if (container(st)) {
                String key = sealed(w, pos, p);
                if (key != null) {
                    tellSealed(p, key);
                    return ActionResult.FAIL;
                }
            } else if (st.isOf(Blocks.LEVER)) {
                OwnerPowers.later(1, () -> leverPulled(w, pos, p));
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) -> {
            if (Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && container(st)) {
                String key = sealed(w, pos, p);
                if (key != null) {
                    tellSealed(p, key);
                    return false;
                }
            }
            return true;
        });
    }
}
