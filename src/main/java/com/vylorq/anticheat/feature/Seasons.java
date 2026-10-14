package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seasons: spring, summer, autumn and winter, a week each (the owner can change the length or turn them off).
 * Spring and summer make crops grow faster around players; autumn's harvest sometimes gives a second helping;
 * winter's rain falls as snow that settles on the ground, and spring melts it again.
 */
public final class Seasons {
    private Seasons() {
    }

    public enum Season { SPRING, SUMMER, AUTUMN, WINTER }

    public static final class State {
        public boolean on = true;
        public long start = System.currentTimeMillis();
        public int daysEach = 7;
    }

    private static State state;
    private static Season last;

    private static Path file() {
        return Ac.get().dir.resolve("seasons.json");
    }

    static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read seasons.json", e);
            }
            if (state == null) {
                state = new State();
                save();
            }
            state.daysEach = Math.max(1, state.daysEach);
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save seasons.json", e);
        }
    }

    private static long length() {
        return state().daysEach * 86_400_000L;
    }

    /** The season now (spring when they're turned off, so nothing changes). */
    public static Season now() {
        if (!state().on) {
            return Season.SPRING;
        }
        long n = Math.floorDiv(System.currentTimeMillis() - state().start, length());
        return Season.values()[(int) Math.floorMod(n, 4)];
    }

    /** How long until the next season (millis). */
    public static long left() {
        return length() - Math.floorMod(System.currentTimeMillis() - state().start, length());
    }

    public static boolean on() {
        return state().on;
    }

    public static void setOn(boolean on) {
        state().on = on;
        save();
    }

    /** Jumps straight to a season (its first day). */
    public static void set(Season s) {
        long now = System.currentTimeMillis();
        long cycle = Math.floorDiv(now - state().start, length());
        long target = cycle - Math.floorMod(cycle, 4) + s.ordinal();
        state().start = now - target * length();
        save();
    }

    public static void setDays(int days) {
        Season s = now();
        state().daysEach = Math.max(1, days);
        set(s);
    }

    public static String name(ServerPlayerEntity p, Season s) {
        return Msg.trFor(p, "season." + s.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static String colour(Season s) {
        return switch (s) {
            case SPRING -> "§a";
            case SUMMER -> "§e";
            case AUTUMN -> "§6";
            case WINTER -> "§b";
        };
    }

    // ---------------------------------------------------------------- every second

    public static void tick(long ticks) {
        if (ticks % 20 != 0 || !state().on) {
            return;
        }
        Season s = now();
        if (last != s) {
            if (last != null) {
                for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                    Mc.title(p, colour(s) + "§l" + name(p, s), "§7" + Msg.trFor(p, "season.begins-" + s.name().toLowerCase(java.util.Locale.ROOT)), 10, 70, 20);
                }
                Ac.LOG.info("The season is now {}", s);
            }
            last = s;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            ServerWorld w = p.getEntityWorld();
            if (p.isSpectator() || !World.OVERWORLD.equals(w.getRegistryKey())) {
                continue;
            }
            switch (s) {
                case SPRING -> {
                    grow(w, p.getBlockPos(), 8);
                    melt(w, p.getBlockPos());
                }
                case SUMMER -> grow(w, p.getBlockPos(), 16);
                case AUTUMN -> grow(w, p.getBlockPos(), 3);
                case WINTER -> {
                    if (w.isRaining()) {
                        snow(w, p.getBlockPos());
                    }
                }
            }
        }
    }

    /** Extra growth: a few more random ticks for the crops around them. */
    private static void grow(ServerWorld w, BlockPos at, int tries) {
        var r = w.getRandom();
        for (int i = 0; i < tries; i++) {
            BlockPos q = at.add(r.nextInt(33) - 16, r.nextInt(9) - 4, r.nextInt(33) - 16);
            BlockState st = w.getBlockState(q);
            Block b = st.getBlock();
            if (b instanceof CropBlock || b instanceof StemBlock || b instanceof SweetBerryBushBlock
                    || st.isOf(Blocks.SUGAR_CANE) || st.isOf(Blocks.CACTUS) || st.isOf(Blocks.BAMBOO)) {
                st.randomTick(w, q, r);
            }
        }
    }

    /** Spots out in the open (sky above, solid ground under) around a player. */
    private static BlockPos openSpot(ServerWorld w, BlockPos at) {
        var r = w.getRandom();
        BlockPos top = w.getTopPosition(Heightmap.Type.MOTION_BLOCKING, at.add(r.nextInt(49) - 24, 0, r.nextInt(49) - 24));
        return top;
    }

    /** Winter: snow settles, a little at a time, wherever it falls in the open (not on claims, the lobby or builds). */
    private static void snow(ServerWorld w, BlockPos at) {
        for (int i = 0; i < 6; i++) {
            BlockPos q = openSpot(w, at);
            if (!w.getBlockState(q).isAir() || WorldGuard.claimedOrLobby(w, q) || Builds.protects(w, q)) {
                continue;
            }
            BlockState below = w.getBlockState(q.down());
            if (below.isSideSolidFullSquare(w, q.down(), Direction.UP) && !below.isOf(Blocks.ICE) && !below.isOf(Blocks.PACKED_ICE)
                    && Blocks.SNOW.getDefaultState().canPlaceAt(w, q)) {
                w.setBlockState(q, Blocks.SNOW.getDefaultState());
            } else if (below.isOf(Blocks.SNOW) && below.get(SnowBlock.LAYERS) < 3) {
                w.setBlockState(q.down(), below.with(SnowBlock.LAYERS, below.get(SnowBlock.LAYERS) + 1));
            }
        }
    }

    /** Spring: the snow melts away. */
    private static void melt(ServerWorld w, BlockPos at) {
        for (int i = 0; i < 10; i++) {
            BlockPos q = openSpot(w, at);
            BlockPos snow = w.getBlockState(q).isOf(Blocks.SNOW) ? q : w.getBlockState(q.down()).isOf(Blocks.SNOW) ? q.down() : null;
            if (snow == null || w.getBiome(snow).value().isCold(snow, w.getSeaLevel())) {
                continue;
            }
            BlockState st = w.getBlockState(snow);
            int layers = st.get(SnowBlock.LAYERS);
            w.setBlockState(snow, layers > 1 ? st.with(SnowBlock.LAYERS, layers - 1) : Blocks.AIR.getDefaultState());
            w.spawnParticles(ParticleTypes.FALLING_WATER, snow.getX() + 0.5, snow.getY() + 0.2, snow.getZ() + 0.5, 3, 0.3, 0.1, 0.3, 0);
        }
    }

    // ---------------------------------------------------------------- hooks

    public static void register() {
        // Autumn's harvest: a ripe crop now and then gives a second helping.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            if (Ac.running() && state().on && now() == Season.AUTUMN && world instanceof ServerWorld w
                    && st.getBlock() instanceof CropBlock crop && crop.isMature(st) && w.getRandom().nextInt(4) == 0) {
                Block.dropStacks(st, w, pos, be, player, player.getMainHandStack());
                w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0);
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            if (Ac.running() && state().on) {
                ServerPlayerEntity p = h.getPlayer();
                OwnerPowers.later(60, () -> {
                    if (!p.isRemoved()) {
                        Season s = now();
                        Msg.actionBar(p, colour(s) + Msg.trFor(p, "season.now", name(p, s),
                                com.vylorq.anticheat.core.util.Durations.format(left())));
                    }
                });
            }
        });
    }
}
