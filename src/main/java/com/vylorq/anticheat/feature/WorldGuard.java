package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.blocklog.BlockChange;
import com.vylorq.anticheat.core.claims.Claim;
import com.vylorq.anticheat.util.Mc;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/** World-side rules called from mixins: claims vs pistons, fluids, fire, hoppers, dispensers, explosions (section 17.6). */
public final class WorldGuard {
    private WorldGuard() {
    }

    private static boolean serverSide(World w) {
        return Ac.running() && w instanceof ServerWorld;
    }

    /** Something at {@code from} affecting {@code to}: allowed only within the same claim or into the wild. */
    public static boolean crossAllowed(World w, BlockPos from, BlockPos to) {
        if (!serverSide(w)) {
            return true;
        }
        String world = Mc.worldId(w);
        if (Ac.get().lobby.inLobby(world, to.getX(), to.getY(), to.getZ()) && !Ac.get().lobby.inLobby(world, from.getX(), from.getY(), from.getZ())) {
            return false;
        }
        return Ac.get().claims.crossAllowed(world, from.getX(), from.getZ(), to.getX(), to.getZ());
    }

    /** Fire in a claim with fire spread off (or in the lobby) goes out. */
    public static boolean fireAllowed(World w, BlockPos pos) {
        if (!serverSide(w)) {
            return true;
        }
        if (LobbyFeature.in(w, pos)) {
            return false;
        }
        Claim c = Ac.get().claims.at(Mc.worldId(w), pos.getX(), pos.getZ());
        return c == null || !c.isActive() || (c.settings.fireSpread && !c.eventLocked);
    }

    /** Filters explosion block damage: claims (unless explosions on), the lobby, event locks, the global switch. */
    private static boolean ownerBlast;

    /** The owner's meteor breaks blocks even while explosions are turned off (the lobby and protected claims stay safe). */
    public static void ownerBlast(boolean on) {
        ownerBlast = on;
    }

    public static void filterExplosion(World w, List<BlockPos> blocks, Entity cause) {
        if (!serverSide(w) || blocks.isEmpty()) {
            return;
        }
        blocks.removeIf(pos -> HomeTeleport.isPad(w, pos) || (w instanceof ServerWorld sw && TempestKeep.protects(sw, pos))
                || Zones.darkAt(w, pos) != null);
        // The owner's orbital strike: its own block-damage rules inside its area.
        OrbitalStrike.Zone strike = OrbitalStrike.zoneAt(w, blocks.get(0));
        if (strike != null && !strike.breakBlocks()) {
            blocks.clear();
            return;
        }
        if (!Ac.get().misc.explosionsEnabled && !ownerBlast && strike == null) {
            blocks.clear();
            return;
        }
        String world = Mc.worldId(w);
        boolean ignoreClaims = strike != null && strike.ignoreClaims();
        blocks.removeIf(pos -> {
            if (Ac.get().lobby.inLobby(world, pos.getX(), pos.getY(), pos.getZ())) {
                return true;
            }
            if (ignoreClaims) {
                return false;
            }
            Claim c = Ac.get().claims.at(world, pos.getX(), pos.getZ());
            return c != null && c.isActive() && (!c.settings.explosions || c.eventLocked);
        });
        OrbitalStrike.record(w, blocks);
        String by = cause == null ? "explosion" : cause.getName().getString();
        for (BlockPos pos : blocks) {
            BlockState st = w.getBlockState(pos);
            if (!st.isAir()) {
                BlockLog.log(null, by, (ServerWorld) w, pos, BlockChange.Kind.EXPLODE, st,
                        net.minecraft.block.Blocks.AIR.getDefaultState(), w.getBlockEntity(pos));
            }
        }
    }

    /** Withers, dragons and other mobs breaking blocks inside claims. */
    public static boolean mobMayBreak(World w, BlockPos pos, Entity breaker) {
        if (!serverSide(w) || breaker == null || breaker instanceof net.minecraft.entity.player.PlayerEntity) {
            return true;
        }
        if (Ac.get().lobby.inLobby(Mc.worldId(w), pos.getX(), pos.getY(), pos.getZ()) || HomeTeleport.isPad(w, pos)) {
            return false;
        }
        Claim c = Ac.get().claims.at(Mc.worldId(w), pos.getX(), pos.getZ());
        return c == null || !c.isActive();
    }

    public static boolean claimedOrLobby(World w, BlockPos pos) {
        if (!serverSide(w)) {
            return false;
        }
        String world = Mc.worldId(w);
        Claim c = Ac.get().claims.at(world, pos.getX(), pos.getZ());
        return (c != null && c.isActive()) || Ac.get().lobby.inLobby(world, pos.getX(), pos.getY(), pos.getZ());
    }
}
