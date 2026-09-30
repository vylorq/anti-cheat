package com.vylorq.anticheat.gametest;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.claims.ClaimManager;
import com.vylorq.anticheat.core.movement.SafeSpot;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Durations;
import com.vylorq.anticheat.feature.Movement;
import com.vylorq.anticheat.feature.WorldGuard;
import com.vylorq.anticheat.feature.Xray;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.List;
import java.util.Map;

/**
 * In-game tests run on a headless server: {@code ./gradlew runGametest}. They check the parts that need a real
 * world (anti-x-ray chunk rewriting, claim border rules, safe setback spots, block snapshots).
 */
public final class AntiCheatGameTests {
    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new GameTestException(net.minecraft.text.Text.literal(what), 0);
        }
    }

    /** Every class a mixin targets. Loading one applies its mixins, so a broken injection fails here. */
    private static final String[] MIXIN_TARGETS = {
            "net.minecraft.item.BlockItem",
            "net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket",
            "net.minecraft.world.chunk.ChunkSection",
            "net.minecraft.server.command.CommandManager",
            "net.minecraft.block.DispenserBlock",
            "net.minecraft.world.explosion.ExplosionImpl",
            "net.minecraft.block.FarmlandBlock",
            "net.minecraft.block.FireBlock",
            "net.minecraft.fluid.FlowableFluid",
            "net.minecraft.block.entity.HopperBlockEntity",
            "net.minecraft.entity.player.HungerManager",
            "net.minecraft.entity.ItemEntity",
            "net.minecraft.entity.LivingEntity",
            "net.minecraft.block.PistonBlock",
            "net.minecraft.server.PlayerManager",
            "net.minecraft.entity.projectile.ProjectileEntity",
            "net.minecraft.server.network.ServerCommonNetworkHandler",
            "net.minecraft.server.network.ServerPlayNetworkHandler",
            "net.minecraft.block.TntBlock",
            "net.minecraft.entity.passive.VillagerEntity",
            "net.minecraft.world.World",
            "net.minecraft.entity.mob.EndermanEntity$PickUpBlockGoal",
            "net.minecraft.server.world.ServerChunkLoadingManager$EntityTracker",
            "net.minecraft.block.AbstractRedstoneGateBlock",
            "net.minecraft.block.ObserverBlock",
            "net.minecraft.block.RedstoneTorchBlock",
            "net.minecraft.screen.slot.CraftingResultSlot",
            "net.minecraft.screen.slot.FurnaceOutputSlot",
            "net.minecraft.entity.TntEntity",
            "net.minecraft.entity.FallingBlockEntity",
            "net.minecraft.server.MinecraftServer",
            "net.minecraft.entity.PlayerLikeEntity",
    };

    @GameTest
    public void allMixinsApply(TestContext ctx) {
        ClassLoader loader = AntiCheatGameTests.class.getClassLoader();
        StringBuilder failed = new StringBuilder();
        for (String name : MIXIN_TARGETS) {
            try {
                Class.forName(name, true, loader);
            } catch (Throwable t) {
                failed.append(name).append(": ").append(t).append('\n');
            }
        }
        check(failed.length() == 0, "mixin targets failed to load:\n" + failed);
        ctx.complete();
    }

    @GameTest
    public void defaultTradesHaveNoMoneyLoops(TestContext ctx) {
        List<String> problems = com.vylorq.anticheat.feature.Traders.economyProblems();
        check(problems.isEmpty(), "economy problems: " + problems);
        ctx.complete();
    }

    @GameTest
    public void tempAdminPutsEverythingBack(TestContext ctx) {
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "TempTester"));
        var pm = ctx.getWorld().getServer().getPlayerManager();
        fake.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STICK, 3));
        com.vylorq.anticheat.feature.TempAdmins.grant(null, fake, true);
        check(com.vylorq.anticheat.feature.TempAdmins.isTemp(fake.getUuid()), "grant not recorded");
        check(pm.isOperator(new net.minecraft.server.PlayerConfigEntry(fake.getGameProfile())), "not op after grant");
        fake.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_BLOCK, 64));
        fake.getEnderChestInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.NETHERITE_INGOT, 64));
        check(com.vylorq.anticheat.feature.TempAdmins.end(null, fake), "end returned false");
        check(!pm.isOperator(new net.minecraft.server.PlayerConfigEntry(fake.getGameProfile())), "still op after end");
        check(!com.vylorq.anticheat.feature.TempAdmins.isTemp(fake.getUuid()), "grant not cleared");
        check(fake.getInventory().getStack(0).isOf(net.minecraft.item.Items.STICK) && fake.getInventory().getStack(0).getCount() == 3,
                "inventory not restored: " + fake.getInventory().getStack(0));
        check(fake.getEnderChestInventory().getStack(0).isEmpty(), "ender chest not restored");
        ctx.complete();
    }

    @GameTest
    public void modIsRunning(TestContext ctx) {
        check(Ac.running(), "AntiCheat services are not running");
        check(Ac.get().db != null, "database not open");
        ctx.complete();
    }

    @GameTest
    public void xrayHidesEnclosedOreOnly(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos base = ctx.getAbsolutePos(new BlockPos(1, 2, 1));
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    w.setBlockState(base.add(x, y, z), Blocks.STONE.getDefaultState());
                }
            }
        }
        w.setBlockState(base, Blocks.DIAMOND_ORE.getDefaultState());
        BlockPos exposed = base.add(3, 0, 0);
        w.setBlockState(exposed, Blocks.DIAMOND_ORE.getDefaultState());
        w.setBlockState(exposed.up(), Blocks.AIR.getDefaultState());
        WorldChunk chunk = w.getWorldChunk(base);
        check(Xray.enclosed(chunk, w, base.getX(), base.getY(), base.getZ()), "ore should be enclosed");
        Map<ChunkSection, ChunkSection> mods = Xray.modifiedSections(chunk);
        ChunkSection real = chunk.getSection(chunk.getSectionIndex(base.getY()));
        ChunkSection sent = mods.getOrDefault(real, real);
        BlockState seen = sent.getBlockState(base.getX() & 15, base.getY() & 15, base.getZ() & 15);
        check(!seen.isOf(Blocks.DIAMOND_ORE), "enclosed diamond ore must be hidden in the packet");
        check(w.getBlockState(base).isOf(Blocks.DIAMOND_ORE), "the real world must not change");
        if ((exposed.getX() >> 4) == chunk.getPos().x && (exposed.getZ() >> 4) == chunk.getPos().z
                && chunk.getSectionIndex(exposed.getY()) == chunk.getSectionIndex(base.getY())) {
            BlockState seenExposed = sent.getBlockState(exposed.getX() & 15, exposed.getY() & 15, exposed.getZ() & 15);
            check(seenExposed.isOf(Blocks.DIAMOND_ORE), "exposed ore must stay visible");
        }
        ctx.complete();
    }

    @GameTest
    public void claimBordersStopCrossings(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 1, 0));
        String name = "gametest_" + a.getX() + "_" + a.getZ();
        ClaimManager.CreateResult r = Ac.get().claims.create(name, Mc.worldId(w), a.getX() + 2, a.getZ() + 2,
                a.getX() + 5, a.getZ() + 5, java.util.UUID.randomUUID(), false, Durations.HOUR, 0, 1000);
        check(r == ClaimManager.CreateResult.OK, "claim create failed: " + r);
        try {
            BlockPos outside = a.add(1, 0, 3);
            BlockPos inside = outside.offset(Direction.EAST);
            check(!WorldGuard.crossAllowed(w, outside, inside), "outside -> inside must be blocked");
            check(WorldGuard.crossAllowed(w, inside, outside), "inside -> outside is allowed");
            check(WorldGuard.crossAllowed(w, inside, inside.east()), "inside -> inside is allowed");
        } finally {
            Ac.get().claims.delete(name);
        }
        ctx.complete();
    }

    @GameTest
    public void setbackAvoidsLava(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        BlockPos floor = ctx.getAbsolutePos(new BlockPos(2, 0, 2));
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                w.setBlockState(floor.add(x, 0, z), Blocks.STONE.getDefaultState());
                w.setBlockState(floor.add(x, 1, z), Blocks.AIR.getDefaultState());
                w.setBlockState(floor.add(x, 2, z), Blocks.AIR.getDefaultState());
            }
        }
        w.setBlockState(floor.up(), Blocks.LAVA.getDefaultState());
        var view = Movement.view(w);
        var bad = new com.vylorq.anticheat.core.util.Vec3(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
        check(!SafeSpot.isSafe(view, bad), "lava spot must not be safe");
        var safe = SafeSpot.find(view, bad, 3);
        check(safe != null, "a safe spot nearby must be found");
        check(!w.getBlockState(BlockPos.ofFloored(safe.x(), safe.y(), safe.z())).isOf(Blocks.LAVA), "safe spot is lava");
        ctx.complete();
    }

    @GameTest
    public void snapshotsRestoreBlocks(TestContext ctx) throws Exception {
        ServerWorld w = ctx.getWorld();
        BlockPos p = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        w.setBlockState(p, Blocks.GOLD_BLOCK.getDefaultState());
        Area a = new Area(Mc.worldId(w), p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
        String name = "gametest-" + p.getX() + "-" + p.getZ();
        BlockSnapshots.save(w, a, name);
        w.setBlockState(p, Blocks.AIR.getDefaultState());
        int changed = BlockSnapshots.restore(w, name);
        check(changed >= 1, "restore changed nothing");
        check(w.getBlockState(p).isOf(Blocks.GOLD_BLOCK), "gold block not restored");
        java.nio.file.Files.deleteIfExists(BlockSnapshots.file(name));
        ctx.complete();
    }

    /**
     * Every Watcher effect is packets only (33.7): the world, the player's inventory and the weather are untouched,
     * and everything sent is taken back by the end (fake figures destroyed, fake blocks and slots resent as real).
     */
    @GameTest(maxTicks = 200)
    public void watcherEffectsAreFakeAndCleanedUp(TestContext ctx) {
        ServerWorld w = ctx.getWorld();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y <= 3; y++) {
                    ctx.setBlockState(new BlockPos(x, y, z), z == 7 ? Blocks.STONE : Blocks.AIR);
                }
            }
        }
        ctx.setBlockState(new BlockPos(1, 1, 3), Blocks.TORCH);
        ctx.setBlockState(new BlockPos(5, 1, 3), Blocks.LANTERN);
        BlockPos origin = ctx.getAbsolutePos(BlockPos.ORIGIN);
        java.util.Map<BlockPos, BlockState> before = new java.util.HashMap<>();
        for (BlockPos pos : BlockPos.iterate(origin.add(-1, -1, -1), origin.add(8, 5, 8))) {
            before.put(pos.toImmutable(), w.getBlockState(pos));
        }
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(w,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "WatchTester"));
        BlockPos stand = ctx.getAbsolutePos(new BlockPos(3, 1, 2));
        fake.refreshPositionAndAngles(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0f, 0f);
        fake.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.COMPASS));
        boolean rainBefore = w.isRaining();

        java.util.Map<Integer, Integer> figures = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, Integer> listed = new java.util.HashMap<>();
        java.util.Map<BlockPos, BlockState> lastBlock = new java.util.HashMap<>();
        java.util.Map<Integer, net.minecraft.item.ItemStack> lastSlot = new java.util.HashMap<>();
        java.util.List<String> started = new java.util.ArrayList<>();
        Object[] lastRain = {null};
        Object[] lastSpawn = {null};
        com.vylorq.anticheat.feature.Watcher.spy = (pl, pk) -> {
            if (pl != fake) {
                return;
            }
            if (pk instanceof net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket sp) {
                figures.merge(sp.getEntityId(), 1, Integer::sum);
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket d) {
                for (int id : d.getEntityIds()) {
                    figures.merge(id, -1, Integer::sum);
                }
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.PlayerListS2CPacket l) {
                for (var e : l.getEntries()) {
                    listed.merge(e.profileId(), 1, Integer::sum);
                }
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket r) {
                for (var id : r.profileIds()) {
                    listed.merge(id, -1, Integer::sum);
                }
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket b) {
                lastBlock.put(b.getPos().toImmutable(), b.getState());
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket u && u.getSyncId() == 0) {
                lastSlot.put(u.getSlot(), u.getStack());
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket g
                    && (g.getReason() == net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket.RAIN_STARTED
                    || g.getReason() == net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket.RAIN_STOPPED)) {
                lastRain[0] = g.getReason();
            } else if (pk instanceof net.minecraft.network.packet.s2c.play.PlayerSpawnPositionS2CPacket sp) {
                lastSpawn[0] = sp.respawnData();
            }
        };
        try {
            for (var eff : com.vylorq.anticheat.core.watcher.WatcherEffect.values()) {
                if (com.vylorq.anticheat.feature.Watcher.runForTest(fake, eff, 1300)) {
                    started.add(eff.id());
                }
            }
        } finally {
            com.vylorq.anticheat.feature.Watcher.spy = null;
        }
        for (String must : List.of("flicker", "sign", "footsteps", "turn_around", "gift", "wrong_compass", "message", "whisper")) {
            check(started.contains(must), must + " did not start; started: " + started);
        }
        for (var e : before.entrySet()) {
            check(w.getBlockState(e.getKey()).equals(e.getValue()), "real block changed at " + e.getKey() + ": " + w.getBlockState(e.getKey()));
        }
        for (var e : figures.entrySet()) {
            check(e.getValue() == 0, "fake figure " + e.getKey() + " left behind (" + e.getValue() + ")");
        }
        for (var e : listed.entrySet()) {
            check(e.getValue() == 0, "fake profile " + e.getKey() + " left in the player list");
        }
        for (var e : lastBlock.entrySet()) {
            check(e.getValue().equals(w.getBlockState(e.getKey())), "fake block left at " + e.getKey() + ": " + e.getValue());
        }
        for (var e : lastSlot.entrySet()) {
            check(net.minecraft.item.ItemStack.areEqual(e.getValue(), fake.getInventory().getStack(e.getKey())),
                    "fake slot " + e.getKey() + " left: " + e.getValue());
        }
        for (int i = 1; i < fake.getInventory().size(); i++) {
            check(fake.getInventory().getStack(i).isEmpty(), "a Watcher item became real in slot " + i);
        }
        check(fake.getInventory().getStack(0).isOf(net.minecraft.item.Items.COMPASS), "compass changed");
        check(w.isRaining() == rainBefore, "real weather changed");
        if (lastRain[0] != null) {
            check(lastRain[0] == (rainBefore ? net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket.RAIN_STARTED
                    : net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket.RAIN_STOPPED), "fake weather left");
        }
        check(lastSpawn[0] == null || lastSpawn[0].equals(w.getServer().getSpawnPoint()), "compass target left: " + lastSpawn[0]);
        ctx.complete();
    }
}
