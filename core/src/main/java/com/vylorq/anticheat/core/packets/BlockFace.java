package com.vylorq.anticheat.core.packets;

/**
 * Checks a block click against geometry the real client can't break: the click point must lie on the face that
 * was clicked (for full blocks), and that face must point towards the player's eyes. Scaffold and "place anywhere"
 * cheats often send a made-up click point or click the far side of a block.
 */
public final class BlockFace {
    private BlockFace() {
    }

    /** @param face 0 down, 1 up, 2 north, 3 south, 4 west, 5 east (Minecraft's Direction order) */
    public static String problem(int face, int bx, int by, int bz, double hx, double hy, double hz,
                                 double eyeX, double eyeY, double eyeZ, boolean fullBlock, double eyeTolerance) {
        // Accurate-placement mods (Litematica, Tweakeroo, Carpet) encode the block's rotation in the click point,
        // putting it 2 or more blocks away on X. That's a known, harmless protocol: leave it alone.
        if (hx - bx >= 2.0) {
            return null;
        }
        double rx = hx - bx;
        double ry = hy - by;
        double rz = hz - bz;
        double eps = 0.002;
        if (fullBlock) {
            boolean onFace = switch (face) {
                case 0 -> Math.abs(ry) < eps;
                case 1 -> Math.abs(ry - 1) < eps;
                case 2 -> Math.abs(rz) < eps;
                case 3 -> Math.abs(rz - 1) < eps;
                case 4 -> Math.abs(rx) < eps;
                case 5 -> Math.abs(rx - 1) < eps;
                default -> true;
            };
            boolean inside = rx > -eps && rx < 1 + eps && ry > -eps && ry < 1 + eps && rz > -eps && rz < 1 + eps;
            if (!onFace || !inside) {
                return String.format(java.util.Locale.ROOT, "click point %.2f %.2f %.2f isn't on the %s face", rx, ry, rz, name(face));
            }
        }
        // The clicked face must point towards the eyes.
        boolean faces = switch (face) {
            case 0 -> eyeY <= by + eyeTolerance;
            case 1 -> eyeY >= by + 1 - eyeTolerance;
            case 2 -> eyeZ <= bz + eyeTolerance;
            case 3 -> eyeZ >= bz + 1 - eyeTolerance;
            case 4 -> eyeX <= bx + eyeTolerance;
            case 5 -> eyeX >= bx + 1 - eyeTolerance;
            default -> true;
        };
        return faces ? null : "clicked the " + name(face) + " face from the other side";
    }

    static String name(int face) {
        return switch (face) {
            case 0 -> "bottom";
            case 1 -> "top";
            case 2 -> "north";
            case 3 -> "south";
            case 4 -> "west";
            case 5 -> "east";
            default -> "?";
        };
    }
}
