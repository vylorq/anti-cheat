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
    public static final List<String> ALL = List.of(LIFESTEAL, VENOM, THUNDERSTRIKE, AUTO_SMELT, TELEPATHY, VEIN_MINER, SWIFTNESS,
            NIGHT_VISION, SOULBOUND);
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
        String id = ALL.get(r.nextInt(ALL.size()));
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

    private static void hit(ServerPlayerEntity p, LivingEntity target, float dealt) {
        ItemStack weapon = p.getMainHandStack();
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
