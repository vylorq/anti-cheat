package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.barrier.Barrier;
import com.vylorq.anticheat.core.util.Area;
import com.vylorq.anticheat.core.util.Vec3;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The Locked Box: a cube (with a floor and a ceiling) the owner draws with the Claim Stick. The world spawn moves
 * inside it, everyone is brought inside (wherever they are, even other dimensions) and nobody leaves, admins
 * included, until the owner removes it. Then the old spawn comes back. Built on a barrier named "locked-box".
 */
public final class LockedBox {
    private LockedBox() {
    }

    public static final String NAME = "locked-box";

    public static Barrier get() {
        return Ac.running() ? Ac.get().barriers.get(NAME) : null;
    }

    /** Creates the box, or moves/resizes it if it exists. @return the spawn inside it */
    public static BlockPos create(ServerPlayerEntity by, ServerWorld w, Area a) {
        MinecraftServer server = Ac.server();
        Barrier b = get();
        boolean fresh = b == null;
        if (fresh) {
            b = new Barrier();
            b.name = NAME;
            BlockPos old = Mc.worldSpawn(server);
            b.restoreSpawn = Mc.worldId(server.getOverworld()) + " " + old.getX() + " " + old.getY() + " " + old.getZ();
        }
        b.world = a.world;
        b.shape = Barrier.Shape.CUBE;
        b.minX = a.minX;
        b.maxX = a.maxX;
        b.minZ = a.minZ;
        b.maxZ = a.maxZ;
        b.minY = a.minY;
        b.maxY = a.maxY;
        b.cy = a.minY;
        b.adminsPass = false;
        b.blockProjectiles = true;
        b.newPlayers = "inside";
        b.createdBy = by == null ? "console" : by.getGameProfile().name();
        if (fresh) {
            Ac.get().barriers.add(b);
        }
        Ac.get().barriers.resetSides(b);
        Ac.markDirty("barriers");
        Ac.saveNow("barriers");

        int cx = (a.minX + a.maxX) / 2;
        int cz = (a.minZ + a.maxZ) / 2;
        Integer y = Barriers.standableAbove(w, cx, a.minY, cz, Math.max(1, a.maxY - a.minY - 1));
        BlockPos spawn = new BlockPos(cx, y != null ? y : a.minY + 1, cz);
        setSpawn(w, spawn);
        Ac.get().barriers.setSpawn(Mc.worldId(w), new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));

        // Everyone comes inside now, wherever they are.
        Vec3 in = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!b.contains(Mc.worldId(p.getEntityWorld()), p.getX(), p.getY(), p.getZ())) {
                Barriers.sendTo(p, w, in);
            }
            b.sides.put(p.getUuid(), true);
        }
        Staff.log(by, fresh ? "lockedbox-create" : "lockedbox-resize", null, NAME,
                a.world + " " + a.minX + "," + a.minY + "," + a.minZ + " to " + a.maxX + "," + a.maxY + "," + a.maxZ);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Msg.send(p, "lockedbox.locked");
        }
        return spawn;
    }

    /** Removes the box and puts the old world spawn back. @return false if there's none */
    public static boolean remove(ServerPlayerEntity by) {
        Barrier b = get();
        if (b == null) {
            return false;
        }
        Ac.get().barriers.remove(NAME);
        Ac.markDirty("barriers");
        Ac.saveNow("barriers");
        if (b.restoreSpawn != null) {
            String[] f = b.restoreSpawn.split(" ");
            if (f.length == 4) {
                ServerWorld w = Mc.world(Ac.server(), f[0]);
                try {
                    setSpawn(w != null ? w : Ac.server().getOverworld(),
                            new BlockPos(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])));
                } catch (NumberFormatException ignored) {
                    // keep the box's spawn
                }
            }
        }
        Staff.log(by, "lockedbox-remove", null, NAME, "");
        for (ServerPlayerEntity p : Ac.server().getPlayerManager().getPlayerList()) {
            Msg.send(p, "lockedbox.unlocked");
        }
        return true;
    }

    private static void setSpawn(ServerWorld w, BlockPos pos) {
        MinecraftServer server = Ac.server();
        server.getCommandManager().parseAndExecute(server.getCommandSource().withWorld(w).withSilent(),
                "setworldspawn " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }
}
