package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import com.vylorq.anticheat.util.PackIds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everyday things: hand-held lights that light the way, Climbing Claws, the Sleeping Bag, gems set into armour, new
 * crops (peppers, rice, glow berries) and their foods, and furniture (sit on any stairs or slab; tables and lamps).
 */
public final class Lifestyle {
    private Lifestyle() {
    }

    public static final String GEAR = "vigil_gear";
    public static final String CROP = "vigil_crop";
    public static final String DECOR = "vigil_decor";
    public static final String SOCKET = "vigil_socket";

    // ---------------------------------------------------------------- items

    private static ItemStack item(Item base, String model, String name, String lore, String tagKey, String tag) {
        ItemStack s = new ItemStack(base);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.literal(lore).styled(x -> x.withItalic(false)))));
        PackIds.apply(s, model);
        ItemConv.setTag(s, tagKey, tag);
        return s;
    }

    public static ItemStack claws() {
        return item(Items.IRON_NUGGET, "climbing_claws", "§fClimbing Claws", "§7Hold them and walk into a wall to climb (sneak to hang).",
                GEAR, "claws");
    }

    public static ItemStack sleepingBag() {
        return item(Items.LEATHER, "sleeping_bag", "§cSleeping Bag", "§7Right-click at night to sleep anywhere (keeps your spawn).",
                GEAR, "sleeping_bag");
    }

    /** pepper, rice, pepper_seeds, rice_seeds, glowberry_seeds */
    public static ItemStack crop(String what, int count) {
        ItemStack s = switch (what) {
            case "pepper" -> item(Items.BEETROOT, "pepper", "§cPepper", "§7Hot! Cook it into Spicy Stew.", CROP, what);
            case "rice" -> item(Items.WHEAT, "rice", "§eRice", "§7Cook it into a Rice Bowl or Spicy Stew.", CROP, what);
            case "pepper_seeds" -> item(Items.BEETROOT_SEEDS, "pepper_seeds", "§6Pepper Seeds", "§7Plant on farmland.", CROP, what);
            case "rice_seeds" -> item(Items.WHEAT_SEEDS, "rice_seeds", "§eRice Seeds", "§7Plant on farmland.", CROP, what);
            default -> item(Items.SWEET_BERRIES, "glowberry_seeds", "§6Glowberry Seeds", "§7Plant on grass: grows a bush of glow berries.", CROP,
                    "glowberry_seeds");
        };
        s.setCount(count);
        return s;
    }

    public static String gearOf(ItemStack s) {
        return s == null || s.isEmpty() ? null : ItemConv.tag(s, GEAR);
    }

    public static String cropOf(ItemStack s) {
        return s == null || s.isEmpty() ? null : ItemConv.tag(s, CROP);
    }

    private static boolean holding(ServerPlayerEntity p, String gear) {
        return gear.equals(gearOf(p.getMainHandStack())) || gear.equals(gearOf(p.getOffHandStack()));
    }

    // ---------------------------------------------------------------- lights in hand

    private static final Set<Item> LIGHTS = Set.of(Items.TORCH, Items.SOUL_TORCH, Items.LANTERN, Items.SOUL_LANTERN, Items.GLOWSTONE,
            Items.SEA_LANTERN, Items.SHROOMLIGHT, Items.JACK_O_LANTERN, Items.OCHRE_FROGLIGHT, Items.VERDANT_FROGLIGHT,
            Items.PEARLESCENT_FROGLIGHT, Items.END_ROD, Items.GLOW_BERRIES, Items.LAVA_BUCKET, Items.REDSTONE_TORCH, Items.CAMPFIRE,
            Items.SOUL_CAMPFIRE, Items.GLOW_INK_SAC, Items.BEACON, Items.CONDUIT, Items.MAGMA_BLOCK, Items.COPPER_TORCH);
    /** Where each player's light is now. */
    private static final Map<UUID, BlockPos> LIT = new ConcurrentHashMap<>();
    private static final Map<UUID, World> LIT_WORLD = new ConcurrentHashMap<>();

    private static boolean lightInHand(ServerPlayerEntity p) {
        return LIGHTS.contains(p.getMainHandStack().getItem()) || LIGHTS.contains(p.getOffHandStack().getItem());
    }

    private static void unlight(UUID id) {
        BlockPos old = LIT.remove(id);
        World w = LIT_WORLD.remove(id);
        if (old != null && w != null && w.getBlockState(old).isOf(Blocks.LIGHT)) {
            w.setBlockState(old, Blocks.AIR.getDefaultState());
        }
    }

    private static void lights(ServerPlayerEntity p) {
        UUID id = p.getUuid();
        if (p.isSpectator() || !lightInHand(p)) {
            unlight(id);
            return;
        }
        BlockPos at = p.getBlockPos().up();
        if (at.equals(LIT.get(id)) && LIT_WORLD.get(id) == p.getEntityWorld()) {
            return;
        }
        unlight(id);
        ServerWorld w = p.getEntityWorld();
        if (w.getBlockState(at).isAir()) {
            w.setBlockState(at, Blocks.LIGHT.getDefaultState().with(Properties.LEVEL_15, 14), net.minecraft.block.Block.NOTIFY_LISTENERS);
            LIT.put(id, at);
            LIT_WORLD.put(id, w);
        }
    }

    // ---------------------------------------------------------------- climbing claws

    private static void climb(ServerPlayerEntity p, long ticks) {
        if (ticks % 2 != 0 || !p.horizontalCollision || p.isSpectator() || p.getAbilities().flying || !holding(p, "claws")) {
            return;
        }
        if (p.isOnGround() && p.isSneaking()) {
            return;
        }
        // Walking into the wall climbs it; sneaking hangs on.
        Vec3d v = p.getVelocity();
        p.setVelocity(v.x, p.isSneaking() ? 0 : 0.24, v.z);
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(p));
        p.fallDistance = 0;
        if (ticks % 8 == 0) {
            p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_LADDER_STEP, SoundCategory.PLAYERS, 0.4f, 1.2f);
        }
    }

    // ---------------------------------------------------------------- sleeping bag

    /** Players resting in a sleeping bag, and where they lay down. */
    private static final Map<UUID, Vec3d> RESTING = new ConcurrentHashMap<>();

    private static boolean night(ServerWorld w) {
        long t = w.getTimeOfDay() % 24000L;
        return t >= 12542 && t <= 23459 || w.isThundering();
    }

    private static void rest(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        if (!World.OVERWORLD.equals(w.getRegistryKey()) || !night(w)) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "bag.not-night"));
            return;
        }
        if (!w.getEntitiesByClass(HostileEntity.class, p.getBoundingBox().expand(8, 5, 8), HostileEntity::isAlive).isEmpty()) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "bag.monsters"));
            return;
        }
        RESTING.put(p.getUuid(), p.getEntityPos());
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0, false, false));
        Msg.actionBar(p, "§9" + Msg.trFor(p, "bag.resting"));
    }

    private static void sleepers(ServerWorld w) {
        if (RESTING.isEmpty()) {
            return;
        }
        List<ServerPlayerEntity> all = new ArrayList<>();
        int asleep = 0;
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.isSpectator()) {
                continue;
            }
            all.add(p);
            Vec3d at = RESTING.get(p.getUuid());
            if (at != null && (p.getEntityPos().squaredDistanceTo(at) > 0.6 || !night(w))) {
                RESTING.remove(p.getUuid());
                at = null;
            }
            if (at != null) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0, false, false));
                asleep++;
            } else if (p.isSleeping()) {
                asleep++;
            }
        }
        RESTING.keySet().removeIf(id -> w.getServer().getPlayerManager().getPlayer(id) == null);
        int percent = w.getGameRules().getValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        int need = Math.max(1, (int) Math.ceil(all.size() * percent / 100.0));
        if (asleep >= need && !all.isEmpty()) {
            long day = w.getTimeOfDay() / 24000L;
            w.setTimeOfDay((day + 1) * 24000L);
            if (w.isRaining()) {
                w.resetWeather();
            }
            for (ServerPlayerEntity p : all) {
                if (RESTING.remove(p.getUuid()) != null) {
                    p.removeStatusEffect(StatusEffects.BLINDNESS);
                    Msg.actionBar(p, "§e" + Msg.trFor(p, "bag.morning"));
                }
            }
        }
    }

    // ---------------------------------------------------------------- gems in armour

    /** A Ruby, Sapphire or Topaz in one hand and an armour piece in the other: the gem is set into it (one each). */
    static boolean socket(ServerPlayerEntity p, ItemStack armor, ItemStack gem, String g) {
        var eq = armor.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null || !eq.slot().isArmorSlot()) {
            return false;
        }
        if (ItemConv.tag(armor, SOCKET) != null) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "socket.already"));
            return true;
        }
        AttributeModifierSlot slot = AttributeModifierSlot.forEquipmentSlot(eq.slot());
        var b = AttributeModifiersComponent.builder();
        for (var e : armor.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT).modifiers()) {
            b.add(e.attribute(), e.modifier(), e.slot());
        }
        Identifier id = Identifier.of("vigil", "socket_" + eq.slot().getName());
        String line;
        switch (g) {
            case "ruby" -> {
                b.add(EntityAttributes.MAX_HEALTH, new EntityAttributeModifier(id, 2, EntityAttributeModifier.Operation.ADD_VALUE), slot);
                line = "§c◆ Ruby: +1 heart";
            }
            case "sapphire" -> {
                b.add(EntityAttributes.ARMOR, new EntityAttributeModifier(id, 1, EntityAttributeModifier.Operation.ADD_VALUE), slot);
                b.add(EntityAttributes.ARMOR_TOUGHNESS, new EntityAttributeModifier(Identifier.of("vigil", "socket_t_" + eq.slot().getName()), 1,
                        EntityAttributeModifier.Operation.ADD_VALUE), slot);
                line = "§9◆ Sapphire: +1 armor, +1 toughness";
            }
            default -> {
                b.add(EntityAttributes.MOVEMENT_SPEED, new EntityAttributeModifier(id, 0.04, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE), slot);
                line = "§6◆ Topaz: +4% speed";
            }
        }
        armor.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());
        List<Text> lore = new ArrayList<>(armor.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
        lore.add(Text.literal(line).styled(x -> x.withItalic(false)));
        armor.set(DataComponentTypes.LORE, new LoreComponent(lore));
        ItemConv.setTag(armor, SOCKET, g);
        gem.decrement(1);
        ServerWorld w = p.getEntityWorld();
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.2f);
        Msg.actionBar(p, "§a" + Msg.trFor(p, "socket.done"));
        return true;
    }

    public static boolean socketForTest(ServerPlayerEntity p, ItemStack armor, ItemStack gem, String g) {
        return socket(p, armor, gem, g);
    }

    // ---------------------------------------------------------------- crops

    public static final class CropState {
        /** "world|x,y,z" -> pepper / rice / glowberry */
        public Map<String, String> planted = new HashMap<>();
    }

    private static CropState crops;

    private static Path cropFile() {
        return Ac.get().dir.resolve("crops.json");
    }

    private static CropState crops() {
        if (crops == null) {
            try {
                Path f = cropFile();
                crops = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), CropState.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read crops.json", e);
            }
            if (crops == null) {
                crops = new CropState();
            }
            if (crops.planted == null) {
                crops.planted = new HashMap<>();
            }
        }
        return crops;
    }

    private static void saveCrops() {
        try {
            Files.createDirectories(cropFile().getParent());
            Files.writeString(cropFile(), ConfigManager.GSON.toJson(crops()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save crops.json", e);
        }
    }

    private static String key(World w, BlockPos pos) {
        return Mc.worldId(w) + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static ActionResult plant(ServerPlayerEntity p, ServerWorld w, BlockPos on, Direction side, ItemStack seeds, String kind) {
        BlockPos at = on.up();
        if (side != Direction.UP || !w.getBlockState(at).isAir()) {
            return ActionResult.FAIL;
        }
        BlockState ground = w.getBlockState(on);
        BlockState plant;
        String crop;
        if (kind.equals("glowberry_seeds")) {
            if (!(ground.isOf(Blocks.GRASS_BLOCK) || ground.isOf(Blocks.DIRT) || ground.isOf(Blocks.MOSS_BLOCK) || ground.isOf(Blocks.PODZOL))) {
                return ActionResult.FAIL;
            }
            plant = Blocks.SWEET_BERRY_BUSH.getDefaultState();
            crop = "glowberry";
        } else {
            if (!ground.isOf(Blocks.FARMLAND)) {
                return ActionResult.FAIL;
            }
            plant = kind.equals("pepper_seeds") ? Blocks.BEETROOTS.getDefaultState() : Blocks.WHEAT.getDefaultState();
            crop = kind.equals("pepper_seeds") ? "pepper" : "rice";
        }
        if (!Protection.canPlace(p, w, at, plant.getBlock())) {
            return ActionResult.FAIL;
        }
        w.setBlockState(at, plant);
        crops().planted.put(key(w, at), crop);
        saveCrops();
        if (!p.isCreative()) {
            seeds.decrement(1);
        }
        w.playSound(null, at, SoundEvents.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 1f, 1f);
        return ActionResult.SUCCESS;
    }

    private static void drop(ServerWorld w, BlockPos pos, ItemStack s) {
        if (!s.isEmpty()) {
            w.spawnEntity(new ItemEntity(w, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, s));
        }
    }

    /** A planted crop broken: our harvest instead of the vanilla one. @return false when we handled it */
    private static boolean harvest(ServerWorld w, BlockPos pos, BlockState st) {
        String k = key(w, pos);
        String crop = crops().planted.get(k);
        if (crop == null) {
            return true;
        }
        boolean matches = crop.equals("glowberry") ? st.isOf(Blocks.SWEET_BERRY_BUSH)
                : crop.equals("pepper") ? st.isOf(Blocks.BEETROOTS) : st.isOf(Blocks.WHEAT);
        crops().planted.remove(k);
        saveCrops();
        if (!matches) {
            return true;
        }
        var r = w.getRandom();
        w.breakBlock(pos, false);
        if (crop.equals("glowberry")) {
            drop(w, pos, crop("glowberry_seeds", 1));
            return false;
        }
        boolean ripe = st.getBlock() instanceof CropBlock c && c.isMature(st);
        if (ripe) {
            drop(w, pos, crop(crop, crop.equals("pepper") ? 1 + r.nextInt(3) : 2 + r.nextInt(3)));
            drop(w, pos, crop(crop + "_seeds", 1 + r.nextInt(2)));
        } else {
            drop(w, pos, crop(crop + "_seeds", 1));
        }
        return false;
    }

    // ---------------------------------------------------------------- furniture

    static final String SEAT = "vigil_seat";

    private static boolean sit(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState st) {
        double y;
        if (st.getBlock() instanceof StairsBlock && st.get(StairsBlock.HALF) == BlockHalf.BOTTOM) {
            y = pos.getY() + 0.5;
        } else if (st.getBlock() instanceof SlabBlock && st.get(SlabBlock.TYPE) == SlabType.BOTTOM) {
            y = pos.getY() + 0.5;
        } else {
            return false;
        }
        if (!w.getBlockState(pos.up()).isAir() || p.hasVehicle()) {
            return false;
        }
        ArmorStandEntity seat = EntityType.ARMOR_STAND.create(w, SpawnReason.EVENT);
        if (seat == null) {
            return false;
        }
        seat.refreshPositionAndAngles(pos.getX() + 0.5, y - 0.5, pos.getZ() + 0.5, p.getYaw(), 0);
        seat.setInvisible(true);
        seat.setNoGravity(true);
        seat.setInvulnerable(true);
        seat.addCommandTag(SEAT);
        w.spawnEntity(seat);
        p.startRiding(seat, true, true);
        return true;
    }

    private static void seats(ServerWorld w) {
        for (var e : w.getEntitiesByType(EntityType.ARMOR_STAND, a -> a.getCommandTags().contains(SEAT) && !a.hasPassengers())) {
            e.discard();
        }
    }

    /** A table or lamp item was placed as a fence: put its top on. */
    private static void topOff(ServerWorld w, BlockPos fence, String what) {
        BlockPos up = fence.up();
        if (!w.getBlockState(fence).isIn(net.minecraft.registry.tag.BlockTags.FENCES) || !w.getBlockState(up).isAir()) {
            return;
        }
        w.setBlockState(up, what.equals("lamp") ? Blocks.LANTERN.getDefaultState() : Blocks.OAK_PRESSURE_PLATE.getDefaultState());
    }

    // ---------------------------------------------------------------- hooks

    public static void tick(long ticks) {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            climb(p, ticks);
            if (ticks % 5 == 0) {
                lights(p);
            }
        }
        if (ticks % 20 == 0) {
            for (ServerWorld w : Ac.server().getWorlds()) {
                seats(w);
            }
            sleepers(Ac.server().getOverworld());
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            unlight(h.getPlayer().getUuid());
            RESTING.remove(h.getPlayer().getUuid());
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            for (UUID id : List.copyOf(LIT.keySet())) {
                unlight(id);
            }
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && "sleeping_bag".equals(gearOf(p.getStackInHand(hand)))) {
                rest(p);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!Ac.running() || !(player instanceof ServerPlayerEntity p) || !(world instanceof ServerWorld w)) {
                return ActionResult.PASS;
            }
            ItemStack held = p.getStackInHand(hand);
            BlockPos pos = hit.getBlockPos();
            BlockState st = w.getBlockState(pos);
            String crop = cropOf(held);
            if (crop != null && crop.endsWith("_seeds")) {
                return plant(p, w, pos, hit.getSide(), held, crop);
            }
            if (crop != null) {
                return ActionResult.PASS;
            }
            // Glow berries from a glowberry bush.
            if (st.isOf(Blocks.SWEET_BERRY_BUSH) && "glowberry".equals(crops().planted.get(key(w, pos)))) {
                int age = st.get(SweetBerryBushBlock.AGE);
                if (age >= 2 && !held.isOf(Items.BONE_MEAL)) {
                    drop(w, pos, new ItemStack(Items.GLOW_BERRIES, 1 + w.getRandom().nextInt(age == 3 ? 3 : 2)));
                    w.setBlockState(pos, st.with(SweetBerryBushBlock.AGE, 1));
                    w.playSound(null, pos, SoundEvents.BLOCK_SWEET_BERRY_BUSH_PICK_BERRIES, SoundCategory.BLOCKS, 1f, 1f);
                    return ActionResult.SUCCESS;
                }
                return held.isOf(Items.BONE_MEAL) ? ActionResult.PASS : ActionResult.FAIL;
            }
            String decor = held.isEmpty() ? null : ItemConv.tag(held, DECOR);
            if (decor != null && hand == Hand.MAIN_HAND) {
                BlockPos placed = st.isReplaceable() ? pos : pos.offset(hit.getSide());
                OwnerPowers.later(1, () -> topOff(w, placed, decor));
                return ActionResult.PASS;
            }
            if (hand == Hand.MAIN_HAND && held.isEmpty() && p.getOffHandStack().isEmpty() && !p.isSneaking() && sit(p, w, pos, st)) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world, player, pos, st, be) ->
                !(Ac.running() && world instanceof ServerWorld w) || harvest(w, pos, st));
        // New seeds turn up in tall grass now and then.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            if (Ac.running() && world instanceof ServerWorld w && !player.isCreative()
                    && (st.isOf(Blocks.SHORT_GRASS) || st.isOf(Blocks.TALL_GRASS) || st.isOf(Blocks.FERN)) && w.getRandom().nextInt(25) == 0) {
                String[] kinds = {"pepper_seeds", "rice_seeds", "glowberry_seeds"};
                drop(w, pos, crop(kinds[w.getRandom().nextInt(kinds.length)], 1));
            }
        });
    }

    public static ItemStack make(String what) {
        return switch (what) {
            case "claws" -> claws();
            case "sleeping_bag" -> sleepingBag();
            default -> null;
        };
    }
}
