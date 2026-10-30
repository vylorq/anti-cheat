package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Skills: Mining, Combat, Farming and Woodcutting, each from level 0 to 50. Doing the thing earns its experience;
 * every level makes its perk a little stronger:
 * Mining and Woodcutting: a chance for the ore or log to drop twice; Farming: a chance for a ripe crop to drop twice;
 * Combat: harder hits. Only natural blocks count (ores mined without Silk Touch, logs from trees with their own
 * leaves), so placing and breaking again earns nothing.
 */
public final class Skills {
    private Skills() {
    }

    public static final List<String> ALL = List.of("mining", "combat", "farming", "woodcutting");
    public static final int MAX_LEVEL = 50;
    private static final Identifier COMBAT = Identifier.of("vigil", "skill_combat");

    /** uuid -> skill -> experience */
    private static Map<String, Map<String, Long>> data;
    private static boolean dirty;

    private static Path file() {
        return Ac.get().dir.resolve("skills.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Long>> data() {
        if (data == null) {
            try {
                Path f = file();
                Map<String, Map<String, Long>> d = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                        new com.google.gson.reflect.TypeToken<Map<String, Map<String, Long>>>() { }.getType()) : null;
                data = d == null ? new HashMap<>() : d;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read skills", e);
                data = new HashMap<>();
            }
        }
        return data;
    }

    public static void save() {
        if (!dirty || data == null) {
            return;
        }
        try {
            Files.writeString(file(), ConfigManager.GSON.toJson(data), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            Ac.LOG.warn("Could not save skills", e);
        }
    }

    /** Experience needed to go from this level to the next. */
    public static long need(int level) {
        return 60 + level * 30L;
    }

    public static long xp(ServerPlayerEntity p, String skill) {
        return data().getOrDefault(p.getUuidAsString(), Map.of()).getOrDefault(skill, 0L);
    }

    public static int level(ServerPlayerEntity p, String skill) {
        long x = xp(p, skill);
        int lv = 0;
        while (lv < MAX_LEVEL && x >= need(lv)) {
            x -= need(lv);
            lv++;
        }
        return lv;
    }

    /** Experience into the current level, and what the level needs. */
    public static long[] progress(ServerPlayerEntity p, String skill) {
        long x = xp(p, skill);
        int lv = 0;
        while (lv < MAX_LEVEL && x >= need(lv)) {
            x -= need(lv);
            lv++;
        }
        return new long[]{x, lv >= MAX_LEVEL ? 0 : need(lv)};
    }

    public static void add(ServerPlayerEntity p, String skill, long amount) {
        if (amount <= 0) {
            return;
        }
        int before = level(p, skill);
        data().computeIfAbsent(p.getUuidAsString(), k -> new HashMap<>()).merge(skill, amount, Long::sum);
        dirty = true;
        int after = level(p, skill);
        if (after > before) {
            p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
            Msg.send(p, "skills.levelup", Msg.trFor(p, "skills." + skill), after);
            if (skill.equals("combat")) {
                combatBonus(p);
            }
            if (after % 10 == 0) {
                Achievements.unlock(p, "skill:" + skill + ":" + after);
            }
        } else {
            long[] pr = progress(p, skill);
            Msg.actionBar(p, "§b+" + amount + " " + Msg.trFor(p, "skills." + skill) + " §7(" + pr[0] + "/" + pr[1] + ")");
        }
    }

    /** The chance (0..1) of a double drop for this skill. */
    public static double perk(ServerPlayerEntity p, String skill) {
        return level(p, skill) * (skill.equals("farming") ? 0.01 : 0.006);
    }

    static void combatBonus(ServerPlayerEntity p) {
        var inst = p.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
        if (inst == null) {
            return;
        }
        double v = level(p, "combat") * 0.004;
        var cur = inst.getModifier(COMBAT);
        if (cur != null && cur.value() == v) {
            return;
        }
        inst.removeModifier(COMBAT);
        if (v > 0) {
            inst.addTemporaryModifier(new EntityAttributeModifier(COMBAT, v, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static boolean silk(ItemStack tool) {
        for (var e : tool.getEnchantments().getEnchantments()) {
            if (e.matchesKey(Enchantments.SILK_TOUCH)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ore(BlockState st) {
        String path = Registries.BLOCK.getId(st.getBlock()).getPath();
        return (path.endsWith("_ore") || Gems.oreOf(st) != null) && !path.equals("ancient_debris");
    }

    private static long oreXp(BlockState st) {
        String path = Registries.BLOCK.getId(st.getBlock()).getPath();
        if (path.contains("diamond") || path.contains("emerald") || Gems.oreOf(st) != null) {
            return 20;
        }
        if (path.contains("gold") || path.contains("lapis") || path.contains("redstone")) {
            return 8;
        }
        return 4;
    }

    /** A log from a tree that grew by itself: natural leaves close by. */
    private static boolean naturalLog(ServerWorld w, BlockPos pos) {
        for (BlockPos q : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 5, 3))) {
            BlockState s = w.getBlockState(q);
            if (s.getBlock() instanceof LeavesBlock && s.contains(LeavesBlock.PERSISTENT) && !s.get(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    private static void twice(ServerWorld w, ServerPlayerEntity p, BlockPos pos, BlockState st, net.minecraft.block.entity.BlockEntity be) {
        Block.dropStacks(st, w, pos, be, p, p.getMainHandStack());
        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 5, 0.3, 0.3, 0.3, 0);
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            if (!Ac.running() || !(world instanceof ServerWorld w) || !(player instanceof ServerPlayerEntity p) || p.isCreative()) {
                return;
            }
            if (ore(st) && !silk(p.getMainHandStack())) {
                add(p, "mining", oreXp(st));
                if (w.getRandom().nextDouble() < perk(p, "mining")) {
                    twice(w, p, pos, st, be);
                }
            } else if (st.isIn(BlockTags.LOGS) && naturalLog(w, pos)) {
                add(p, "woodcutting", 2);
                if (w.getRandom().nextDouble() < perk(p, "woodcutting")) {
                    twice(w, p, pos, st, be);
                }
            } else if (st.getBlock() instanceof CropBlock crop && crop.isMature(st)) {
                add(p, "farming", 2);
                if (w.getRandom().nextDouble() < perk(p, "farming")) {
                    twice(w, p, pos, st, be);
                }
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((e, source) -> {
            if (Ac.running() && source.getAttacker() instanceof ServerPlayerEntity p && e != p) {
                if (e instanceof ServerPlayerEntity) {
                    add(p, "combat", 15);
                } else if (e instanceof Monster) {
                    add(p, "combat", Math.max(2, (long) (((LivingEntity) e).getMaxHealth() / 5)));
                }
            }
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> combatBonus(h.player));
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> combatBonus(now));
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            for (String name : List.of("achievements", "skills")) {
                d.register(net.minecraft.server.command.CommandManager.literal(name).executes(ctx -> {
                    ServerPlayerEntity p = ctx.getSource().getPlayer();
                    if (p != null) {
                        com.vylorq.anticheat.gui.AchievementsMenu.open(p);
                    }
                    return 1;
                }));
            }
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
    }

    public static void tick(long ticks) {
        if (ticks % 1200 == 0) {
            save();
        }
    }
}
