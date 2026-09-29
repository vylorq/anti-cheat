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
        if (!ac.barriers.tick().isEmpty()) {
            Ac.markDirty("barriers");
        }
        for (ServerPlayerEntity p : ac.server.getPlayerManager().getPlayerList()) {
            for (Barrier b : ac.barriers.nearWall(Mc.worldId(p.getWorld()), Mc.vec(p.getPos()), 4)) {
                showWall(p, b);
            }
        }
    }

    /** Respawn inside a barrier the player belongs to. */
    public static void afterRespawn(ServerPlayerEntity p) {
        Vec3 inside = Ac.get().barriers.respawnInside(p.getUuid(), Mc.worldId(p.getWorld()));
        String world = Ac.get().barriers.worldOfInside(p.getUuid());
        if (inside != null && world != null) {
            var w = Mc.world(Ac.server(), world);
            if (w != null) {
                Mc.teleport(p, w, inside.x(), inside.y(), inside.z(), p.getYaw(), p.getPitch());
            }
        }
    }
}
