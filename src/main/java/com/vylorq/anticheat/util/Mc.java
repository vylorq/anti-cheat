package com.vylorq.anticheat.util;

import com.vylorq.anticheat.core.util.BlockPos3;
import com.vylorq.anticheat.core.util.Location;
import com.vylorq.anticheat.core.util.Vec3;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/** Small Minecraft helpers. Version-sensitive calls are kept here so a port only touches one file. */
public final class Mc {
    /** Vanilla permission level check (op level 0-4) for a command source. */
    public static boolean hasLevel(net.minecraft.server.command.ServerCommandSource src, int level) {
        return src.getPermissions().hasPermission(new net.minecraft.command.permission.Permission.Level(
                net.minecraft.command.permission.PermissionLevel.fromLevel(level)));
    }

    /** Vanilla permission level check (op level 0-4) for a player. */
    public static boolean hasLevel(net.minecraft.entity.player.PlayerEntity p, int level) {
        return p.getPermissions().hasPermission(new net.minecraft.command.permission.Permission.Level(
                net.minecraft.command.permission.PermissionLevel.fromLevel(level)));
    }

    /** World spawn block position (1.21.9+ keeps it in the server's spawn point). */
    public static BlockPos worldSpawn(MinecraftServer server) {
        return server.getSpawnPoint().getPos();
    }

    /** Loads saved block entity NBT into an existing block entity (1.21.6+ reads through a ReadView). */
    public static void loadBlockEntity(net.minecraft.block.entity.BlockEntity be, net.minecraft.nbt.NbtCompound nbt,
                                       net.minecraft.registry.RegistryWrapper.WrapperLookup lookup) {
        be.read(net.minecraft.storage.NbtReadView.create(net.minecraft.util.ErrorReporter.EMPTY, lookup, nbt));
        be.markDirty();
    }

    private Mc() {
    }

    public static String worldId(World w) {
        return w.getRegistryKey().getValue().toString();
    }

    public static ServerWorld world(MinecraftServer server, String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) {
            return null;
        }
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, ident));
    }

    public static Vec3 vec(Vec3d v) {
        return new Vec3(v.x, v.y, v.z);
    }

    public static Vec3d vec(Vec3 v) {
        return new Vec3d(v.x(), v.y(), v.z());
    }

    public static BlockPos3 pos(BlockPos p) {
        return new BlockPos3(p.getX(), p.getY(), p.getZ());
    }

    public static BlockPos pos(BlockPos3 p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    public static Location location(Entity e) {
        return new Location(worldId(e.getEntityWorld()), e.getX(), e.getY(), e.getZ(), e.getYaw(), e.getPitch());
    }

    /** Teleports any entity (players included) across worlds. */
    public static void teleport(Entity e, ServerWorld world, double x, double y, double z, float yaw, float pitch) {
        if (e.hasVehicle()) {
            e.stopRiding();
        }
        e.teleportTo(new TeleportTarget(world, new Vec3d(x, y, z), Vec3d.ZERO, yaw, pitch, TeleportTarget.NO_OP));
    }

    /** @return false if the world doesn't exist. */
    public static boolean teleport(Entity e, MinecraftServer server, Location l) {
        ServerWorld w = world(server, l.world());
        if (w == null) {
            return false;
        }
        teleport(e, w, l.x(), l.y(), l.z(), l.yaw(), l.pitch());
        return true;
    }

    public static void title(ServerPlayerEntity p, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
        if (subtitle != null) {
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(subtitle)));
        }
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title == null ? "" : title)));
    }

    public static void sound(ServerPlayerEntity p, SoundEvent sound, float volume, float pitch) {
        RegistryEntry<SoundEvent> entry = Registries.SOUND_EVENT.getEntry(sound);
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(entry, SoundCategory.MASTER, p.getX(), p.getY(), p.getZ(),
                volume, pitch, p.getRandom().nextLong()));
    }

    private static Method particleMethod;
    private static boolean particleWithImportant;

    /**
     * Sends a particle to one player. The ServerWorld method's parameter list changed across 1.21.x
     * (an extra "important" boolean), so it is looked up by its parameter types once.
     */
    public static void particle(ServerPlayerEntity p, ParticleEffect effect, double x, double y, double z) {
        try {
            if (particleMethod == null) {
                for (Method m : ServerWorld.class.getMethods()) {
                    Class<?>[] t = m.getParameterTypes();
                    if (t.length >= 11 && t[0] == ServerPlayerEntity.class && ParticleEffect.class.isAssignableFrom(t[1])
                            && t[2] == boolean.class) {
                        if (t.length == 12 && t[3] == boolean.class && t[4] == double.class) {
                            particleMethod = m;
                            particleWithImportant = true;
                            break;
                        }
                        if (t.length == 11 && t[3] == double.class) {
                            particleMethod = m;
                            particleWithImportant = false;
                        }
                    }
                }
                if (particleMethod == null) {
                    return;
                }
            }
            ServerWorld w = (ServerWorld) p.getEntityWorld();
            if (particleWithImportant) {
                particleMethod.invoke(w, p, effect, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            } else {
                particleMethod.invoke(w, p, effect, true, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        } catch (ReflectiveOperationException ignored) {
            // particles are cosmetic
        }
    }

    public static String itemId(net.minecraft.item.Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    public static String blockId(net.minecraft.block.Block block) {
        return Registries.BLOCK.getId(block).toString();
    }

    /** Block lookup used to read stored block states. */
    public static net.minecraft.registry.RegistryEntryLookup<net.minecraft.block.Block> blockLookup() {
        return Registries.BLOCK;
    }

    public static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
