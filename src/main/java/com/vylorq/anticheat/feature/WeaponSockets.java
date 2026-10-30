package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gem sockets in weapons: a gem in one hand and a sword, axe, spear, mace, trident or bow in the other (or /socket
 * &lt;gem&gt; with the weapon in hand) sets the gem in. Two different gems at most per weapon; they stay through death.
 */
public final class WeaponSockets {
    private WeaponSockets() {
    }

    static final String TAG = "vigil_wsockets";
    public static final int MAX = 2;

    /** gem -> what it does in a weapon. */
    public static final Map<String, String> WHAT = new LinkedHashMap<>();

    static {
        WHAT.put("ruby", "+1.5 attack damage");
        WHAT.put("topaz", "attacks faster");
        WHAT.put("sapphire", "hits give you Speed");
        WHAT.put("voidstone", "hits wither the target");
        WHAT.put("bloodstone", "hits heal you a little");
        WHAT.put("emberite", "hits set the target on fire");
        WHAT.put("frostshard", "hits slow the target");
        WHAT.put("starmetal", "hits sometimes strike a star: the target glows");
        WHAT.put("soulstone", "hits weaken the target");
    }

    public static boolean isWeapon(ItemStack s) {
        if (s.isEmpty()) {
            return false;
        }
        String path = Registries.ITEM.getId(s.getItem()).getPath();
        return path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear") || path.equals("mace")
                || path.equals("trident") || path.equals("bow") || path.equals("crossbow");
    }

    public static List<String> of(ItemStack weapon) {
        List<String> out = new ArrayList<>();
        String v = weapon.isEmpty() ? null : ItemConv.tag(weapon, TAG);
        if (v != null) {
            for (String g : v.split(",")) {
                if (WHAT.containsKey(g)) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    /** Sets the gem (one of the gem stack) into the weapon. True if the player was told something. */
    public static boolean socket(ServerPlayerEntity p, ItemStack weapon, ItemStack gem, String g) {
        if (!isWeapon(weapon) || !WHAT.containsKey(g)) {
            return false;
        }
        List<String> now = of(weapon);
        if (now.contains(g)) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "wsocket.same"));
            return true;
        }
        if (now.size() >= MAX) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "wsocket.full", MAX));
            return true;
        }
        if (g.equals("ruby") || g.equals("topaz")) {
            var b = AttributeModifiersComponent.builder();
            for (var e : weapon.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT).modifiers()) {
                b.add(e.attribute(), e.modifier(), e.slot());
            }
            if (g.equals("ruby")) {
                b.add(EntityAttributes.ATTACK_DAMAGE, new EntityAttributeModifier(Identifier.of("vigil", "wsocket_ruby"), 1.5,
                        EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND);
            } else {
                b.add(EntityAttributes.ATTACK_SPEED, new EntityAttributeModifier(Identifier.of("vigil", "wsocket_topaz"), 0.3,
                        EntityAttributeModifier.Operation.ADD_VALUE), AttributeModifierSlot.MAINHAND);
            }
            weapon.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, b.build());
        }
        now.add(g);
        ItemConv.setTag(weapon, TAG, String.join(",", now));
        List<Text> lore = new ArrayList<>(weapon.getOrDefault(DataComponentTypes.LORE, LoreComponent.DEFAULT).lines());
        var def = Gems.GEMS.get(g);
        lore.add(Text.literal((def == null ? "§f" : def.color()) + "◆ " + (def == null ? g : def.name()) + ": §7" + WHAT.get(g))
                .styled(x -> x.withItalic(false)));
        weapon.set(DataComponentTypes.LORE, new LoreComponent(lore));
        gem.decrement(1);
        p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.4f);
        Msg.actionBar(p, "§a" + Msg.trFor(p, "wsocket.done"));
        return true;
    }

    /** /socket &lt;gem&gt;: the weapon in the main hand, the gem from anywhere in the inventory. */
    public static int command(ServerPlayerEntity p, String g) {
        ItemStack weapon = p.getMainHandStack();
        if (!isWeapon(weapon)) {
            Msg.send(p, "wsocket.hold");
            return 0;
        }
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            var gem = Gems.gemOf(s);
            if (gem != null && gem.id().equals(g)) {
                return socket(p, weapon, s, g) ? 1 : 0;
            }
        }
        Msg.send(p, "wsocket.no-gem");
        return 0;
    }

    public static void register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(
                net.minecraft.server.command.CommandManager.literal("socket")
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p != null) {
                                Msg.send(p, "wsocket.help");
                            }
                            return 1;
                        })
                        .then(net.minecraft.server.command.CommandManager.argument("gem", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    WHAT.keySet().forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> {
                                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                                    return p == null ? 0 : command(p, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "gem"));
                                }))));
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((target, source, base, taken, blocked) -> {
            if (!Ac.running() || taken <= 0 || !(source.getAttacker() instanceof ServerPlayerEntity a) || target == a) {
                return;
            }
            List<String> gems = of(a.getMainHandStack());
            if (gems.isEmpty()) {
                return;
            }
            ServerWorld w = a.getEntityWorld();
            for (String g : gems) {
                switch (g) {
                    case "sapphire" -> a.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 0));
                    case "voidstone" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
                    case "bloodstone" -> a.heal(taken * 0.1f);
                    case "emberite" -> target.setOnFireFor(4);
                    case "frostshard" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
                    case "soulstone" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 0));
                    case "starmetal" -> {
                        if (w.getRandom().nextFloat() < 0.15f) {
                            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
                            if (bolt != null) {
                                bolt.setCosmetic(true); // a flash and a crack only: no fire, no damage
                                bolt.refreshPositionAfterTeleport(target.getX(), target.getY(), target.getZ());
                                w.spawnEntity(bolt);
                            }
                            target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 120, 0));
                            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 0));
                        }
                    }
                    default -> {
                    }
                }
            }
        });
    }

    static boolean has(LivingEntity e, String g) {
        return e instanceof ServerPlayerEntity p && of(p.getMainHandStack()).contains(g);
    }
}
