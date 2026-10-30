package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dragons. A Dragon Egg (very rare, from the End's structures) is yours once you use it. Keep it warm with blaze
 * powder until it hatches; then feed your dragon meat and fish and it grows: Hatchling, Young, Adult. Call it with
 * /dragon (or from its menu) and it flies at your shoulder and fights for you, never against players:
 * <ul>
 * <li>Hatchling: breathes small flames at monsters that go for you; gives you Night Vision.</li>
 * <li>Young: hotter flames, and you can't burn; sneak while falling to glide on its wings.</li>
 * <li>Adult: a great fire breath, a roar that throws monsters back, and you heal while it's well fed.</li>
 * </ul>
 * It gets hungry while it's out: a hungry dragon won't fight until it's fed. One dragon each.
 */
public final class Dragons {
    private Dragons() {
    }

    static final String EGG = "vigil_dragon_egg";
    static final String TAG = "vigil_dragon";
    /** Blaze powder to hatch the egg; growth to become Young; growth to become Adult. */
    public static final int WARMTH = 10, YOUNG = 120, ADULT = 400;

    public static final class Dragon {
        public String stage = "egg";
        public int warmth;
        public int growth;
        public int hunger = 60;
        public String name = "Dragon";
    }

    private static Map<String, Dragon> data;
    private static boolean dirty;
    private static final Map<UUID, MobEntity> OUT = new HashMap<>();
    private static final Map<UUID, Long> BREATH = new HashMap<>();
    private static final Map<UUID, Long> ROAR = new HashMap<>();

    private static Path file() {
        return Ac.get().dir.resolve("dragons.json");
    }

    private static Map<String, Dragon> data() {
        if (data == null) {
            try {
                Path f = file();
                Map<String, Dragon> d = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                        new com.google.gson.reflect.TypeToken<Map<String, Dragon>>() { }.getType()) : null;
                data = d == null ? new HashMap<>() : d;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read dragons", e);
                data = new HashMap<>();
            }
        }
        return data;
    }

    static void save() {
        if (!dirty || data == null) {
            return;
        }
        try {
            Files.writeString(file(), ConfigManager.GSON.toJson(data), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            Ac.LOG.warn("Could not save dragons", e);
        }
    }

    public static Dragon of(ServerPlayerEntity p) {
        return data().get(p.getUuidAsString());
    }

    public static boolean out(ServerPlayerEntity p) {
        MobEntity m = OUT.get(p.getUuid());
        return m != null && !m.isRemoved();
    }

    public static ItemStack egg() {
        ItemStack s = new ItemStack(Items.FIREWORK_STAR);
        s.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§c§lDragon Egg").styled(x -> x.withItalic(false)));
        s.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Something is moving inside, and it's warm.").styled(x -> x.withItalic(false)),
                Text.literal("§8Use it to make it yours, then type /dragon.").styled(x -> x.withItalic(false)))));
        s.set(DataComponentTypes.RARITY, Rarity.EPIC);
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        s.set(DataComponentTypes.MAX_STACK_SIZE, 1);
        s.remove(DataComponentTypes.FIREWORK_EXPLOSION);
        ItemConv.setTag(s, EGG, "1");
        return s;
    }

    public static String stageName(ServerPlayerEntity p, Dragon d) {
        return Msg.trFor(p, "dragon.stage." + d.stage);
    }

    /** Growth needed for the next stage (0 at the top). */
    public static int nextAt(Dragon d) {
        return switch (d.stage) {
            case "hatchling" -> YOUNG;
            case "young" -> ADULT;
            default -> 0;
        };
    }

    private static int level(Dragon d) {
        return switch (d.stage) {
            case "hatchling" -> 1;
            case "young" -> 2;
            case "adult" -> 3;
            default -> 0;
        };
    }

    // ---------------------------------------------------------------- feeding

    /** What this food gives: {hunger, growth}, or null if a dragon won't eat it. */
    private static int[] food(Item i) {
        if (i == Items.BEEF || i == Items.PORKCHOP || i == Items.MUTTON) return new int[]{14, 5};
        if (i == Items.COOKED_BEEF || i == Items.COOKED_PORKCHOP || i == Items.COOKED_MUTTON) return new int[]{20, 7};
        if (i == Items.CHICKEN || i == Items.RABBIT || i == Items.COD || i == Items.SALMON) return new int[]{10, 3};
        if (i == Items.COOKED_CHICKEN || i == Items.COOKED_RABBIT || i == Items.COOKED_COD || i == Items.COOKED_SALMON) return new int[]{14, 5};
        if (i == Items.GOLDEN_CARROT) return new int[]{10, 8};
        if (i == Items.MAGMA_CREAM) return new int[]{6, 10};
        return null;
    }

    /** Feeds one of whatever it can eat from the inventory (blaze powder for an egg). */
    public static void feed(ServerPlayerEntity p) {
        Dragon d = of(p);
        if (d == null) {
            Msg.send(p, "dragon.none");
            return;
        }
        var inv = p.getInventory();
        if (d.stage.equals("egg")) {
            for (int i = 0; i < inv.size(); i++) {
                ItemStack s = inv.getStack(i);
                if (s.isOf(Items.BLAZE_POWDER)) {
                    s.decrement(1);
                    d.warmth++;
                    dirty = true;
                    p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 0.6f, 1.4f);
                    if (d.warmth >= WARMTH) {
                        d.stage = "hatchling";
                        d.hunger = 60;
                        p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_SNIFFER_EGG_HATCH, SoundCategory.PLAYERS, 1f, 0.8f);
                        Msg.send(p, "dragon.hatched");
                    } else {
                        Msg.actionBar(p, "§6" + Msg.trFor(p, "dragon.warmed", d.warmth, WARMTH));
                    }
                    return;
                }
            }
            Msg.actionBar(p, "§c" + Msg.trFor(p, "dragon.need-blaze"));
            return;
        }
        if (d.hunger >= 100) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "dragon.full"));
            return;
        }
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            int[] f = s.isEmpty() ? null : food(s.getItem());
            if (f != null) {
                s.decrement(1);
                d.hunger = Math.min(100, d.hunger + f[0]);
                grow(p, d, f[1]);
                MobEntity m = OUT.get(p.getUuid());
                if (m != null && m.getEntityWorld() instanceof ServerWorld w) {
                    w.spawnParticles(ParticleTypes.HEART, m.getX(), m.getY() + 0.8, m.getZ(), 3, 0.3, 0.3, 0.3, 0);
                }
                p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_GENERIC_EAT.value(), SoundCategory.PLAYERS, 0.8f, 0.7f);
                Msg.actionBar(p, "§a" + Msg.trFor(p, "dragon.fed", d.hunger));
                return;
            }
        }
        Msg.actionBar(p, "§c" + Msg.trFor(p, "dragon.need-food"));
    }

    private static void grow(ServerPlayerEntity p, Dragon d, int amount) {
        if (d.stage.equals("egg") || d.stage.equals("adult")) {
            return;
        }
        d.growth += amount;
        dirty = true;
        String before = d.stage;
        if (d.stage.equals("hatchling") && d.growth >= YOUNG) {
            d.stage = "young";
        } else if (d.stage.equals("young") && d.growth >= ADULT) {
            d.stage = "adult";
        }
        if (!before.equals(d.stage)) {
            Msg.send(p, "dragon.grew", d.name, stageName(p, d));
            p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 0.5f, 1.6f);
            if (out(p)) {
                // A bigger body for the bigger dragon.
                dismiss(p, false);
                summon(p);
            }
        }
    }

    // ---------------------------------------------------------------- out and about

    public static void summon(ServerPlayerEntity p) {
        Dragon d = of(p);
        if (d == null) {
            Msg.send(p, "dragon.none");
            return;
        }
        if (d.stage.equals("egg")) {
            Msg.send(p, "dragon.still-egg", d.warmth, WARMTH);
            return;
        }
        if (out(p)) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        MobEntity m = EntityType.ALLAY.create(w, SpawnReason.MOB_SUMMONED);
        if (m == null) {
            return;
        }
        m.refreshPositionAndAngles(p.getX(), p.getY() + 2, p.getZ(), p.getYaw(), 0);
        m.setAiDisabled(true);
        m.setNoGravity(true);
        m.setInvulnerable(true);
        m.setSilent(true);
        m.setPersistent();
        m.addCommandTag(TAG);
        m.addCommandTag(TAG + ":" + p.getUuidAsString());
        m.setCustomName(Text.literal("§c" + d.name));
        m.setCustomNameVisible(false);
        ModelMobs.attach(m, "dragon_" + d.stage);
        OUT.put(p.getUuid(), m); // first, so it isn't taken for a leftover as it loads
        w.spawnEntity(m);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 0.6f, 1.5f);
        Msg.actionBar(p, "§6" + Msg.trFor(p, "dragon.called", d.name));
    }

    public static void dismiss(ServerPlayerEntity p, boolean tell) {
        MobEntity m = OUT.remove(p.getUuid());
        if (m != null && !m.isRemoved()) {
            m.discard();
            if (tell) {
                Msg.actionBar(p, "§7" + Msg.trFor(p, "dragon.home", of(p) == null ? "" : of(p).name));
            }
        }
    }

    private static boolean isTarget(LivingEntity e, ServerPlayerEntity owner) {
        return e instanceof Monster && e.isAlive() && !e.getCommandTags().contains(TAG) && !(e instanceof ServerPlayerEntity);
    }

    private static void breathe(ServerWorld w, MobEntity m, ServerPlayerEntity p, Dragon d, LivingEntity target) {
        Vec3d from = m.getEntityPos().add(0, 0.5, 0);
        Vec3d to = target.getEntityPos().add(0, target.getHeight() / 2, 0);
        Vec3d step = to.subtract(from);
        int n = (int) Math.ceil(step.length() * 3);
        for (int i = 0; i <= n; i++) {
            Vec3d at = from.add(step.multiply(i / (double) n));
            w.spawnParticles(level(d) >= 3 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, at.x, at.y, at.z, 2, 0.08, 0.08, 0.08, 0.01);
        }
        float dmg = switch (level(d)) {
            case 1 -> 3f;
            case 2 -> 5f;
            default -> 8f;
        };
        target.damage(w, w.getDamageSources().playerAttack(p), dmg);
        target.setOnFireFor(2 + level(d));
        w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.6f, 0.7f);
        if (!target.isAlive()) {
            grow(p, d, 2);
        }
    }

    private static void roar(ServerWorld w, MobEntity m, ServerPlayerEntity p, List<LivingEntity> near) {
        w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.PLAYERS, 1f, 1.2f);
        w.spawnParticles(ParticleTypes.EXPLOSION, m.getX(), m.getY(), m.getZ(), 4, 1, 0.5, 1, 0);
        for (LivingEntity e : near) {
            Vec3d away = e.getEntityPos().subtract(p.getEntityPos()).normalize().multiply(1.4);
            e.addVelocity(away.x, 0.5, away.z);
            e.velocityModified = true;
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0));
            e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1));
        }
    }

    public static void tick(long ticks) {
        if (ticks % 1200 == 0) {
            save();
        }
        if (OUT.isEmpty() || ticks % 2 != 0) {
            return;
        }
        var it = OUT.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            MobEntity m = e.getValue();
            ServerPlayerEntity p = Ac.server().getPlayerManager().getPlayer(e.getKey());
            Dragon d = p == null ? null : of(p);
            if (m.isRemoved() || p == null || d == null || p.isSpectator()) {
                if (!m.isRemoved()) {
                    m.discard();
                }
                it.remove();
                continue;
            }
            int lv = level(d);
            // Fly at the owner's shoulder (higher as it grows).
            double yaw = Math.toRadians(p.getYaw());
            // Big as it grows: the adult (seven blocks tall) flies well above and behind.
            double side = lv == 3 ? 4.5 : 1.5 + lv, back = lv == 3 ? 6 : 1 + lv, up = lv == 3 ? 4.5 : 1.8 + lv * 0.8;
            Vec3d want = p.getEntityPos().add(-Math.sin(yaw) * -back + Math.cos(yaw) * side, up, Math.cos(yaw) * -back + Math.sin(yaw) * side);
            if (m.getEntityWorld() != p.getEntityWorld() || m.squaredDistanceTo(want) > 30 * 30) {
                com.vylorq.anticheat.util.Mc.teleport(m, p.getEntityWorld(), want.x, want.y, want.z, p.getYaw(), 0);
                continue;
            }
            Vec3d now = m.getEntityPos();
            Vec3d next = now.add(want.subtract(now).multiply(0.25));
            m.refreshPositionAndAngles(next.x, next.y, next.z, p.getYaw(), 0);
            m.setHeadYaw(p.getYaw());
            m.setBodyYaw(p.getYaw());
            ServerWorld w = p.getEntityWorld();
            if (ticks % 20 != 0) {
                continue;
            }
            // Gifts to its rider-friend while it's out.
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 320, 0, true, false, true));
            if (lv >= 2) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 60, 0, true, false, true));
                if (p.isSneaking() && !p.isOnGround() && p.getVelocity().y < -0.4) {
                    p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 40, 0, true, false, true));
                    Movement.grace(p, 40);
                }
            }
            if (lv >= 3 && d.hunger > 50) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 60, 0, true, false, true));
            }
            // Hunger: one point every half a minute while it's out.
            if (ticks % 600 == 0 && d.hunger > 0) {
                d.hunger--;
                dirty = true;
                if (d.hunger == 20 || d.hunger == 0) {
                    Msg.send(p, d.hunger == 0 ? "dragon.starving" : "dragon.hungry", d.name);
                }
            }
            if (d.hunger <= 0) {
                continue;
            }
            List<LivingEntity> near = w.getEntitiesByClass(LivingEntity.class, new Box(p.getBlockPos()).expand(10), x -> isTarget(x, p));
            if (near.isEmpty()) {
                continue;
            }
            long t = w.getTime();
            if (t >= BREATH.getOrDefault(p.getUuid(), 0L)) {
                LivingEntity target = null;
                if (p.getAttacking() != null && isTarget(p.getAttacking(), p) && p.getAttacking().squaredDistanceTo(p) < 14 * 14) {
                    target = p.getAttacking();
                }
                if (target == null) {
                    for (LivingEntity x : near) {
                        if (x instanceof MobEntity mob && mob.getTarget() == p) {
                            target = x;
                            break;
                        }
                    }
                }
                if (target != null) {
                    breathe(w, m, p, d, target);
                    BREATH.put(p.getUuid(), t + (lv == 1 ? 80 : lv == 2 ? 60 : 40));
                }
            }
            if (lv >= 3 && near.size() >= 3 && t >= ROAR.getOrDefault(p.getUuid(), 0L)) {
                List<LivingEntity> close = near.stream().filter(x -> x.squaredDistanceTo(p) < 8 * 8).toList();
                if (close.size() >= 3) {
                    roar(w, m, p, close);
                    ROAR.put(p.getUuid(), t + 600);
                }
            }
        }
    }

    public static void rename(ServerPlayerEntity p, String name) {
        Dragon d = of(p);
        if (d == null) {
            Msg.send(p, "dragon.none");
            return;
        }
        d.name = name.length() > 24 ? name.substring(0, 24) : name;
        dirty = true;
        MobEntity m = OUT.get(p.getUuid());
        if (m != null) {
            m.setCustomName(Text.literal("§c" + d.name));
        }
        Msg.send(p, "dragon.renamed", d.name);
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && "1".equals(ItemConv.tag(p.getStackInHand(hand), EGG))) {
                if (of(p) != null) {
                    Msg.send(p, "dragon.already");
                    return ActionResult.FAIL;
                }
                p.getStackInHand(hand).decrement(1);
                data().put(p.getUuidAsString(), new Dragon());
                dirty = true;
                save();
                Msg.send(p, "dragon.claimed", WARMTH);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
        // Only its owner can do anything with it (feed it by using food on it); nobody can hand it items.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (Ac.running() && entity.getCommandTags().contains(TAG)) {
                if (player instanceof ServerPlayerEntity p && entity.getCommandTags().contains(TAG + ":" + p.getUuidAsString())
                        && food(p.getStackInHand(hand).getItem()) != null) {
                    feed(p);
                }
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> dismiss(handler.player, false));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (e instanceof MobEntity m && m.getCommandTags().contains(TAG) && !OUT.containsValue(m)) {
                OwnerPowers.later(1, m::discard);
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((disp, reg, env) -> disp.register(
                net.minecraft.server.command.CommandManager.literal("dragon")
                        .executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p != null) {
                                com.vylorq.anticheat.gui.DragonMenu.open(p);
                            }
                            return 1;
                        })
                        .then(net.minecraft.server.command.CommandManager.literal("call").executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p != null) {
                                if (out(p)) {
                                    dismiss(p, true);
                                } else {
                                    summon(p);
                                }
                            }
                            return 1;
                        }))
                        .then(net.minecraft.server.command.CommandManager.literal("feed").executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            if (p != null) {
                                feed(p);
                            }
                            return 1;
                        }))
                        .then(net.minecraft.server.command.CommandManager.literal("name")
                                .then(net.minecraft.server.command.CommandManager.argument("name",
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString()).executes(ctx -> {
                                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                                    if (p != null) {
                                        rename(p, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "name").replace("§", ""));
                                    }
                                    return 1;
                                })))));
    }
}
