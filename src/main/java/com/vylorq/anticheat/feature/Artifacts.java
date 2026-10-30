package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Artifacts: rare charms found in the structures' chests. Use one while wearing armor and it is set into that armor
 * (the chestplate first, else the helmet, leggings or boots). Two artifacts at most on a player; they work while the
 * armor is worn. When the player dies, every artifact set in their armor is lost.
 * <p>Two of the same artifact combine (sneak and use one) into a Greater one, which works harder. Some pairs worn
 * together make a set with a bonus of its own.
 */
public final class Artifacts {
    private Artifacts() {
    }

    static final String TAG = "vigil_artifact";
    /** On an armor piece: the artifacts set into it, comma separated. */
    static final String IN_ARMOR = "vigil_artifacts";
    static final String LEVEL = "vigil_artifact_lvl";
    public static final int MAX = 2;

    public record Def(String id, Item item, String name, String color, String what) {
    }

    public static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static void add(String id, Item item, String name, String color, String what) {
        DEFS.put(id, new Def(id, item, name, color, what));
    }

    static {
        add("haste_ring", Items.GOLD_NUGGET, "Ring of Haste", "§e", "Haste II: mine much faster.");
        add("moon_charm", Items.AMETHYST_SHARD, "Moon Charm", "§d", "Night Vision; Strength at night.");
        add("anchor_stone", Items.FLINT, "Anchor Stone", "§7", "Nothing can knock you back.");
        add("vampire_fang", Items.GHAST_TEAR, "Vampire Fang", "§4", "Your hits heal you a little.");
        add("wind_charm", Items.BREEZE_ROD, "Wind Charm", "§f", "Jump Boost II and Speed.");
        add("thorn_heart", Items.RESIN_BRICK, "Thorn Heart", "§c", "Whoever hits you takes a quarter back.");
        add("magnet_ring", Items.COPPER_INGOT, "Magnet Ring", "§6", "Drops within 8 blocks fly to you.");
        add("frost_amulet", Items.PRISMARINE_CRYSTALS, "Frost Amulet", "§b", "Your hits slow enemies down.");
        add("lucky_clover", Items.EMERALD, "Lucky Clover", "§a", "Luck II: better loot and fishing.");
        add("heart_crystal", Items.NETHER_STAR, "Heart Crystal", "§c", "+4 hearts.");
        add("ember_core", Items.BLAZE_POWDER, "Ember Core", "§6", "Fire Resistance.");
        add("tide_pearl", Items.NAUTILUS_SHELL, "Tide Pearl", "§3", "Water Breathing and Dolphin's Grace.");
    }

    /** Pairs that make a set when both are worn: id -> (first, second, what it adds). */
    public record Set(String name, String a, String b, String what) {
    }

    public static final List<Set> SETS = List.of(
            new Set("Night Blaze", "ember_core", "moon_charm", "your hits set enemies on fire"),
            new Set("Storm Rider", "wind_charm", "anchor_stone", "no fall damage"),
            new Set("Blood Thorns", "vampire_fang", "thorn_heart", "damage you send back heals you"),
            new Set("Deep Freeze", "frost_amulet", "tide_pearl", "your hits freeze enemies solid"),
            new Set("Prospector", "haste_ring", "lucky_clover", "Haste and Luck one level higher"),
            new Set("Lifeline", "heart_crystal", "magnet_ring", "Regeneration"));

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Identifier HEART = Identifier.of("vigil", "artifact_heart");
    private static final Identifier ANCHOR = Identifier.of("vigil", "artifact_anchor");

    private static String title(Def d, int level) {
        return (level >= 2 ? "Greater " : "") + d.name();
    }

    private static Set setOf(String id) {
        for (Set s : SETS) {
            if (s.a().equals(id) || s.b().equals(id)) {
                return s;
            }
        }
        return null;
    }

    public static ItemStack item(String id) {
        return item(id, 1);
    }

    public static ItemStack item(String id, int level) {
        Def d = DEFS.get(id);
        if (d == null) {
            return null;
        }
        ItemStack s = new ItemStack(d.item());
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(d.color() + "✦ " + title(d, level) + (level >= 2 ? " II" : ""))
                .styled(x -> x.withItalic(false)));
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7Artifact: " + d.what() + (level >= 2 ? " §d(stronger)" : "")).styled(x -> x.withItalic(false)));
        Set set = setOf(id);
        if (set != null) {
            Def other = DEFS.get(set.a().equals(id) ? set.b() : set.a());
            lore.add(Text.literal("§5Set §d" + set.name() + "§5 with " + other.name() + ": " + set.what()).styled(x -> x.withItalic(false)));
        }
        lore.add(Text.literal("§8Use it while wearing armor to set it in.").styled(x -> x.withItalic(false)));
        if (level < 2) {
            lore.add(Text.literal("§8Sneak and use with two of them: a Greater one.").styled(x -> x.withItalic(false)));
        }
        lore.add(Text.literal("§8Two at most. Lost when you die.").styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(lore));
        s.set(DataComponentTypes.RARITY, Rarity.EPIC);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        ItemConv.setTag(s, TAG, id);
        if (level >= 2) {
            ItemConv.setTag(s, LEVEL, "2");
        }
        return s;
    }

    private static int levelOf(ItemStack s) {
        return "2".equals(ItemConv.tag(s, LEVEL)) ? 2 : 1;
    }

    /** What is set into one armor piece: id -> level ("id" or "id@2" in the tag). */
    private static Map<String, Integer> in(ItemStack armor) {
        String v = armor.isEmpty() ? null : ItemConv.tag(armor, IN_ARMOR);
        Map<String, Integer> out = new LinkedHashMap<>();
        if (v != null) {
            for (String e : v.split(",")) {
                String id = e.endsWith("@2") ? e.substring(0, e.length() - 2) : e;
                if (DEFS.containsKey(id)) {
                    out.put(id, e.endsWith("@2") ? 2 : 1);
                }
            }
        }
        return out;
    }

    /** The artifacts working on this player now, with their levels (from the armor they wear; two at most). */
    public static Map<String, Integer> worn(ServerPlayerEntity p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (EquipmentSlot slot : ARMOR) {
            for (var e : in(p.getEquippedStack(slot)).entrySet()) {
                if (out.size() < MAX && !out.containsKey(e.getKey())) {
                    out.put(e.getKey(), e.getValue());
                }
            }
        }
        return out;
    }

    public static boolean has(ServerPlayerEntity p, String id) {
        return worn(p).containsKey(id);
    }

    /** The set this player wears, or null. */
    public static Set set(Map<String, Integer> w) {
        for (Set s : SETS) {
            if (w.containsKey(s.a()) && w.containsKey(s.b())) {
                return s;
            }
        }
        return null;
    }

    private static void write(ItemStack armor, Map<String, Integer> ids) {
        List<Text> lore = new ArrayList<>();
        LoreComponent old = armor.get(DataComponentTypes.LORE);
        if (old != null) {
            for (Text t : old.lines()) {
                if (!t.getString().startsWith("✦ ")) {
                    lore.add(t);
                }
            }
        }
        List<String> tag = new ArrayList<>();
        for (var e : ids.entrySet()) {
            Def d = DEFS.get(e.getKey());
            lore.add(Text.literal(d.color() + "✦ " + title(d, e.getValue()) + " §7- " + d.what()).styled(x -> x.withItalic(false)));
            tag.add(e.getValue() >= 2 ? e.getKey() + "@2" : e.getKey());
        }
        if (ids.isEmpty()) {
            ItemConv.removeTag(armor, IN_ARMOR);
        } else {
            ItemConv.setTag(armor, IN_ARMOR, String.join(",", tag));
        }
        armor.set(DataComponentTypes.LORE, new LoreComponent(lore));
    }

    /** Sneak + use: two of the same (plain) artifact become one Greater artifact. */
    private static boolean combine(ServerPlayerEntity p, ItemStack held, String id) {
        if (levelOf(held) >= 2) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "artifacts.max-level"));
            return true;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s != held && !s.isEmpty() && id.equals(ItemConv.tag(s, TAG)) && levelOf(s) == 1) {
                s.decrement(1);
                held.decrement(1);
                ItemStack greater = item(id, 2);
                if (!inv.insertStack(greater)) {
                    p.dropItem(greater, false);
                }
                p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.7f, 1.4f);
                Msg.actionBar(p, "§d" + Msg.trFor(p, "artifacts.combined", title(DEFS.get(id), 2)));
                Achievements.unlock(p, "feat:greater");
                return true;
            }
        }
        Msg.actionBar(p, "§c" + Msg.trFor(p, "artifacts.need-two"));
        return true;
    }

    private static void use(ServerPlayerEntity p, ItemStack held, String id) {
        if (p.isSneaking()) {
            combine(p, held, id);
            return;
        }
        Map<String, Integer> now = worn(p);
        if (now.containsKey(id)) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "artifacts.already"));
            return;
        }
        int total = 0;
        for (EquipmentSlot slot : ARMOR) {
            total += in(p.getEquippedStack(slot)).size();
        }
        if (total >= MAX) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "artifacts.full", MAX));
            return;
        }
        int level = levelOf(held);
        for (EquipmentSlot slot : ARMOR) {
            ItemStack armor = p.getEquippedStack(slot);
            if (armor.isEmpty()) {
                continue;
            }
            Map<String, Integer> ids = in(armor);
            ids.put(id, level);
            write(armor, ids);
            held.decrement(1);
            Achievements.unlock(p, "artifact:" + id);
            p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_SMITHING_TABLE_USE, SoundCategory.PLAYERS, 0.8f, 1.3f);
            Msg.actionBar(p, "§a" + Msg.trFor(p, "artifacts.set", title(DEFS.get(id), level)));
            Set set = set(worn(p));
            if (set != null && (set.a().equals(id) || set.b().equals(id))) {
                Msg.send(p, "artifacts.set-made", set.name(), set.what());
            }
            return;
        }
        Msg.actionBar(p, "§c" + Msg.trFor(p, "artifacts.no-armor"));
    }

    /** Just before a player dies: every artifact set in their armor (worn or carried) breaks. */
    public static void onDeath(ServerPlayerEntity p) {
        if (p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING) || p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return; // the totem saves them: nothing is lost
        }
        boolean lost = false;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && ItemConv.tag(s, IN_ARMOR) != null) {
                write(s, Map.of());
                lost = true;
            }
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getEquippedStack(slot);
            if (!s.isEmpty() && ItemConv.tag(s, IN_ARMOR) != null) {
                write(s, Map.of());
                lost = true;
            }
        }
        if (lost) {
            Msg.send(p, "artifacts.lost");
        }
    }

    // ---------------------------------------------------------------- what they do

    private static void effect(ServerPlayerEntity p, RegistryEntry<StatusEffect> e, int amp) {
        p.addStatusEffect(new StatusEffectInstance(e, e == StatusEffects.NIGHT_VISION ? 320 : 60, amp, true, false, true));
    }

    private static void modifier(ServerPlayerEntity p, RegistryEntry<EntityAttribute> a, Identifier id, double v, boolean on) {
        var inst = p.getAttributeInstance(a);
        if (inst == null) {
            return;
        }
        var cur = inst.getModifier(id);
        if (cur != null && (!on || cur.value() != v)) {
            inst.removeModifier(id);
            if (p.getHealth() > p.getMaxHealth()) {
                p.setHealth(p.getMaxHealth());
            }
            cur = null;
        }
        if (on && cur == null) {
            inst.addTemporaryModifier(new EntityAttributeModifier(id, v, EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    public static void tick(long ticks) {
        if (ticks % 5 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Map<String, Integer> w = worn(p);
            if (w.containsKey("magnet_ring")) {
                SecretItems.magnetTick(p);
            }
            if (ticks % 20 != 0) {
                continue;
            }
            Set set = set(w);
            int plus = set != null && set.name().equals("Prospector") ? 1 : 0;
            modifier(p, EntityAttributes.MAX_HEALTH, HEART, w.getOrDefault("heart_crystal", 0) * 8, w.containsKey("heart_crystal"));
            modifier(p, EntityAttributes.KNOCKBACK_RESISTANCE, ANCHOR, 1, w.containsKey("anchor_stone"));
            if (set != null && set.name().equals("Lifeline")) {
                effect(p, StatusEffects.REGENERATION, 0);
            }
            for (var e : w.entrySet()) {
                int lv = e.getValue() - 1; // Greater ones are one level higher
                switch (e.getKey()) {
                    case "haste_ring" -> effect(p, StatusEffects.HASTE, 1 + lv + plus);
                    case "moon_charm" -> {
                        effect(p, StatusEffects.NIGHT_VISION, 0);
                        if (p.getEntityWorld().isNight()) {
                            effect(p, StatusEffects.STRENGTH, lv);
                        }
                    }
                    case "anchor_stone" -> {
                        if (lv > 0) {
                            effect(p, StatusEffects.RESISTANCE, 0);
                        }
                    }
                    case "wind_charm" -> {
                        effect(p, StatusEffects.JUMP_BOOST, 1 + lv);
                        effect(p, StatusEffects.SPEED, lv);
                    }
                    case "lucky_clover" -> effect(p, StatusEffects.LUCK, 1 + lv + plus);
                    case "ember_core" -> {
                        effect(p, StatusEffects.FIRE_RESISTANCE, 0);
                        if (lv > 0) {
                            effect(p, StatusEffects.STRENGTH, 0);
                        }
                    }
                    case "tide_pearl" -> {
                        effect(p, StatusEffects.WATER_BREATHING, 0);
                        effect(p, StatusEffects.DOLPHINS_GRACE, 0);
                        if (lv > 0) {
                            effect(p, StatusEffects.CONDUIT_POWER, 0);
                        }
                    }
                    case "magnet_ring" -> {
                        if (lv > 0) {
                            effect(p, StatusEffects.HASTE, 0);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p) {
                ItemStack held = p.getStackInHand(hand);
                String id = ItemConv.tag(held, TAG);
                if (id != null && DEFS.containsKey(id)) {
                    use(p, held, id);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });
        // Storm Rider: no fall damage.
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (Ac.running() && entity instanceof ServerPlayerEntity p && source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_FALL)) {
                Set set = set(worn(p));
                return set == null || !set.name().equals("Storm Rider");
            }
            return true;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!Ac.running() || taken <= 0 || source.getSource() != source.getAttacker() || source.isOf(net.minecraft.entity.damage.DamageTypes.THORNS)) {
                return;
            }
            if (source.getAttacker() instanceof ServerPlayerEntity a && entity != a) {
                Map<String, Integer> w = worn(a);
                Set set = set(w);
                if (w.containsKey("vampire_fang")) {
                    a.heal(taken * (w.get("vampire_fang") >= 2 ? 0.35f : 0.2f));
                }
                if (w.containsKey("frost_amulet")) {
                    entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, w.get("frost_amulet") >= 2 ? 80 : 60,
                            w.get("frost_amulet") >= 2 ? 2 : 1));
                }
                if (set != null && set.name().equals("Deep Freeze")) {
                    entity.setFrozenTicks(Math.max(entity.getFrozenTicks(), entity.getMinFreezeDamageTicks() + 60));
                    entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 3));
                }
                if (set != null && set.name().equals("Night Blaze")) {
                    entity.setOnFireFor(4);
                }
                if (w.getOrDefault("ember_core", 0) >= 2) {
                    entity.setOnFireFor(3);
                }
            }
            if (entity instanceof ServerPlayerEntity v && source.getAttacker() instanceof LivingEntity a && a != v) {
                Map<String, Integer> w = worn(v);
                if (w.containsKey("thorn_heart")) {
                    float back = taken * (w.get("thorn_heart") >= 2 ? 0.45f : 0.25f);
                    a.damage(v.getEntityWorld(), v.getDamageSources().thorns(v), back);
                    Set set = set(w);
                    if (set != null && set.name().equals("Blood Thorns")) {
                        v.heal(back * 0.5f);
                    }
                }
            }
        });
    }
}
