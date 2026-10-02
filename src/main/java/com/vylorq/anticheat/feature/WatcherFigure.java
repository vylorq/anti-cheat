package com.vylorq.anticheat.feature;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.mixin.PlayerEntityAccessor;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * A player-shaped figure that exists only in one player's client (33.2). It's never added to the world, so it can't
 * be hit, pushed or interacted with: the server doesn't know an entity with its id. It isn't listed in the tab list
 * and its name tag is hidden with a team that only that client knows about.
 */
public final class WatcherFigure {
    private static final UUID WATCHER_UUID = UUID.fromString("5f1c0a7e-0d6b-4c4e-9a9e-3b0c4f6e7a21");
    private static final String WATCHER_NAME = "Watcher";
    private static int teamCounter;

    private final FakePlayer fake;
    private final GameProfile profile;
    private final boolean dressed;
    private final double scale;
    private final String teamName;
    /** A dark skull for a face instead of the faceless hood (for jumpscares, seen up close). */
    private boolean skull;
    /** Invisible body: only the armour and the skull show, floating, with no hands. */
    private boolean hollow;
    public Vec3d pos = Vec3d.ZERO;
    public float yaw;
    public float pitch;

    private WatcherFigure(ServerWorld world, GameProfile profile, boolean dressed, double scale) {
        this.profile = profile;
        this.fake = FakePlayer.get(world, profile);
        this.dressed = dressed;
        this.scale = scale;
        this.teamName = "acw" + (teamCounter++ % 100000);
    }

    /** The tall hooded figure. Uses the configured skin if there is one, otherwise a black outfit. */
    public static WatcherFigure watcher(ServerWorld world) {
        var cfg = Ac.config().watcher;
        boolean skin = cfg.skinValue != null && !cfg.skinValue.isBlank();
        PropertyMap props = skin
                ? new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", cfg.skinValue.trim(),
                cfg.skinSignature == null || cfg.skinSignature.isBlank() ? null : cfg.skinSignature.trim())))
                : PropertyMap.EMPTY;
        WatcherFigure f = new WatcherFigure(world, new GameProfile(WATCHER_UUID, WATCHER_NAME, props), !skin, skin ? 1.25 : 1.35);
        if (!skin) {
            // No skin set: an empty suit of black armour with a skull for a head, nothing inside it.
            f.skull = true;
            f.hollow = true;
        }
        return f;
    }

    /** The player's own skin, name hidden (33.5 doppelgänger). */
    public static WatcherFigure doppelganger(ServerPlayerEntity of) {
        UUID id = UUID.nameUUIDFromBytes(("watcher-double:" + of.getUuid()).getBytes(StandardCharsets.UTF_8));
        GameProfile real = of.getGameProfile();
        return new WatcherFigure(of.getEntityWorld(), new GameProfile(id, "Double", real.properties()), false, 1.0);
    }

    /** Gives it a face: a dark skull instead of the faceless hood. */
    public WatcherFigure skull() {
        this.skull = true;
        return this;
    }

    public int id() {
        return fake.getId();
    }

    /** Eye position (for gaze checks). */
    public Vec3d head() {
        return pos.add(0, 1.62 * scale, 0);
    }

    public WatcherFigure at(Vec3d pos, float yaw, float pitch) {
        this.pos = pos;
        this.yaw = yaw;
        this.pitch = pitch;
        return this;
    }

    private static byte angle(float degrees) {
        return (byte) MathHelper.floor(degrees * 256.0F / 360.0F);
    }

    public void show(ServerPlayerEntity viewer) {
                // Only ADD_PLAYER: the client learns the skin but doesn't list it in the tab list.
        Watcher.send(viewer, new PlayerListS2CPacket(EnumSet.of(PlayerListS2CPacket.Action.ADD_PLAYER), List.<ServerPlayerEntity>of(fake)));
        Scoreboard board = new Scoreboard();
        Team team = board.addTeam(teamName);
        team.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
        team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        board.addScoreHolderToTeam(profile.name(), team);
        Watcher.send(viewer, TeamS2CPacket.updateTeam(team, true));
        Watcher.send(viewer, new EntitySpawnS2CPacket(id(), profile.id(), pos.x, pos.y, pos.z, pitch, yaw, EntityType.PLAYER, 0,
                Vec3d.ZERO, yaw));
        // Show every skin layer (hood, sleeves...).
        Watcher.send(viewer, new EntityTrackerUpdateS2CPacket(id(), List.of(
                DataTracker.SerializedEntry.of(PlayerEntityAccessor.ac$modelParts(), (byte) 0x7F))));
        if (hollow) {
            // Entity flag 0x20: invisible (armour still shows).
            Watcher.send(viewer, new EntityTrackerUpdateS2CPacket(id(), List.of(
                    new DataTracker.SerializedEntry<>(0, net.minecraft.entity.data.TrackedDataHandlerRegistry.BYTE, (byte) 0x20))));
        }
        if (dressed) {
            Watcher.send(viewer, new EntityEquipmentUpdateS2CPacket(id(), outfit(skull)));
        }
        if (scale != 1.0) {
            EntityAttributeInstance inst = new EntityAttributeInstance(EntityAttributes.SCALE, i -> {
            });
            inst.setBaseValue(scale);
            Watcher.send(viewer, new EntityAttributesS2CPacket(id(), List.of(inst)));
        }
        Watcher.send(viewer, new EntitySetHeadYawS2CPacket(fake, angle(yaw)));
    }

    private static List<Pair<EquipmentSlot, ItemStack>> outfit(boolean skull) {
        List<Pair<EquipmentSlot, ItemStack>> out = new ArrayList<>();
        // A black block over the head reads as a faceless hood from any distance; up close, a dark skull.
        out.add(Pair.of(EquipmentSlot.HEAD, new ItemStack(skull ? Items.WITHER_SKELETON_SKULL : Items.BLACK_CONCRETE)));
        out.add(Pair.of(EquipmentSlot.CHEST, black(Items.LEATHER_CHESTPLATE)));
        out.add(Pair.of(EquipmentSlot.LEGS, black(Items.LEATHER_LEGGINGS)));
        out.add(Pair.of(EquipmentSlot.FEET, black(Items.LEATHER_BOOTS)));
        return out;
    }

    private static ItemStack black(net.minecraft.item.Item item) {
        ItemStack s = new ItemStack(item);
        // Pitch black all over (the built-in skin adds the eyes and teeth).
        s.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0x050506));
        return s;
    }

    /** Turns head and body toward a point. */
    public void lookAt(ServerPlayerEntity viewer, Vec3d target) {
        Vec3d h = head();
        double dx = target.x - h.x;
        double dz = target.z - h.z;
        double dy = target.y - h.y;
        yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        rotate(viewer);
    }

    public void rotate(ServerPlayerEntity viewer) {
        Watcher.send(viewer, new EntityS2CPacket.Rotate(id(), angle(yaw), angle(pitch), true));
        Watcher.send(viewer, new EntitySetHeadYawS2CPacket(fake, angle(yaw)));
    }

    public void moveTo(ServerPlayerEntity viewer, Vec3d to, float yaw, float pitch) {
        this.pos = to;
        this.yaw = yaw;
        this.pitch = pitch;
        Watcher.send(viewer, new EntityPositionSyncS2CPacket(id(), new EntityPosition(to, Vec3d.ZERO, yaw, pitch), true));
        Watcher.send(viewer, new EntitySetHeadYawS2CPacket(fake, angle(yaw)));
    }

    /** Removes everything show() sent. Safe to call more than once. */
    public void hide(ServerPlayerEntity viewer) {
                Watcher.send(viewer, new EntitiesDestroyS2CPacket(id()));
        Watcher.send(viewer, new PlayerRemoveS2CPacket(List.of(profile.id())));
        Scoreboard board = new Scoreboard();
        Team team = board.addTeam(teamName);
        Watcher.send(viewer, TeamS2CPacket.updateRemovedTeam(team));
    }
}
