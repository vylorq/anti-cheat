package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.core.team.Team;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import com.vylorq.anticheat.util.PackIds;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Special blocks: ordinary blocks (so Bedrock players see them too) that the mod remembers by where they were placed.
 * <ul>
 * <li><b>Elevator</b>: stand on it and jump to go up to the next elevator above you (up to 48 blocks); sneak to go
 * down to the next one below.</li>
 * <li><b>Night Lamp</b>: a lamp that switches itself on at dusk and off at dawn.</li>
 * <li><b>Trophy Pedestal</b>: put a boss trophy on it and the boss's name floats above it in gold, with sparkles.</li>
 * <li><b>Team Banner</b> (/team banner): your team's colour, and its name floating above it.</li>
 * </ul>
 * Broken, each gives back its own item (not the plain block).
 */
public final class SpecialBlocks {
    private SpecialBlocks() {
    }

    static final String TAG = "vigil_block";
    static final String DISPLAY_TAG = "vigil_block_display";
    /** A special block: the plain block it is, its name and what it does. */
    record Def(Item base, String name, String line1, String line2) {
    }

    static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        DEFS.put("elevator", new Def(Items.CHISELED_TUFF_BRICKS, "§bElevator", "§7Jump on it: up to the next elevator above.",
                "§7Sneak on it: down to the next one below."));
        DEFS.put("night_lamp", new Def(Items.REDSTONE_LAMP, "§eNight Lamp", "§7Lights up by itself at dusk,", "§7goes out at dawn."));
        DEFS.put("pedestal", new Def(Items.CHISELED_QUARTZ_BLOCK, "§6Trophy Pedestal", "§7Put a boss trophy on top:", "§7its name shines above it."));
        Machines.defs(DEFS);
    }

    public static final List<String> KINDS = List.copyOf(DEFS.keySet());

    public static final class State {
        /** "world|x|y|z" -> kind ("elevator", "night_lamp", "pedestal", "banner:<team id>", "trophy:<name>"). */
        public Map<String, String> blocks = new LinkedHashMap<>();
    }

    private static State state;

    private static Path file() {
        return Ac.get().dir.resolve("special_blocks.json");
    }

    static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read special_blocks.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.blocks == null) {
                state.blocks = new LinkedHashMap<>();
            }
        }
        return state;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save special_blocks.json", e);
        }
    }

    static String key(ServerWorld w, BlockPos p) {
        return Mc.worldId(w) + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ();
    }

    public static String kindAt(ServerWorld w, BlockPos p) {
        return state().blocks.get(key(w, p));
    }

    // ---------------------------------------------------------------- items

    public static ItemStack item(String kind) {
        Def d = DEFS.get(kind);
        if (d == null) {
            return null;
        }
        ItemStack s = new ItemStack(d.base());
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(d.name()).styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(d.line1()).styled(x -> x.withItalic(false)),
                Text.literal(d.line2()).styled(x -> x.withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.UNCOMMON);
        ItemConv.setTag(s, TAG, kind);
        return s;
    }

    /** /team banner: a banner in the team's colour that shows the team's name above it. */
    public static void teamBanner(ServerPlayerEntity p) {
        Team t = Teams.tm().teamOf(p.getUuid());
        if (t == null) {
            Msg.send(p, "team.r.not_in_team");
            return;
        }
        ItemStack s = new ItemStack(bannerFor(t.color));
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(Teams.tagText(t) + " §f" + t.name + " §7Banner").styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal("§7Shows your team's name above it.").styled(x -> x.withItalic(false)))));
        ItemConv.setTag(s, TAG, "banner:" + t.id);
        p.getInventory().offerOrDrop(s);
        Msg.send(p, "blocks.banner-given");
    }

    private static Item bannerFor(String color) {
        return switch (color == null ? "" : color) {
            case "red", "dark_red" -> Items.RED_BANNER;
            case "gold", "yellow" -> Items.YELLOW_BANNER;
            case "green", "dark_green" -> Items.GREEN_BANNER;
            case "aqua", "dark_aqua" -> Items.CYAN_BANNER;
            case "blue", "dark_blue" -> Items.BLUE_BANNER;
            case "light_purple" -> Items.MAGENTA_BANNER;
            case "dark_purple" -> Items.PURPLE_BANNER;
            case "gray" -> Items.LIGHT_GRAY_BANNER;
            case "dark_gray" -> Items.GRAY_BANNER;
            case "black" -> Items.BLACK_BANNER;
            default -> Items.WHITE_BANNER;
        };
    }

    // ---------------------------------------------------------------- placing and breaking

    /** Called when a block item is about to be placed: once it's there, it's remembered. */
    private static void placing(ServerPlayerEntity p, ServerWorld w, ItemStack held, BlockPos clicked, net.minecraft.util.math.Direction side) {
        String kind = ItemConv.tag(held, TAG);
        boolean trophy = "1".equals(ItemConv.tag(held, Bosses.TROPHY_TAG));
        if (kind == null && !trophy) {
            return;
        }
        BlockPos at = w.getBlockState(clicked).isReplaceable() ? clicked : clicked.offset(side);
        Block block = ((BlockItem) held.getItem()).getBlock();
        String name = held.contains(DataComponentTypes.CUSTOM_NAME) ? held.getName().getString() : "";
        OwnerPowers.later(1, () -> {
            var st = w.getBlockState(at);
            if (!st.isOf(block) && !(trophy && st.getBlock() instanceof net.minecraft.block.AbstractSkullBlock)
                    && !(kind != null && kind.startsWith("banner:") && st.getBlock() instanceof net.minecraft.block.AbstractBannerBlock)) {
                return;
            }
            if (trophy) {
                // A trophy on a pedestal: its name in gold above it.
                if ("pedestal".equals(kindAt(w, at.down()))) {
                    state().blocks.put(key(w, at), "trophy:" + name);
                    display(w, at, "§6§l" + name, 1.3);
                    save();
                    w.playSound(null, at, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.BLOCKS, 0.6f, 1.2f);
                }
                return;
            }
            String stored = Machines.placed(p, w, at, kind);
            if (stored == null) {
                return;
            }
            state().blocks.put(key(w, at), stored);
            if (kind.startsWith("banner:")) {
                Team t = Teams.tm().get(kind.substring(7));
                if (t != null) {
                    display(w, at, Teams.tagText(t) + " §f§l" + t.name, 2.4);
                }
            } else if (kind.equals("night_lamp")) {
                lamp(w, at);
            }
            save();
        });
    }

    /** Broken: its own item comes back, its floating name goes. @return false to stop the plain block dropping */
    private static boolean breaking(ServerWorld w, BlockPos pos, ServerPlayerEntity p) {
        String k = key(w, pos);
        String kind = state().blocks.remove(k);
        if (kind == null) {
            return true;
        }
        removeDisplay(w, pos);
        // A pedestal's trophy loses its name too.
        if ("pedestal".equals(kind) && state().blocks.getOrDefault(key(w, pos.up()), "").startsWith("trophy:")) {
            state().blocks.remove(key(w, pos.up()));
            removeDisplay(w, pos.up());
        }
        save();
        if (kind.startsWith("trophy:")) {
            return true;      // the head drops as itself (the trophy's name stays on it)
        }
        Machines.broken(w, pos, kind);
        ItemStack drop = kind.startsWith("banner:") ? null : item(kind.split(":")[0]);
        if (drop == null) {
            return true;
        }
        w.removeBlock(pos, false);
        if (!p.isCreative()) {
            w.spawnEntity(new ItemEntity(w, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, drop));
        }
        return false;
    }

    private static void display(ServerWorld w, BlockPos at, String text, double up) {
        String cmd = String.format(java.util.Locale.ROOT, "summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\",\"%s\"],billboard:\"center\","
                + "see_through:0b,shadow:1b,background:0,text:%s}", at.getX() + 0.5, at.getY() + up, at.getZ() + 0.5, DISPLAY_TAG,
                DISPLAY_TAG + ":" + at.getX() + "," + at.getY() + "," + at.getZ(), snbtText(text));
        try {
            Ac.server().getCommandManager().parseAndExecute(Ac.server().getCommandSource().withWorld(w).withSilent(), cmd);
        } catch (Exception e) {
            Ac.LOG.debug("display", e);
        }
    }

    /** A plain text component in SNBT, for the summon command. */
    private static String snbtText(String text) {
        return "{text:\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static void removeDisplay(ServerWorld w, BlockPos at) {
        String tag = DISPLAY_TAG + ":" + at.getX() + "," + at.getY() + "," + at.getZ();
        for (var e : w.getEntitiesByClass(DisplayEntity.TextDisplayEntity.class, new Box(at).expand(3), d -> d.getCommandTags().contains(tag))) {
            e.discard();
        }
    }

    // ---------------------------------------------------------------- elevators

    private static final Map<UUID, Boolean> WAS_GROUND = new HashMap<>();
    private static final Set<UUID> WAS_SNEAK = new HashSet<>();
    private static final Map<UUID, Long> NEXT = new HashMap<>();

    private static void elevators(long ticks) {
        if (state().blocks.isEmpty()) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            boolean ground = p.isOnGround();
            boolean wasGround = WAS_GROUND.getOrDefault(p.getUuid(), true);
            boolean sneak = p.isSneaking();
            boolean pressed = sneak && !WAS_SNEAK.contains(p.getUuid());
            WAS_GROUND.put(p.getUuid(), ground);
            if (sneak) {
                WAS_SNEAK.add(p.getUuid());
            } else {
                WAS_SNEAK.remove(p.getUuid());
            }
            boolean jumped = wasGround && !ground && p.getVelocity().y > 0.2;
            if (!jumped && !pressed) {
                continue;
            }
            ServerWorld w = p.getEntityWorld();
            if (ticks < NEXT.getOrDefault(p.getUuid(), 0L)) {
                continue;
            }
            BlockPos under = BlockPos.ofFloored(p.getX(), p.getY() - (jumped ? 0.6 : 0.2), p.getZ());
            if (!"elevator".equals(kindAt(w, under))) {
                under = under.down();
                if (!"elevator".equals(kindAt(w, under))) {
                    continue;
                }
            }
            int dir = jumped ? 1 : -1;
            for (int d = 2; d <= 48; d++) {
                BlockPos to = under.up(dir * d);
                if ("elevator".equals(kindAt(w, to)) && w.getBlockState(to.up()).getCollisionShape(w, to.up()).isEmpty()
                        && w.getBlockState(to.up(2)).getCollisionShape(w, to.up(2)).isEmpty()) {
                    Movement.grace(p, 20);
                    p.requestTeleport(p.getX(), to.getY() + 1, p.getZ());
                    p.setVelocity(0, 0, 0);
                    p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(p));
                    p.fallDistance = 0;
                    w.playSound(null, to.up(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.BLOCKS, 0.4f, 1.8f);
                    NEXT.put(p.getUuid(), ticks + 8);
                    break;
                }
            }
        }
    }

    // ---------------------------------------------------------------- lamps and pedestals

    private static boolean night(ServerWorld w) {
        return BoiledOne.night(w);
    }

    private static void lamp(ServerWorld w, BlockPos at) {
        var st = w.getBlockState(at);
        if (st.isOf(Blocks.REDSTONE_LAMP) && st.get(RedstoneLampBlock.LIT) != night(w)) {
            w.setBlockState(at, st.with(RedstoneLampBlock.LIT, night(w)), Block.NOTIFY_LISTENERS);
        }
    }

    private static void slow(long ticks) {
        if (state().blocks.isEmpty()) {
            return;
        }
        List<String> gone = new ArrayList<>();
        for (var e : state().blocks.entrySet()) {
            String[] p = e.getKey().split("\\|");
            ServerWorld w = Mc.world(Ac.server(), p[0]);
            if (w == null) {
                continue;
            }
            BlockPos pos = new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            if (!w.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            String kind = e.getValue();
            if (kind.equals("night_lamp")) {
                if (!w.getBlockState(pos).isOf(Blocks.REDSTONE_LAMP)) {
                    gone.add(e.getKey());
                } else {
                    lamp(w, pos);
                }
            } else if (kind.startsWith("trophy:")) {
                if (w.getBlockState(pos).isAir()) {
                    gone.add(e.getKey());
                    removeDisplay(w, pos);
                } else {
                    w.spawnParticles(net.minecraft.particle.ParticleTypes.WAX_OFF, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0);
                }
            }
        }
        if (!gone.isEmpty()) {
            gone.forEach(state().blocks::remove);
            save();
        }
    }

    /** Every remembered block of a kind ("miner" also finds "miner:12"), as world + position. */
    static List<Map.Entry<ServerWorld, BlockPos>> all(String kind) {
        List<Map.Entry<ServerWorld, BlockPos>> out = new ArrayList<>();
        for (var e : state().blocks.entrySet()) {
            String v = e.getValue();
            if (!v.equals(kind) && !v.startsWith(kind + ":")) {
                continue;
            }
            String[] p = e.getKey().split("\\|");
            ServerWorld w = Mc.world(Ac.server(), p[0]);
            if (w != null) {
                out.add(Map.entry(w, new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]))));
            }
        }
        return out;
    }

    static void set(ServerWorld w, BlockPos pos, String value) {
        state().blocks.put(key(w, pos), value);
        dirty = true;
    }

    private static boolean dirty;

    public static void tick(long ticks) {
        if (dirty && ticks % 200 == 0) {
            dirty = false;
            save();
        }
        Machines.tick(ticks);
        elevators(ticks);
        if (ticks % 40 == 0) {
            slow(ticks);
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && world instanceof ServerWorld w
                    && p.getStackInHand(hand).getItem() instanceof BlockItem) {
                placing(p, w, p.getStackInHand(hand), hit.getBlockPos(), hit.getSide());
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) ->
                !(Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p) || breaking(w, pos, p));
    }
}
