package com.vylorq.anticheat.feature;

import com.mojang.brigadier.CommandDispatcher;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.core.claims.ClaimAction;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Icons;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * Home teleporters. Each player can place one, only at their home (their team's land, a claim they belong to, or
 * next to their bed). Using it takes them to the lobby; /home in the lobby takes them back to it. Those are the only
 * two trips it allows.
 */
public final class HomeTeleport {
    private HomeTeleport() {
    }

    public static final String KEY = "vigil_home";
    static final int CHARGE_TICKS = 60;
    static final int BED_RANGE = 12;
    private static final DustParticleEffect TEAL = new DustParticleEffect(0x3FF2D0, 1.2f);
    private static final DustParticleEffect TEAL_DARK = new DustParticleEffect(0x1C8F9E, 1.5f);

    public static final class Pad {
        public String world;
        public int x;
        public int y;
        public int z;
        public String name;
    }

    private static Map<String, Pad> pads;
    private static final Map<String, String> BY_POS = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- storage

    private static Path file() {
        return Ac.get().dir.resolve("home-teleporters.json");
    }

    static synchronized Map<String, Pad> pads() {
        if (pads == null) {
            pads = new ConcurrentHashMap<>();
            try {
                Path f = file();
                if (Files.exists(f)) {
                    Map<String, Pad> read = ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                            new com.google.gson.reflect.TypeToken<HashMap<String, Pad>>() { }.getType());
                    if (read != null) {
                        pads.putAll(read);
                    }
                }
            } catch (Exception e) {
                Ac.LOG.warn("Could not read home-teleporters.json", e);
            }
            BY_POS.clear();
            pads.forEach((id, p) -> BY_POS.put(key(p.world, p.x, p.y, p.z), id));
        }
        return pads;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(pads()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save home-teleporters.json", e);
        }
    }

    private static String key(String world, int x, int y, int z) {
        return world + "|" + x + "|" + y + "|" + z;
    }

    public static Pad padOf(UUID player) {
        return pads().get(player.toString());
    }

    /** Whose teleporter is at this block, or null. */
    public static UUID ownerAt(World w, BlockPos pos) {
        if (w.isClient() || !Ac.running()) {
            return null;
        }
        pads();
        String id = BY_POS.get(key(Mc.worldId(w), pos.getX(), pos.getY(), pos.getZ()));
        return id == null ? null : UUID.fromString(id);
    }

    public static boolean isPad(World w, BlockPos pos) {
        return ownerAt(w, pos) != null;
    }

    private static void register(UUID player, String name, ServerWorld w, BlockPos pos) {
        Pad p = new Pad();
        p.world = Mc.worldId(w);
        p.x = pos.getX();
        p.y = pos.getY();
        p.z = pos.getZ();
        p.name = name;
        pads().put(player.toString(), p);
        BY_POS.put(key(p.world, p.x, p.y, p.z), player.toString());
        save();
    }

    private static void unregister(UUID player) {
        Pad p = pads().remove(player.toString());
        if (p != null) {
            BY_POS.remove(key(p.world, p.x, p.y, p.z));
            save();
        }
    }

    // ---------------------------------------------------------------- the item

    public static ItemStack item() {
        ItemStack s = Icons.glint(Icons.of(Items.ECHO_SHARD, "§b⌂ Home Teleporter",
                "Right-click a block at your home to place it", "(your team's land, a claim you're in, or near your bed)",
                "Use it: go to the lobby", "In the lobby: /home brings you back", "One per player"));
        ItemConv.setTag(s, KEY, "1");
        com.vylorq.anticheat.util.PackIds.apply(s, "home_teleporter");
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        return s;
    }

    public static boolean isItem(ItemStack s) {
        return !s.isEmpty() && ItemConv.tag(s, KEY) != null;
    }

    private static boolean carries(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (isItem(inv.getStack(i))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- where it may go

    /** @return null when allowed, else the message key saying why not */
    public static String whyNot(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        if (LobbyFeature.in(w, pos)) {
            return "home.not-lobby";
        }
        Claim c = Claims.at(w, pos);
        if (c != null && c.isActive()) {
            if (c.spawnProtection || !c.members.containsKey(p.getUuid())) {
                return "home.not-yours";
            }
            return null;
        }
        var team = Teams.at(w, pos);
        if (team != null) {
            return team == Teams.tm().teamOf(p.getUuid()) ? null : "home.not-yours";
        }
        return bedNear(w, pos) ? null : "home.not-home";
    }

    static boolean bedNear(ServerWorld w, BlockPos pos) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -BED_RANGE; dx <= BED_RANGE; dx++) {
                for (int dz = -BED_RANGE; dz <= BED_RANGE; dz++) {
                    m.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (w.getBlockState(m).getBlock() instanceof BedBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Right-click on a block with the teleporter item: place it on that face. */
    public static void place(ServerPlayerEntity p, ServerWorld w, BlockHitResult hit, ItemStack stack) {
        BlockPos pos = w.getBlockState(hit.getBlockPos()).isReplaceable() ? hit.getBlockPos() : hit.getBlockPos().offset(hit.getSide());
        p.playerScreenHandler.syncState();
        Pad old = padOf(p.getUuid());
        if (old != null) {
            Msg.send(p, "home.already", old.x + " " + old.y + " " + old.z);
            return;
        }
        if (!w.getBlockState(pos).isReplaceable() || !w.getBlockState(pos.up()).getCollisionShape(w, pos.up()).isEmpty()) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "home.no-room"));
            return;
        }
        String why = whyNot(p, w, pos);
        if (why != null) {
            Msg.send(p, why, BED_RANGE);
            return;
        }
        if (Jail.isJailed(p) || StaffTools.isFrozen(p) || !Claims.check(p, w, pos, ClaimAction.PLACE)) {
            return;
        }
        w.setBlockState(pos, Blocks.LODESTONE.getDefaultState());
        stack.decrement(1);
        register(p.getUuid(), p.getGameProfile().name(), w, pos);
        burst(w, Vec3d.ofBottomCenter(pos.up()));
        sound(p, "home_place");
        w.playSound(null, pos, SoundEvents.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, SoundCategory.BLOCKS, 1f, 1.2f);
        Msg.send(p, "home.placed");
        Staff.log(p, "home-teleporter-placed", p.getUuid(), p.getGameProfile().name(), Mc.worldId(w) + " " + pos.toShortString());
    }

    /** Someone breaks a teleporter. @return false to cancel the vanilla break (it is always handled here) */
    public static boolean breakPad(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        UUID owner = ownerAt(w, pos);
        if (owner == null) {
            return true;
        }
        if (!owner.equals(p.getUuid()) && !Perms.isActiveStaff(p)) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "home.not-your-pad", pads().get(owner.toString()).name));
            Movement.ghostBlock(p);
            return false;
        }
        String ownerName = pads().get(owner.toString()).name;
        unregister(owner);
        // No lodestone drops: the item goes back to its owner.
        w.setBlockState(pos, Blocks.AIR.getDefaultState());
        ServerPlayerEntity o = Ac.server().getPlayerManager().getPlayer(owner);
        if (o != null && !carries(o)) {
            o.getInventory().offerOrDrop(item());
        }
        w.spawnParticles(TEAL_DARK, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30, 0.4, 0.4, 0.4, 0);
        Msg.send(p, "home.removed");
        Staff.log(p, "home-teleporter-removed", owner, ownerName, pos.toShortString());
        return false;
    }

    // ---------------------------------------------------------------- travel

    private record Charge(boolean toLobby, Vec3d start, long startTick) {
    }

    private static final Map<UUID, Charge> CHARGING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new ConcurrentHashMap<>();
    private static long now;

    /** Right-click on a teleporter block. @return true when it was one (handled) */
    public static boolean click(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        UUID owner = ownerAt(w, pos);
        if (owner == null) {
            return false;
        }
        if (!owner.equals(p.getUuid())) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "home.not-your-pad", pads().get(owner.toString()).name));
            return true;
        }
        start(p, true);
        return true;
    }

    /** @return null when they may travel, else why not */
    private static String blocked(ServerPlayerEntity p) {
        if (Jail.isJailed(p) || WaitingRoomFeature.waiting(p) || Arenas.inMatch(p) || StaffTools.isFrozen(p)) {
            return "home.blocked";
        }
        Long f = FIGHT.get(p.getUuid());
        if (f != null && System.currentTimeMillis() - f < 15_000) {
            return "home.in-fight";
        }
        Long c = COOLDOWN.get(p.getUuid());
        if (c != null && System.currentTimeMillis() < c) {
            return "home.cooldown";
        }
        return null;
    }

    static void start(ServerPlayerEntity p, boolean toLobby) {
        String why = blocked(p);
        if (why != null) {
            Long c = COOLDOWN.get(p.getUuid());
            Msg.send(p, why, c == null ? 0 : Math.max(1, (c - System.currentTimeMillis()) / 1000));
            return;
        }
        if (CHARGING.containsKey(p.getUuid())) {
            return;
        }
        CHARGING.put(p.getUuid(), new Charge(toLobby, p.getEntityPos(), now));
        sound(p, "home_charge");
        p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1f, 1.6f);
        Msg.actionBar(p, "§b" + Msg.trFor(p, toLobby ? "home.charging-lobby" : "home.charging-home"));
    }

    /** /home: in the lobby, back to your teleporter; without one, you get the item. */
    static int home(ServerPlayerEntity p) {
        Pad pad = padOf(p.getUuid());
        if (pad == null) {
            if (carries(p)) {
                Msg.send(p, "home.place-it", BED_RANGE);
            } else {
                p.getInventory().offerOrDrop(item());
                Msg.send(p, "home.given", BED_RANGE);
            }
            return 1;
        }
        if (!LobbyFeature.in(p)) {
            Msg.send(p, "home.only-from-lobby", pad.x + " " + pad.y + " " + pad.z);
            return 0;
        }
        if (!valid(pad, p.getUuid())) {
            Msg.send(p, "home.lost");
            return 0;
        }
        start(p, false);
        return 1;
    }

    /** The teleporter block is still there (removes the record when it isn't). */
    private static boolean valid(Pad pad, UUID owner) {
        ServerWorld w = Mc.world(Ac.server(), pad.world);
        if (w == null) {
            return false;
        }
        BlockPos pos = new BlockPos(pad.x, pad.y, pad.z);
        if (!w.getBlockState(pos).isOf(Blocks.LODESTONE)) {
            unregister(owner);
            return false;
        }
        return true;
    }

    private static void arrive(ServerPlayerEntity p, Charge c) {
        ServerWorld from = p.getEntityWorld();
        Vec3d was = p.getEntityPos();
        if (c.toLobby()) {
            if (Ac.get().lobby.data().spawn == null) {
                Msg.send(p, "lobby.not-set");
                return;
            }
            LobbyFeature.teleport(p);
        } else {
            Pad pad = padOf(p.getUuid());
            if (pad == null || !valid(pad, p.getUuid())) {
                Msg.send(p, "home.lost");
                return;
            }
            ServerWorld w = Mc.world(Ac.server(), pad.world);
            BlockPos up = new BlockPos(pad.x, pad.y + 1, pad.z);
            if (!w.getBlockState(up).getCollisionShape(w, up).isEmpty() || !w.getBlockState(up.up()).getCollisionShape(w, up.up()).isEmpty()) {
                Msg.send(p, "home.pad-blocked");
                return;
            }
            Mc.teleport(p, w, pad.x + 0.5, pad.y + 1, pad.z + 0.5, p.getYaw(), p.getPitch());
        }
        Ac.session(p).teleported();
        COOLDOWN.put(p.getUuid(), System.currentTimeMillis() + 10_000);
        from.spawnParticles(TEAL_DARK, was.x, was.y + 1, was.z, 40, 0.4, 0.9, 0.4, 0);
        from.spawnParticles(ParticleTypes.REVERSE_PORTAL, was.x, was.y + 1, was.z, 40, 0.3, 0.8, 0.3, 0.05);
        burst(p.getEntityWorld(), p.getEntityPos());
        sound(p, "home_tp");
        p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), SoundCategory.PLAYERS, 0.8f, 1.6f);
        Msg.actionBar(p, "§b" + Msg.trFor(p, c.toLobby() ? "home.arrived-lobby" : "home.arrived-home"));
    }

    private static void burst(ServerWorld w, Vec3d at) {
        w.spawnParticles(TEAL, at.x, at.y + 1, at.z, 50, 0.5, 1.0, 0.5, 0);
        w.spawnParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 20, 0.4, 0.8, 0.4, 0.05);
    }

    private static void sound(ServerPlayerEntity p, String name) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound(name))),
                SoundCategory.PLAYERS, p.getX(), p.getY(), p.getZ(), 1f, 1f, p.getRandom().nextLong()));
    }

    /** Fought another player in the last 15 seconds. */
    public static boolean inFight(ServerPlayerEntity p) {
        Long f = FIGHT.get(p.getUuid());
        return f != null && System.currentTimeMillis() - f < 15_000;
    }

    /** Getting hurt cancels a charge; fighting another player blocks travel for 15 seconds. */
    public static void onDamage(ServerPlayerEntity p, Entity attacker) {
        if (CHARGING.remove(p.getUuid()) != null) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "home.cancelled"));
        }
        if (attacker instanceof ServerPlayerEntity a && a != p) {
            long t = System.currentTimeMillis();
            FIGHT.put(p.getUuid(), t);
            FIGHT.put(a.getUuid(), t);
        }
    }

    public static void tick(long ticks) {
        now = ticks;
        if (!CHARGING.isEmpty()) {
            for (var en : CHARGING.entrySet()) {
                ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(en.getKey());
                Charge c = en.getValue();
                if (p == null) {
                    CHARGING.remove(en.getKey());
                    continue;
                }
                if (p.getEntityPos().squaredDistanceTo(c.start()) > 1.0) {
                    CHARGING.remove(en.getKey());
                    Msg.actionBar(p, "§c" + Msg.trFor(p, "home.moved"));
                    continue;
                }
                long t = ticks - c.startTick();
                ServerWorld w = p.getEntityWorld();
                double h = t / (double) CHARGE_TICKS * 2.2;
                for (int k = 0; k < 3; k++) {
                    double a = t * 0.45 + k * 2 * Math.PI / 3;
                    w.spawnParticles(TEAL, p.getX() + Math.cos(a) * 0.8, p.getY() + h, p.getZ() + Math.sin(a) * 0.8, 1, 0, 0, 0, 0);
                }
                if (t % 4 == 0) {
                    w.spawnParticles(ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 6, 0.4, 0.6, 0.4, 0.3);
                }
                if (t % 20 == 0 && t > 0) {
                    Msg.actionBar(p, "§b" + Msg.trFor(p, c.toLobby() ? "home.charging-lobby" : "home.charging-home") + " §7" + (3 - t / 20));
                }
                if (t >= CHARGE_TICKS) {
                    CHARGING.remove(en.getKey());
                    arrive(p, c);
                }
            }
        }
        // A soft glow over teleporters that someone is near (and a check that they still exist).
        if (ticks % 10 == 0) {
            for (var en : pads().entrySet()) {
                Pad pad = en.getValue();
                ServerWorld w = Mc.world(Ac.server(), pad.world);
                if (w == null || w.getClosestPlayer(pad.x + 0.5, pad.y, pad.z + 0.5, 24, false) == null) {
                    continue;
                }
                if (!w.getBlockState(new BlockPos(pad.x, pad.y, pad.z)).isOf(Blocks.LODESTONE)) {
                    unregister(UUID.fromString(en.getKey()));
                    continue;
                }
                double a = ticks * 0.08;
                for (int k = 0; k < 2; k++) {
                    double b = a + k * Math.PI;
                    w.spawnParticles(TEAL, pad.x + 0.5 + Math.cos(b) * 0.45, pad.y + 1.2 + Math.sin(a * 2) * 0.2, pad.z + 0.5 + Math.sin(b) * 0.45, 1, 0, 0, 0, 0);
                }
                if (ticks % 40 == 0) {
                    w.spawnParticles(ParticleTypes.END_ROD, pad.x + 0.5, pad.y + 1.3, pad.z + 0.5, 1, 0.1, 0.1, 0.1, 0.01);
                }
            }
        }
        if (ticks % 1200 == 0) {
            long cut = System.currentTimeMillis() - 60_000;
            FIGHT.values().removeIf(t -> t < cut);
            COOLDOWN.values().removeIf(t -> t < System.currentTimeMillis());
        }
    }

    public static void registerCommand(CommandDispatcher<ServerCommandSource> d) {
        d.register(literal("home").executes(ctx -> {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            if (p == null) {
                Msg.err(ctx.getSource(), "general.players-only");
                return 0;
            }
            return home(p);
        }));
    }
}
