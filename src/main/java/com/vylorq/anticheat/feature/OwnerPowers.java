package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.config.ConfigManager;
import com.vylorq.anticheat.perm.Perms;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.GameMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Powers that only the owner has (after logging in with the admin PIN): flying in survival, god mode, speed, night
 * vision, instant breaking, a player radar and a ghost mode. They stay on across rejoins. While any is on, the
 * anti-cheat leaves the owner alone.
 */
public final class OwnerPowers {
    private OwnerPowers() {
    }

    public enum Power { FLY, GOD, SPEED, NIGHT_VISION, INSTA_BREAK, RADAR, GHOST,
        ONE_PUNCH, LIFESTEAL, MEGA_KNOCKBACK, NO_COOLDOWN, FORCE_FIELD }

    /** The combat toggles (shown in the combat menu, not the main one). */
    public static boolean combat(Power p) {
        return p.ordinal() >= Power.ONE_PUNCH.ordinal();
    }

    /** Saved in config/vigil/owner.json. */
    public static final class State {
        public boolean fly;
        public boolean god;
        public int speed;
        public boolean nightVision;
        public boolean instaBreak;
        public boolean radar;
        public Ghost ghost;
        public boolean realLightning;
        /** normal, grand or silent. */
        public String joinStyle = "normal";
        /** Secret item id -> cooldown in seconds the owner set (replaces the item's own). */
        public Map<String, Integer> itemCooldowns = new java.util.LinkedHashMap<>();
        /** Freeze wand: how far the circle reaches (blocks from the owner). */
        public int freezeRadius = 10;
        /** Players the freeze wand froze (so thawing only lets those go). */
        public java.util.Set<String> wandFrozen = new java.util.HashSet<>();
        public boolean onePunch;
        public boolean lifesteal;
        public boolean megaKnockback;
        public boolean noCooldown;
        public boolean forceField;
        /** Mob wipe: how far it reaches. */
        public int wipeRadius = 32;
        /** Orbital strike settings. */
        public OrbitalStrike.Settings orbital = new OrbitalStrike.Settings();
    }

    public static final class Ghost {
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
        public String mode;
    }

    private static State state;
    private static long lastToolUse;
    private static final Identifier SPEED_ID = Identifier.of("vigil", "owner_speed");
    private static final Identifier COOLDOWN_ID = Identifier.of("vigil", "owner_no_cooldown");

    // ---------------------------------------------------------------- state

    private static Path file() {
        return Ac.get().dir.resolve("owner.json");
    }

    public static State state() {
        if (state == null) {
            try {
                Path f = file();
                state = Files.exists(f) ? ConfigManager.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), State.class) : null;
            } catch (Exception e) {
                Ac.LOG.warn("Could not read owner.json", e);
            }
            if (state == null) {
                state = new State();
            }
            if (state.wandFrozen == null) {
                state.wandFrozen = new java.util.HashSet<>();
            }
            if (state.freezeRadius <= 0) {
                state.freezeRadius = 10;
            }
            if (state.wipeRadius <= 0) {
                state.wipeRadius = 32;
            }
        }
        return state;
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), ConfigManager.GSON.toJson(state()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Ac.LOG.warn("Could not save owner.json", e);
        }
    }

    /** The owner, logged in with the PIN. */
    public static boolean owner(ServerPlayerEntity p) {
        return p != null && Perms.isOwner(p.getUuid()) && Perms.pinOk(p);
    }

    /** Checks and explains. */
    public static boolean require(ServerPlayerEntity p) {
        if (owner(p)) {
            return true;
        }
        Msg.send(p, Perms.isOwner(p.getUuid()) ? "staff.pin.required" : "owner.only");
        return false;
    }

    public static boolean on(ServerPlayerEntity p, Power power) {
        if (!owner(p)) {
            return false;
        }
        State s = state();
        return switch (power) {
            case FLY -> s.fly;
            case GOD -> s.god;
            case SPEED -> s.speed > 0;
            case NIGHT_VISION -> s.nightVision;
            case INSTA_BREAK -> s.instaBreak;
            case RADAR -> s.radar;
            case GHOST -> s.ghost != null;
            case ONE_PUNCH -> s.onePunch;
            case LIFESTEAL -> s.lifesteal;
            case MEGA_KNOCKBACK -> s.megaKnockback;
            case NO_COOLDOWN -> s.noCooldown;
            case FORCE_FIELD -> s.forceField;
        };
    }

    /** The anti-cheat leaves the owner alone while powers are on (or a tool was just used). */
    public static boolean exempt(UUID id) {
        if (!Perms.isOwner(id)) {
            return false;
        }
        State s = state();
        return s.fly || s.god || s.speed > 0 || s.instaBreak || s.ghost != null
                || s.onePunch || s.lifesteal || s.megaKnockback || s.noCooldown || s.forceField || OwnerCombat.berserk(id)
                || System.currentTimeMillis() - lastToolUse < 10_000;
    }

    static void usedTool() {
        lastToolUse = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- sounds

    /** A sound from the owner pack (silent without it), plus a vanilla one everyone hears. */
    public static void sfx(ServerPlayerEntity p, String custom, SoundEvent vanilla, float pitch) {
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound(custom))),
                SoundCategory.PLAYERS, p.getX(), p.getY(), p.getZ(), 1f, 1f, p.getRandom().nextLong()));
        if (vanilla != null) {
            p.getEntityWorld().playSound(null, p.getX(), p.getY(), p.getZ(), vanilla, SoundCategory.PLAYERS, 0.7f, pitch);
        }
    }

    // ---------------------------------------------------------------- toggles

    public static void toggle(ServerPlayerEntity p, Power power) {
        if (!require(p)) {
            return;
        }
        State s = state();
        boolean now;
        switch (power) {
            case FLY -> now = s.fly = !s.fly;
            case GOD -> now = s.god = !s.god;
            case SPEED -> {
                s.speed = (s.speed + 1) % 4;
                now = s.speed > 0;
            }
            case NIGHT_VISION -> now = s.nightVision = !s.nightVision;
            case INSTA_BREAK -> now = s.instaBreak = !s.instaBreak;
            case RADAR -> {
                now = s.radar = !s.radar;
                radarPush(p, now);
            }
            case GHOST -> now = ghost(p);
            case ONE_PUNCH -> now = s.onePunch = !s.onePunch;
            case LIFESTEAL -> now = s.lifesteal = !s.lifesteal;
            case MEGA_KNOCKBACK -> now = s.megaKnockback = !s.megaKnockback;
            case NO_COOLDOWN -> now = s.noCooldown = !s.noCooldown;
            case FORCE_FIELD -> now = s.forceField = !s.forceField;
            default -> now = false;
        }
        save();
        apply(p);
        String name = Msg.trFor(p, "owner.power." + power.name().toLowerCase());
        if (power == Power.SPEED) {
            name += s.speed > 0 ? " " + s.speed : "";
        }
        Msg.send(p, now ? "owner.on" : "owner.off", name);
        OwnerFx.powerSound(p, power, now);
        ServerWorld w = p.getEntityWorld();
        w.spawnParticles(now ? ParticleTypes.END_ROD : ParticleTypes.SMOKE, p.getX(), p.getY() + 1, p.getZ(), 24, 0.4, 0.6, 0.4, 0.05);
        Staff.log(p, "owner-power", p.getUuid(), p.getGameProfile().name(), power.name().toLowerCase() + (now ? " on" : " off"));
    }

    public static void setSpeed(ServerPlayerEntity p, int level) {
        if (!require(p)) {
            return;
        }
        state().speed = Math.max(0, Math.min(3, level));
        save();
        apply(p);
        Msg.send(p, level > 0 ? "owner.on" : "owner.off", Msg.trFor(p, "owner.power.speed") + (level > 0 ? " " + level : ""));
        OwnerFx.powerSound(p, Power.SPEED, level > 0);
    }

    private static boolean ghost(ServerPlayerEntity p) {
        State s = state();
        if (s.ghost == null) {
            Ghost g = new Ghost();
            g.world = Mc.worldId(p.getEntityWorld());
            g.x = p.getX();
            g.y = p.getY();
            g.z = p.getZ();
            g.yaw = p.getYaw();
            g.pitch = p.getPitch();
            g.mode = p.interactionManager.getGameMode().asString();
            s.ghost = g;
            p.changeGameMode(GameMode.SPECTATOR);
            return true;
        }
        Ghost g = s.ghost;
        s.ghost = null;
        ServerWorld w = Mc.world(Ac.server(), g.world);
        Mc.teleport(p, w != null ? w : p.getEntityWorld(), g.x, g.y, g.z, g.yaw, g.pitch);
        GameMode mode = GameMode.byId(g.mode, GameMode.SURVIVAL);
        p.changeGameMode(mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : mode);
        return false;
    }

    /** Puts the saved powers on the owner (after login, respawn, world change...). Called twice a second. */
    public static void apply(ServerPlayerEntity p) {
        boolean ok = owner(p);
        State s = state();
        boolean creativeLike = p.isCreative() || p.isSpectator();
        // Fly
        if (!creativeLike) {
            boolean fly = ok && s.fly;
            if (p.getAbilities().allowFlying != fly) {
                p.getAbilities().allowFlying = fly;
                if (!fly) {
                    if (p.getAbilities().flying && !p.isOnGround()) {
                        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0, false, false, true));
                    }
                    p.getAbilities().flying = false;
                }
                p.sendAbilitiesUpdate();
            }
        }
        // Speed (walking, flying and swimming)
        EntityAttributeInstance speed = p.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
        int level = ok ? s.speed : 0;
        if (speed != null) {
            EntityAttributeModifier m = speed.getModifier(SPEED_ID);
            double want = level * 0.4;
            if (m == null ? level > 0 : m.value() != want) {
                speed.removeModifier(SPEED_ID);
                if (level > 0) {
                    speed.addTemporaryModifier(new EntityAttributeModifier(SPEED_ID, want, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                }
            }
        }
        float fly = 0.05f * (1 + level * 0.6f);
        if (Math.abs(p.getAbilities().getFlySpeed() - fly) > 0.0001f) {
            p.getAbilities().setFlySpeed(fly);
            p.sendAbilitiesUpdate();
        }
        if (level > 0 && !p.hasStatusEffect(StatusEffects.DOLPHINS_GRACE)) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DOLPHINS_GRACE, StatusEffectInstance.INFINITE, 0, false, false, false));
        } else if (level == 0 && p.getStatusEffect(StatusEffects.DOLPHINS_GRACE) != null
                && p.getStatusEffect(StatusEffects.DOLPHINS_GRACE).isInfinite()) {
            p.removeStatusEffect(StatusEffects.DOLPHINS_GRACE);
        }
        // No cooldown: attacks recharge instantly (always a full-power hit).
        EntityAttributeInstance atk = p.getAttributeInstance(EntityAttributes.ATTACK_SPEED);
        boolean fast = ok && s.noCooldown;
        if (atk != null && (atk.getModifier(COOLDOWN_ID) != null) != fast) {
            atk.removeModifier(COOLDOWN_ID);
            if (fast) {
                atk.addTemporaryModifier(new EntityAttributeModifier(COOLDOWN_ID, 1000, EntityAttributeModifier.Operation.ADD_VALUE));
            }
        }
        // Night vision
        var nv = p.getStatusEffect(StatusEffects.NIGHT_VISION);
        if (ok && s.nightVision && (nv == null || !nv.isInfinite())) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, StatusEffectInstance.INFINITE, 0, false, false, true));
        } else if (!(ok && s.nightVision) && nv != null && nv.isInfinite()) {
            p.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
        // God mode: never hungry either
        if (ok && s.god) {
            p.getHungerManager().setFoodLevel(20);
            p.getHungerManager().setSaturationLevel(20f);
            p.setAir(p.getMaxAir());
            p.extinguish();
        }
    }

    private record Delayed(long at, Runnable r) {
    }

    private static final java.util.List<Delayed> DELAYED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static long now;

    static void later(int ticks, Runnable r) {
        DELAYED.add(new Delayed(now + ticks, r));
    }

    public static void tick(long ticks) {
        now = ticks;
        for (Delayed d : DELAYED) {
            if (d.at() <= ticks) {
                DELAYED.remove(d);
                try {
                    d.r().run();
                } catch (Exception e) {
                    Ac.LOG.warn("Owner task failed", e);
                }
            }
        }
        if (ticks % 10 != 0) {
            return;
        }
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            if (Perms.isOwner(p.getUuid())) {
                apply(p);
                if (ticks % 40 == 0 && on(p, Power.RADAR)) {
                    radarPush(p, true);
                }
            } else if (ticks % 100 == 0) {
                OwnerTools.confiscate(p);
            }
        }
    }

    /** God mode: no damage at all. */
    public static boolean blocksDamage(ServerPlayerEntity p) {
        return on(p, Power.GOD);
    }

    // ---------------------------------------------------------------- goto

    /** Takes the owner to x, z (any distance): on the ground there, or at y when given. Stays inside the world border. */
    public static void goTo(ServerPlayerEntity p, int x, int z, Integer y) {
        if (!require(p)) {
            return;
        }
        net.minecraft.server.world.ServerWorld w = p.getEntityWorld();
        var border = w.getWorldBorder();
        x = (int) Math.max(border.getBoundWest() + 1, Math.min(border.getBoundEast() - 1, x));
        z = (int) Math.max(border.getBoundNorth() + 1, Math.min(border.getBoundSouth() - 1, z));
        w.getChunk(x >> 4, z >> 4); // generates it if nobody has been there
        int ty;
        if (y != null) {
            ty = y;
        } else if (w.getDimension().hasCeiling()) {
            // The Nether: the first space with room to stand, from the bottom up (not on its roof).
            ty = w.getBottomY() + 1;
            for (int yy = w.getBottomY() + 1; yy < 120; yy++) {
                var pos = new net.minecraft.util.math.BlockPos(x, yy, z);
                if (w.getBlockState(pos.down()).isSolidBlock(w, pos.down()) && w.getBlockState(pos).isAir() && w.getBlockState(pos.up()).isAir()) {
                    ty = yy;
                    break;
                }
            }
        } else {
            ty = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
            if (ty <= w.getBottomY()) {
                ty = 100; // nothing there (the End's void): stay up and fly
            }
        }
        Mc.teleport(p, w, x + 0.5, ty, z + 0.5, p.getYaw(), p.getPitch());
        Msg.send(p, "owner.goto", x, ty, z);
    }

    // ---------------------------------------------------------------- radar

    /** Entity flag byte as the server would send it, with or without the glow bit. */
    static byte flags(net.minecraft.entity.Entity e, boolean glow) {
        int f = 0;
        if (e.isOnFire()) f |= 0x01;
        if (e.isSneaking()) f |= 0x02;
        if (e.isSprinting()) f |= 0x08;
        if (e.isSwimming()) f |= 0x10;
        if (e.isInvisible()) f |= 0x20;
        if (glow || e.isGlowing()) f |= 0x40;
        if (e instanceof net.minecraft.entity.LivingEntity l && l.isGliding()) f |= 0x80;
        return (byte) f;
    }

    /** Sends every other player's flags to the owner, glowing (or back to normal). */
    static void radarPush(ServerPlayerEntity owner, boolean glow) {
        for (ServerPlayerEntity other : owner.getEntityWorld().getPlayers()) {
            if (other != owner) {
                owner.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(other.getId(), List.of(
                        new DataTracker.SerializedEntry<>(0, TrackedDataHandlerRegistry.BYTE, flags(other, glow)))));
            }
        }
    }

    /** Outgoing packets to the owner: keep other players glowing while the radar is on. */
    public static net.minecraft.network.packet.Packet<?> radarRewrite(ServerPlayerEntity to, net.minecraft.network.packet.Packet<?> packet) {
        if (!(packet instanceof EntityTrackerUpdateS2CPacket u) || !Perms.isOwner(to.getUuid()) || !state().radar || !owner(to)) {
            return packet;
        }
        var e = to.getEntityWorld().getEntityById(u.id());
        if (!(e instanceof PlayerEntity) || e == to) {
            return packet;
        }
        List<DataTracker.SerializedEntry<?>> out = new ArrayList<>(u.trackedValues().size());
        boolean changed = false;
        for (DataTracker.SerializedEntry<?> entry : u.trackedValues()) {
            if (entry.id() == 0 && entry.value() instanceof Byte b) {
                out.add(new DataTracker.SerializedEntry<>(0, TrackedDataHandlerRegistry.BYTE, (byte) (b | 0x40)));
                changed = true;
            } else {
                out.add(entry);
            }
        }
        return changed ? new EntityTrackerUpdateS2CPacket(u.id(), out) : packet;
    }

    // ---------------------------------------------------------------- joining

    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("vigil-owner-pack".getBytes(StandardCharsets.UTF_8));

    static String packHash() {
        try (var in = OwnerPowers.class.getResourceAsStream("/vigil/owner-pack.sha1")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Sends the Vigil texture and sound pack (required for everyone unless turned off in the config). */
    public static void sendPack(ServerPlayerEntity p) {
        var cfg = Ac.config().owner;
        if (cfg.packUrl == null || cfg.packUrl.isBlank()) {
            return;
        }
        if (cfg.packUrl.endsWith("/resourcepack/vigil-owner.zip")) {
            // The pack was renamed.
            cfg.packUrl = cfg.packUrl.replace("/resourcepack/vigil-owner.zip", "/resourcepack/pack.zip");
        }
        boolean required = cfg.packRequired && cfg.packForEveryone;
        p.networkHandler.sendPacket(new ResourcePackSendS2CPacket(PACK_ID, cfg.packUrl, packHash(), required,
                Optional.of(Text.literal(Msg.trFor(p, Perms.isOwner(p.getUuid()) ? "owner.pack-prompt" : "pack.prompt")))));
    }

    /** The player's answer to the pack. Declining or failing to load it disconnects them when it's required. */
    public static void packStatus(ServerPlayerEntity p, UUID id, String status) {
        var cfg = Ac.config().owner;
        if (!PACK_ID.equals(id) || !cfg.packRequired || !cfg.packForEveryone || com.vylorq.anticheat.ui.Viewer.isBedrock(p)) {
            return;
        }
        switch (status) {
            case "DECLINED", "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD", "DISCARDED" -> Ac.server().execute(() -> {
                if (!p.isDisconnected()) {
                    Ac.LOG.info("{} didn't load the Vigil pack ({}): disconnecting", p.getGameProfile().name(), status);
                    p.networkHandler.disconnect(Text.literal(Msg.trFor(p, "DECLINED".equals(status) ? "pack.declined" : "pack.failed")));
                }
            });
            default -> {
            }
        }
    }

    /** Join message: a silent owner is only shown to staff. */
    public static boolean silentJoin(ServerPlayerEntity p) {
        return Perms.isOwner(p.getUuid()) && "silent".equals(state().joinStyle);
    }

    public static void onJoin(ServerPlayerEntity p) {
        var cfg = Ac.config().owner;
        boolean owner = Perms.isOwner(p.getUuid());
        // Bedrock players can't load Java packs (Geyser shows them the normal items).
        if (cfg.sendPack && (owner || cfg.packForEveryone) && !com.vylorq.anticheat.ui.Viewer.isBedrock(p)) {
            sendPack(p);
        }
        if (!owner) {
            OwnerTools.confiscate(p);
            return;
        }
        if ("grand".equals(state().joinStyle)) {
            var server = Ac.server();
            String name = p.getGameProfile().name();
            // A moment later, so everyone's game has the owner loaded.
            later(40, () -> {
                if (p.isRemoved()) {
                    return;
                }
                for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                    o.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 20));
                    o.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§6✦ " + name + " ✦")));
                    o.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§e" + Msg.trFor(o, "owner.arrived"))));
                    o.networkHandler.sendPacket(new PlaySoundS2CPacket(RegistryEntry.of(SoundEvent.of(com.vylorq.anticheat.util.PackIds.sound("owner_join"))),
                            SoundCategory.MASTER, o.getX(), o.getY(), o.getZ(), 1f, 1f, 0));
                    Mc.sound(o, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1f);
                }
                ServerWorld w = p.getEntityWorld();
                w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 120, 0.6, 1.0, 0.6, 0.4);
                w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 60, 1.2, 1.2, 1.2, 0.05);
            });
        }
    }

    public static void setFreezeRadius(ServerPlayerEntity p, int radius) {
        if (!require(p)) {
            return;
        }
        state().freezeRadius = Math.max(1, Math.min(100, radius));
        save();
        Msg.send(p, "owner.freeze-radius-set", state().freezeRadius);
        sfx(p, "mode", SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.4f);
    }

    public static void setWipeRadius(ServerPlayerEntity p, int radius) {
        if (!require(p)) {
            return;
        }
        state().wipeRadius = Math.max(4, Math.min(128, radius));
        save();
        Msg.send(p, "owner.wipe-radius-set", state().wipeRadius);
        sfx(p, "mode", SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), 1.4f);
    }

    public static void setJoinStyle(ServerPlayerEntity p, String style) {
        if (!require(p)) {
            return;
        }
        state().joinStyle = style;
        save();
        Msg.send(p, "owner.join-style", Msg.trFor(p, "owner.join." + style));
        sfx(p, "mode", SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
    }
}
