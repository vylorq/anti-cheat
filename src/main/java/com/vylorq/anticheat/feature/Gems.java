package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import com.vylorq.anticheat.util.PackIds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gems and their ores. Five new ores grow in new chunks (data/vigil/worldgen: Ruby and Voidstone in deepslate,
 * Sapphire, Topaz and Bloodstone in stone). Mined with a good enough pickaxe they drop a raw chunk, which smelts into
 * the gem. In the world they're note block states the resource pack draws as ores (they never act like note blocks).
 * <ul>
 *   <li>Ruby: Ruby armour and tools (between diamond and netherite).</li>
 *   <li>Sapphire: Sapphire armour (Water Breathing, Haste).</li>
 *   <li>Topaz: furnace fuel that burns four times as long as coal.</li>
 *   <li>Voidstone (very deep, very rare): hold it, with a netherite piece in your other hand, and use it: the piece
 *   gets stronger (once).</li>
 *   <li>Bloodstone: its ore only gives anything during The Boiling Night; it makes the Lantern of Dawn and Boiled
 *   Armor.</li>
 * </ul>
 */
public final class Gems {
    private Gems() {
    }

    static final String GEM = "vigil_gem";
    static final String RAW = "vigil_raw";
    static final String MAKE = "vigil_make";
    static final String VOID = "vigil_void";

    record Gem(String id, String name, String color, int note, int tier, int xpMin, int xpMax) {
    }

    /** tier: 1 = iron pickaxe or better, 2 = diamond or better. */
    static final Map<String, Gem> GEMS = new LinkedHashMap<>();

    static {
        for (Gem g : List.of(new Gem("ruby", "Ruby", "§c", 1, 1, 3, 7), new Gem("sapphire", "Sapphire", "§9", 2, 1, 3, 7),
                new Gem("topaz", "Topaz", "§6", 3, 1, 2, 5), new Gem("voidstone", "Voidstone", "§5", 4, 2, 6, 12),
                new Gem("bloodstone", "Bloodstone", "§4", 5, 1, 4, 9))) {
            GEMS.put(g.id(), g);
        }
    }

    // ---------------------------------------------------------------- the ore blocks

    /** The note block state that is this gem's ore. */
    public static BlockState oreState(Gem g) {
        return Blocks.NOTE_BLOCK.getDefaultState().with(NoteBlock.INSTRUMENT, NoteBlockInstrument.DRAGON)
                .with(NoteBlock.NOTE, g.note()).with(NoteBlock.POWERED, false);
    }

    /** Which gem's ore this is, or null. */
    public static Gem oreOf(BlockState st) {
        if (!st.isOf(Blocks.NOTE_BLOCK) || st.get(NoteBlock.INSTRUMENT) != NoteBlockInstrument.DRAGON || st.get(NoteBlock.POWERED)) {
            return null;
        }
        int n = st.get(NoteBlock.NOTE);
        for (Gem g : GEMS.values()) {
            if (g.note() == n) {
                return g;
            }
        }
        return null;
    }

    private static boolean canMine(Gem g, ItemStack tool) {
        if (!tool.isIn(ItemTags.PICKAXES)) {
            return false;
        }
        boolean diamond = tool.isOf(Items.DIAMOND_PICKAXE) || tool.isOf(Items.NETHERITE_PICKAXE);
        boolean iron = diamond || tool.isOf(Items.IRON_PICKAXE);
        return g.tier() >= 2 ? diamond : iron;
    }

    /** What the ore drops (used for every way it breaks: mined, blown up...). */
    private static List<ItemStack> drops(Gem g, ItemStack tool, net.minecraft.util.math.random.Random r) {
        List<ItemStack> out = new ArrayList<>();
        if (tool == null || !canMine(g, tool)) {
            return out;
        }
        if (g.id().equals("bloodstone") && !BoiledOne.eventOn()) {
            return out;         // it only gives anything during The Boiling Night
        }
        int fortune = 0;
        var e = Ac.server().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                .getEntry(net.minecraft.enchantment.Enchantments.FORTUNE.getValue());
        if (e.isPresent()) {
            fortune = net.minecraft.enchantment.EnchantmentHelper.getLevel(e.get(), tool);
        }
        int count = 1 + (fortune > 0 ? Math.max(0, r.nextInt(fortune + 2) - 1) : 0);
        out.add(raw(g, count));
        return out;
    }

    // ---------------------------------------------------------------- items

    private static ItemStack named(Item base, String model, String name, List<String> lore, Rarity rarity) {
        ItemStack s = new ItemStack(base);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name).styled(x -> x.withItalic(false)));
        List<Text> l = new ArrayList<>();
        for (String line : lore) {
            l.add(Text.literal(line).styled(x -> x.withItalic(false)));
        }
        s.set(DataComponentTypes.LORE, new LoreComponent(l));
        s.set(DataComponentTypes.RARITY, rarity);
        PackIds.apply(s, model);
        return s;
    }

    public static ItemStack gem(Gem g, int count) {
        ItemStack s = named(Items.DISC_FRAGMENT_5, "gem_" + g.id(), g.color() + g.name(), List.of("§7" + use(g)), Rarity.RARE);
        ItemConv.setTag(s, GEM, g.id());
        s.setCount(count);
        return s;
    }

    public static ItemStack raw(Gem g, int count) {
        ItemStack s = named(Items.DISC_FRAGMENT_5, "raw_" + g.id(), g.color() + "Raw " + g.name(), List.of("§7Smelt it into " + g.name() + "."),
                Rarity.UNCOMMON);
        ItemConv.setTag(s, RAW, g.id());
        s.setCount(count);
        return s;
    }

    /** The ore as a block item (owner only: it places the ore). */
    public static ItemStack oreItem(Gem g) {
        ItemStack s = named(Items.NOTE_BLOCK, "ore_" + g.id(), g.color() + g.name() + " Ore", List.of("§8Places the ore"), Rarity.UNCOMMON);
        s.set(DataComponentTypes.BLOCK_STATE, net.minecraft.component.type.BlockStateComponent.DEFAULT
                .with(NoteBlock.INSTRUMENT, NoteBlockInstrument.DRAGON).with(NoteBlock.NOTE, g.note()).with(NoteBlock.POWERED, false));
        return s;
    }

    private static String use(Gem g) {
        return switch (g.id()) {
            case "ruby" -> "Makes Ruby armor and tools.";
            case "sapphire" -> "Makes Sapphire armor.";
            case "topaz" -> "Burns 4x longer than coal.";
            case "voidstone" -> "Use it with netherite gear in your other hand.";
            default -> "Makes the Lantern of Dawn and Boiled Armor.";
        };
    }

    public static Gem gemOf(ItemStack s) {
        String id = s.isEmpty() ? null : ItemConv.tag(s, GEM);
        return id == null ? null : GEMS.get(id);
    }

    public static boolean isTopaz(ItemStack s) {
        Gem g = gemOf(s);
        return g != null && g.id().equals("topaz");
    }

    // ---------------------------------------------------------------- Ruby tools

    static final String[] TOOLS = {"sword", "pickaxe", "axe", "shovel", "hoe"};
    private static final Item[] TOOL_BASE = {Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE};
    // One more damage than diamond, and more durability (diamond 1561, netherite 2031).
    private static final double[] TOOL_DAMAGE = {7, 5, 9, 5.5, 0};
    private static final double[] TOOL_SPEED = {-2.4, -2.8, -3.0, -3.0, 0};

    public static ItemStack rubyTool(int i) {
        ItemStack s = named(TOOL_BASE[i], "ruby_" + TOOLS[i], "§cRuby " + Character.toUpperCase(TOOLS[i].charAt(0)) + TOOLS[i].substring(1),
                List.of("§7Stronger than diamond."), Rarity.UNCOMMON);
        s.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.builder()
                .add(EntityAttributes.ATTACK_DAMAGE, new EntityAttributeModifier(Item.BASE_ATTACK_DAMAGE_MODIFIER_ID, TOOL_DAMAGE[i],
                        EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.ATTACK_SPEED, new EntityAttributeModifier(Item.BASE_ATTACK_SPEED_MODIFIER_ID, TOOL_SPEED[i],
                        EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND)
                .build());
        s.set(DataComponentTypes.MAX_DAMAGE, 1850);
        ItemConv.setTag(s, MAKE + "_done", "1");
        return s;
    }

    // ---------------------------------------------------------------- crafted things come out plain; finished here

    /** Crafted / smelted items carry only what they are (custom_data vigil_make): they get their real form here. */
    static void finish(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) {
                continue;
            }
            String make = ItemConv.tag(st, MAKE);
            if (make == null) {
                continue;
            }
            ItemStack done = make(make);
            if (done != null) {
                done.setCount(Math.max(1, st.getCount()));
                inv.setStack(i, done);
            }
        }
    }

    private static ItemStack make(String what) {
        String[] parts = what.split(":");
        switch (parts[0]) {
            case "gem" -> {
                Gem g = GEMS.get(parts[1]);
                return g == null ? null : gem(g, 1);
            }
            case "ruby_tool" -> {
                for (int i = 0; i < TOOLS.length; i++) {
                    if (TOOLS[i].equals(parts[1])) {
                        return rubyTool(i);
                    }
                }
                return null;
            }
            case "armor" -> {
                var set = ArmorSets.SETS.get(parts[1]);
                return set == null ? null : ArmorSets.piece(set, Integer.parseInt(parts[2]));
            }
            case "dawn_lantern" -> {
                return BoiledOmens.lantern();
            }
            case "boiled" -> {
                return BoiledFight.armor(Integer.parseInt(parts[1]));
            }
            default -> {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- Voidstone

    private static ActionResult voidUpgrade(ServerPlayerEntity p, Hand hand) {
        ItemStack stone = p.getStackInHand(hand);
        ItemStack gear = p.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);
        if (!Ac.server().getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ITEM).getId(gear.getItem()).getPath().startsWith("netherite_")
                || gear.isOf(Items.NETHERITE_INGOT) || gear.isOf(Items.NETHERITE_SCRAP) || gear.isOf(Items.NETHERITE_BLOCK)
                || gear.isOf(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE)) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "gems.void-hold"));
            return ActionResult.FAIL;
        }
        if ("1".equals(ItemConv.tag(gear, VOID))) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "gems.void-already"));
            return ActionResult.FAIL;
        }
        var existing = gear.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        var b = AttributeModifiersComponent.builder();
        for (var e : existing.modifiers()) {
            b.add(e.attribute(), e.modifier(), e.slot());
        }
        var eq = gear.get(DataComponentTypes.EQUIPPABLE);
        if (eq != null) {
            AttributeModifierSlot slot = AttributeModifierSlot.forEquipmentSlot(eq.slot());
            b.add(EntityAttributes.ARMOR, new EntityAttributeModifier(Identifier.of("vigil", "void_armor"), 2, EntityAttributeModifier.Operation.ADD_VALUE), slot);
            b.add(EntityAttributes.ARMOR_TOUGHNESS, new EntityAttributeModifier(Identifier.of("vigil", "void_toughness"), 1, EntityAttributeModifier.Operation.ADD_VALUE), slot);
            b.add(EntityAttributes.MAX_HEALTH, new EntityAttributeModifier(Identifier.of("vigil", "void_health"), 2, EntityAttributeModifier.Operation.ADD_VALUE), slot);
        } else {
            b.add(EntityAttributes.ATTACK_DAMAGE, new EntityAttributeModifier(Identifier.of("vigil", "void_damage"), 3, EntityAttributeModifier.Operation.ADD_VALUE),
                    AttributeModifierSlot.MAINHAND);
        }
        gear.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());
        List<Text> lore = new ArrayList<>(gear.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
        lore.add(Text.literal("§5✦ Void-touched").styled(x -> x.withItalic(false)));
        gear.set(DataComponentTypes.LORE, new LoreComponent(lore));
        gear.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        ItemConv.setTag(gear, VOID, "1");
        stone.decrement(1);
        ServerWorld w = p.getEntityWorld();
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.6f, 1.6f);
        w.spawnParticles(net.minecraft.particle.ParticleTypes.PORTAL, p.getX(), p.getY() + 1, p.getZ(), 60, 0.5, 0.8, 0.5, 0.4);
        Msg.actionBar(p, "§5" + Msg.trFor(p, "gems.void-done"));
        return ActionResult.SUCCESS;
    }

    // ---------------------------------------------------------------- hooks

    public static void register() {
        // What the ores drop, however they break.
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            if (!Ac.running() || table.getKey().isEmpty()
                    || !table.getKey().get().getValue().equals(Identifier.ofVanilla("blocks/note_block"))) {
                return;
            }
            BlockState st = context.get(net.minecraft.loot.context.LootContextParameters.BLOCK_STATE);
            Gem g = st == null ? null : oreOf(st);
            if (g == null) {
                return;
            }
            drops.clear();
            drops.addAll(drops(g, context.get(net.minecraft.loot.context.LootContextParameters.TOOL), context.getRandom()));
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            Gem g = Ac.running() && world instanceof ServerWorld w && player instanceof ServerPlayerEntity p && !p.isCreative() ? oreOf(st) : null;
            if (g != null && canMine(g, player.getMainHandStack()) && (!g.id().equals("bloodstone") || BoiledOne.eventOn())) {
                ExperienceOrbEntity.spawn((ServerWorld) world, Vec3d.ofCenter(pos), g.xpMin() + world.getRandom().nextInt(g.xpMax() - g.xpMin() + 1));
            }
        });
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p) {
                Gem g = gemOf(p.getStackInHand(hand));
                if (g != null && g.id().equals("voidstone")) {
                    return voidUpgrade(p, hand);
                }
            }
            return ActionResult.PASS;
        });
        // New chunks: the ores (data/vigil/worldgen/placed_feature/ore_*).
        var overworld = net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld();
        for (String id : List.of("ruby", "sapphire", "voidstone", "bloodstone")) {
            net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(overworld, net.minecraft.world.gen.GenerationStep.Feature.UNDERGROUND_ORES,
                    net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.PLACED_FEATURE, Identifier.of("vigil", "ore_" + id)));
        }
        net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(
                net.fabricmc.fabric.api.biome.v1.BiomeSelectors.tag(net.minecraft.registry.tag.BiomeTags.IS_BADLANDS)
                        .or(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.includeByKey(net.minecraft.world.biome.BiomeKeys.DESERT)),
                net.minecraft.world.gen.GenerationStep.Feature.UNDERGROUND_ORES,
                net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.PLACED_FEATURE, Identifier.of("vigil", "ore_topaz")));
    }

    public static List<Gem> all() {
        return new ArrayList<>(GEMS.values());
    }
}
