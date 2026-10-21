package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.rule.GameRules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom enchantments (data/vigil/enchantment, so they look and combine like real ones: books, anvils, tooltips).
 * They never come from the enchanting table: bosses drop them as books.
 * <ul>
 *   <li>Lifesteal I-III (swords, axes): heals 10% of the damage dealt per level.</li>
 *   <li>Venom I-II (swords): poisons what you hit.</li>
 *   <li>Thunderstrike (swords, axes): one hit in eight calls lightning down on the target.</li>
 *   <li>Auto-Smelt (tools): blocks drop already smelted.</li>
 *   <li>Telepathy (tools): drops go straight into your inventory.</li>
 *   <li>Vein Miner (pickaxes): mines the whole ore vein at once (up to 24 blocks).</li>
 *   <li>Swiftness I-II (boots): speed while worn.</li>
 *   <li>Night Vision (helmets): night vision while worn.</li>
 *   <li>Soulbound (anything): stays with you when you die.</li>
 *   <li>Vitality I-III (chestplates): two extra hearts per level.</li>
 *   <li>Fire Ward (any armour): fire resistance while worn.</li>
 *   <li>Leaping I-II (boots): jump boost while worn.</li>
 *   <li>Gills (helmets): breathe underwater.</li>
 *   <li>Reflection I-III (any armour): sends 10% per level of a melee hit back (30% at most).</li>
 *   <li>Second Wind (chestplates): at 3 hearts or less, a burst of regeneration and absorption (once a minute).</li>
 *   <li>Might I-II (leggings): strength while worn.</li>
 *   <li>Thunderlord I-III (swords, axes, maces): every third hit on the same target strikes for 3 more per level.</li>
 *   <li>Executioner I-III (swords, axes, maces): 2 more damage per level to targets below 30% health.</li>
 * </ul>
 */
public final class CustomEnchants {
    private CustomEnchants() {
    }

    public static final String LIFESTEAL = "lifesteal";
    public static final String VENOM = "venom";
    public static final String THUNDERSTRIKE = "thunderstrike";
    public static final String AUTO_SMELT = "auto_smelt";
    public static final String TELEPATHY = "telepathy";
    public static final String VEIN_MINER = "vein_miner";
    public static final String SWIFTNESS = "swiftness";
    public static final String NIGHT_VISION = "night_vision";
    public static final String SOULBOUND = "soulbound";
    public static final String VITALITY = "vitality";
    public static final String FIRE_WARD = "fire_ward";
    public static final String LEAPING = "leaping";
    public static final String GILLS = "gills";
    public static final String REFLECTION = "reflection";
    public static final String SECOND_WIND = "second_wind";
    public static final String MIGHT = "might";
    public static final String THUNDERLORD = "thunderlord";
    public static final String EXECUTIONER = "executioner";
    public static final List<String> ALL = List.of(LIFESTEAL, VENOM, THUNDERSTRIKE, AUTO_SMELT, TELEPATHY, VEIN_MINER, SWIFTNESS,
            NIGHT_VISION, SOULBOUND, VITALITY, FIRE_WARD, LEAPING, GILLS, REFLECTION, SECOND_WIND, MIGHT, THUNDERLORD, EXECUTIONER);
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    static final int VEIN_MAX = 24;
    static final int THUNDER_ODDS = 8;

    public static RegistryEntry<Enchantment> entry(String id) {
        return Ac.server().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getEntry(Identifier.of("vigil", id)).orElse(null);
    }

    public static int maxLevel(String id) {
        RegistryEntry<Enchantment> e = entry(id);
        return e == null ? 1 : e.value().getMaxLevel();
    }

    public static int level(ItemStack s, String id) {
        if (s.isEmpty()) {
            return 0;
        }
        RegistryEntry<Enchantment> e = entry(id);
        return e == null ? 0 : EnchantmentHelper.getLevel(e, s);
    }

    /** An enchanted book with one of them. */
    public static ItemStack book(String id, int level) {
        ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
        RegistryEntry<Enchantment> e = entry(id);
        if (e != null) {
            EnchantmentHelper.apply(b, builder -> builder.add(e, Math.max(1, Math.min(level, e.value().getMaxLevel()))));
        }
        return b;
    }

    /** A random one, at a random level (a boss's drop). */
    public static ItemStack randomBook(Random r) {
        List<String> on = ALL.stream().filter(id -> ContentToggles.on("enchant:" + id)).toList();
        if (on.isEmpty()) {
            return ItemStack.EMPTY;
        }
        String id = on.get(r.nextInt(on.size()));
        return book(id, 1 + r.nextInt(maxLevel(id)));
    }

    /** Puts one straight onto an item (owner command), whatever the item. */
    public static boolean apply(ItemStack s, String id, int level) {
        RegistryEntry<Enchantment> e = entry(id);
        if (e == null || s.isEmpty()) {
            return false;
        }
        EnchantmentHelper.apply(s, builder -> builder.set(e, Math.max(1, level)));
        return true;
    }

    // ---------------------------------------------------------------- hitting

    /** Set while an enchantment deals its own extra damage, so that hit doesn't set off enchantments again. */
    private static boolean extra;
    /** Thunderlord: hits in a row on the same target (player -> target id, count). */
    private static final Map<UUID, int[]> STREAK = new ConcurrentHashMap<>();

    private static void extraDamage(ServerPlayerEntity p, LivingEntity target, float amount) {
        extra = true;
        try {
            target.damage(p.getEntityWorld(), p.getDamageSources().playerAttack(p), amount);
        } finally {
            extra = false;
        }
    }

    private static void hit(ServerPlayerEntity p, LivingEntity target, float dealt) {
        if (extra) {
            return;
        }
        ItemStack weapon = p.getMainHandStack();
        int tl = level(weapon, THUNDERLORD);
        if (tl > 0 && target.isAlive()) {
            int[] st = STREAK.computeIfAbsent(p.getUuid(), k -> new int[]{-1, 0});
            st[1] = st[0] == target.getId() ? st[1] + 1 : 1;
            st[0] = target.getId();
            if (st[1] >= 3) {
                st[1] = 0;
                ServerWorld w = p.getEntityWorld();
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
                if (bolt != null) {
                    bolt.refreshPositionAndAngles(target.getX(), target.getY(), target.getZ(), 0, 0);
                    bolt.setCosmetic(true);
                    w.spawnEntity(bolt);
                }
                extraDamage(p, target, 3f * tl);
            }
        }
        int ex = level(weapon, EXECUTIONER);
        if (ex > 0 && target.isAlive() && target.getHealth() < target.getMaxHealth() * 0.3f) {
            extraDamage(p, target, 2f * ex);
        }
        int ls = level(weapon, LIFESTEAL);
        if (ls > 0 && dealt > 0 && p.getHealth() < p.getMaxHealth()) {
            p.heal(Math.min(4f, dealt * 0.1f * ls));
        }
        int venom = level(weapon, VENOM);
        if (venom > 0 && target.isAlive()) {
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 60 * venom, venom - 1), p);
        }
        if (level(weapon, THUNDERSTRIKE) > 0 && target.isAlive() && p.getRandom().nextInt(THUNDER_ODDS) == 0) {
            ServerWorld w = p.getEntityWorld();
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.refreshPositionAndAngles(target.getX(), target.getY(), target.getZ(), 0, 0);
                bolt.setCosmetic(true);
                w.spawnEntity(bolt);
            }
            target.damage(w, w.getDamageSources().lightningBolt(), 5f);
        }
    }

    // ---------------------------------------------------------------- mining

    /** Breaking blocks for a vein (so those breaks don't start veins of their own). */
    private static boolean veining;

    private static void broke(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState st) {
        ItemStack tool = p.getMainHandStack();
        boolean smelt = level(tool, AUTO_SMELT) > 0;
        boolean tele = level(tool, TELEPATHY) > 0;
        if (smelt || tele) {
            for (ItemEntity it : w.getEntitiesByClass(ItemEntity.class, new Box(pos).expand(0.75), e -> e.getItemAge() < 2 && e.isAlive())) {
                ItemStack s = it.getStack();
                if (smelt) {
                    ItemStack out = smelted(w, s);
                    if (out != null) {
                        s = out;
                        it.setStack(s);
                    }
                }
                if (tele) {
                    p.getInventory().offerOrDrop(s.copy());
                    it.discard();
                }
            }
        }
        if (!veining && level(tool, VEIN_MINER) > 0 && ore(st)) {
            vein(p, w, pos, st);
        }
    }

    private static boolean ore(BlockState st) {
        return st.isIn(net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags.ORES) || st.isOf(net.minecraft.block.Blocks.ANCIENT_DEBRIS);
    }

    /** What a furnace would make of this stack (same count), or null. */
    private static ItemStack smelted(ServerWorld w, ItemStack s) {
        var input = new SingleStackRecipeInput(s.copyWithCount(1));
        var match = w.getRecipeManager().getFirstMatch(RecipeType.SMELTING, input, w);
        if (match.isEmpty()) {
            return null;
        }
        ItemStack out = match.get().value().craft(input, w.getRegistryManager());
        if (out.isEmpty()) {
            return null;
        }
        return out.copyWithCount(Math.min(out.getMaxCount(), out.getCount() * s.getCount()));
    }

    /** The rest of the vein (same block, touching), up to {@link #VEIN_MAX}, as if they mined each one. */
    private static void vein(ServerPlayerEntity p, ServerWorld w, BlockPos start, BlockState st) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> found = new ArrayList<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && found.size() < VEIN_MAX) {
            BlockPos c = queue.poll();
            for (BlockPos n : BlockPos.iterate(c.add(-1, -1, -1), c.add(1, 1, 1))) {
                BlockPos b = n.toImmutable();
                if (seen.add(b) && w.getBlockState(b).isOf(st.getBlock())) {
                    found.add(b);
                    queue.add(b);
                    if (found.size() >= VEIN_MAX) {
                        break;
                    }
                }
            }
        }
        veining = true;
        try {
            for (BlockPos b : found) {
                if (p.getMainHandStack().isEmpty() || level(p.getMainHandStack(), VEIN_MINER) == 0) {
                    break;          // the pickaxe broke
                }
                p.interactionManager.tryBreakBlock(b);
            }
        } finally {
            veining = false;
        }
    }

    // ---------------------------------------------------------------- armour

    private static void wear(ServerPlayerEntity p) {
        int swift = level(p.getEquippedStack(EquipmentSlot.FEET), SWIFTNESS);
        if (swift > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, swift - 1, true, false, true));
        }
        if (level(p.getEquippedStack(EquipmentSlot.HEAD), NIGHT_VISION) > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 320, 0, true, false, true));
        }
        int vit = level(p.getEquippedStack(EquipmentSlot.CHEST), VITALITY);
        if (vit > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.HEALTH_BOOST, 60, vit - 1, true, false, true));
        }
        if (armorLevel(p, FIRE_WARD) > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 60, 0, true, false, true));
        }
        int leap = level(p.getEquippedStack(EquipmentSlot.FEET), LEAPING);
        if (leap > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 60, leap - 1, true, false, true));
        }
        if (level(p.getEquippedStack(EquipmentSlot.HEAD), GILLS) > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 60, 0, true, false, true));
        }
        int might = level(p.getEquippedStack(EquipmentSlot.LEGS), MIGHT);
        if (might > 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 60, might - 1, true, false, true));
        }
    }

    /** The enchantment's levels added up over everything worn. */
    private static int armorLevel(ServerPlayerEntity p, String id) {
        int n = 0;
        for (EquipmentSlot slot : ARMOR) {
            n += level(p.getEquippedStack(slot), id);
        }
        return n;
    }

    private static final Map<UUID, Long> SECOND_WIND_USED = new ConcurrentHashMap<>();

    /** Someone wearing enchanted armour was hurt. */
    private static void hurt(ServerPlayerEntity p, net.minecraft.entity.damage.DamageSource source, float taken) {
        int refl = armorLevel(p, REFLECTION);
        if (refl > 0 && taken > 0 && !source.isOf(net.minecraft.entity.damage.DamageTypes.THORNS) && source.getAttacker() instanceof LivingEntity a && source.getSource() == a && a != p && a.isAlive()) {
            float back = taken * Math.min(0.3f, 0.1f * refl);
            ServerWorld w = p.getEntityWorld();
            a.damage(w, w.getDamageSources().thorns(p), back);
        }
        if (p.isAlive() && p.getHealth() <= 6f && level(p.getEquippedStack(EquipmentSlot.CHEST), SECOND_WIND) > 0) {
            long now = System.currentTimeMillis();
            Long last = SECOND_WIND_USED.get(p.getUuid());
            if (last == null || now - last > 60_000) {
                SECOND_WIND_USED.put(p.getUuid(), now);
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.ABSORPTION, 200, 1));
                p.getEntityWorld().spawnParticles(net.minecraft.particle.ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(),
                        30, 0.4, 0.6, 0.4, 0.2);
            }
        }
    }

    // ---------------------------------------------------------------- soulbound

    private static final Map<UUID, List<ItemStack>> KEPT = new ConcurrentHashMap<>();

    private static void dying(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        if (w.getGameRules().getValue(GameRules.KEEP_INVENTORY)) {
            return;
        }
        var inv = p.getInventory();
        List<ItemStack> keep = new ArrayList<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (level(s, SOULBOUND) > 0) {
                keep.add(s.copy());
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        if (keep.isEmpty()) {
            return;
        }
        KEPT.computeIfAbsent(p.getUuid(), k -> new ArrayList<>()).addAll(keep);
        // Saved after all (a totem, or something else that cancels death): give them straight back.
        OwnerPowers.later(1, () -> {
            if (!p.isRemoved() && p.isAlive()) {
                giveBack(p);
            }
        });
    }

    private static void giveBack(ServerPlayerEntity p) {
        List<ItemStack> keep = KEPT.remove(p.getUuid());
        if (keep != null) {
            for (ItemStack s : keep) {
                p.getInventory().offerOrDrop(s);
            }
        }
    }

    // ---------------------------------------------------------------- hooks

    public static void tick(long ticks) {
        if (ticks % 20 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            wear(p);
        }
    }

    public static void register() {
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (Ac.running() && !blocked && source.getAttacker() instanceof ServerPlayerEntity p && source.getSource() == p && entity != p) {
                hit(p, entity, taken);
            }
            if (Ac.running() && entity instanceof ServerPlayerEntity hurtP && !blocked) {
                hurt(hurtP, source, taken);
            }
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world, player, pos, st, be) -> {
            if (Ac.running() && player instanceof ServerPlayerEntity p && world instanceof ServerWorld w) {
                broke(p, w, pos, st);
            }
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (Ac.running() && entity instanceof ServerPlayerEntity p) {
                dying(p);
            }
            return true;
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((old, now, alive) -> giveBack(now));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, sender, server) -> {
            if (KEPT.containsKey(h.player.getUuid()) && h.player.isAlive()) {
                giveBack(h.player);
            }
        });
    }

    public static boolean loadedForTest() {
        return ALL.stream().allMatch(id -> entry(id) != null);
    }
}
