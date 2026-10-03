package com.vylorq.anticheat.core;

import com.vylorq.anticheat.core.packets.BlockFace;
import com.vylorq.anticheat.core.packets.BreakTracker;
import com.vylorq.anticheat.core.packets.PacketRate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacketChecksTest {
    @Test
    void realClicksPass() {
        // Standing on top of block (0,64,0), placing against the east face of the block in front (bridging).
        assertNull(BlockFace.problem(5, 0, 63, 0, 1.0, 63.4, 0.7, 1.3, 65.62, 0.5, true, 0.5));
        // Clicking the top of a block below you.
        assertNull(BlockFace.problem(1, 3, 63, 3, 3.5, 64.0, 3.2, 3.4, 65.62, 3.1, true, 0.5));
        // Clicking the underside of a block above (ceiling).
        assertNull(BlockFace.problem(0, 0, 67, 0, 0.5, 67.0, 0.5, 0.4, 65.62, 0.5, true, 0.5));
        // Slabs and other partial blocks: the click point isn't on the block edge, that's fine.
        assertNull(BlockFace.problem(1, 0, 63, 0, 0.5, 63.5, 0.5, 0.5, 65.62, 0.5, false, 0.5));
        // Sneaking at the edge, eye just below the top of the block being clicked from the side.
        assertNull(BlockFace.problem(1, 0, 64, 0, 0.5, 65.0, 0.5, 0.5, 64.9, 1.5, true, 0.5));
        // Litematica/Tweakeroo accurate placement puts extra data in the X coordinate.
        assertNull(BlockFace.problem(1, 0, 63, 0, 2.5, 64.0, 0.5, 0.5, 65.62, 0.5, true, 0.5));
    }

    @Test
    void madeUpClicksCaught() {
        // Scaffold sending the block centre as the click point.
        assertNotNull(BlockFace.problem(1, 0, 63, 0, 0.5, 63.5, 0.5, 0.5, 65.62, 0.5, true, 0.5));
        // Clicking the bottom face of a block from well above it.
        assertNotNull(BlockFace.problem(0, 0, 63, 0, 0.5, 63.0, 0.5, 0.5, 65.62, 0.5, true, 0.5));
        // Clicking the west face while standing far to the east.
        assertNotNull(BlockFace.problem(4, 0, 64, 0, 0.0, 64.5, 0.5, 3.0, 65.62, 0.5, true, 0.5));
    }

    @Test
    void fastBreakNeedsAPattern() {
        BreakTracker legit = new BreakTracker();
        boolean flagged = false;
        // Normal mining with the odd lag hiccup (1 in 6 overruled).
        for (int i = 0; i < 300; i++) {
            flagged |= legit.onFinish(i % 6 == 0);
        }
        assertFalse(flagged);
        BreakTracker cheat = new BreakTracker();
        flagged = false;
        for (int i = 0; i < 20; i++) {
            flagged |= cheat.onFinish(i % 5 != 0);
        }
        assertTrue(flagged);
    }

    @Test
    void packetFloodOnlyAboveLimit() {
        PacketRate r = new PacketRate();
        boolean over = false;
        // 60 packets a second for a minute: a busy but normal player.
        for (int s = 0; s < 60; s++) {
            for (int i = 0; i < 60; i++) {
                over |= r.add(s * 1000L + i * 16, 2500);
            }
        }
        assertFalse(over);
        PacketRate flood = new PacketRate();
        over = false;
        for (int i = 0; i < 5000; i++) {
            over |= flood.add(100_000 + i / 10, 2500);
        }
        assertTrue(over);
    }
}
