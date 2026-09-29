package com.vylorq.anticheat.core.xray;

import com.vylorq.anticheat.core.util.BlockPos3;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Fake diamond veins (section 9). Positions are derived from a secret seed and the chunk, so they are the same
 * every time a chunk is sent and never need saving. They are only sent to clients (never placed in the world),
 * inside solid stone with no air around them, so only an x-ray user can see them.
 */
public final class XrayTrap {
    private final long secret;

    public XrayTrap(long secret) {
        this.secret = secret;
    }

    /** Candidate fake-ore positions for a chunk. The caller must still check each one is fully enclosed. */
    public List<BlockPos3> veinsFor(String world, int chunkX, int chunkZ, int veins, int minY, int maxY) {
        List<BlockPos3> out = new ArrayList<>();
        if (veins <= 0 || maxY <= minY) {
            return out;
        }
        long seed = secret ^ (world.hashCode() * 0x9E3779B97F4A7C15L) ^ ((long) chunkX * 341873128712L)
                ^ ((long) chunkZ * 132897987541L);
        SplittableRandom r = new SplittableRandom(seed);
        for (int v = 0; v < veins; v++) {
            int x = (chunkX << 4) + 2 + r.nextInt(12);
            int z = (chunkZ << 4) + 2 + r.nextInt(12);
            int y = minY + r.nextInt(maxY - minY);
            int size = 2 + r.nextInt(4);
            out.add(new BlockPos3(x, y, z));
            for (int i = 1; i < size; i++) {
                int dir = r.nextInt(3);
                x += dir == 0 ? 1 : 0;
                y += dir == 1 ? 1 : 0;
                z += dir == 2 ? 1 : 0;
                out.add(new BlockPos3(x, y, z));
            }
        }
        return out;
    }

    public boolean isTrap(String world, BlockPos3 p, int veins, int minY, int maxY) {
        return veinsFor(world, p.x() >> 4, p.z() >> 4, veins, minY, maxY).contains(p);
    }
}
