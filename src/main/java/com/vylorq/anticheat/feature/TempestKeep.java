package com.vylorq.anticheat.feature;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.BlockSnapshots;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeverBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Tempest Keep: one huge fortress the owner places (/owner tempest place), the only home of the Stormbreaker,
 * which only one player can ever claim.
 *
 * <p>Built from data/vigil/structure/tempest/keep.nbt; layout.json (made by scripts/secrets/tempest.py with it) says
 * where the gates, levers and rooms are. Once someone is inside the entrance seals behind them, and stays sealed until
 * nobody is left inside. The lever room opens on the right three levers (the colours on the far wall) and answers a
 * wrong guess with lightning and shades. The maze's gate opens from the lever at its heart. Stepping into the arena
 * wakes the Tempest Lord and locks the gate behind you until he falls; then the vault and the way out open. The first
 * Stormbreaker taken from the vault is the only one there will ever be.</p>
 *
 * <p>Inside the keep nobody but the owner can break or place blocks, pour water or lava, or throw ender pearls.</p>
 */
public final class TempestKeep {
    private TempestKeep() {
    }

    static final Identifier TEMPLATE = Identifier.of("vigil", "tempest/keep");
    static final Identifier LAYOUT = Identifier.of("vigil", "tempest/layout.json");
    private static final String SNAP = "tempest_keep";
    /** Ticks with nobody inside before the keep resets for the next group. */
    private static final int EMPTY_RESET = 60 * 20;

    /** The saved state: where it stands, and the Stormbreaker's fate. */
    public static final class State {
        public String world;
        public int x;
        public int y;
        public int z;
        public boolean placed;
        /** The Stormbreaker was put in the vault (it only ever is once). */
        public boolean filled;
        /** Someone took it. */
        public boolean claimed;
        public String claimedBy;
        /** Gates that are open right now (by name). */
        public Set<String> open = new HashSet<>();
        public String boss;
        public boolean bossDone;
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("tempest-keep.json");
    }

    public static synchronized State state() {
        if (state == null) {
            try {
                state = Files.exists(file()) ? ConfigManager.GSON.fromJson(Files.readString(file()), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read the Tempest Keep's state", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.open == null) {
                state.open = new HashSet<>();
            }
        }
        return state;
    }

    static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()));
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the Tempest Keep's state", e);
        }
    }

    // ---------------------------------------------------------------- the layout

    public record Gate(String name, int[] box, String block, boolean closedAtStart) {
    }

    public record Layout(int[] size, List<Gate> gates, List<int[]> levers, Set<Integer> code, Map<String, int[]> regions,
                  int[] mazeLever, int[] bossSpawn, int[] vaultChest) {
    }

    private static Layout layout;

    static Layout layout() {
        if (layout == null) {
            try {
                var res = Ac.server().getResourceManager().getResource(LAYOUT).orElseThrow();
                JsonObject o;
                try (var in = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                    o = JsonParser.parseReader(in).getAsJsonObject();
                }
                List<Gate> gates = new ArrayList<>();
                for (var g : o.getAsJsonArray("gates")) {
                    JsonObject go = g.getAsJsonObject();
                    gates.add(new Gate(go.get("name").getAsString(), ints(go.getAsJsonArray("box")), go.get("block").getAsString(),
                            go.get("closed").getAsBoolean()));
                }
                List<int[]> levers = new ArrayList<>();
                for (var l : o.getAsJsonArray("levers")) {
                    levers.add(ints(l.getAsJsonArray()));
                }
                Set<Integer> code = new HashSet<>();
                for (var c : o.getAsJsonArray("code")) {
                    code.add(c.getAsInt());
                }
                Map<String, int[]> regions = new LinkedHashMap<>();
                for (var e : o.getAsJsonObject("regions").entrySet()) {
                    regions.put(e.getKey(), ints(e.getValue().getAsJsonArray()));
                }
                layout = new Layout(ints(o.getAsJsonArray("size")), gates, levers, code, regions, ints(o.getAsJsonArray("maze_lever")),
                        ints(o.getAsJsonArray("boss_spawn")), ints(o.getAsJsonArray("vault_chest")));
            } catch (Exception e) {
                throw new IllegalStateException("The Tempest Keep's layout couldn't be read", e);
            }
        }
        return layout;
    }

    /** For the game tests. */
    public static Layout layoutForTest() {
        return layout();
    }

    private static int[] ints(JsonArray a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.get(i).getAsInt();
        }
        return out;
    }

    private static ServerWorld world() {
        State s = state();
        if (!s.placed) {
            return null;
        }
        for (ServerWorld w : Ac.server().getWorlds()) {
            if (Mc.worldId(w).equals(s.world)) {
                return w;
            }
        }
        return null;
    }

    private static BlockPos at(int[] p) {
        State s = state();
        return new BlockPos(s.x + p[0], s.y + p[1], s.z + p[2]);
    }

    /** Whether a spot is inside a region of the keep (x0 y0 z0 x1 y1 z1 relative to the keep). */
    private static boolean in(String region, ServerWorld w, double x, double y, double z) {
        State s = state();
        if (!s.placed || w == null || !Mc.worldId(w).equals(s.world)) {
            return false;
        }
        int[] r = layout().regions().get(region);
        double rx = x - s.x;
        double ry = y - s.y;
        double rz = z - s.z;
        return rx >= r[0] && rx < r[3] + 1 && ry >= r[1] && ry < r[4] + 1 && rz >= r[2] && rz < r[5] + 1;
    }

    /** Whether a block is part of the keep (its whole footprint, roof included). */
    public static boolean protects(ServerWorld w, BlockPos pos) {
        State s = state();
        if (!s.placed || !Mc.worldId(w).equals(s.world)) {
            return false;
        }
        int[] size = layout().size();
        int rx = pos.getX() - s.x;
        int ry = pos.getY() - s.y;
        int rz = pos.getZ() - s.z;
        return rx >= 0 && rx < size[0] && ry >= 0 && ry < size[1] + 2 && rz >= 0 && rz < size[2];
    }

    private static List<ServerPlayerEntity> playersIn(String region, ServerWorld w) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (!p.isSpectator() && p.isAlive() && in(region, w, p.getX(), p.getY(), p.getZ())) {
                out.add(p);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- gates

    private static Gate gate(String name) {
        for (Gate g : layout().gates()) {
            if (g.name().equals(name)) {
                return g;
            }
        }
        throw new IllegalArgumentException(name);
    }

    static boolean isOpen(String name) {
        return state().open.contains(name);
    }

    private static void setGate(ServerWorld w, String name, boolean open) {
        if (open == isOpen(name)) {
            return;
        }
        Gate g = gate(name);
        BlockState fill = open ? net.minecraft.block.Blocks.AIR.getDefaultState()
                : Registries.BLOCK.get(Identifier.of(g.block())).getDefaultState();
        int[] b = g.box();
        for (int x = b[0]; x <= b[3]; x++) {
            for (int y = b[1]; y <= b[4]; y++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    w.setBlockState(at(new int[]{x, y, z}), fill);
                }
            }
        }
        if (open) {
            state().open.add(name);
        } else {
            state().open.remove(name);
        }
        BlockPos mid = at(new int[]{(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2});
        w.playSound(null, mid, open ? SoundEvents.BLOCK_IRON_DOOR_OPEN : SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 2f, 0.5f);
        w.playSound(null, mid, SoundEvents.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS, 1.5f, 0.6f);
        save();
    }

    // ---------------------------------------------------------------- placing and removing

    public static void place(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        if (s.placed) {
            Msg.send(p, "keep.already", s.world.replace("minecraft:", ""), s.x, s.y, s.z);
            return;
        }
        ServerWorld w = p.getEntityWorld();
        var tpl = w.getStructureTemplateManager().getTemplate(TEMPLATE);
        if (tpl.isEmpty()) {
            Msg.send(p, "keep.missing");
            return;
        }
        int[] size = layout().size();
        // The entrance faces the owner: the keep runs east from two blocks in front of their feet.
        BlockPos origin = new BlockPos(p.getBlockX() + 2, p.getBlockY() - 3, p.getBlockZ() - size[2] / 2);
        Area area = new Area(Mc.worldId(w), origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size[0] - 1, origin.getY() + size[1] + 1, origin.getZ() + size[2] - 1);
        try {
            BlockSnapshots.save(w, area, SNAP);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save the ground under the Tempest Keep", e);
            Msg.send(p, "keep.failed", e.toString());
            return;
        }
        tpl.get().place(w, origin, origin, new StructurePlacementData(), w.getRandom(), 2);
        s.world = Mc.worldId(w);
        s.x = origin.getX();
        s.y = origin.getY();
        s.z = origin.getZ();
        s.placed = true;
        s.open.clear();
        for (Gate g : layout().gates()) {
            if (!g.closedAtStart()) {
                s.open.add(g.name());
            }
        }
        s.boss = null;
        s.bossDone = false;
        save();
        Staff.log(p, "tempest-place", null, null, origin.toShortString());
        Msg.send(p, "keep.placed", origin.getX(), origin.getY(), origin.getZ());
    }

    public static void remove(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = world();
        if (w == null) {
            Msg.send(p, "keep.none");
            return;
        }
        killBoss(w);
        try {
            BlockSnapshots.restore(w, SNAP);
            Files.deleteIfExists(BlockSnapshots.file(SNAP));
        } catch (Exception e) {
            Ac.LOG.warn("Could not put the ground back under the Tempest Keep", e);
            Msg.send(p, "keep.failed", e.toString());
            return;
        }
        // Its mobs go with it.
        int[] size = layout().size();
        State s = state();
        var box = new net.minecraft.util.math.Box(s.x, s.y, s.z, s.x + size[0], s.y + size[1] + 2, s.z + size[2]);
        for (MobEntity m : w.getEntitiesByClass(MobEntity.class, box, e -> e.getCommandTags().stream()
                .anyMatch(t -> t.startsWith(StructureMobs.TAG) || t.equals(Bosses.GUARD_TAG)))) {
            m.discard();
        }
        s.placed = false;
        s.open.clear();
        save();
        Staff.log(p, "tempest-remove", null, null, "");
        Msg.send(p, "keep.removed");
    }

    /** Owner: closes everything again for the next group (the Stormbreaker's fate is kept). */
    public static void reset(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        ServerWorld w = world();
        if (w == null) {
            Msg.send(p, "keep.none");
            return;
        }
        resetRun(w);
        Msg.send(p, "keep.reset");
    }

    public static void info(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        State s = state();
        if (!s.placed) {
            Msg.send(p, "keep.none");
            return;
        }
        Msg.send(p, "keep.info", s.world.replace("minecraft:", ""), s.x, s.y, s.z,
                s.claimed ? Msg.trFor(p, "keep.info.claimed", s.claimedBy) : s.filled ? Msg.trFor(p, "keep.info.waiting")
                        : Msg.trFor(p, "keep.info.sealed"));
    }

    // ---------------------------------------------------------------- the run

    private static long emptyTicks;

    private static void resetRun(ServerWorld w) {
        killBoss(w);
        State s = state();
        for (Gate g : layout().gates()) {
            setGate(w, g.name(), !g.closedAtStart());
        }
        for (int[] l : layout().levers()) {
            setLever(w, at(l), false);
        }
        setLever(w, at(layout().mazeLever()), false);
        s.bossDone = false;
        save();
    }

    private static void killBoss(ServerWorld w) {
        State s = state();
        if (s.boss != null) {
            Entity e = w.getEntity(UUID.fromString(s.boss));
            if (e != null) {
                e.discard();
            }
            s.boss = null;
        }
    }

    private static boolean lever(ServerWorld w, BlockPos pos) {
        BlockState st = w.getBlockState(pos);
        return st.getBlock() instanceof LeverBlock && st.get(LeverBlock.POWERED);
    }

    private static void setLever(ServerWorld w, BlockPos pos, boolean on) {
        BlockState st = w.getBlockState(pos);
        if (st.getBlock() instanceof LeverBlock && st.get(LeverBlock.POWERED) != on) {
            w.setBlockState(pos, st.with(LeverBlock.POWERED, on), 3);
        }
    }

    public static void tick(long ticks) {
        if (ticks % 10 != 0) {
            return;
        }
        ServerWorld w = world();
        if (w == null) {
            return;
        }
        State s = state();
        Layout lay = layout();
        if (!w.isChunkLoaded(s.x >> 4, s.z >> 4)) {
            return; // nobody near: nothing to do
        }
        List<ServerPlayerEntity> inside = playersIn("inside", w);
        if (inside.isEmpty()) {
            emptyTicks += 10;
            if (emptyTicks >= EMPTY_RESET && (!isOpen("seal") || isOpen("code") || isOpen("maze") || s.bossDone || s.boss != null)) {
                resetRun(w);
            }
            return;
        }
        emptyTicks = 0;

        // The entrance seals behind whoever goes in.
        if (isOpen("seal") && !playersIn("seal_trigger", w).isEmpty()) {
            setGate(w, "seal", false);
            for (ServerPlayerEntity p : inside) {
                Mc.title(p, "§b§lThe Tempest Keep", "§7" + Msg.trFor(p, "keep.sealed"), 10, 60, 20);
            }
        }

        // The lever code.
        if (!isOpen("code")) {
            Set<Integer> on = new HashSet<>();
            for (int i = 0; i < lay.levers().size(); i++) {
                if (lever(w, at(lay.levers().get(i)))) {
                    on.add(i);
                }
            }
            if (on.equals(lay.code())) {
                setGate(w, "code", true);
                for (ServerPlayerEntity p : playersIn("levers", w)) {
                    Msg.actionBar(p, "§b" + Msg.trFor(p, "keep.code-right"));
                }
            } else if (on.size() >= lay.code().size()) {
                wrongCode(w, lay);
            }
        }

        // The lever at the maze's heart.
        if (!isOpen("maze") && s.boss == null && !s.bossDone && lever(w, at(lay.mazeLever()))) {
            setGate(w, "maze", true);
            for (ServerPlayerEntity p : playersIn("maze", w)) {
                Msg.actionBar(p, "§b" + Msg.trFor(p, "keep.maze-open"));
            }
        }

        // The arena and its lord.
        List<ServerPlayerEntity> fighters = playersIn("arena", w);
        MobEntity boss = s.boss == null ? null : w.getEntity(UUID.fromString(s.boss)) instanceof MobEntity m ? m : null;
        BlockPos lair = at(lay.bossSpawn());
        boolean killed = s.boss != null && wasKilled(s.boss);
        if (s.boss != null && boss == null && !killed && w.isChunkLoaded(lair.getX() >> 4, lair.getZ() >> 4)) {
            // Gone without dying (removed by the owner): he rises again next time someone steps in.
            s.boss = null;
            save();
        } else if (killed) {
            s.boss = null;
            s.bossDone = true;
            setGate(w, "maze", true);
            setGate(w, "vault", true);
            setGate(w, "exit", true);
            if (!s.filled) {
                BlockPos c = at(lay.vaultChest());
                Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(),
                        "loot replace block " + c.getX() + " " + c.getY() + " " + c.getZ() + " container.13 loot vigil:items/stormbreaker");
                s.filled = true;
            }
            for (ServerPlayerEntity p : inside) {
                Mc.title(p, "§b§lThe Tempest Lord falls", "§7" + Msg.trFor(p, s.claimed ? "keep.vault-empty" : "keep.vault-open"), 10, 80, 20);
            }
            save();
        } else if (s.boss == null && !s.bossDone && isOpen("maze") && !fighters.isEmpty()) {
            Vec3d spot = Vec3d.ofBottomCenter(at(lay.bossSpawn()));
            MobEntity lord = Bosses.spawn(w, spot, Bosses.TEMPEST_LORD);
            if (lord != null) {
                s.boss = lord.getUuidAsString();
                setGate(w, "maze", false);
                for (ServerPlayerEntity p : fighters) {
                    Mc.title(p, "§b§lThe Tempest Lord", "§c" + Msg.trFor(p, "keep.boss-wakes"), 10, 70, 20);
                }
                save();
            }
        } else if (boss != null && fighters.isEmpty() && !isOpen("maze")) {
            // Everyone in the arena died: the gate opens again for the next try (he heals himself meanwhile).
            setGate(w, "maze", true);
        } else if (boss != null && !fighters.isEmpty() && isOpen("maze") && playersIn("maze", w).isEmpty()) {
            setGate(w, "maze", false);
        }

        // The Stormbreaker's fate: once it leaves the vault chest, it's claimed for good.
        if (s.filled && !s.claimed) {
            BlockPos c = at(lay.vaultChest());
            if (w.getBlockEntity(c) instanceof Inventory inv && !holdsStormbreaker(inv)) {
                ServerPlayerEntity taker = null;
                double best = Double.MAX_VALUE;
                for (ServerPlayerEntity p : inside) {
                    double d = p.squaredDistanceTo(Vec3d.ofCenter(c));
                    if (d < best) {
                        best = d;
                        taker = p;
                    }
                }
                s.claimed = true;
                s.claimedBy = taker == null ? "?" : taker.getGameProfile().name();
                save();
                for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
                    Mc.title(p, "§b§l⚡ " + s.claimedBy, "§7" + Msg.trFor(p, "keep.claimed"), 10, 100, 30);
                    Mc.sound(p, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 0.7f);
                }
                Ac.LOG.info("{} claimed the Stormbreaker", s.claimedBy);
            }
        }
    }

    private static final Set<String> KILLED = new HashSet<>();

    /** Bosses calls this when one dies, so a kill isn't mistaken for the boss's chunk unloading. */
    static void bossDied(Entity e) {
        KILLED.add(e.getUuidAsString());
    }

    private static boolean wasKilled(String id) {
        return KILLED.remove(id);
    }

    private static boolean holdsStormbreaker(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (SecretItems.STORMBREAKER.equals(SecretItems.idOf(inv.getStack(i)))) {
                return true;
            }
        }
        return false;
    }

    private static long lastTrap;

    /** A wrong code: lightning on everyone in the room, shades, and the levers spring back. */
    private static void wrongCode(ServerWorld w, Layout lay) {
        for (int[] l : lay.levers()) {
            setLever(w, at(l), false);
        }
        long now = System.currentTimeMillis();
        if (now - lastTrap < 1500) {
            return;
        }
        lastTrap = now;
        for (ServerPlayerEntity p : playersIn("levers", w)) {
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.refreshPositionAfterTeleport(p.getEntityPos());
                w.spawnEntity(bolt);
            }
            Msg.actionBar(p, "§c" + Msg.trFor(p, "keep.code-wrong"));
            for (int i = 0; i < 2; i++) {
                var vex = EntityType.VEX.create(w, SpawnReason.EVENT);
                if (vex != null) {
                    vex.refreshPositionAndAngles(p.getX() + (i == 0 ? 2 : -2), p.getY() + 1.5, p.getZ(), 0, 0);
                    vex.addCommandTag(Bosses.GUARD_TAG);
                    StructureMobs.guard(vex);
                    w.spawnEntity(vex);
                    vex.setTarget(p);
                }
            }
        }
    }

    // ---------------------------------------------------------------- no cheating your way through

    private static boolean exempt(ServerPlayerEntity p) {
        return Perms.isOwner(p.getUuid());
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) ->
                !(Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !exempt(p) && protects(w, pos)));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !exempt(p)) {
                ItemStack held = p.getStackInHand(hand);
                if ((held.getItem() instanceof BlockItem || held.getItem() instanceof BucketItem) && protects(w, hit.getBlockPos())) {
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "keep.no-build"));
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !exempt(p)) {
                ItemStack held = p.getStackInHand(hand);
                boolean cheat = held.isOf(Items.ENDER_PEARL) || held.isOf(Items.CHORUS_FRUIT) || held.getItem() instanceof BucketItem
                        || held.isOf(Items.WIND_CHARGE);
                if (cheat && protects(w, p.getBlockPos())) {
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "keep.no-cheat"));
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });
    }
}
