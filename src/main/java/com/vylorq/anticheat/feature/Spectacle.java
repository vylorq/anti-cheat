package com.vylorq.anticheat.feature;

import com.google.gson.JsonObject;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Msg;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Owner shows: a statue of any player built out of blocks from their skin (one skin pixel per block, 32 blocks tall,
 * facing the owner; /owner statue undo takes the last one away again), and a fireworks show.
 */
public final class Spectacle {
    private Spectacle() {
    }

    // ---------------------------------------------------------------- statues

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** The last statue each owner built: every block it replaced, to put back. */
    private static final Map<UUID, Map<BlockPos, BlockState>> LAST = new HashMap<>();

    /** The blocks a statue is built from, with their average colours. */
    private static final Object[][] PALETTE = {
            {Blocks.WHITE_CONCRETE, 0xCFD5D6}, {Blocks.ORANGE_CONCRETE, 0xE06101}, {Blocks.MAGENTA_CONCRETE, 0xA9309F},
            {Blocks.LIGHT_BLUE_CONCRETE, 0x2489C7}, {Blocks.YELLOW_CONCRETE, 0xF1AF15}, {Blocks.LIME_CONCRETE, 0x5EA918},
            {Blocks.PINK_CONCRETE, 0xD5658E}, {Blocks.GRAY_CONCRETE, 0x373A3E}, {Blocks.LIGHT_GRAY_CONCRETE, 0x7D7D73},
            {Blocks.CYAN_CONCRETE, 0x157788}, {Blocks.PURPLE_CONCRETE, 0x64209C}, {Blocks.BLUE_CONCRETE, 0x2C2E8F},
            {Blocks.BROWN_CONCRETE, 0x603C20}, {Blocks.GREEN_CONCRETE, 0x495B24}, {Blocks.RED_CONCRETE, 0x8E2121},
            {Blocks.BLACK_CONCRETE, 0x080A0F},
            {Blocks.WHITE_TERRACOTTA, 0xD1B2A1}, {Blocks.ORANGE_TERRACOTTA, 0xA15325}, {Blocks.MAGENTA_TERRACOTTA, 0x95586C},
            {Blocks.LIGHT_BLUE_TERRACOTTA, 0x706C8A}, {Blocks.YELLOW_TERRACOTTA, 0xBA8523}, {Blocks.LIME_TERRACOTTA, 0x677534},
            {Blocks.PINK_TERRACOTTA, 0xA14E4E}, {Blocks.GRAY_TERRACOTTA, 0x392A23}, {Blocks.LIGHT_GRAY_TERRACOTTA, 0x876B62},
            {Blocks.CYAN_TERRACOTTA, 0x565B5B}, {Blocks.PURPLE_TERRACOTTA, 0x764656}, {Blocks.BLUE_TERRACOTTA, 0x4A3B5B},
            {Blocks.BROWN_TERRACOTTA, 0x4D3323}, {Blocks.GREEN_TERRACOTTA, 0x4C532A}, {Blocks.RED_TERRACOTTA, 0x8F3D2E},
            {Blocks.BLACK_TERRACOTTA, 0x251610}, {Blocks.TERRACOTTA, 0x985E43},
            {Blocks.WHITE_WOOL, 0xE9ECEC}, {Blocks.YELLOW_WOOL, 0xF8C527}, {Blocks.LIGHT_BLUE_WOOL, 0x3AAFD9},
            {Blocks.PINK_WOOL, 0xED8DAC}, {Blocks.LIME_WOOL, 0x70B919}, {Blocks.ORANGE_WOOL, 0xF07613},
            {Blocks.SMOOTH_SANDSTONE, 0xE0D6AA}, {Blocks.BIRCH_PLANKS, 0xC0AF79}, {Blocks.OAK_PLANKS, 0xA2834F},
            {Blocks.SPRUCE_PLANKS, 0x735531}, {Blocks.DARK_OAK_PLANKS, 0x422B14}, {Blocks.MANGROVE_PLANKS, 0x763631},
            {Blocks.STONE, 0x7E7E7E}, {Blocks.SMOOTH_STONE, 0x9E9E9E}, {Blocks.DEEPSLATE, 0x505052}, {Blocks.QUARTZ_BLOCK, 0xECE6DF},
            {Blocks.GOLD_BLOCK, 0xF6D03D}, {Blocks.IRON_BLOCK, 0xDCDCDC}, {Blocks.LAPIS_BLOCK, 0x1F438C}, {Blocks.EMERALD_BLOCK, 0x2ACB57},
            {Blocks.DIAMOND_BLOCK, 0x62EDE4}, {Blocks.REDSTONE_BLOCK, 0xAF1805}, {Blocks.COAL_BLOCK, 0x101010},
            {Blocks.NETHERRACK, 0x612626}, {Blocks.PACKED_MUD, 0x8E6B50}, {Blocks.MUD_BRICKS, 0x89684F}, {Blocks.SNOW_BLOCK, 0xF9FEFE},
            {Blocks.PRISMARINE, 0x639C97}, {Blocks.DARK_PRISMARINE, 0x335B4B}, {Blocks.CRIMSON_PLANKS, 0x653147},
            {Blocks.WARPED_PLANKS, 0x2B6963}, {Blocks.HONEYCOMB_BLOCK, 0xE5941E}, {Blocks.SANDSTONE, 0xD8CB9B}};

    static Block nearest(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        Block best = Blocks.STONE;
        long bestD = Long.MAX_VALUE;
        for (Object[] e : PALETTE) {
            int c = (int) e[1];
            long dr = r - (c >> 16 & 0xFF);
            long dg = g - (c >> 8 & 0xFF);
            long db = b - (c & 0xFF);
            // Weighted a little towards how eyes see brightness.
            long d = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
            if (d < bestD) {
                bestD = d;
                best = (Block) e[0];
            }
        }
        return best;
    }

    /** One box of the body: where it sits in the statue, its size, and where its skin is (base layer, overlay layer). */
    private record Part(int x, int y, int z, int w, int h, int d, int u, int v, int ou, int ov) {
    }

    private static final Part[] PARTS = {
            new Part(4, 24, 0, 8, 8, 8, 0, 0, 32, 0),      // head
            new Part(4, 12, 2, 8, 12, 4, 16, 16, 16, 32),  // body
            new Part(0, 12, 2, 4, 12, 4, 40, 16, 40, 32),  // right arm
            new Part(12, 12, 2, 4, 12, 4, 32, 48, 48, 48), // left arm
            new Part(4, 0, 2, 4, 12, 4, 0, 16, 0, 32),     // right leg
            new Part(8, 0, 2, 4, 12, 4, 16, 48, 0, 48)};   // left leg

    /** The skin pixel showing on this block of a part (the side it faces decides which bit of the skin). */
    private static int pixel(BufferedImage skin, Part p, int i, int j, int k, int u, int v) {
        int w = p.w(), h = p.h(), d = p.d();
        int tx;
        int ty;
        if (k == d - 1) {                 // front
            tx = u + d + i;
            ty = v + d + (h - 1 - j);
        } else if (k == 0) {              // back
            tx = u + 2 * d + w + (w - 1 - i);
            ty = v + d + (h - 1 - j);
        } else if (i == 0) {              // its right side
            tx = u + k;
            ty = v + d + (h - 1 - j);
        } else if (i == w - 1) {          // its left side
            tx = u + d + w + (d - 1 - k);
            ty = v + d + (h - 1 - j);
        } else if (j == h - 1) {          // top
            tx = u + d + i;
            ty = v + k;
        } else {                          // bottom (or inside)
            tx = u + d + w + i;
            ty = v + k;
        }
        if (tx < 0 || ty < 0 || tx >= skin.getWidth() || ty >= skin.getHeight()) {
            return 0;
        }
        return skin.getRGB(tx, ty);
    }

    public static void statue(ServerPlayerEntity owner, String name) {
        if (!OwnerPowers.require(owner)) {
            return;
        }
        Msg.send(owner, "statue.fetching", name);
        CompletableFuture.supplyAsync(() -> {
            try {
                var skin = WatcherSkins.find(name);
                JsonObject tex = ConfigManager.GSON.fromJson(new String(Base64.getDecoder().decode(skin.value()), StandardCharsets.UTF_8), JsonObject.class);
                String url = tex.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString().replace("http://", "https://");
                byte[] png = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(),
                        HttpResponse.BodyHandlers.ofByteArray()).body();
                return ImageIO.read(new ByteArrayInputStream(png));
            } catch (Exception e) {
                Ac.LOG.warn("Couldn't get the skin for a statue of {}: {}", name, e.toString());
                return null;
            }
        }).thenAccept(img -> Ac.server().execute(() -> {
            if (owner.isRemoved()) {
                return;
            }
            if (img == null) {
                Msg.send(owner, "statue.no-skin", name);
                return;
            }
            build(owner, img);
            Msg.send(owner, "statue.built", name);
        }));
    }

    static void build(ServerPlayerEntity owner, BufferedImage skin) {
        ServerWorld w = owner.getEntityWorld();
        boolean old = skin.getHeight() == 32;
        Direction facing = owner.getHorizontalFacing();
        Direction front = facing.getOpposite();
        Direction right = facing.rotateYClockwise();
        BlockPos base = owner.getBlockPos().offset(facing, 6);
        Map<BlockPos, BlockState> undo = new HashMap<>();
        for (Part p : PARTS) {
            // Old 64x32 skins have no left arm or leg of their own: they use the right ones.
            int u = p.u(), v = p.v(), ou = p.ou(), ov = p.ov();
            if (old && v >= 32) {
                u = p.u() == 32 ? 40 : 0;
                v = 16;
                ou = -100;
            }
            if (old && ov >= 32) {
                ou = -100;
            }
            for (int i = 0; i < p.w(); i++) {
                for (int j = 0; j < p.h(); j++) {
                    for (int k = 0; k < p.d(); k++) {
                        int c = pixel(skin, p, i, j, k, u, v);
                        if (ou >= 0) {
                            int o = pixel(skin, p, i, j, k, ou, ov);
                            if ((o >>> 24) > 128) {
                                c = o;
                            }
                        }
                        Block b = (c >>> 24) < 16 ? Blocks.STONE : nearest(c & 0xFFFFFF);
                        int x = p.x() + i - 8;
                        int y = p.y() + j;
                        int z = p.z() + k - 4;
                        BlockPos at = base.offset(right, x).up(y).offset(front, z);
                        undo.putIfAbsent(at.toImmutable(), w.getBlockState(at));
                        w.setBlockState(at, b.getDefaultState());
                    }
                }
            }
        }
        LAST.put(owner.getUuid(), undo);
    }

    public static void buildForTest(ServerPlayerEntity owner, BufferedImage skin) {
        build(owner, skin);
    }

    public static void undoForTest(ServerPlayerEntity owner) {
        var undo = LAST.remove(owner.getUuid());
        if (undo != null) {
            undo.forEach(owner.getEntityWorld()::setBlockState);
        }
    }

    public static void undo(ServerPlayerEntity owner) {
        if (!OwnerPowers.require(owner)) {
            return;
        }
        var undo = LAST.remove(owner.getUuid());
        if (undo == null) {
            Msg.send(owner, "statue.nothing");
            return;
        }
        ServerWorld w = owner.getEntityWorld();
        undo.forEach(w::setBlockState);
        Msg.send(owner, "statue.removed");
    }

    // ---------------------------------------------------------------- fireworks

    public static void fireworks(ServerPlayerEntity owner, int seconds) {
        if (!OwnerPowers.require(owner)) {
            return;
        }
        ServerWorld w = owner.getEntityWorld();
        BlockPos at = owner.getBlockPos();
        var r = w.getRandom();
        FireworkExplosionComponent.Type[] shapes = FireworkExplosionComponent.Type.values();
        for (int t = 0; t < seconds * 20; t += 4) {
            OwnerPowers.later(t, () -> {
                for (int n = 0; n < 2; n++) {
                    List<Integer> colours = new ArrayList<>();
                    for (int c = 0; c < 1 + r.nextInt(3); c++) {
                        colours.add(java.awt.Color.HSBtoRGB(r.nextFloat(), 0.6f + r.nextFloat() * 0.4f, 1f) & 0xFFFFFF);
                    }
                    var boom = new FireworkExplosionComponent(shapes[r.nextInt(shapes.length)], IntList.of(colours.stream().mapToInt(i -> i).toArray()),
                            IntList.of(java.awt.Color.HSBtoRGB(r.nextFloat(), 0.5f, 1f) & 0xFFFFFF), r.nextBoolean(), r.nextBoolean());
                    ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
                    rocket.set(DataComponentTypes.FIREWORKS, new FireworksComponent(1 + r.nextInt(3), List.of(boom)));
                    double x = at.getX() + 0.5 + (r.nextDouble() - 0.5) * 40;
                    double z = at.getZ() + 0.5 + (r.nextDouble() - 0.5) * 40;
                    double y = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) x, (int) z);
                    w.spawnEntity(new FireworkRocketEntity(w, x, y, z, rocket));
                }
            });
        }
        Msg.send(owner, "fireworks.started", seconds);
    }
}
