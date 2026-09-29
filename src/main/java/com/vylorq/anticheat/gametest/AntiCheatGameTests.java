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
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
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
public final class AntiCheatGameTests implements FabricGameTest {
    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new GameTestException(what);
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
    };

    @GameTest(templateName = EMPTY_STRUCTURE)
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

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void defaultTradesHaveNoMoneyLoops(TestContext ctx) {
        List<String> problems = com.vylorq.anticheat.feature.Traders.economyProblems();
        check(problems.isEmpty(), "economy problems: " + problems);
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
    public void modIsRunning(TestContext ctx) {
        check(Ac.running(), "AntiCheat services are not running");
        check(Ac.get().db != null, "database not open");
        ctx.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE)
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

    @GameTest(templateName = EMPTY_STRUCTURE)
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

    @GameTest(templateName = EMPTY_STRUCTURE)
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

    @GameTest(templateName = EMPTY_STRUCTURE)
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
}
