package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secret items (found in the secret structures) and the new craftable tools. Each is a normal item carrying a
 * vigil model id; everything they do is here. Nothing is stronger than diamond / iron gear, and every item has a
 * cooldown (shown on the item like an ender pearl's).
 */
public final class SecretItems {
    private SecretItems() {
    }

    // Secret (structure loot)
    public static final String VOIDBLADE = "voidblade";
    public static final String STORMBREAKER = "stormbreaker";
    public static final String TIDECALLER = "tidecaller";
    public static final String PHOENIX = "phoenix_feather";
    public static final String SHADOW = "shadow_cloak";
    public static final String SEEKER = "seeker_compass";
    // Craftable
    public static final String HAMMER = "hammer";
    public static final String LUMBER = "lumber_axe";
    public static final String GRAPPLE = "grappling_hook";
    public static final String MAGNET = "magnet_charm";
    public static final String POUCH = "ender_pouch";
    public static final String BACKPACK = "backpack";

    // Boss weapons (each boss drops its own)
    public static final List<String> BOSS_WEAPONS = List.of("tide_trident", "colossus_maul", "storm_fang", "forge_cleaver",
            "dune_blade", "glacier_axe", "thornspine", "hollow_edge");

    public static final List<String> ALL = java.util.stream.Stream.concat(List.of(VOIDBLADE, STORMBREAKER, TIDECALLER, PHOENIX, SHADOW, SEEKER,
            HAMMER, LUMBER, GRAPPLE, MAGNET, POUCH, BACKPACK).stream(), BOSS_WEAPONS.stream()).toList();
    public static final List<String> CRAFTABLE = List.of(HAMMER, LUMBER, GRAPPLE, MAGNET, POUCH, BACKPACK);

    public static final TagKey<Structure> SECRET_STRUCTURES = TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("vigil", "secret"));

    private static final DustParticleEffect VOID = new DustParticleEffect(0x6A2BD6, 1.3f);
    private static final DustParticleEffect SHADE = new DustParticleEffect(0x1A1426, 1.6f);
    private static final DustParticleEffect TIDE = new DustParticleEffect(0x3FA8FF, 1.3f);
    private static final DustParticleEffect PHOENIX_FX = new DustParticleEffect(0xFF8A1E, 1.6f);
    private static final DustParticleEffect SEEK = new DustParticleEffect(0x7FFFD4, 1.2f);
    private static final DustParticleEffect MAGNET_FX = new DustParticleEffect(0xE23B3B, 0.9f);

    /** The item's id ("voidblade"...), or null for anything else. */
    public static String idOf(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        var cmd = s.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd == null || cmd.strings().isEmpty()) {
            return null;
        }
        return BY_MODEL.get(cmd.strings().get(0));
    }

    private static final Map<String, String> BY_MODEL = new java.util.HashMap<>();

    static {
        for (String id : ALL) {
            BY_MODEL.put(com.vylorq.anticheat.util.PackIds.model(id), id);
        }
    }

    static boolean is(ItemStack s, String id) {
        return id.equals(idOf(s));
    }

    /** Gives an item through its loot table (the same definition the chests and recipes use). */
    public static void give(ServerPlayerEntity p, String id) {
        var src = Ac.server().getCommandSource().withSilent();
        Ac.server().getCommandManager().parseAndExecute(src, "loot give " + p.getGameProfile().name() + " loot vigil:items/" + id);
    }

    // ---------------------------------------------------------------- shared helpers

    private static boolean cooling(ServerPlayerEntity p, ItemStack s) {
        if (p.getItemCooldownManager().isCoolingDown(s)) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "secret.cooldown"));
            return true;
        }
        return false;
    }

    private static void cool(ServerPlayerEntity p, ItemStack s, int ticks) {
        Integer set = setCooldown(idOf(s));
        p.getItemCooldownManager().set(s, set != null ? set * 20 : ticks);
    }

    /** Each item's own cooldown in ticks (what it uses unless the owner set another). */
    public static final Map<String, Integer> DEFAULT_COOLDOWNS = Map.ofEntries(
            Map.entry(VOIDBLADE, 120), Map.entry(STORMBREAKER, 400), Map.entry(TIDECALLER, 300), Map.entry(PHOENIX, 12000),
            Map.entry(SHADOW, 900), Map.entry(SEEKER, 200), Map.entry(HAMMER, 8), Map.entry(LUMBER, 60), Map.entry(GRAPPLE, 60),
            Map.entry(MAGNET, 20), Map.entry(POUCH, 100), Map.entry(BACKPACK, 20), Map.entry("tide_trident", 160),
            Map.entry("colossus_maul", 200), Map.entry("storm_fang", 120), Map.entry("forge_cleaver", 100),
            Map.entry("dune_blade", 240), Map.entry("glacier_axe", 200), Map.entry("thornspine", 160), Map.entry("hollow_edge", 200));

    /** The cooldown in seconds the owner set for this item, or null for its own. */
    private static Integer setCooldown(String id) {
        var m = OwnerPowers.state().itemCooldowns;
        return id == null || m == null ? null : m.get(id);
    }

    private static long phoenixMillis() {
        Integer set = setCooldown(PHOENIX);
        return set != null ? set * 1000L : DEFAULT_COOLDOWNS.get(PHOENIX) * 50L;
    }

    /** Owner: sets an item's cooldown in seconds (null puts its own back). */
    public static void setCooldown(ServerPlayerEntity p, String id, Integer seconds) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        var st = OwnerPowers.state();
        if (st.itemCooldowns == null) {
            st.itemCooldowns = new java.util.LinkedHashMap<>();
        }
        if (seconds == null) {
            st.itemCooldowns.remove(id);
        } else {
            st.itemCooldowns.put(id, seconds);
        }
        OwnerPowers.save();
        Msg.send(p, "secret.cooldown-set", id.replace('_', ' '), fmtSeconds(seconds != null ? seconds * 20 : DEFAULT_COOLDOWNS.get(id)));
    }

    /** Owner: every item's cooldown, its own or the one set. */
    public static void listCooldowns(ServerPlayerEntity p) {
        if (!OwnerPowers.require(p)) {
            return;
        }
        for (String id : ALL) {
            Integer set = setCooldown(id);
            Msg.send(p, set != null ? "secret.cooldown-entry-set" : "secret.cooldown-entry", id.replace('_', ' '),
                    fmtSeconds(set != null ? set * 20 : DEFAULT_COOLDOWNS.get(id)));
        }
    }

    private static String fmtSeconds(int ticks) {
        return ticks % 20 == 0 ? (ticks / 20) + "s" : String.format(java.util.Locale.ROOT, "%.1fs", ticks / 20.0);
    }

    /** A pack sound for everyone near (they all have the pack), plus a quiet vanilla layer. */
    static void sound(ServerWorld w, Vec3d at, String name, float volume) {
        var entry = RegistryEntry.of(SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound(name)));
        for (ServerPlayerEntity o : w.getPlayers()) {
            if (o.squaredDistanceTo(at) < 40 * 40) {
                o.networkHandler.sendPacket(new PlaySoundS2CPacket(entry, SoundCategory.PLAYERS, at.x, at.y, at.z, volume, 1f, o.getRandom().nextLong()));
            }
        }
    }

    private static void push(Entity e, Vec3d v) {
        e.setVelocity(Vec3d.ZERO);
        e.addVelocity(v);
        if (e instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
        }
    }

    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

    /** A click on a block sends "used on block" and "used item": act once. */
    private static boolean debounce(ServerPlayerEntity p) {
        long now = System.currentTimeMillis();
        Long last = LAST_USE.put(p.getUuid(), now);
        return last != null && now - last < 150;
    }

    // ---------------------------------------------------------------- right-click

    /** Items with a right-click of their own. */
    public static boolean hasUse(ItemStack s) {
        String id = idOf(s);
        return id != null && switch (id) {
            case SEEKER, MAGNET, POUCH, BACKPACK, TIDECALLER, GRAPPLE -> true;
            default -> false;
        };
    }

    /** Right-click with an item. @return the result, or null to let the game handle it */
    public static ActionResult use(ServerPlayerEntity p, Hand hand, ItemStack s) {
        String id = idOf(s);
        if (id == null) {
            return null;
        }
        switch (id) {
            case TIDECALLER -> {
                // Throwing stays normal; sneak + right-click calls the wave.
                if (!p.isSneaking()) {
                    return null;
                }
                if (!debounce(p) && !cooling(p, s)) {
                    tideWave(p, s);
                }
                p.playerScreenHandler.syncState();
                return ActionResult.FAIL;
            }
            case GRAPPLE -> {
                if (p.fishHook != null && !p.getItemCooldownManager().isCoolingDown(s)) {
                    grapple(p, s, p.fishHook);
                }
                return null;
            }
            case SEEKER, MAGNET, POUCH, BACKPACK -> {
                if (debounce(p) || cooling(p, s)) {
                    return ActionResult.FAIL;
                }
                switch (id) {
                    case SEEKER -> seek(p, hand, s);
                    case MAGNET -> magnetToggle(p, s);
                    case POUCH -> pouch(p, s);
                    default -> backpack(p, hand, s);
                }
                return ActionResult.SUCCESS;
            }
            default -> {
                return null;
            }
        }
    }

    // ---------------------------------------------------------------- hits

    /** How charged each player's last swing was (read before the game resets it). */
    private static final Map<java.util.UUID, Float> CHARGE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Most a secret weapon can take off a player in one hit (after armour): 4 hearts. */
    public static final float PLAYER_CAP = 8f;
    public static final float BOSS_MIN = 20f;
    public static final float BOSS_MAX = 40f;

    /** The secret weapon behind this damage ("voidblade", a boss weapon...), or null. */
    public static String weaponOf(DamageSource src) {
        if (!(src.getAttacker() instanceof PlayerEntity)) {
            return null;
        }
        String id = idOf(src.getWeaponStack());
        return id != null && (BOSS_WEAPONS.contains(id) || id.equals(VOIDBLADE) || id.equals(STORMBREAKER)
                || id.equals(TIDECALLER)) ? id : null;
    }

    /**
     * A secret weapon's hit on a boss: 20, plus 2 for each level of Sharpness / Impaling / Density (40 at level 10),
     * less for a half-charged swing the same way the game does it.
     */
    public static float bossHit(DamageSource src) {
        ItemStack s = src.getWeaponStack();
        int level = 0;
        for (var en : s.getEnchantments().getEnchantmentEntries()) {
            var k = en.getKey();
            if (k.matchesKey(net.minecraft.enchantment.Enchantments.SHARPNESS) || k.matchesKey(net.minecraft.enchantment.Enchantments.IMPALING)
                    || k.matchesKey(net.minecraft.enchantment.Enchantments.DENSITY)) {
                level = Math.max(level, en.getIntValue());
            }
        }
        float hit = Math.min(BOSS_MAX, BOSS_MIN + 2f * Math.min(10, level));
        if (src.getSource() == src.getAttacker()) {
            float c = CHARGE.getOrDefault(src.getAttacker().getUuid(), 1f);
            hit *= 0.2f + c * c * 0.8f;
        }
        return hit;
    }

    /** A player hit something (the hit was allowed). */
    public static void onHit(ServerPlayerEntity p, Entity target) {
        CHARGE.put(p.getUuid(), p.getAttackCooldownProgress(0.5f));
        shadowBreak(p);
        if (!(target instanceof LivingEntity e) || !e.isAlive()) {
            return;
        }
        ItemStack s = p.getMainHandStack();
        String id = idOf(s);
        if (id == null || p.getItemCooldownManager().isCoolingDown(s)) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        if (VOIDBLADE.equals(id)) {
            // Pulls them a little toward you (once every 6 seconds).
            cool(p, s, 120);
            OwnerPowers.later(1, () -> {
                if (!e.isAlive()) {
                    return;
                }
                Vec3d d = p.getEntityPos().subtract(e.getEntityPos()).multiply(1, 0, 1);
                if (d.lengthSquared() > 1e-4) {
                    push(e, d.normalize().multiply(0.8).add(0, 0.2, 0));
                }
                w.spawnParticles(VOID, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.5, 0.3, 0);
                w.spawnParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY() + 1, e.getZ(), 20, 0.3, 0.5, 0.3, 0.05);
                sound(w, e.getEntityPos(), "voidblade", 1f);
            });
        } else if (BOSS_WEAPONS.contains(id)) {
            bossWeaponHit(p, s, id, e, w);
        } else if (STORMBREAKER.equals(id) && p.getRandom().nextFloat() < 0.25f) {
            // One hit in four calls a small lightning strike (no fire on blocks); then 20 seconds to recharge.
            cool(p, s, 400);
            LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w, SpawnReason.TRIGGERED);
            if (bolt != null) {
                bolt.refreshPositionAfterTeleport(e.getEntityPos());
                bolt.setCosmetic(true);
                w.spawnEntity(bolt);
            }
            e.damage(w, p.getDamageSources().playerAttack(p), 4f);
            e.setFireTicks(Math.max(e.getFireTicks(), 40));
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1, e.getZ(), 30, 0.3, 0.6, 0.3, 0.3);
            sound(w, e.getEntityPos(), "stormbreaker", 1.5f);
        }
    }

    private static final DustParticleEffect SAND = new DustParticleEffect(0xE8C77A, 1.4f);
    private static final DustParticleEffect THORN = new DustParticleEffect(0x6FD05A, 1.2f);
    private static final DustParticleEffect STONE = new DustParticleEffect(0x6C7684, 1.6f);

    /** The bosses' weapons: each hit effect has its own recharge. */
    private static void bossWeaponHit(ServerPlayerEntity p, ItemStack s, String id, LivingEntity e, ServerWorld w) {
        double x = e.getX();
        double y = e.getY() + e.getHeight() / 2;
        double z = e.getZ();
        switch (id) {
            case "tide_trident" -> {
                cool(p, s, 160);
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
                w.spawnParticles(ParticleTypes.SPLASH, x, y, z, 30, 0.4, 0.5, 0.4, 0.2);
                w.spawnParticles(TIDE, x, y, z, 16, 0.4, 0.5, 0.4, 0);
                sound(w, e.getEntityPos(), "tidecaller", 0.8f);
            }
            case "colossus_maul" -> {
                cool(p, s, 200);
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20, 6));
                w.spawnParticles(STONE, x, y, z, 24, 0.4, 0.5, 0.4, 0);
                w.spawnParticles(ParticleTypes.CRIT, x, y, z, 20, 0.3, 0.4, 0.3, 0.3);
                sound(w, e.getEntityPos(), "hammer", 1.2f);
            }
            case "storm_fang" -> {
                cool(p, s, 120);
                int n = 0;
                for (Entity o : w.getOtherEntities(e, e.getBoundingBox().expand(4))) {
                    if (n >= 3 || o == p || !(o instanceof LivingEntity l) || !l.isAlive() || o.isSpectator()) {
                        continue;
                    }
                    if (o instanceof ServerPlayerEntity op && (!Protection.pvpAllowed(p, op) || LobbyFeature.in(op))) {
                        continue;
                    }
                    l.damage(w, p.getDamageSources().playerAttack(p), 3f);
                    Vec3d a = e.getEntityPos().add(0, 1, 0);
                    Vec3d b = l.getEntityPos().add(0, 1, 0);
                    for (int i = 0; i <= 8; i++) {
                        Vec3d c = a.lerp(b, i / 8.0);
                        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 1, 0.05, 0.05, 0.05, 0);
                    }
                    n++;
                }
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 20, 0.3, 0.5, 0.3, 0.3);
                sound(w, e.getEntityPos(), "stormbreaker", 0.7f);
            }
            case "forge_cleaver" -> {
                cool(p, s, 100);
                e.setFireTicks(Math.max(e.getFireTicks(), 80));
                w.spawnParticles(ParticleTypes.FLAME, x, y, z, 24, 0.3, 0.5, 0.3, 0.05);
                sound(w, e.getEntityPos(), "flame_hit", 0.9f);
            }
            case "dune_blade" -> {
                cool(p, s, 240);
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 40, 0));
                w.spawnParticles(SAND, x, y + 0.5, z, 30, 0.4, 0.4, 0.4, 0);
                sound(w, e.getEntityPos(), "shadow_on", 0.8f);
            }
            case "glacier_axe" -> {
                cool(p, s, 200);
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 2));
                e.setFrozenTicks(Math.max(e.getFrozenTicks(), e.getMinFreezeDamageTicks() - 1));
                w.spawnParticles(ParticleTypes.SNOWFLAKE, x, y, z, 30, 0.4, 0.5, 0.4, 0.05);
                sound(w, e.getEntityPos(), "frost_hit", 0.9f);
            }
            case "thornspine" -> {
                cool(p, s, 160);
                e.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 60, 1));
                w.spawnParticles(THORN, x, y, z, 24, 0.4, 0.5, 0.4, 0);
                sound(w, e.getEntityPos(), "voidblade", 0.7f);
            }
            case "hollow_edge" -> {
                cool(p, s, 200);
                p.heal(6f);
                w.spawnParticles(VOID, x, y, z, 20, 0.3, 0.5, 0.3, 0);
                w.spawnParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2.1, p.getZ(), 3, 0.3, 0.1, 0.3, 0);
                sound(w, e.getEntityPos(), "lifesteal_hit", 1f);
            }
            default -> {
            }
        }
    }

    /** Anyone hurt: a shadow-cloaked player shows up again. */
    public static void onHurt(ServerPlayerEntity p) {
        shadowBreak(p);
    }

    // ---------------------------------------------------------------- tidecaller

    private static void tideWave(ServerPlayerEntity p, ItemStack s) {
        cool(p, s, 300);
        ServerWorld w = p.getEntityWorld();
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1f).multiply(1, 0, 1).normalize();
        int n = 0;
        for (Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(7))) {
            if (!(e instanceof LivingEntity l) || !l.isAlive() || e.isSpectator()) {
                continue;
            }
            Vec3d to = e.getEntityPos().subtract(p.getEntityPos()).multiply(1, 0, 1);
            if (to.length() > 7 || to.normalize().dotProduct(look) < 0.5) {
                continue;
            }
            if (e instanceof ServerPlayerEntity o && (!Protection.pvpAllowed(p, o) || LobbyFeature.in(o))) {
                continue;
            }
            push(e, look.multiply(1.3).add(0, 0.35, 0));
            e.extinguish();
            n++;
        }
        for (int step = 0; step < 7; step++) {
            int k = step;
            OwnerPowers.later(step, () -> {
                Vec3d c = eye.add(look.multiply(1 + k)).add(0, -1.2, 0);
                Vec3d side = new Vec3d(-look.z, 0, look.x);
                for (int i = -3; i <= 3; i++) {
                    Vec3d at = c.add(side.multiply(i * 0.45 * (1 + k * 0.2)));
                    w.spawnParticles(ParticleTypes.SPLASH, at.x, at.y + 0.6, at.z, 4, 0.1, 0.3, 0.1, 0.1);
                    w.spawnParticles(TIDE, at.x, at.y + 0.3 + Math.random() * 0.8, at.z, 1, 0, 0, 0, 0);
                }
            });
        }
        sound(w, p.getEntityPos(), "tidecaller", 1.4f);
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, SoundCategory.PLAYERS, 0.6f, 0.8f);
        if (n > 0) {
            Msg.actionBar(p, "§b" + Msg.trFor(p, "secret.tide-hit", n));
        }
    }

    // ---------------------------------------------------------------- phoenix feather

    private static final Map<UUID, Long> PHOENIX_COOLDOWN = new ConcurrentHashMap<>();

    /** About to die: a phoenix feather in the hotbar or off hand saves them once (used up). @return true when saved */
    public static boolean phoenix(ServerPlayerEntity p, DamageSource source) {
        if (source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        }
        Long last = PHOENIX_COOLDOWN.get(p.getUuid());
        if (last != null && System.currentTimeMillis() - last < phoenixMillis()) {
            return false;
        }
        var inv = p.getInventory();
        int slot = -1;
        if (is(p.getOffHandStack(), PHOENIX)) {
            slot = -2;
        } else {
            for (int i = 0; i < 9; i++) {
                if (is(inv.getStack(i), PHOENIX)) {
                    slot = i;
                    break;
                }
            }
        }
        if (slot == -1) {
            return false;
        }
        if (slot == -2) {
            p.getOffHandStack().decrement(1);
        } else {
            inv.getStack(slot).decrement(1);
        }
        PHOENIX_COOLDOWN.put(p.getUuid(), System.currentTimeMillis());
        p.setHealth(8f);
        p.extinguish();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 200, 0));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 0));
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 80, 0.5, 1, 0.5, 0.15);
        w.spawnParticles(PHOENIX_FX, p.getX(), p.getY() + 1, p.getZ(), 60, 0.6, 1.1, 0.6, 0);
        for (int step = 0; step < 12; step++) {
            int k = step;
            OwnerPowers.later(step * 2, () -> {
                for (int i = 0; i < 8; i++) {
                    double a = k * 0.6 + i * Math.PI / 4;
                    w.spawnParticles(ParticleTypes.FLAME, p.getX() + Math.cos(a) * 0.9, p.getY() + k * 0.18, p.getZ() + Math.sin(a) * 0.9, 1, 0, 0, 0, 0);
                }
            });
        }
        sound(w, p.getEntityPos(), "phoenix", 2f);
        Msg.send(p, "secret.phoenix-saved");
        return true;
    }

    // ---------------------------------------------------------------- shadow cloak

    private static final Map<UUID, Integer> SNEAK = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> SHADOWED = new ConcurrentHashMap<>();

    private static void shadowTick(ServerPlayerEntity p, long ticks) {
        ItemStack cloak = p.getOffHandStack();
        UUID id = p.getUuid();
        Long until = SHADOWED.get(id);
        if (until != null) {
            if (System.currentTimeMillis() > until || !is(cloak, SHADOW)) {
                shadowBreak(p);
            } else if (ticks % 10 == 0) {
                p.getEntityWorld().spawnParticles(SHADE, p.getX(), p.getY() + 0.1, p.getZ(), 1, 0.2, 0, 0.2, 0);
            }
            return;
        }
        if (!is(cloak, SHADOW) || !p.isSneaking() || p.getVelocity().horizontalLengthSquared() > 0.003
                || p.getItemCooldownManager().isCoolingDown(cloak)) {
            SNEAK.remove(id);
            return;
        }
        int t = SNEAK.merge(id, 1, Integer::sum);
        if (t % 10 == 0 && t < 60) {
            p.getEntityWorld().spawnParticles(SHADE, p.getX(), p.getY() + 1, p.getZ(), 6, 0.3, 0.5, 0.3, 0);
        }
        if (t >= 60) {
            SNEAK.remove(id);
            SHADOWED.put(id, System.currentTimeMillis() + 20_000);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 400, 0, false, false, true));
            p.getEntityWorld().spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.7, 0.4, 0.02);
            sound(p.getEntityWorld(), p.getEntityPos(), "shadow_on", 0.8f);
            Msg.actionBar(p, "§8" + Msg.trFor(p, "secret.shadow-on"));
        }
    }

    /** Ends the cloak (after attacking, getting hurt, 20 seconds, or taking it off); then 45 seconds to recharge. */
    static void shadowBreak(ServerPlayerEntity p) {
        if (SHADOWED.remove(p.getUuid()) == null) {
            return;
        }
        var eff = p.getStatusEffect(StatusEffects.INVISIBILITY);
        if (eff != null && eff.getDuration() <= 400) {
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
        }
        ItemStack cloak = p.getOffHandStack();
        if (is(cloak, SHADOW)) {
            cool(p, cloak, 900);
        }
        p.getEntityWorld().spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 20, 0.3, 0.6, 0.3, 0.02);
        sound(p.getEntityWorld(), p.getEntityPos(), "shadow_off", 0.8f);
        Msg.actionBar(p, "§7" + Msg.trFor(p, "secret.shadow-off"));
    }

    // ---------------------------------------------------------------- seeker compass

    private static void seek(ServerPlayerEntity p, Hand hand, ItemStack s) {
        ServerWorld w = p.getEntityWorld();
        BlockPos found = w.locateStructure(SECRET_STRUCTURES, p.getBlockPos(), 300, true);
        if (found == null) {
            cool(p, s, 200);
            Msg.send(p, "secret.seeker-none");
            return;
        }
        // Used up: it becomes a plain compass locked onto that place.
        ItemStack compass = new ItemStack(Items.COMPASS);
        compass.set(DataComponentTypes.LODESTONE_TRACKER, new LodestoneTrackerComponent(Optional.of(GlobalPos.create(w.getRegistryKey(), found)), false));
        compass.set(DataComponentTypes.ITEM_NAME, Text.literal("Seeker Compass (spent)").styled(st -> st.withColor(0x7FFFD4)));
        compass.set(DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(List.of(
                Text.literal("Points to a secret place").styled(st -> st.withColor(0xAAAAAA).withItalic(false)))));
        s.decrement(1);
        if (s.isEmpty()) {
            p.setStackInHand(hand, compass);
        } else {
            p.getInventory().offerOrDrop(compass);
        }
        int dist = (int) Math.sqrt(found.getSquaredDistance(p.getBlockPos().getX(), found.getY(), p.getBlockPos().getZ()));
        w.spawnParticles(SEEK, p.getX(), p.getY() + 1.2, p.getZ(), 40, 0.5, 0.5, 0.5, 0);
        sound(w, p.getEntityPos(), "seeker", 1f);
        Msg.send(p, "secret.seeker-found", dist);
    }

    // ---------------------------------------------------------------- magnet

    private static void magnetToggle(ServerPlayerEntity p, ItemStack s) {
        boolean on = !Boolean.TRUE.equals(s.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE));
        s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, on);
        cool(p, s, 20);
        sound(p.getEntityWorld(), p.getEntityPos(), on ? "magnet_on" : "magnet_off", 0.8f);
        Msg.actionBar(p, (on ? "§c" : "§7") + Msg.trFor(p, on ? "secret.magnet-on" : "secret.magnet-off"));
    }

    private static boolean magnetOn(ServerPlayerEntity p) {
        if (isOnMagnet(p.getOffHandStack())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (isOnMagnet(p.getInventory().getStack(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnMagnet(ItemStack s) {
        return is(s, MAGNET) && Boolean.TRUE.equals(s.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE));
    }

    private static void magnetTick(ServerPlayerEntity p) {
        ServerWorld w = p.getEntityWorld();
        Vec3d c = p.getEntityPos().add(0, 0.6, 0);
        for (ItemEntity it : w.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(7), e -> e.isAlive() && !e.cannotPickup())) {
            BlockPos at = it.getBlockPos();
            if (LobbyFeature.in(w, at)) {
                continue;
            }
            var claim = Claims.at(w, at);
            if (claim != null && !Claims.can(p, claim, com.vylorq.anticheat.core.claims.ClaimAction.CONTAINER)) {
                continue;
            }
            Vec3d d = c.subtract(it.getEntityPos());
            if (d.lengthSquared() < 0.5) {
                continue;
            }
            it.setVelocity(d.normalize().multiply(0.3));
            if (w.getRandom().nextInt(6) == 0) {
                w.spawnParticles(MAGNET_FX, it.getX(), it.getY() + 0.2, it.getZ(), 1, 0, 0, 0, 0);
            }
        }
    }

    // ---------------------------------------------------------------- ender pouch / backpack

    private static void pouch(ServerPlayerEntity p, ItemStack s) {
        if (HomeTeleport.inFight(p)) {
            Msg.actionBar(p, "§c" + Msg.trFor(p, "secret.in-fight"));
            return;
        }
        cool(p, s, 100);
        var ender = p.getEnderChestInventory();
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, inv, pl) -> GenericContainerScreenHandler.createGeneric9x3(syncId, inv, ender),
                Text.translatable("container.enderchest")));
        sound(p.getEntityWorld(), p.getEntityPos(), "pouch_open", 0.8f);
    }

    private static void backpack(ServerPlayerEntity p, Hand hand, ItemStack s) {
        if (hand != Hand.MAIN_HAND) {
            Msg.actionBar(p, "§7" + Msg.trFor(p, "secret.backpack-main-hand"));
            return;
        }
        cool(p, s, 20);
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, inv, pl) -> new BackpackHandler(syncId, inv, s),
                s.getName()));
        sound(p.getEntityWorld(), p.getEntityPos(), "backpack_open", 0.8f);
    }

    /** 27 slots kept inside the backpack item. Closes if the backpack leaves the hand; never holds backpacks or shulkers. */
    static final class BackpackHandler extends GenericContainerScreenHandler {
        private final ItemStack bag;
        private final SimpleInventory items;
        private final PlayerEntity owner;

        BackpackHandler(int syncId, PlayerInventory playerInv, ItemStack bag) {
            this(syncId, playerInv, bag, load(bag));
        }

        private BackpackHandler(int syncId, PlayerInventory playerInv, ItemStack bag, SimpleInventory items) {
            super(ScreenHandlerType.GENERIC_9X3, syncId, playerInv, items, 3);
            this.bag = bag;
            this.items = items;
            this.owner = playerInv.player;
        }

        private static SimpleInventory load(ItemStack bag) {
            DefaultedList<ItemStack> list = DefaultedList.ofSize(27, ItemStack.EMPTY);
            bag.getOrDefault(DataComponentTypes.CONTAINER, ContainerComponent.DEFAULT).copyTo(list);
            SimpleInventory inv = new SimpleInventory(27);
            for (int i = 0; i < 27; i++) {
                inv.setStack(i, list.get(i));
            }
            return inv;
        }

        private void save() {
            List<ItemStack> list = new ArrayList<>(27);
            for (int i = 0; i < 27; i++) {
                list.add(items.getStack(i).copy());
            }
            bag.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(list));
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return player.getMainHandStack() == bag && !bag.isEmpty();
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // The backpack itself can't be moved while it's open, and number-key swaps are off.
            if (actionType == SlotActionType.SWAP || (slotIndex >= 0 && slotIndex < slots.size() && slots.get(slotIndex).getStack() == bag)) {
                return;
            }
            super.onSlotClick(slotIndex, button, actionType, player);
            for (int i = 0; i < 27; i++) {
                ItemStack in = items.getStack(i);
                if (!in.isEmpty() && (is(in, BACKPACK) || (in.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock))) {
                    items.setStack(i, ItemStack.EMPTY);
                    owner.getInventory().offerOrDrop(in);
                }
            }
            save();
        }

        @Override
        public void onClosed(PlayerEntity player) {
            super.onClosed(player);
            save();
        }
    }

    // ---------------------------------------------------------------- grappling hook

    private static void grapple(ServerPlayerEntity p, ItemStack s, FishingBobberEntity hook) {
        if (hook.getHookedEntity() != null) {
            return;
        }
        ServerWorld w = p.getEntityWorld();
        boolean stuck = hook.isOnGround() || !w.isSpaceEmpty(hook, hook.getBoundingBox().expand(0.2));
        if (!stuck || hook.isTouchingWater()) {
            return;
        }
        Vec3d d = hook.getEntityPos().subtract(p.getEntityPos());
        double dist = d.length();
        if (dist < 2 || dist > 32) {
            return;
        }
        Vec3d v = d.normalize().multiply(Math.min(2.0, 0.3 + dist * 0.1)).add(0, 0.35 + Math.max(0, d.y) * 0.03, 0);
        push(p, v);
        cool(p, s, 60);
        w.spawnParticles(ParticleTypes.CRIT, p.getX(), p.getY() + 1, p.getZ(), 12, 0.3, 0.3, 0.3, 0.2);
        sound(w, p.getEntityPos(), "grapple", 1f);
    }

    // ---------------------------------------------------------------- hammer / lumber axe

    private static int extra;

    /** True while the hammer or lumber axe breaks the extra blocks (they aren't counted by the anti-cheat). */
    public static boolean extraBreak() {
        return extra > 0;
    }

    public static void afterBreak(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState state) {
        if (extra > 0 || p.isSneaking() || p.isCreative()) {
            return;
        }
        ItemStack s = p.getMainHandStack();
        String id = idOf(s);
        if (id == null || p.getItemCooldownManager().isCoolingDown(s)) {
            return;
        }
        if (HAMMER.equals(id) && s.isSuitableFor(state)) {
            hammer(p, w, pos, state, s);
        } else if (LUMBER.equals(id) && state.isIn(BlockTags.LOGS)) {
            lumber(p, w, pos, s);
        }
    }

    private static void hammer(ServerPlayerEntity p, ServerWorld w, BlockPos pos, BlockState state, ItemStack s) {
        float hardness = state.getHardness(w, pos);
        Direction.Axis axis;
        if (Math.abs(p.getPitch()) > 50) {
            axis = Direction.Axis.Y;
        } else {
            axis = p.getHorizontalFacing().getAxis();
        }
        List<BlockPos> around = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                around.add(switch (axis) {
                    case Y -> pos.add(a, 0, b);
                    case X -> pos.add(0, a, b);
                    default -> pos.add(a, b, 0);
                });
            }
        }
        cool(p, s, 8);
        extra++;
        try {
            for (BlockPos o : around) {
                if (s.isEmpty() || p.getMainHandStack() != s) {
                    break;
                }
                BlockState st = w.getBlockState(o);
                float h = st.getHardness(w, o);
                if (st.isAir() || h < 0 || h > hardness + 1.5f || w.getBlockEntity(o) != null || !s.isSuitableFor(st)) {
                    continue;
                }
                p.interactionManager.tryBreakBlock(o);
            }
        } finally {
            extra--;
        }
        sound(w, Vec3d.ofCenter(pos), "hammer", 0.7f);
    }

    private static void lumber(ServerPlayerEntity p, ServerWorld w, BlockPos start, ItemStack s) {
        // Only real trees: connected logs that have natural (not player-placed) leaves next to them.
        Set<BlockPos> logs = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        boolean leaves = false;
        queue.add(start);
        while (!queue.isEmpty() && logs.size() < 64) {
            BlockPos c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = c.add(dx, dy, dz);
                        if (logs.contains(n) || n.equals(start) || Math.abs(n.getX() - start.getX()) > 8 || Math.abs(n.getZ() - start.getZ()) > 8) {
                            continue;
                        }
                        BlockState st = w.getBlockState(n);
                        if (st.isIn(BlockTags.LOGS)) {
                            logs.add(n);
                            queue.add(n);
                        } else if (st.getBlock() instanceof LeavesBlock && !st.get(LeavesBlock.PERSISTENT)) {
                            leaves = true;
                        }
                    }
                }
            }
        }
        if (!leaves || logs.isEmpty()) {
            return;
        }
        cool(p, s, 60);
        List<BlockPos> order = new ArrayList<>(logs);
        order.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        extra++;
        try {
            for (BlockPos o : order) {
                if (s.isEmpty() || p.getMainHandStack() != s) {
                    break;
                }
                p.interactionManager.tryBreakBlock(o);
            }
        } finally {
            extra--;
        }
        sound(w, Vec3d.ofCenter(start), "lumber", 1f);
    }

    // ---------------------------------------------------------------- sunken vault lock

    /** The code on the vault's signs: open, shut, shut, open (the same read from either end). */
    static final boolean[] VAULT_CODE = {true, false, false, true};

    /** A trapdoor was flipped: if it's part of a sunken vault lock, check the code. */
    public static void lockFlipped(ServerWorld w, BlockPos lever) {
        OwnerPowers.later(1, () -> {
            BlockPos door = null;
            for (BlockPos q : BlockPos.iterate(lever.add(-5, -3, -5), lever.add(5, 3, 5))) {
                if (w.getBlockState(q).getBlock() instanceof DoorBlock && w.getBlockState(q).isOf(Blocks.IRON_DOOR)
                        && w.getBlockState(q.down()).isOf(Blocks.REINFORCED_DEEPSLATE)) {
                    door = q.toImmutable();
                    break;
                }
            }
            if (door == null) {
                return;
            }
            List<BlockPos> levers = new ArrayList<>();
            for (BlockPos q : BlockPos.iterate(door.add(-5, -2, -5), door.add(5, 3, 5))) {
                if (w.getBlockState(q).getBlock() instanceof net.minecraft.block.TrapdoorBlock) {
                    levers.add(q.toImmutable());
                }
            }
            if (levers.size() != VAULT_CODE.length) {
                return;
            }
            levers.sort((a, b) -> Integer.compare(a.getX() + a.getZ(), b.getX() + b.getZ()));
            boolean ok = true;
            for (int i = 0; i < levers.size(); i++) {
                if (w.getBlockState(levers.get(i)).get(net.minecraft.block.TrapdoorBlock.OPEN) != VAULT_CODE[i]) {
                    ok = false;
                    break;
                }
            }
            BlockState ds = w.getBlockState(door);
            boolean open = ds.get(DoorBlock.OPEN);
            if (ok != open) {
                ((DoorBlock) ds.getBlock()).setOpen(null, w, ds, door, ok);
                sound(w, Vec3d.ofCenter(door), ok ? "vault_open" : "vault_wrong", 1.2f);
            }
        });
    }

    // ---------------------------------------------------------------- loot

    /** Chests where a Seeker Compass can turn up (8%), so the first secret place can be found. */
    private static final List<String> COMPASS_CHESTS = List.of("chests/simple_dungeon", "chests/abandoned_mineshaft",
            "chests/desert_pyramid", "chests/buried_treasure", "chests/shipwreck_map");

    public static void register() {
        net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY.register((key, table, source, registries) -> {
            if (!source.isBuiltin() || !"minecraft".equals(key.getValue().getNamespace()) || !COMPASS_CHESTS.contains(key.getValue().getPath())) {
                return;
            }
            table.pool(net.minecraft.loot.LootPool.builder()
                    .rolls(net.minecraft.loot.provider.number.ConstantLootNumberProvider.create(1))
                    .conditionally(net.minecraft.loot.condition.RandomChanceLootCondition.builder(0.08f))
                    .with(net.minecraft.loot.entry.LootTableEntry.builder(net.minecraft.registry.RegistryKey.of(RegistryKeys.LOOT_TABLE,
                            Identifier.of("vigil", "items/seeker_compass")))));
        });
    }

    // ---------------------------------------------------------------- every tick

    public static void tick(long ticks) {
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (p.isSpectator()) {
                continue;
            }
            shadowTick(p, ticks);
            if (ticks % 40 == 0) {
                var inv = p.getInventory();
                for (int i = 0; i < inv.size(); i++) {
                    com.vylorq.anticheat.util.PackIds.upgrade(inv.getStack(i));
                }
                var ender = p.getEnderChestInventory();
                for (int i = 0; i < ender.size(); i++) {
                    com.vylorq.anticheat.util.PackIds.upgrade(ender.getStack(i));
                }
            }
            if (ticks % 4 == 0 && magnetOn(p)) {
                magnetTick(p);
            }
        }
        if (ticks % 6000 == 0) {
            LAST_USE.clear();
            PHOENIX_COOLDOWN.values().removeIf(t -> System.currentTimeMillis() - t > phoenixMillis());
        }
    }
}
