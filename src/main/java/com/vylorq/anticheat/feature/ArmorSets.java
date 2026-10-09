package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Armour sets. Four are crafted (an armour piece in the middle of eight of the set's material, see
 * data/vigil/recipe/armor_*); eight are secret, one for each boss: the boss drops pieces of its own set and the chests
 * of its structure sometimes hold one. Each set has its own look (a trim, or a dye) and stats, and wearing all four
 * pieces gives the set's bonus.
 */
public final class ArmorSets {
    private ArmorSets() {
    }

    static final String TAG = "vigil_armor";
    static final String READY = "vigil_armor_v";
    static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    static final String[] PIECE = {"Helm", "Chestplate", "Leggings", "Boots"};
    static final String[] ICON = {"helmet", "chestplate", "leggings", "boots"};

    /** What the full set does to whoever hits its wearer. */
    enum OnHit { NONE, BURN, LIGHTNING, FREEZE, THORNS }

    /**
     * @param base     leather, chainmail, iron, gold, diamond or netherite
     * @param armor    armour points per piece (head, chest, legs, feet)
     * @param health   extra max health per piece
     * @param speed    movement speed per piece (fraction, e.g. 0.03)
     * @param trim     trim pattern and material (null: none), or a dye colour for leather
     * @param effects  the full-set bonus (effect, amplifier)
     */
    record Set(String id, String name, String color, boolean secret, String source, Item[] base, int[] armor, double toughness,
               double knockback, double health, double speed, String trimPattern, String trimMaterial, int dye,
               List<Map.Entry<RegistryEntry<StatusEffect>, Integer>> effects, OnHit onHit, String bonus) {
    }

    private static Item[] items(String kind) {
        return switch (kind) {
            case "leather" -> new Item[]{Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
            case "chain" -> new Item[]{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
            case "iron" -> new Item[]{Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            case "gold" -> new Item[]{Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS};
            case "diamond" -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
            default -> new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
        };
    }

    private static Map.Entry<RegistryEntry<StatusEffect>, Integer> fx(RegistryEntry<StatusEffect> e, int amp) {
        return Map.entry(e, amp);
    }

    static final Map<String, Set> SETS = new LinkedHashMap<>();

    private static void add(Set s) {
        SETS.put(s.id(), s);
    }

    static {
        // ---- crafted
        add(new Set("emerald", "Emerald Armor", "§a", false, "craft", items("iron"), new int[]{3, 8, 6, 3}, 1, 0, 0, 0,
                "sentry", "emerald", 0, List.of(fx(StatusEffects.HERO_OF_THE_VILLAGE, 1), fx(StatusEffects.LUCK, 0)), OnHit.NONE,
                "Hero of the Village, Luck"));
        add(new Set("obsidian", "Obsidian Armor", "§5", false, "craft", items("diamond"), new int[]{4, 9, 7, 4}, 3, 0.15, 0, -0.02,
                "rib", "netherite", 0, List.of(fx(StatusEffects.FIRE_RESISTANCE, 0), fx(StatusEffects.RESISTANCE, 0)), OnHit.NONE,
                "Fire Resistance, Resistance"));
        add(new Set("phantom", "Phantom Armor", "§b", false, "craft", items("chain"), new int[]{2, 5, 4, 2}, 0, 0, 0, 0.03,
                null, null, 0x9FD8E8, List.of(fx(StatusEffects.SLOW_FALLING, 0), fx(StatusEffects.SPEED, 0), fx(StatusEffects.JUMP_BOOST, 0)),
                OnHit.NONE, "Slow Falling, Speed, Jump Boost"));
        add(new Set("ruby", "Ruby Armor", "§c", false, "craft", items("diamond"), new int[]{3, 8, 6, 3}, 2.5, 0.05, 0, 0,
                null, null, 0, List.of(fx(StatusEffects.STRENGTH, 0)), OnHit.NONE, "Strength"));
        add(new Set("sapphire", "Sapphire Armor", "§9", false, "craft", items("diamond"), new int[]{3, 8, 6, 3}, 2, 0, 0, 0,
                null, null, 0, List.of(fx(StatusEffects.WATER_BREATHING, 0), fx(StatusEffects.HASTE, 0)), OnHit.NONE,
                "Water Breathing, Haste"));
        add(new Set("tide", "Tide Armor", "§3", false, "craft", items("iron"), new int[]{3, 7, 6, 3}, 1, 0, 0, 0,
                "tide", "diamond", 0, List.of(fx(StatusEffects.WATER_BREATHING, 0), fx(StatusEffects.DOLPHINS_GRACE, 0)), OnHit.NONE,
                "Water Breathing, Dolphin's Grace"));
        // ---- secret (bosses and their structures)
        add(new Set("abyssal", "Abyssal Armor", "§3§l", true, "drowned_warden", items("netherite"), new int[]{4, 9, 7, 4}, 3.5, 0.1, 2, 0,
                "tide", "lapis", 0, List.of(fx(StatusEffects.CONDUIT_POWER, 0), fx(StatusEffects.DOLPHINS_GRACE, 0),
                fx(StatusEffects.WATER_BREATHING, 0)), OnHit.NONE, "Conduit Power, Dolphin's Grace, Water Breathing"));
        add(new Set("colossus", "Colossus Armor", "§8§l", true, "deepslate_colossus", items("netherite"), new int[]{5, 10, 8, 5}, 4, 0.25, 2,
                -0.03, "bolt", "iron", 0, List.of(fx(StatusEffects.RESISTANCE, 1)), OnHit.NONE, "Resistance II, can't be knocked back"));
        add(new Set("tempest", "Tempest Armor", "§b§l", true, "storm_phantom", items("netherite"), new int[]{4, 8, 7, 4}, 3, 0.1, 0, 0.04,
                "flow", "copper", 0, List.of(fx(StatusEffects.SPEED, 1), fx(StatusEffects.JUMP_BOOST, 1)), OnHit.LIGHTNING,
                "Speed II, Jump Boost II; lightning strikes those who hit you"));
        add(new Set("inferno", "Inferno Armor", "§6§l", true, "forgemaster", items("netherite"), new int[]{4, 9, 7, 4}, 3, 0.1, 2, 0,
                "snout", "gold", 0, List.of(fx(StatusEffects.FIRE_RESISTANCE, 0), fx(StatusEffects.STRENGTH, 0)), OnHit.BURN,
                "Fire Resistance, Strength; sets those who hit you on fire"));
        add(new Set("dune", "Dune Armor", "§e§l", true, "sand_colossus", items("gold"), new int[]{4, 8, 6, 4}, 2.5, 0.05, 2, 0.03,
                "dune", "gold", 0, List.of(fx(StatusEffects.HASTE, 1), fx(StatusEffects.SPEED, 0)), OnHit.NONE, "Haste II, Speed"));
        add(new Set("glacier", "Glacier Armor", "§f§l", true, "frost_titan", items("diamond"), new int[]{4, 9, 7, 4}, 3, 0.1, 2, 0,
                "ward", "quartz", 0, List.of(fx(StatusEffects.RESISTANCE, 0)), OnHit.FREEZE,
                "Resistance; freezes those who hit you"));
        add(new Set("thornback", "Thornback Armor", "§2§l", true, "thornback_beast", items("netherite"), new int[]{4, 9, 7, 4}, 3, 0.1, 2, 0,
                "wild", "emerald", 0, List.of(fx(StatusEffects.REGENERATION, 0)), OnHit.THORNS,
                "Regeneration; a third of every hit goes back to the attacker"));
        add(new Set("hollow", "Hollow Armor", "§5§l", true, "hollow_watcher", items("netherite"), new int[]{4, 9, 7, 4}, 3, 0.1, 2, 0.02,
                "eye", "amethyst", 0, List.of(fx(StatusEffects.NIGHT_VISION, 0)), OnHit.NONE,
                "Night Vision; invisible while sneaking"));
    }

    /** The secret set of a boss, or of the structure it guards. */
    public static Set ofBoss(String kind) {
        if (Bosses.TEMPEST_LORD.equals(kind)) {
            kind = "storm_phantom";
        }
        for (Set s : SETS.values()) {
            if (s.secret() && s.source().equals(kind)) {
                return s;
            }
        }
        return null;
    }

    private static final Map<String, String> STRUCTURES = Map.of("sunken_vault", "drowned_warden", "buried_vault", "deepslate_colossus",
            "sky_citadel", "storm_phantom", "nether_forge", "forgemaster", "desert_tomb", "sand_colossus", "frozen_bastion", "frost_titan",
            "overgrown_labyrinth", "thornback_beast", "watchers_hollow", "hollow_watcher");

    // ---------------------------------------------------------------- the items

    /** One piece (0 helm, 1 chest, 2 legs, 3 boots) of a set. */
    public static ItemStack piece(Set s, int slot) {
        ItemStack st = new ItemStack(s.base()[slot]);
        st.set(DataComponentTypes.CUSTOM_NAME, Text.literal(s.color() + s.name().replace("Armor", PIECE[slot])).styled(x -> x.withItalic(false)));
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7" + (s.secret() ? "Secret set" : "Crafted set") + " · " + s.name()).styled(x -> x.withItalic(false)));
        lore.add(Text.literal("§8Full set: §7" + s.bonus()).styled(x -> x.withItalic(false)));
        st.set(DataComponentTypes.LORE, new LoreComponent(lore));
        st.set(DataComponentTypes.RARITY, s.secret() ? Rarity.EPIC : Rarity.UNCOMMON);
        EquipmentSlot es = SLOTS[slot];
        AttributeModifierSlot ms = AttributeModifierSlot.forEquipmentSlot(es);
        var b = AttributeModifiersComponent.builder();
        mod(b, EntityAttributes.ARMOR, s, slot, "armor", s.armor()[slot], ms);
        if (s.toughness() > 0) {
            mod(b, EntityAttributes.ARMOR_TOUGHNESS, s, slot, "toughness", s.toughness(), ms);
        }
        if (s.knockback() > 0) {
            mod(b, EntityAttributes.KNOCKBACK_RESISTANCE, s, slot, "knockback", s.knockback(), ms);
        }
        if (s.health() > 0) {
            mod(b, EntityAttributes.MAX_HEALTH, s, slot, "health", s.health(), ms);
        }
        if (s.speed() != 0) {
            b.add(EntityAttributes.MOVEMENT_SPEED, new EntityAttributeModifier(Identifier.of("vigil", "armor_" + s.id() + "_speed_" + slot),
                    s.speed(), EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE), ms);
        }
        st.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());
        // Its own look (the resource pack): the icon, and the armour as worn.
        com.vylorq.anticheat.util.PackIds.apply(st, "armor_" + s.id() + "_" + ICON[slot]);
        st.set(DataComponentTypes.EQUIPPABLE, net.minecraft.component.type.EquippableComponent.builder(es)
                .equipSound(s.base()[0] == Items.NETHERITE_HELMET ? net.minecraft.sound.SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE
                        : net.minecraft.sound.SoundEvents.ITEM_ARMOR_EQUIP_DIAMOND)
                .model(net.minecraft.registry.RegistryKey.of(net.minecraft.item.equipment.EquipmentAssetKeys.REGISTRY_KEY,
                        Identifier.of("vigil", com.vylorq.anticheat.util.PackIds.code("armor_" + s.id()))))
                .build());
        ItemConv.setTag(st, TAG, s.id());
        ItemConv.setTag(st, READY, "1");
        return st;
    }

    private static void mod(AttributeModifiersComponent.Builder b, RegistryEntry<EntityAttribute> a, Set s, int slot, String what,
                            double v, AttributeModifierSlot ms) {
        b.add(a, new EntityAttributeModifier(Identifier.of("vigil", "armor_" + s.id() + "_" + what + "_" + slot), v,
                EntityAttributeModifier.Operation.ADD_VALUE), ms);
    }

    public static List<ItemStack> full(Set s) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            out.add(piece(s, i));
        }
        return out;
    }

    public static Set setOf(ItemStack st) {
        String id = st.isEmpty() ? null : ItemConv.tag(st, TAG);
        return id == null ? null : SETS.get(id);
    }

    /** The set they wear all four pieces of, or null. */
    public static Set wearing(LivingEntity e) {
        Set s = null;
        for (EquipmentSlot slot : SLOTS) {
            Set here = setOf(e.getEquippedStack(slot));
            if (here == null || (s != null && s != here)) {
                return null;
            }
            s = here;
        }
        return s;
    }

    /** A crafted piece comes out of the crafting table plain (just tagged): it gets its stats and look here. */
    static void finish(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty() || ItemConv.tag(st, READY) != null) {
                continue;
            }
            Set s = setOf(st);
            if (s == null) {
                continue;
            }
            for (int slot = 0; slot < 4; slot++) {
                if (st.isOf(s.base()[slot])) {
                    ItemStack done = piece(s, slot);
                    done.set(DataComponentTypes.ENCHANTMENTS, st.get(DataComponentTypes.ENCHANTMENTS));
                    inv.setStack(i, done);
                    break;
                }
            }
        }
    }

    /** A random piece of a boss's set (bosses always drop one). */
    public static ItemStack bossDrop(String kind, Random r) {
        Set s = ofBoss(kind);
        return s == null ? ItemStack.EMPTY : piece(s, r.nextInt(4));
    }

    // ---------------------------------------------------------------- full-set bonuses

    private static void bonus(ServerPlayerEntity p) {
        Set s = wearing(p);
        if (s == null) {
            return;
        }
        for (var e : s.effects()) {
            p.addStatusEffect(new StatusEffectInstance(e.getKey(), e.getKey() == StatusEffects.NIGHT_VISION ? 320 : 60, e.getValue(),
                    true, false, true));
        }
        if (s.id().equals("hollow") && p.isSneaking()) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 30, 0, true, false, true));
        }
    }

    private static void hit(LivingEntity wearer, LivingEntity attacker, float taken) {
        Set s = wearing(wearer);
        if (s == null || attacker == wearer || !(wearer.getEntityWorld() instanceof ServerWorld w)) {
            return;
        }
        switch (s.onHit()) {
            case BURN -> attacker.setOnFireFor(4);
            case FREEZE -> {
                attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2));
                attacker.setFrozenTicks(Math.max(attacker.getFrozenTicks(), 200));
            }
            case THORNS -> {
                if (taken > 0) {
                    attacker.damage(w, w.getDamageSources().thorns(wearer), taken / 3f);
                }
            }
            case LIGHTNING -> {
                if (w.getRandom().nextInt(5) == 0) {
                    var bolt = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(w, net.minecraft.entity.SpawnReason.TRIGGERED);
                    if (bolt != null) {
                        bolt.refreshPositionAndAngles(attacker.getX(), attacker.getY(), attacker.getZ(), 0, 0);
                        bolt.setCosmetic(true);
                        w.spawnEntity(bolt);
                    }
                    attacker.damage(w, w.getDamageSources().lightningBolt(), 4f);
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- hooks

    public static void tick(long ticks) {
        if (ticks % 20 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            finish(p);
            Gems.finish(p);
            bonus(p);
        }
    }

    private static boolean reacting;

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!Ac.running() || reacting || !(source.getAttacker() instanceof LivingEntity attacker) || source.getSource() != attacker) {
                return;
            }
            reacting = true;
            try {
                hit(entity, attacker, taken);
            } finally {
                reacting = false;
            }
        });
        // Secret pieces in their structures' chests (one chest in 8; the small rooms one in 20).
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, drops) -> {
            if (!Ac.running() || table.getKey().isEmpty()) {
                return;
            }
            var id = table.getKey().get().getValue();
            if (!"vigil".equals(id.getNamespace()) || !id.getPath().startsWith("chests/")) {
                return;
            }
            String name = id.getPath().substring("chests/".length());
            boolean room = name.endsWith("_room");
            String kind = STRUCTURES.get(room ? name.substring(0, name.length() - 5) : name);
            Set s = kind == null ? null : ofBoss(kind);
            if (s != null && context.getRandom().nextInt(room ? 20 : 8) == 0) {
                drops.add(piece(s, context.getRandom().nextInt(4)));
            }
        });
    }

    public static List<Set> all() {
        return new ArrayList<>(SETS.values());
    }
}
