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
            "net.minecraft.block.EndPortalBlock",
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
    public void autoTotemCatchesInstantRefillOnly(TestContext ctx) {
        var w = ctx.getWorld();
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "TotemTester"));
        var ac = Ac.get();
        var s = Ac.session(fake);
        java.util.UUID id = fake.getUuid();
        ac.engine.violations().reset(id);

        // A real pop: the totem in the offhand saves the player, and the watch starts.
        fake.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        boolean saved = ((com.vylorq.anticheat.mixin.LivingEntityInvoker) fake).ac$tryUseDeathProtector(w.getDamageSources().generic());
        check(saved, "the totem didn't pop");
        check(fake.getOffHandStack().isEmpty(), "the popped totem is still in the offhand");
        long pop = System.nanoTime();
        check(s.totemWatch.watching(pop), "the pop wasn't noticed");

        // Auto-totem: the game answers the ping 40 ms later and a new totem is in the offhand 20 ms after that.
        com.vylorq.anticheat.feature.AutoTotem.pong(fake, s.totemPingId, pop + 40_000_000L);
        com.vylorq.anticheat.feature.AutoTotem.before(fake, pop + 60_000_000L);
        fake.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        com.vylorq.anticheat.feature.AutoTotem.after(fake);
        double caught = ac.engine.violations().points(id, com.vylorq.anticheat.core.detect.CheckType.AUTO_TOTEM);
        check(caught > 0, "an instant totem refill wasn't flagged");

        // A person: same lag, but the totem goes in 450 ms after the game saw the pop.
        boolean saved2 = ((com.vylorq.anticheat.mixin.LivingEntityInvoker) fake).ac$tryUseDeathProtector(w.getDamageSources().generic());
        check(saved2, "the second totem didn't pop");
        long pop2 = System.nanoTime();
        com.vylorq.anticheat.feature.AutoTotem.pong(fake, s.totemPingId, pop2 + 40_000_000L);
        com.vylorq.anticheat.feature.AutoTotem.before(fake, pop2 + 490_000_000L);
        fake.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        com.vylorq.anticheat.feature.AutoTotem.after(fake);
        check(ac.engine.violations().points(id, com.vylorq.anticheat.core.detect.CheckType.AUTO_TOTEM) == caught,
                "a human-speed totem refill was flagged");

        // Totems moved around with no pop going on (sorting the inventory) never count.
        fake.setStackInHand(net.minecraft.util.Hand.OFF_HAND, net.minecraft.item.ItemStack.EMPTY);
        com.vylorq.anticheat.feature.AutoTotem.before(fake, System.nanoTime());
        fake.setStackInHand(net.minecraft.util.Hand.OFF_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        com.vylorq.anticheat.feature.AutoTotem.after(fake);
        check(ac.engine.violations().points(id, com.vylorq.anticheat.core.detect.CheckType.AUTO_TOTEM) == caught,
                "a totem moved without a pop was flagged");
        ac.engine.violations().reset(id);
        ctx.complete();
    }

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
        check(Ac.running(), "Vigil services are not running");
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

    /** Data saved under the old mod id is moved to config/vigil on first start, and the database renamed. */
    @GameTest
    public void oldDataMovesToVigil(TestContext ctx) throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("vigil-migrate");
        java.nio.file.Path old = java.nio.file.Files.createDirectories(root.resolve("anticheat"));
        java.nio.file.Files.writeString(old.resolve("config.json"), "{\"general\":{}}");
        java.nio.file.Files.writeString(old.resolve("anticheat.db"), "db");
        java.nio.file.Files.createDirectories(old.resolve("evidence"));
        Ac.migrateFromAntiCheat(root);
        java.nio.file.Path now = root.resolve("vigil");
        check(!java.nio.file.Files.exists(old), "old folder still there");
        check(java.nio.file.Files.readString(now.resolve("config.json")).contains("general"), "config not moved");
        check(java.nio.file.Files.readString(now.resolve("vigil.db")).equals("db"), "database not renamed");
        check(java.nio.file.Files.isDirectory(now.resolve("evidence")), "evidence not moved");
        Ac.migrateFromAntiCheat(root);
        check(java.nio.file.Files.exists(now.resolve("vigil.db")), "second start broke the data");
        ctx.complete();
    }

    /** Finds text that is a language key instead of real words (a missing translation). */
    private static void noRawKeys(com.vylorq.anticheat.gui.Menu m, String lang, List<String> problems) {
        java.util.regex.Pattern key = java.util.regex.Pattern.compile("\\b(ui|panel|settings|set|help|cm|am|rv|in|tr|st|trade|cat|flow|language|watcher|alert)\\.[a-z0-9_.\\-]+\\b");
        List<String> texts = new java.util.ArrayList<>();
        texts.add(m.titleText().getString());
        for (int i = 0; i < m.size(); i++) {
            var stack = m.inventory().getStack(i);
            if (stack.isEmpty()) {
                continue;
            }
            texts.add(stack.getName().getString());
            var lore = stack.get(net.minecraft.component.DataComponentTypes.LORE);
            if (lore != null) {
                for (var l : lore.lines()) {
                    texts.add(l.getString());
                }
            }
        }
        for (String t : texts) {
            var mt = key.matcher(t);
            if (mt.find()) {
                problems.add(lang + " '" + m.titleText().getString() + "': " + mt.group());
            }
        }
    }

    /**
     * Opens every Vigil menu as the owner, in English and in Arabic: nothing throws and no text is a raw language
     * key (34.14, 34.15).
     */
    @GameTest(maxTicks = 400)
    public void everyMenuRendersInEveryLanguage(TestContext ctx) {
        var cfg = Ac.config();
        String ownerBefore = cfg.general.ownerUuid;
        boolean pinBefore = cfg.staff.requirePin;
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "UiTester"));
        cfg.general.ownerUuid = fake.getUuid().toString();
        cfg.staff.requirePin = false;
        List<String> problems = new java.util.ArrayList<>();
        List<com.vylorq.anticheat.gui.Menu> opened = new java.util.ArrayList<>();
        com.vylorq.anticheat.gui.Menu.onOpen = opened::add;
        try {
            for (String lang : List.of("en_us", "ar_sa")) {
                Ac.get().misc.languages.put(fake.getUuid(), lang);
                opened.clear();
                java.util.List<Runnable> screens = new java.util.ArrayList<>();
                screens.add(() -> com.vylorq.anticheat.gui.VigilPanel.open(fake));
                for (var cat : com.vylorq.anticheat.ui.Theme.Category.values()) {
                    screens.add(() -> com.vylorq.anticheat.gui.VigilPanel.openCategory(fake, cat));
                }
                for (var pg : com.vylorq.anticheat.gui.SettingsMenu.Page.values()) {
                    screens.add(() -> com.vylorq.anticheat.gui.SettingsMenu.page(fake, pg));
                }
                screens.add(() -> com.vylorq.anticheat.gui.SettingsMenu.sensitivity(fake));
                screens.add(() -> com.vylorq.anticheat.gui.InspectMenu.overview(fake, fake.getUuid()));
                screens.add(() -> com.vylorq.anticheat.gui.InspectMenu.punish(fake, fake.getUuid()));
                screens.add(() -> com.vylorq.anticheat.gui.InspectMenu.effects(fake, fake.getUuid()));
                screens.add(() -> com.vylorq.anticheat.gui.InspectMenu.antiCheat(fake, fake.getUuid()));
                screens.add(() -> com.vylorq.anticheat.gui.InspectMenu.location(fake, fake.getUuid()));
                screens.add(() -> com.vylorq.anticheat.gui.InspectMenu.deaths(fake, fake.getUuid(), 0));
                screens.add(() -> com.vylorq.anticheat.gui.StatsMenu.open(fake));
                screens.add(() -> com.vylorq.anticheat.gui.ArenaMenu.join(fake));
                screens.add(() -> com.vylorq.anticheat.gui.LanguageMenu.open(fake));
                screens.add(() -> com.vylorq.anticheat.gui.Confirm.open(fake, com.vylorq.anticheat.ui.Theme.Category.CLAIMS, "Q", "D", null, () -> { }));
                for (Runnable r : screens) {
                    try {
                        r.run();
                    } catch (Throwable e) {
                        problems.add(lang + ": " + e);
                        Ac.LOG.error("Menu failed to render", e);
                    }
                }
                for (var m : opened) {
                    noRawKeys(m, lang, problems);
                }
                check(opened.size() >= 30, lang + ": only " + opened.size() + " menus opened");
            }
        } finally {
            com.vylorq.anticheat.gui.Menu.onOpen = null;
            cfg.general.ownerUuid = ownerBefore;
            cfg.staff.requirePin = pinBefore;
            Ac.get().misc.languages.remove(fake.getUuid());
        }
        check(problems.isEmpty(), "menu problems:\n" + String.join("\n", problems.subList(0, Math.min(20, problems.size()))));
        ctx.complete();
    }

    /** Display items in a menu can't be taken, moved, swapped, dropped or collected (34.3). */
    @GameTest
    public void menuItemsCantBeTaken(TestContext ctx) {
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ClickTester"));
        fake.getInventory().clear();
        com.vylorq.anticheat.gui.Menu m = com.vylorq.anticheat.gui.Menu.std(com.vylorq.anticheat.ui.Theme.Category.SETTINGS, "Test");
        int[] clicks = {0};
        m.renderer(menu -> menu.set(22, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 5), null, (p, c) -> clicks[0]++));
        m.open(fake);
        var handler = new com.vylorq.anticheat.gui.MenuHandler(net.minecraft.screen.ScreenHandlerType.GENERIC_9X6, 99, fake.getInventory(),
                m.inventory(), 6, m);
        fake.currentScreenHandler = handler;
        for (var action : net.minecraft.screen.slot.SlotActionType.values()) {
            int before = clicks[0];
            for (int button : new int[]{0, 1, 40}) {
                try {
                    handler.onSlotClick(22, button, action, fake);
                } catch (Throwable ignored) {
                    // some combinations are invalid; they just mustn't give items
                }
                handler.setCursorStack(net.minecraft.item.ItemStack.EMPTY);
            }
            boolean presses = action == net.minecraft.screen.slot.SlotActionType.PICKUP || action == net.minecraft.screen.slot.SlotActionType.QUICK_MOVE;
            check(presses || clicks[0] == before, action + " pressed a button (only clicks and shift-clicks should)");
        }
        handler.onSlotClick(-999, 0, net.minecraft.screen.slot.SlotActionType.PICKUP, fake);
        for (int i = 0; i < fake.getInventory().size(); i++) {
            check(!fake.getInventory().getStack(i).isOf(net.minecraft.item.Items.DIAMOND), "a display item reached the inventory (slot " + i + ")");
        }
        check(m.inventory().getStack(22).isOf(net.minecraft.item.Items.DIAMOND) && m.inventory().getStack(22).getCount() == 5, "display item changed");
        check(clicks[0] > 0, "the button never worked");
        fake.currentScreenHandler = fake.playerScreenHandler;
        ctx.complete();
    }

    @GameTest
    public void endStaysClosedUntilOpened(TestContext ctx) {
        var cfg = Ac.config().general;
        boolean was = cfg.endOpen;
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "EndTester"));
        BlockPos frame = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        ctx.getWorld().setBlockState(frame, Blocks.END_PORTAL_FRAME.getDefaultState());
        fake.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENDER_EYE));
        var hit = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(frame), Direction.UP, frame, false);
        var portal = (net.minecraft.block.EndPortalBlock) Blocks.END_PORTAL;
        try {
            cfg.endOpen = false;
            var r = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(fake, ctx.getWorld(), net.minecraft.util.Hand.MAIN_HAND, hit);
            check(r == net.minecraft.util.ActionResult.FAIL, "an eye of ender went into a frame while the End is closed");
            check(portal.createTeleportTarget(ctx.getWorld(), fake, frame) == null, "an End portal worked while the End is closed");
            cfg.endOpen = true;
            check(portal.createTeleportTarget(ctx.getWorld(), fake, frame) != null, "End portals don't work after opening the End");
        } finally {
            cfg.endOpen = was;
        }
        ctx.complete();
    }

    @GameTest
    public void endPortalRoomsHideAndComeBack(TestContext ctx) {
        var cfg = Ac.config().general;
        boolean was = cfg.endOpen;
        var w = ctx.getWorld();
        BlockPos a = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        BlockPos b = a.east();
        var frame = Blocks.END_PORTAL_FRAME.getDefaultState()
                .with(net.minecraft.block.EndPortalFrameBlock.FACING, Direction.WEST).with(net.minecraft.block.EndPortalFrameBlock.EYE, true);
        w.setBlockState(a, frame);
        w.setBlockState(b, Blocks.END_PORTAL.getDefaultState());
        try {
            cfg.endOpen = false;
            int hidden = com.vylorq.anticheat.feature.EndLock.hide(w, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
            check(hidden == 2, "hid " + hidden + " blocks instead of 2");
            check(!w.getBlockState(a).isOf(Blocks.END_PORTAL_FRAME) && !w.getBlockState(b).isOf(Blocks.END_PORTAL), "portal room still there");
            int back = com.vylorq.anticheat.feature.EndLock.restore(w, a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
            check(back == 2 && w.getBlockState(a).equals(frame) && w.getBlockState(b).isOf(Blocks.END_PORTAL),
                    "portal room didn't come back exactly (" + back + ", " + w.getBlockState(a) + ")");

            // A built portal room works while the End is closed, and isn't hidden.
            var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "EndBuilder"));
            BlockPos c = com.vylorq.anticheat.feature.EndLock.build(w, ctx.getAbsolutePos(new BlockPos(1, 2, 1)).up(40), Direction.SOUTH);
            check(w.getBlockState(c).isOf(Blocks.END_PORTAL) && w.getBlockState(c.north(2)).isOf(Blocks.END_PORTAL_FRAME), "portal room not built");
            check(((net.minecraft.block.EndPortalBlock) Blocks.END_PORTAL).createTeleportTarget(w, fake, c) != null, "built portal doesn't work");
            check(com.vylorq.anticheat.feature.EndLock.hide(w, c.getX() - 2, c.getY(), c.getZ() - 2, c.getX() + 2, c.getY(), c.getZ() + 2) == 0,
                    "built portal room got hidden");
        } finally {
            cfg.endOpen = was;
            Ac.get().end.built.clear();
        }
        ctx.complete();
    }

    @GameTest
    public void builderGetsBuildingBlocksOnly(TestContext ctx) {
        var B = new Object() {
            boolean ok(net.minecraft.item.Item item) {
                return com.vylorq.anticheat.feature.BuilderMode.allowed(new net.minecraft.item.ItemStack(item));
            }
        };
        check(B.ok(net.minecraft.item.Items.STONE) && B.ok(net.minecraft.item.Items.OAK_PLANKS) && B.ok(net.minecraft.item.Items.GLASS)
                && B.ok(net.minecraft.item.Items.OAK_SIGN) && B.ok(net.minecraft.item.Items.OAK_STAIRS), "a building block was refused");
        for (var bad : new net.minecraft.item.Item[]{net.minecraft.item.Items.CHEST, net.minecraft.item.Items.BARREL,
                net.minecraft.item.Items.SHULKER_BOX, net.minecraft.item.Items.FURNACE, net.minecraft.item.Items.HOPPER,
                net.minecraft.item.Items.CRAFTING_TABLE, net.minecraft.item.Items.ENDER_CHEST, net.minecraft.item.Items.TNT,
                net.minecraft.item.Items.COMMAND_BLOCK, net.minecraft.item.Items.SPAWNER, net.minecraft.item.Items.DIAMOND_SWORD,
                net.minecraft.item.Items.WATER_BUCKET, net.minecraft.item.Items.ENDER_PEARL, net.minecraft.item.Items.ZOMBIE_SPAWN_EGG}) {
            check(!B.ok(bad), bad + " was allowed for a builder");
        }

        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "BuilderTester"));
        fake.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        fake.getInventory().clear();
        fake.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 7));
        try {
            com.vylorq.anticheat.feature.BuilderMode.start(null, fake, 0, false, true);
            check(fake.isCreative() && !fake.getInventory().getStack(0).isOf(net.minecraft.item.Items.DIAMOND), "builder mode didn't start cleanly");
            fake.getInventory().setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD));
            fake.getInventory().setStack(2, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE, 64));
            check(com.vylorq.anticheat.feature.BuilderMode.sanitize(fake) == 1 && fake.getInventory().getStack(1).isEmpty()
                    && fake.getInventory().getStack(2).isOf(net.minecraft.item.Items.STONE), "non-blocks weren't taken away");
            BlockPos chest = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
            ctx.getWorld().setBlockState(chest, Blocks.CHEST.getDefaultState());
            check(!com.vylorq.anticheat.feature.BuilderMode.mayUse(ctx.getWorld(), chest)
                    && !com.vylorq.anticheat.feature.BuilderMode.mayBreak(ctx.getWorld(), chest), "builder could open or break a chest");
        } finally {
            com.vylorq.anticheat.feature.BuilderMode.end(null, fake);
        }
        check(!fake.isCreative() && fake.getInventory().getStack(0).isOf(net.minecraft.item.Items.DIAMOND)
                && fake.getInventory().getStack(0).getCount() == 7 && fake.getInventory().getStack(2).isEmpty(),
                "inventory or game mode didn't come back");
        ctx.complete();
    }

    @GameTest
    public void buildFilesReadAndWrite(TestContext ctx) throws Exception {
        var stairs = Blocks.OAK_STAIRS.getDefaultState().with(net.minecraft.block.StairsBlock.FACING, Direction.EAST)
                .with(net.minecraft.block.StairsBlock.HALF, net.minecraft.block.enums.BlockHalf.TOP);
        check(com.vylorq.anticheat.feature.BuildFiles.parseState(com.vylorq.anticheat.feature.BuildFiles.stateString(stairs)).equals(stairs),
                "block state text doesn't round-trip: " + com.vylorq.anticheat.feature.BuildFiles.stateString(stairs));

        // .schem: write, read back
        var c = new com.vylorq.anticheat.feature.BuildFiles.Clip(3, 2, 2);
        c.set(0, 0, 0, Blocks.STONE.getDefaultState());
        c.set(2, 1, 1, stairs);
        c.set(1, 0, 1, Blocks.GLASS.getDefaultState());
        c.ox = -1;
        c.oy = -4;
        c.oz = 2;
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("vigil", ".schem");
        com.vylorq.anticheat.feature.BuildFiles.write(c, tmp);
        var back = com.vylorq.anticheat.feature.BuildFiles.read(tmp);
        java.nio.file.Files.deleteIfExists(tmp);
        check(back.sx == 3 && back.sy == 2 && back.sz == 2 && java.util.Arrays.equals(back.states, c.states), "schematic changed after saving");
        check(back.ox == -1 && back.oy == -4 && back.oz == 2, "schematic lost where it sits");

        // The lobby that comes with the mod: every block known, the spawn spot on its floor
        byte[] lobby;
        try (var in = com.vylorq.anticheat.feature.BuildFiles.class.getResourceAsStream("/vigil/builds/lobby_enraze.schem")) {
            check(in != null, "bundled lobby missing");
            lobby = in.readAllBytes();
        }
        var lc = com.vylorq.anticheat.feature.BuildFiles.read(lobby);
        int solid = 0;
        for (var st : lc.states) {
            if (!st.isAir()) {
                solid++;
            }
        }
        check(lc.sx == 109 && lc.sy == 63 && lc.sz == 109 && solid == 115499, "bundled lobby read wrong: " + lc.sx + "x" + lc.sy + "x" + lc.sz + " " + solid);
        check(lc.get(-lc.ox, -lc.oy - 1, -lc.oz).isOf(Blocks.SMOOTH_STONE_SLAB) && lc.get(-lc.ox, -lc.oy, -lc.oz).isAir(), "bundled lobby spawn spot wrong");

        // .litematic: 2 x 1 x 2, palette air/stone/dirt, 2 bits per block, negative size
        var root = new net.minecraft.nbt.NbtCompound();
        var regions = new net.minecraft.nbt.NbtCompound();
        var r = new net.minecraft.nbt.NbtCompound();
        var pos = new net.minecraft.nbt.NbtCompound();
        pos.putInt("x", 1);
        pos.putInt("y", 0);
        pos.putInt("z", 1);
        var size = new net.minecraft.nbt.NbtCompound();
        size.putInt("x", -2);
        size.putInt("y", 1);
        size.putInt("z", -2);
        r.put("Position", pos);
        r.put("Size", size);
        var pal = new net.minecraft.nbt.NbtList();
        for (var b : new net.minecraft.block.Block[]{Blocks.AIR, Blocks.STONE, Blocks.DIRT}) {
            pal.add(net.minecraft.nbt.NbtHelper.fromBlockState(b.getDefaultState()));
        }
        r.put("BlockStatePalette", pal);
        r.putLongArray("BlockStates", new long[]{1L | (2L << 2) | (2L << 4)});
        regions.put("main", r);
        root.put("Regions", regions);
        var out = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(root, out);
        var lit = com.vylorq.anticheat.feature.BuildFiles.read(out.toByteArray());
        check(lit.sx == 2 && lit.sy == 1 && lit.sz == 2 && lit.get(0, 0, 0).isOf(Blocks.STONE) && lit.get(1, 0, 0).isOf(Blocks.DIRT)
                && lit.get(0, 0, 1).isOf(Blocks.DIRT) && lit.get(1, 0, 1).isAir(), "litematic read wrong");

        // .nbt (structure block file)
        var st = new net.minecraft.nbt.NbtCompound();
        var sz = new net.minecraft.nbt.NbtList();
        for (int v : new int[]{2, 1, 1}) {
            sz.add(net.minecraft.nbt.NbtInt.of(v));
        }
        st.put("size", sz);
        var spal = new net.minecraft.nbt.NbtList();
        spal.add(net.minecraft.nbt.NbtHelper.fromBlockState(Blocks.BRICKS.getDefaultState()));
        st.put("palette", spal);
        var blocks = new net.minecraft.nbt.NbtList();
        var blk = new net.minecraft.nbt.NbtCompound();
        var bp = new net.minecraft.nbt.NbtList();
        for (int v : new int[]{1, 0, 0}) {
            bp.add(net.minecraft.nbt.NbtInt.of(v));
        }
        blk.put("pos", bp);
        blk.putInt("state", 0);
        blocks.add(blk);
        st.put("blocks", blocks);
        var out2 = new java.io.ByteArrayOutputStream();
        net.minecraft.nbt.NbtIo.writeCompressed(st, out2);
        var nbt = com.vylorq.anticheat.feature.BuildFiles.read(out2.toByteArray());
        check(nbt.get(1, 0, 0).isOf(Blocks.BRICKS) && nbt.get(0, 0, 0).isAir(), "structure file read wrong");
        ctx.complete();
    }

    private static void runBuildJobs() {
        for (int i = 0; i < 50; i++) {
            com.vylorq.anticheat.feature.BuilderTools.tick();
        }
    }

    @GameTest
    public void builderToolsEditUndoCopyPaste(TestContext ctx) {
        var w = ctx.getWorld();
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ToolTester"));
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 1, 0));
        BlockPos b = ctx.getAbsolutePos(new BlockPos(1, 2, 1));
        BlockPos chest = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        try {
            com.vylorq.anticheat.feature.BuilderMode.start(null, fake, 0, true);
            check(java.util.stream.IntStream.range(0, fake.getInventory().size()).anyMatch(i ->
                    com.vylorq.anticheat.feature.Tools.is(fake.getInventory().getStack(i), com.vylorq.anticheat.feature.Tools.BUILDER_WAND)),
                    "builder didn't get the wand");
            w.setBlockState(chest, Blocks.CHEST.getDefaultState());
            com.vylorq.anticheat.feature.BuilderTools.corner(fake, w, a, true);
            com.vylorq.anticheat.feature.BuilderTools.corner(fake, w, b, false);
            com.vylorq.anticheat.feature.BuilderTools.set(fake, Blocks.SPRUCE_PLANKS.getDefaultState());
            runBuildJobs();
            check(w.getBlockState(a).isOf(Blocks.SPRUCE_PLANKS) && w.getBlockState(b).isOf(Blocks.SPRUCE_PLANKS), "fill didn't work");
            check(w.getBlockState(chest).isOf(Blocks.CHEST), "the fill replaced a chest");
            com.vylorq.anticheat.feature.BuilderTools.set(fake, Blocks.TNT.getDefaultState());
            runBuildJobs();
            check(!w.getBlockState(a).isOf(Blocks.TNT), "a builder filled with TNT");
            var summary = com.vylorq.anticheat.feature.BuilderLog.summary(fake.getUuid(), 0);
            check(summary.toolChanges() >= 7 && summary.placed().getOrDefault("spruce_planks", 0) >= 7,
                    "the builder log missed tool changes: " + summary);
            boolean coords = com.vylorq.anticheat.feature.BuilderLog.read(fake.getUuid(), 0).stream()
                    .anyMatch(e -> e.type().startsWith("TOOL") && e.x() == a.getX() && e.y() == a.getY() && e.z() == a.getZ());
            check(coords, "the builder log has no coordinates for a changed block");

            fake.setPosition(net.minecraft.util.math.Vec3d.ofBottomCenter(a));
            com.vylorq.anticheat.feature.BuilderTools.copy(fake);
            fake.setPosition(net.minecraft.util.math.Vec3d.ofBottomCenter(a.up(10)));
            com.vylorq.anticheat.feature.BuilderTools.paste(fake, true);
            com.vylorq.anticheat.feature.BuilderTools.confirmPaste(fake);
            runBuildJobs();
            check(w.getBlockState(a.up(10)).isOf(Blocks.SPRUCE_PLANKS) && w.getBlockState(chest.up(10)).isAir(),
                    "paste wrong (containers must not be copied)");
            com.vylorq.anticheat.feature.BuilderTools.undo(fake);
            runBuildJobs();
            check(w.getBlockState(a.up(10)).isAir(), "undo didn't remove the paste");
            com.vylorq.anticheat.feature.BuilderTools.undo(fake);
            runBuildJobs();
            check(w.getBlockState(a).isAir(), "undo didn't remove the fill");
        } finally {
            com.vylorq.anticheat.feature.BuilderMode.end(null, fake);
            w.setBlockState(chest, Blocks.AIR.getDefaultState());
        }
        ctx.complete();
    }

    @GameTest
    public void builderShapesMixesAndFlip(TestContext ctx) {
        var mix = com.vylorq.anticheat.feature.BuilderTools.pattern("70%stone_bricks,30%cracked_stone_bricks");
        check(mix != null && mix.describe().contains("70%"), "block mix not understood");
        java.util.Set<net.minecraft.block.Block> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(mix.pick().getBlock());
        }
        check(seen.size() == 2, "the mix only gave " + seen);
        check(com.vylorq.anticheat.feature.BuilderTools.pattern("not_a_block") == null, "unknown block accepted");

        var w = ctx.getWorld();
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ShapeTester"));
        BlockPos c = ctx.getAbsolutePos(new BlockPos(1, 2, 1)).up(60);
        try {
            com.vylorq.anticheat.feature.BuilderMode.start(null, fake, 0, true);
            fake.setPosition(net.minecraft.util.math.Vec3d.ofBottomCenter(c));
            com.vylorq.anticheat.feature.BuilderTools.sphere(fake, com.vylorq.anticheat.feature.BuilderTools.single(Blocks.GLASS.getDefaultState()), 2, true);
            runBuildJobs();
            check(w.getBlockState(c.up(2)).isOf(Blocks.GLASS) && w.getBlockState(c).isAir(), "hollow sphere wrong");
            com.vylorq.anticheat.feature.BuilderTools.undo(fake);
            runBuildJobs();
            check(w.getBlockState(c.up(2)).isAir(), "sphere undo failed");

            // flip: a stair facing east, copied and flipped east-west, faces west
            w.setBlockState(c, Blocks.OAK_STAIRS.getDefaultState().with(net.minecraft.block.StairsBlock.FACING, Direction.EAST));
            com.vylorq.anticheat.feature.BuilderTools.corner(fake, w, c, true);
            com.vylorq.anticheat.feature.BuilderTools.corner(fake, w, c, false);
            com.vylorq.anticheat.feature.BuilderTools.copy(fake);
            com.vylorq.anticheat.feature.BuilderTools.flip(fake, true);
            var clip = com.vylorq.anticheat.feature.BuilderTools.clipboard(fake);
            check(clip.get(0, 0, 0).get(net.minecraft.block.StairsBlock.FACING) == Direction.WEST, "flip didn't mirror the stairs");
            w.setBlockState(c, Blocks.AIR.getDefaultState());
        } finally {
            com.vylorq.anticheat.feature.BuilderMode.end(null, fake);
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 100)
    public void builderDraftIsApprovedIntoTheLobby(TestContext ctx) {
        var w = ctx.getWorld();
        var lobby = Ac.get().lobby.data();
        var oldArea = lobby.area;
        BlockPos a = ctx.getAbsolutePos(new BlockPos(0, 1, 0));
        lobby.area = new com.vylorq.anticheat.core.util.Area(com.vylorq.anticheat.util.Mc.worldId(w), a.getX(), a.getY(), a.getZ(),
                a.getX() + 2, a.getY() + 2, a.getZ() + 2);
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "DraftTester"));
        try {
            w.setBlockState(a, Blocks.OAK_PLANKS.getDefaultState());
            com.vylorq.anticheat.feature.BuilderMode.start(null, fake, 0, false);
            check(com.vylorq.anticheat.feature.BuilderMode.get(fake.getUuid()).draft, "builder didn't get a draft");
            for (int i = 0; i < 20 && !com.vylorq.anticheat.feature.BuilderDrafts.get(fake.getUuid()).ready; i++) {
                com.vylorq.anticheat.feature.BuilderTools.tick();
            }
            var d = com.vylorq.anticheat.feature.BuilderDrafts.get(fake.getUuid());
            check(d.ready, "the draft copy never finished");
            BlockPos copy = a.add(com.vylorq.anticheat.feature.BuilderDrafts.OFFSET, 0, 0);
            check(w.getBlockState(copy).isOf(Blocks.OAK_PLANKS), "the draft isn't a copy of the lobby");
            check(com.vylorq.anticheat.feature.BuilderMode.mayBuildAt(fake, w, copy) && !com.vylorq.anticheat.feature.BuilderMode.mayBuildAt(fake, w, a),
                    "a draft builder could build in the real lobby");
            w.setBlockState(copy.up(), Blocks.GOLD_BLOCK.getDefaultState());
            check(w.getBlockState(a.up()).isAir(), "the real lobby changed before approval");
            check(com.vylorq.anticheat.feature.BuilderDrafts.approve(null, fake.getUuid()), "approve refused");
            runBuildJobs();
            check(w.getBlockState(a.up()).isOf(Blocks.GOLD_BLOCK) && w.getBlockState(a).isOf(Blocks.OAK_PLANKS), "approval didn't copy the change");
            check(com.vylorq.anticheat.feature.BuilderLog.read(fake.getUuid(), 0).stream().anyMatch(e -> e.type().equals("APPROVE")
                    && e.x() == a.getX() && e.y() == a.getY() + 1), "approval wasn't logged with coordinates");
        } finally {
            com.vylorq.anticheat.feature.BuilderMode.end(null, fake);
            com.vylorq.anticheat.feature.BuilderDrafts.discard(fake.getUuid());
            lobby.area = oldArea;
            w.setBlockState(a, Blocks.AIR.getDefaultState());
            w.setBlockState(a.up(), Blocks.AIR.getDefaultState());
        }
        ctx.complete();
    }

    @GameTest
    public void endPortalRoomCanBeRemoved(TestContext ctx) {
        var w = ctx.getWorld();
        BlockPos feet = ctx.getAbsolutePos(new BlockPos(1, 2, 1)).up(80);
        BlockPos c = feet.offset(Direction.SOUTH, 6);
        w.setBlockState(c.down(), Blocks.DIRT.getDefaultState());
        w.setBlockState(c.east(4).up(), Blocks.OAK_LOG.getDefaultState());
        com.vylorq.anticheat.feature.EndLock.build(w, feet, Direction.SOUTH);
        check(w.getBlockState(c).isOf(Blocks.END_PORTAL), "portal room not built");
        check(com.vylorq.anticheat.feature.EndLock.removeBuilt(w, c.north(2)), "remove didn't find the portal room");
        check(w.getBlockState(c).isAir() && w.getBlockState(c.north(2)).isAir(), "portal or frames still there");
        check(w.getBlockState(c.down()).isOf(Blocks.DIRT) && w.getBlockState(c.east(4).up()).isOf(Blocks.OAK_LOG),
                "what was there before didn't come back");
        check(!com.vylorq.anticheat.feature.EndLock.removeBuilt(w, c), "removed twice");
        w.setBlockState(c.down(), Blocks.AIR.getDefaultState());
        w.setBlockState(c.east(4).up(), Blocks.AIR.getDefaultState());
        ctx.complete();
    }

    @GameTest
    public void scareWarningMustBeAccepted(TestContext ctx) {
        var cfg = Ac.config().watcher;
        boolean was = cfg.warnOnJoin;
        boolean kicks = cfg.declineKicks;
        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "ScaredTester"));
        try {
            cfg.warnOnJoin = true;
            cfg.declineKicks = false;
            check(com.vylorq.anticheat.feature.ScareWarning.pending(fake), "a new player wasn't asked");
            check(com.vylorq.anticheat.feature.Watcher.start(fake, com.vylorq.anticheat.core.watcher.WatcherEffect.JUMPSCARE, "test", true) == null,
                    "the Watcher scared someone who hadn't accepted");
            check(!com.vylorq.anticheat.command.Commands.allowCommand(fake, "spawn"), "commands worked before answering");
            com.vylorq.anticheat.feature.ScareWarning.decline(fake);
            check(!com.vylorq.anticheat.feature.ScareWarning.pending(fake) && !com.vylorq.anticheat.feature.ScareWarning.accepted(fake.getUuid()),
                    "declining (no-scares mode) should let them play without scares");
            com.vylorq.anticheat.feature.ScareWarning.accept(fake);
            check(com.vylorq.anticheat.feature.ScareWarning.accepted(fake.getUuid()), "accepting didn't stick");
            cfg.warnOnJoin = false;
            var other = java.util.UUID.randomUUID();
            check(com.vylorq.anticheat.feature.ScareWarning.accepted(other), "with the warning off everyone counts as accepted");
        } finally {
            com.vylorq.anticheat.feature.Watcher.stopFor(fake.getUuid(), true);
            cfg.warnOnJoin = was;
            cfg.declineKicks = kicks;
        }
        ctx.complete();
    }

    @GameTest(maxTicks = 100)
    public void eventsStartStopAndStats(TestContext ctx) {
        com.vylorq.anticheat.feature.WorldEvents.stop("stopped");
        // Scary events need someone who accepted the warning: with nobody online they refuse.
        check("events.nobody".equals(com.vylorq.anticheat.feature.WorldEvents.start(com.vylorq.anticheat.feature.WorldEvents.Kind.BLOOD_MOON, "test")),
                "a scary event started with nobody to scare");
        check(com.vylorq.anticheat.feature.WorldEvents.start(com.vylorq.anticheat.feature.WorldEvents.Kind.GOLDEN_HOUR, "test") == null,
                "golden hour didn't start");
        check(com.vylorq.anticheat.feature.WorldEvents.start(com.vylorq.anticheat.feature.WorldEvents.Kind.GOLDEN_HOUR, "test") != null,
                "two events at once");
        for (int i = 0; i < 25; i++) {
            com.vylorq.anticheat.feature.WorldEvents.tick(ctx.getWorld().getServer());
        }
        check(com.vylorq.anticheat.feature.WorldEvents.secondsLeft() > 0, "event ended too early");
        com.vylorq.anticheat.feature.WorldEvents.stop("stopped");
        check(com.vylorq.anticheat.feature.WorldEvents.active() == null, "event didn't stop");

        var fake = net.fabricmc.fabric.api.entity.FakePlayer.get(ctx.getWorld(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "StatTester"));
        var row = com.vylorq.anticheat.feature.PlayerStats.of(fake);
        check(row != null && row.name().equals("StatTester") && row.playTicks() >= 0, "stats didn't read");
        check(com.vylorq.anticheat.feature.PlayerStats.time(20 * 60 * 90).equals("1h 30m"), "playtime format");
        ctx.complete();
    }

    @GameTest
    public void teamTerritoryAndFriendlyFire(TestContext ctx) {
        var w = ctx.getWorld();
        var tm = com.vylorq.anticheat.feature.Teams.tm();
        var leader = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "TeamLead"));
        var mate = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "TeamMate"));
        var outsider = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Outsider"));
        String name = "T" + Long.toString(System.nanoTime() % 100000);
        BlockPos pos = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        try {
            check(tm.create(name, null, leader.getUuid()) == com.vylorq.anticheat.core.team.TeamManager.Result.OK, "team not created");
            tm.invite(leader.getUuid(), mate.getUuid(), com.vylorq.anticheat.feature.Teams.limits());
            tm.join(mate.getUuid(), name, com.vylorq.anticheat.feature.Teams.limits());
            var r = tm.claim(leader.getUuid(), com.vylorq.anticheat.util.Mc.worldId(w), pos.getX() >> 4, pos.getZ() >> 4, com.vylorq.anticheat.feature.Teams.limits());
            check(r == com.vylorq.anticheat.core.team.TeamManager.Result.OK || r == com.vylorq.anticheat.core.team.TeamManager.Result.CHUNK_TAKEN,
                    "claim failed: " + r);
            if (r == com.vylorq.anticheat.core.team.TeamManager.Result.OK) {
                // Land isn't protected: an outsider breaking in only raises a raid alert.
                com.vylorq.anticheat.feature.Teams.watch(outsider, w, pos, com.vylorq.anticheat.core.claims.ClaimAction.BREAK);
                check(com.vylorq.anticheat.feature.Teams.at(w, pos) == tm.get(name), "land not claimed");
            }
            check(com.vylorq.anticheat.feature.Teams.friendlyFireBlocked(leader, mate), "teammates could hurt each other");
            check(!com.vylorq.anticheat.feature.Teams.friendlyFireBlocked(leader, outsider), "outsiders were protected");
            com.vylorq.anticheat.feature.Teams.remember(mate);
            com.vylorq.anticheat.feature.Teams.syncTags();
            var st = w.getServer().getScoreboard().getScoreHolderTeam("TeamMate");
            check(st != null && st.getName().startsWith("vt_"), "no name tag team");
        } finally {
            var t = tm.get(name);
            if (t != null) {
                tm.removeTeam(t);
            }
            com.vylorq.anticheat.feature.Teams.syncTags();
        }
        ctx.complete();
    }

    @GameTest
    public void teamAlliesAllyFireAndVault(TestContext ctx) {
        var w = ctx.getWorld();
        var tm = com.vylorq.anticheat.feature.Teams.tm();
        var lim = com.vylorq.anticheat.feature.Teams.limits();
        var R = com.vylorq.anticheat.core.team.TeamManager.Result.OK;
        var a = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "AllyA"));
        var b = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "AllyB"));
        var outsider = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Stranger"));
        String na = String.format("A%05d", System.nanoTime() % 100000);
        String nb = String.format("B%05d", System.nanoTime() % 100000);
        BlockPos pos = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        try {
            check(tm.create(na, null, a.getUuid()) == R && tm.create(nb, null, b.getUuid()) == R, "teams not created");
            check(!com.vylorq.anticheat.feature.Teams.damageBlocked(a, b), "strangers were protected");
            check(tm.ally(a.getUuid(), nb, 3) == com.vylorq.anticheat.core.team.TeamManager.Result.ALLY_REQUESTED, "no ally request");
            check(!tm.allied(a.getUuid(), b.getUuid()), "allied before accepting");
            check(tm.ally(b.getUuid(), na, 3) == R, "ally not accepted");
            var ta = tm.get(na);
            var tb = tm.get(nb);
            check(!com.vylorq.anticheat.feature.Teams.damageBlocked(a, b), "allies can hurt each other by default");
            tb.allyFire = false;
            check(com.vylorq.anticheat.feature.Teams.damageBlocked(a, b), "ally fire off on one side didn't protect");
            check(com.vylorq.anticheat.feature.Teams.damageBlocked(b, a), "ally fire off didn't work both ways");
            tb.allyFire = true;
            check(com.vylorq.anticheat.feature.Teams.visible(ta, ta) && com.vylorq.anticheat.feature.Teams.visible(tb, ta), "own or ally land hidden on the map");
            tb.shareMap = false;
            check(!com.vylorq.anticheat.feature.Teams.visible(tb, ta) && !com.vylorq.anticheat.feature.Teams.visible(ta, tb), "map sharing off didn't hide both ways");
            tb.shareMap = true;
            check(!com.vylorq.anticheat.feature.Teams.visible(ta, null), "someone with no team saw team land");
            check(!com.vylorq.anticheat.feature.Teams.damageBlocked(outsider, a), "strangers were protected");
            var inv = com.vylorq.anticheat.feature.Teams.vault(ta);
            inv.setStack(3, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 5));
            check(ta.vault.size() == 1, "vault not saved");
            com.vylorq.anticheat.feature.Teams.forgetVault(ta, null);
            check(ta.vault.isEmpty(), "forgotten vault kept items");
            check(tm.unally(a.getUuid(), nb) == R && !tm.allied(a.getUuid(), b.getUuid()), "unally failed");
            check(!com.vylorq.anticheat.feature.Teams.visible(tb, ta), "another team's land showed on the map");
        } finally {
            for (String n : new String[]{na, nb}) {
                var t = tm.get(n);
                if (t != null) {
                    tm.removeTeam(t);
                }
            }
        }
        ctx.complete();
    }

    @GameTest
    public void tradersHaveStockAndPrices(TestContext ctx) {
        var econ = com.vylorq.anticheat.Ac.get().economy;
        var t = new com.vylorq.anticheat.core.trader.Trader();
        t.entity = java.util.UUID.randomUUID();
        t.specialty = com.vylorq.anticheat.core.trader.Specialty.MASON;
        econ.rotate(t, com.vylorq.anticheat.feature.Traders.settings(), java.util.List.of(), new java.util.SplittableRandom(5));
        check(!t.offers.isEmpty(), "a trader has no stock");
        econ.marketTick(java.util.List.of(t), com.vylorq.anticheat.feature.Traders.settings(), new java.util.SplittableRandom(6));
        for (var o : t.offers) {
            check(o.stock > 0, "an offer has no stock");
            check(com.vylorq.anticheat.feature.Traders.price(t, o) > 0, "an offer has no price");
        }
        double diamonds = com.vylorq.anticheat.feature.Traders.stackValue(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 3));
        double one = com.vylorq.anticheat.feature.Traders.stackValue(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 1));
        check(one > 0 && diamonds > one, "item values are wrong: " + one + " / " + diamonds);
        ctx.complete();
    }

    @GameTest
    public void cashShopsAndTeamLevels(TestContext ctx) {
        var w = ctx.getWorld();
        var a = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "CashA"));
        var b = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "CashB"));
        var m = com.vylorq.anticheat.Ac.get().market;
        m.setCash(a.getUuid(), 50);
        check(com.vylorq.anticheat.feature.Markets.cashMode(), "cash is the default market money");
        check(com.vylorq.anticheat.feature.Markets.takeMoney(a, 20), "couldn't take cash");
        check(!com.vylorq.anticheat.feature.Markets.takeMoney(a, 31), "took more cash than there was");
        com.vylorq.anticheat.feature.Markets.giveMoney(b, 5);
        check(m.balance(a.getUuid()) == 30 && m.balance(b.getUuid()) == 5, "wrong balances");
        check(com.vylorq.anticheat.feature.Markets.money(12).contains("12"), "money text");
        a.getInventory().insertStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.EMERALD, 10));
        check(com.vylorq.anticheat.feature.Markets.countItem(a, net.minecraft.item.Items.EMERALD) == 10, "emerald count");
        check(com.vylorq.anticheat.feature.Markets.takeItem(a, net.minecraft.item.Items.EMERALD, 4), "take emeralds");
        check(com.vylorq.anticheat.feature.Markets.countItem(a, net.minecraft.item.Items.EMERALD) == 6, "emeralds left");

        var tm = com.vylorq.anticheat.feature.Teams.tm();
        String name = String.format("L%05d", System.nanoTime() % 100000);
        try {
            check(tm.create(name, null, a.getUuid()) == com.vylorq.anticheat.core.team.TeamManager.Result.OK, "team not created");
            var t = tm.get(name);
            check(com.vylorq.anticheat.feature.Teams.vaultSize(t) == 27, "small vault at level 1");
            var inv = com.vylorq.anticheat.feature.Teams.vault(t);
            inv.setStack(5, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 2));
            com.vylorq.anticheat.feature.Teams.addXp(t, 1000);
            check(com.vylorq.anticheat.core.team.TeamManager.level(t.xp) >= 3, "no level up");
            var big = com.vylorq.anticheat.feature.Teams.vault(t);
            check(big.size() == 54, "vault didn't grow");
            check(big.getStack(5).isOf(net.minecraft.item.Items.DIAMOND), "vault items lost when it grew");
        } finally {
            var t = tm.get(name);
            if (t != null) {
                tm.removeTeam(t);
            }
        }
        ctx.complete();
    }

    @GameTest
    public void boothBuyAndNegotiate(TestContext ctx) {
        var w = ctx.getWorld();
        var seller = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "BoothSeller"));
        var buyer = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "BoothBuyer"));
        var m = com.vylorq.anticheat.Ac.get().market;
        BlockPos pos = ctx.getAbsolutePos(new BlockPos(1, 1, 1));
        var booth = m.addBooth(com.vylorq.anticheat.util.Mc.worldId(w), pos.getX(), pos.getY(), pos.getZ(), 0);
        try {
            check(m.claimBooth(booth.id, seller.getUuid(), "BoothSeller") == com.vylorq.anticheat.core.market.Market.Result.OK, "claim");
            String sword = com.vylorq.anticheat.util.ItemConv.encode(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD));
            String apple = com.vylorq.anticheat.util.ItemConv.encode(new net.minecraft.item.ItemStack(net.minecraft.item.Items.APPLE, 8));
            m.list(booth.id, seller.getUuid(), sword, "Sword", 100, "cash", 18);
            m.list(booth.id, seller.getUuid(), apple, "Apples", 10, "cash", 18);
            m.setCash(buyer.getUuid(), 200);
            m.setCash(seller.getUuid(), 0);
            var swordListing = booth.listings.get(0);
            var appleListing = booth.listings.get(1);
            com.vylorq.anticheat.feature.Booths.makeOffer(buyer, booth, swordListing, 70);
            check(m.balance(buyer.getUuid()) == 130, "offer money not held: " + m.balance(buyer.getUuid()));
            var offers = m.offersFor(booth.id);
            check(offers.size() == 1, "offer missing");
            com.vylorq.anticheat.feature.Booths.accept(seller, offers.get(0).id);
            check(m.balance(seller.getUuid()) == 70, "seller not paid");
            var owed = m.takeItems(buyer.getUuid());
            check(owed.size() == 1 && com.vylorq.anticheat.util.ItemConv.decode(owed.get(0)).isOf(net.minecraft.item.Items.DIAMOND_SWORD), "buyer didn't get the sword");
            com.vylorq.anticheat.feature.Booths.buy(buyer, booth, appleListing);
            check(m.balance(buyer.getUuid()) == 120 && m.balance(seller.getUuid()) == 80, "buy-now money wrong");
            check(com.vylorq.anticheat.feature.Markets.countItem(buyer, net.minecraft.item.Items.APPLE) == 8, "apples not given");
            check(booth.listings.isEmpty() && booth.sales == 2, "listings not cleared");
        } finally {
            m.deleteBooth(booth.id);
        }
        ctx.complete();
    }

    @GameTest
    public void bedrockButtonsAndChat(TestContext ctx) {
        var plain = com.vylorq.anticheat.ui.Btn.of(net.minecraft.item.Items.STONE).name("x").left("open").build();
        check(com.vylorq.anticheat.ui.Btn.actionsOf(plain).isEmpty(), "a plain button shouldn't ask on Bedrock");
        var multi = com.vylorq.anticheat.ui.Btn.of(net.minecraft.item.Items.STONE).name("x").left("buy").right("offer").shift("remove").build();
        var acts = com.vylorq.anticheat.ui.Btn.actionsOf(multi);
        check(acts.size() == 3 && acts.get(1)[0].equals("RIGHT") && acts.get(1)[1].equals("offer")
                && acts.get(2)[0].equals("SHIFT_LEFT"), "button actions not stored");
        for (String[] a : acts) {
            com.vylorq.anticheat.gui.Menu.Click.valueOf(a[0]);
        }
        var rightOnly = com.vylorq.anticheat.ui.Btn.of(net.minecraft.item.Items.STONE).name("x").right("kick").build();
        check(com.vylorq.anticheat.ui.Btn.actionsOf(rightOnly).size() == 1, "right-only button must ask on Bedrock");
        net.minecraft.text.MutableText msg = net.minecraft.text.Text.literal("Offer! ");
        msg.append(com.vylorq.anticheat.util.Msg.button("[Accept]", "/booth accept 7", ""));
        check(com.vylorq.anticheat.util.BedrockText.hasButtons(msg), "button not found");
        String shown = com.vylorq.anticheat.util.BedrockText.withCommands(msg).getString();
        check(shown.contains("[Accept]") && shown.contains("/booth accept 7"), "command not shown for Bedrock: " + shown);
        check(!com.vylorq.anticheat.util.BedrockText.hasButtons(net.minecraft.text.Text.literal("hi")), "plain text has no buttons");
        var self = com.vylorq.anticheat.util.Msg.suggest("/inspect <player>", "/inspect ", "");
        check(com.vylorq.anticheat.util.BedrockText.withCommands(self).getString().equals("/inspect <player>"), "command shown twice");
        ctx.complete();
    }

    @GameTest
    public void inventoryResetDeleteAndUndo(TestContext ctx) {
        var w = ctx.getWorld();
        var admin = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "InvAdmin"));
        var p = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "InvTarget"));
        p.getInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 10));
        p.getInventory().setStack(1, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIRT, 64));
        p.getInventory().setStack(5, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 3));
        p.getEnderChestInventory().setStack(2, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 7));
        var inv = com.vylorq.anticheat.gui.InventoryTools.of(p);
        // Delete only diamonds, not from the ender chest.
        com.vylorq.anticheat.gui.InventoryTools.delete(admin, p.getUuid(), inv, java.util.Set.of("minecraft:diamond"), false);
        check(p.getInventory().getStack(0).isEmpty() && p.getInventory().getStack(5).isEmpty(), "diamonds not deleted");
        check(p.getInventory().getStack(1).isOf(net.minecraft.item.Items.DIRT), "dirt was deleted too");
        check(p.getEnderChestInventory().getStack(2).getCount() == 7, "ender chest touched");
        com.vylorq.anticheat.gui.InventoryTools.undo(admin, p.getUuid(), inv);
        check(p.getInventory().getStack(0).getCount() == 10 && p.getInventory().getStack(5).getCount() == 3, "undo didn't put diamonds back");
        check(com.vylorq.anticheat.feature.Markets.countItem(p, net.minecraft.item.Items.DIRT) == 64
                && com.vylorq.anticheat.feature.Markets.countItem(p, net.minecraft.item.Items.DIAMOND) == 13
                && p.getEnderChestInventory().getStack(2).getCount() == 7, "undo duplicated items");
        // Reset everything, ender chest included.
        com.vylorq.anticheat.gui.InventoryTools.reset(admin, p.getUuid(), inv, true);
        check(p.getInventory().isEmpty() && p.getEnderChestInventory().isEmpty(), "reset left items");
        com.vylorq.anticheat.gui.InventoryTools.undo(admin, p.getUuid(), inv);
        check(p.getInventory().getStack(1).getCount() == 64 && p.getEnderChestInventory().getStack(2).getCount() == 7
                && com.vylorq.anticheat.feature.Markets.countItem(p, net.minecraft.item.Items.DIAMOND) == 13, "undo after reset failed");
        check(!com.vylorq.anticheat.Ac.get().misc.inventoryBackups.containsKey(p.getUuid()), "backup not cleared");
        ctx.complete();
    }

    @GameTest
    public void snapshotsNotesBlacklistShop(TestContext ctx) {
        var w = ctx.getWorld();
        var admin = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "SnapAdmin"));
        var p = net.fabricmc.fabric.api.entity.FakePlayer.get(w, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "SnapTarget"));
        var DIAMOND = net.minecraft.item.Items.DIAMOND;
        // Snapshot, change the inventory, restore.
        p.getInventory().setStack(3, new net.minecraft.item.ItemStack(DIAMOND, 5));
        var inv = com.vylorq.anticheat.gui.InventoryTools.of(p);
        var snap = com.vylorq.anticheat.gui.Snapshots.take(p.getUuid(), inv, "test", "before", false);
        p.getInventory().setStack(3, net.minecraft.item.ItemStack.EMPTY);
        p.getInventory().setStack(7, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIRT, 1));
        com.vylorq.anticheat.gui.Snapshots.restore(admin, p.getUuid(), inv, snap);
        check(p.getInventory().getStack(3).getCount() == 5 && p.getInventory().getStack(7).isEmpty(), "snapshot restore wrong");
        check(com.vylorq.anticheat.gui.Snapshots.list(p.getUuid()).size() == 2, "the pre-restore snapshot is missing");
        // Notes.
        com.vylorq.anticheat.gui.Notes.add(admin, p.getUuid(), "warned for spam");
        var notes = com.vylorq.anticheat.gui.Notes.of(p.getUuid());
        check(notes.size() == 1 && notes.get(0).text.equals("warned for spam"), "note not saved");
        com.vylorq.anticheat.gui.Notes.remove(admin, p.getUuid(), notes.get(0));
        check(com.vylorq.anticheat.gui.Notes.of(p.getUuid()).isEmpty(), "note not removed");
        // Blacklist: TNT is removed, diamonds stay.
        var bl = com.vylorq.anticheat.Ac.config().itemBlacklist;
        java.util.List<String> before = new java.util.ArrayList<>(bl.items);
        try {
            bl.items = new java.util.ArrayList<>(java.util.List.of("tnt"));
            p.getInventory().setStack(8, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TNT, 16));
            p.getEnderChestInventory().setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.TNT, 4));
            int removed = com.vylorq.anticheat.feature.ItemBlacklist.clean(p);
            check(removed == 20 && p.getInventory().getStack(8).isEmpty() && p.getEnderChestInventory().getStack(0).isEmpty(), "blacklist missed TNT: " + removed);
            check(p.getInventory().getStack(3).isOf(DIAMOND), "blacklist removed something else");
        } finally {
            bl.items = before;
        }
        // Server shop: buy and sell for cash.
        var m = com.vylorq.anticheat.Ac.get().market;
        var item = m.addServerItem(com.vylorq.anticheat.util.ItemConv.encode(new net.minecraft.item.ItemStack(DIAMOND, 1)), "1x Diamond", 50, 20);
        try {
            m.setCash(p.getUuid(), 120);
            com.vylorq.anticheat.feature.ServerShop.buy(p, item, 2);
            check(m.balance(p.getUuid()) == 20 && com.vylorq.anticheat.feature.Markets.countItem(p, DIAMOND) == 7, "server shop buy wrong");
            com.vylorq.anticheat.feature.ServerShop.sell(p, item, 3);
            check(m.balance(p.getUuid()) == 80 && com.vylorq.anticheat.feature.Markets.countItem(p, DIAMOND) == 4, "server shop sell wrong");
        } finally {
            m.removeServerItem(item.id);
        }
        // Settings backup.
        var saved = com.vylorq.anticheat.gui.SettingsBackups.save("test");
        check(saved != null && com.vylorq.anticheat.gui.SettingsBackups.list().contains(saved), "settings backup not saved");
        ctx.complete();
    }

    @GameTest
    public void featureSwitches(TestContext ctx) {
        var claims = com.vylorq.anticheat.feature.Features.Feature.CLAIMS;
        var teams = com.vylorq.anticheat.feature.Features.Feature.TEAMS;
        boolean claimsWas = com.vylorq.anticheat.feature.Features.on(claims);
        boolean teamsWas = com.vylorq.anticheat.feature.Features.on(teams);
        try {
            check(com.vylorq.anticheat.feature.Features.forCommand("claim") == claims, "claim command not mapped");
            check(com.vylorq.anticheat.feature.Features.forCommand("tca") == teams, "tca not mapped");
            check(com.vylorq.anticheat.feature.Features.forCommand("vigil") == null, "/vigil must never be switched off");
            check(com.vylorq.anticheat.feature.Features.forCommand("settings") == null, "/settings must never be switched off");
            com.vylorq.anticheat.feature.Features.set(claims, false);
            check(!com.vylorq.anticheat.feature.Features.on(claims), "flag not off");
            com.vylorq.anticheat.feature.Features.set(teams, false);
            check(!com.vylorq.anticheat.Ac.config().teams.enabled && !com.vylorq.anticheat.feature.Teams.enabled(), "teams setting not linked");
            com.vylorq.anticheat.feature.Features.set(teams, true);
            check(com.vylorq.anticheat.Ac.config().teams.enabled, "teams not back on");
            for (var f : com.vylorq.anticheat.feature.Features.Feature.values()) {
                check(com.vylorq.anticheat.feature.Features.byId(f.id) == f, "id lookup " + f.id);
            }
        } finally {
            com.vylorq.anticheat.feature.Features.set(claims, claimsWas);
            com.vylorq.anticheat.feature.Features.set(teams, teamsWas);
        }
        ctx.complete();
    }
}
