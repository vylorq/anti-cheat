package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
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
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Artifacts: rare charms found in the structures' chests. Use one while wearing armor and it is set into that armor
 * (the chestplate first, else the helmet, leggings or boots). Two artifacts at most on a player; they work while the
 * armor is worn. When the player dies, every artifact set in their armor is lost.
 */
public final class Artifacts {
    private Artifacts() {
    }

    static final String TAG = "vigil_artifact";
    /** On an armor piece: the artifacts set into it, comma separated. */
    static final String IN_ARMOR = "vigil_artifacts";
    public static final int MAX = 2;

    record Def(String id, Item item, String name, String color, String what) {
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

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final Identifier HEART = Identifier.of("vigil", "artifact_heart");
    private static final Identifier ANCHOR = Identifier.of("vigil", "artifact_anchor");

    public static ItemStack item(String id) {
        Def d = DEFS.get(id);
        if (d == null) {
            return null;
        }
        ItemStack s = new ItemStack(d.item());
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal(d.color() + "✦ " + d.name()).styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Artifact: " + d.what()).styled(x -> x.withItalic(false)),
                Text.literal("§8Use it while wearing armor to set it in.").styled(x -> x.withItalic(false)),
                Text.literal("§8Two at most. Lost when you die.").styled(x -> x.withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.EPIC);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        ItemConv.setTag(s, TAG, id);
        return s;
    }

    private static List<String> in(ItemStack armor) {
        String v = armor.isEmpty() ? null : ItemConv.tag(armor, IN_ARMOR);
        List<String> out = new ArrayList<>();
        if (v != null) {
            for (String id : v.split(",")) {
                if (DEFS.containsKey(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** The artifacts working on this player now (from the armor they wear; two at most). */
    public static List<String> worn(ServerPlayerEntity p) {
        List<String> out = new ArrayList<>();
        for (EquipmentSlot slot : ARMOR) {
            for (String id : in(p.getEquippedStack(slot))) {
                if (out.size() < MAX && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    public static boolean has(ServerPlayerEntity p, String id) {
        return worn(p).contains(id);
    }

    private static void write(ItemStack armor, List<String> ids) {
        List<Text> lore = new ArrayList<>();
        LoreComponent old = armor.get(DataComponentTypes.LORE);
        if (old != null) {
            for (Text t : old.lines()) {
                if (!t.getString().startsWith("✦ ")) {
                    lore.add(t);
                }
            }
        }
        for (String id : ids) {
            Def d = DEFS.get(id);
            lore.add(Text.literal(d.color() + "✦ " + d.name() + " §7- " + d.what()).styled(x -> x.withItalic(false)));
        }
        if (ids.isEmpty()) {
            ItemConv.removeTag(armor, IN_ARMOR);
        } else {
            ItemConv.setTag(armor, IN_ARMOR, String.join(",", ids));
        }
        armor.set(DataComponentTypes.LORE, new LoreComponent(lore));
    }

    private static void use(ServerPlayerEntity p, ItemStack held, String id) {
        List<String> now = worn(p);
        if (now.contains(id)) {
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
        for (EquipmentSlot slot : ARMOR) {
            ItemStack armor = p.getEquippedStack(slot);
            if (armor.isEmpty()) {
                continue;
            }
            List<String> ids = in(armor);
            ids.add(id);
            write(armor, ids);
            held.decrement(1);
            p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_SMITHING_TABLE_USE, SoundCategory.PLAYERS, 0.8f, 1.3f);
            Msg.actionBar(p, "§a" + Msg.trFor(p, "artifacts.set", DEFS.get(id).name()));
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
                write(s, List.of());
                lost = true;
            }
        }
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = p.getEquippedStack(slot);
            if (!s.isEmpty() && ItemConv.tag(s, IN_ARMOR) != null) {
                write(s, List.of());
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
        if (on && !inst.hasModifier(id)) {
            inst.addTemporaryModifier(new EntityAttributeModifier(id, v, EntityAttributeModifier.Operation.ADD_VALUE));
        } else if (!on && inst.hasModifier(id)) {
            inst.removeModifier(id);
            if (p.getHealth() > p.getMaxHealth()) {
                p.setHealth(p.getMaxHealth());
            }
        }
    }

    public static void tick(long ticks) {
        if (ticks % 5 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            List<String> w = worn(p);
            if (w.contains("magnet_ring")) {
                Vec3d to = p.getEntityPos().add(0, 0.5, 0);
                for (ItemEntity it : p.getEntityWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(8), e -> !e.cannotPickup())) {
                    Vec3d d = to.subtract(it.getEntityPos());
                    if (d.lengthSquared() > 1) {
                        it.setVelocity(d.normalize().multiply(0.45));
                        it.velocityModified = true;
                    }
                }
            }
            if (ticks % 20 != 0) {
                continue;
            }
            modifier(p, EntityAttributes.MAX_HEALTH, HEART, 8, w.contains("heart_crystal"));
            modifier(p, EntityAttributes.KNOCKBACK_RESISTANCE, ANCHOR, 1, w.contains("anchor_stone"));
            for (String id : w) {
                switch (id) {
                    case "haste_ring" -> effect(p, StatusEffects.HASTE, 1);
                    case "moon_charm" -> {
                        effect(p, StatusEffects.NIGHT_VISION, 0);
                        if (p.getEntityWorld().isNight()) {
                            effect(p, StatusEffects.STRENGTH, 0);
                        }
                    }
                    case "wind_charm" -> {
                        effect(p, StatusEffects.JUMP_BOOST, 1);
                        effect(p, StatusEffects.SPEED, 0);
                    }
                    case "lucky_clover" -> effect(p, StatusEffects.LUCK, 1);
                    case "ember_core" -> effect(p, StatusEffects.FIRE_RESISTANCE, 0);
                    case "tide_pearl" -> {
                        effect(p, StatusEffects.WATER_BREATHING, 0);
                        effect(p, StatusEffects.DOLPHINS_GRACE, 0);
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
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!Ac.running() || taken <= 0 || source.getSource() != source.getAttacker() || source.isOf(net.minecraft.entity.damage.DamageTypes.THORNS)) {
                return;
            }
            if (source.getAttacker() instanceof ServerPlayerEntity a && entity != a) {
                List<String> w = worn(a);
                if (w.contains("vampire_fang")) {
                    a.heal(taken * 0.2f);
                }
                if (w.contains("frost_amulet")) {
                    entity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
                }
            }
            if (entity instanceof ServerPlayerEntity v && source.getAttacker() instanceof LivingEntity a && a != v
                    && has(v, "thorn_heart")) {
                a.damage(v.getEntityWorld(), v.getDamageSources().thorns(v), taken * 0.25f);
            }
        });
    }
}
