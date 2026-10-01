package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;

/** Barrier particle walls and respawn handling (section 19). */
public final class Barriers {
    private Barriers() {
    }

    /** Shows a patch of wall around the nearest point to the player. */
    public static void showWall(ServerPlayerEntity p, Barrier b) {
        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();
        for (int i = -3; i <= 3; i++) {
            for (int j = 0; j <= 3; j++) {
                double x;
                double z;
                double y = py + j;
                switch (b.shape) {
                    case BOX -> {
                        double cx = Math.max(b.minX, Math.min(b.maxX + 1, px));
                        double cz = Math.max(b.minZ, Math.min(b.maxZ + 1, pz));
                        boolean xEdge = Math.min(Math.abs(px - b.minX), Math.abs(px - (b.maxX + 1)))
                                < Math.min(Math.abs(pz - b.minZ), Math.abs(pz - (b.maxZ + 1)));
                        if (xEdge) {
                            x = Math.abs(px - b.minX) < Math.abs(px - (b.maxX + 1)) ? b.minX : b.maxX + 1;
                            z = cz + i;
                        } else {
                            z = Math.abs(pz - b.minZ) < Math.abs(pz - (b.maxZ + 1)) ? b.minZ : b.maxZ + 1;
                            x = cx + i;
                        }
                    }
                    default -> {
                        double ang = Math.atan2(pz - b.cz, px - b.cx) + i * (1.0 / Math.max(3, b.radius));
                        double r = b.radius;
                        if (b.shape == Barrier.Shape.SPHERE) {
                            double dy = y - b.cy;
                            r = Math.sqrt(Math.max(0, b.radius * b.radius - dy * dy));
                        }
                        x = b.cx + Math.cos(ang) * r;
                        z = b.cz + Math.sin(ang) * r;
                    }
                }
                Mc.particle(p, ParticleTypes.FLAME, x, y, z);
            }
        }
    }

    /** Every second: particle walls for players close to a barrier, expired barriers removed. */
    public static void tick() {
        Ac ac = Ac.get();
        var spawn = Mc.worldSpawn(ac.server);
        ac.barriers.setSpawn(Mc.worldId(ac.server.getOverworld()), new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        if (!ac.barriers.tick().isEmpty()) {
            Ac.markDirty("barriers");
        }
        for (ServerPlayerEntity p : ac.server.getPlayerManager().getPlayerList()) {
            for (Barrier b : ac.barriers.nearWall(Mc.worldId(p.getEntityWorld()), Mc.vec(p.getEntityPos()), 4)) {
                showWall(p, b);
            }
        }
    }

    /** Respawn inside a barrier the player belongs to (at the world spawn if it's inside, else the middle). */
    public static void afterRespawn(ServerPlayerEntity p) {
        Vec3 inside = Ac.get().barriers.respawnInside(p.getUuid(), Mc.worldId(p.getEntityWorld()));
        String world = Ac.get().barriers.worldOfInside(p.getUuid());
        if (inside != null && world != null) {
            var w = Mc.world(Ac.server(), world);
            Barrier b = null;
            for (Barrier x : Ac.get().barriers.list()) {
                if (Boolean.TRUE.equals(x.sides.get(p.getUuid()))) {
                    b = x;
                }
            }
            boolean already = b != null && b.contains(Mc.worldId(p.getEntityWorld()), p.getX(), p.getY(), p.getZ());
            if (w != null && !already) {
                sendTo(p, w, inside);
            }
        }
    }

    private static final java.util.Map<java.util.UUID, Long> TOLD = new java.util.HashMap<>();

    /** Moves a player to a spot, standing safely on the ground there (barrier walls go from bedrock to sky). */
    public static void sendTo(ServerPlayerEntity p, net.minecraft.server.world.ServerWorld w, Vec3 to) {
        int x = (int) Math.floor(to.x());
        int z = (int) Math.floor(to.z());
        double y = to.y();
        net.minecraft.util.math.BlockPos feet = net.minecraft.util.math.BlockPos.ofFloored(to.x(), to.y(), to.z());
        boolean standable = w.getBlockState(feet).getCollisionShape(w, feet).isEmpty()
                && w.getBlockState(feet.up()).getCollisionShape(w, feet.up()).isEmpty()
                && !w.getBlockState(feet.down()).getCollisionShape(w, feet.down()).isEmpty();
        if (!standable) {
            y = w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        Mc.teleport(p, w, x + 0.5, y, z + 0.5, p.getYaw(), p.getPitch());
        long now = System.currentTimeMillis();
        Long last = TOLD.get(p.getUuid());
        if (last == null || now - last > 10_000) {
            TOLD.put(p.getUuid(), now);
            com.vylorq.anticheat.util.Msg.actionBar(p, com.vylorq.anticheat.util.Msg.trFor(p, "barrier.moved-back"));
        }
    }
}
