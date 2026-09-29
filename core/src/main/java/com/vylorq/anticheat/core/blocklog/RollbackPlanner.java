package com.vylorq.anticheat.core.blocklog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns logged changes into the operations that undo (rollback) or redo (restore) them. Changes are applied
 * newest-first for rollback so each block returns to its oldest "before" state in the selection.
 */
public final class RollbackPlanner {
    public record Op(String world, int x, int y, int z, String state, String nbt, BlockChange source) {
    }

    private RollbackPlanner() {
    }

    /** @param changes changes in any order; only the not-yet-rolled-back ones are used */
    public static List<Op> rollback(List<BlockChange> changes) {
        List<BlockChange> sorted = new ArrayList<>(changes);
        sorted.removeIf(c -> c.rolledBack || c.kind == BlockChange.Kind.CONTAINER_ADD
                || c.kind == BlockChange.Kind.CONTAINER_REMOVE);
        sorted.sort((a, b) -> Long.compare(b.time, a.time));
        List<Op> ops = new ArrayList<>();
        for (BlockChange c : sorted) {
            ops.add(new Op(c.world, c.x, c.y, c.z, c.before, c.beforeNbt, c));
        }
        return dedupeKeepLast(ops);
    }

    /** Redo: oldest first, to each change's "after" state. */
    public static List<Op> restore(List<BlockChange> changes) {
        List<BlockChange> sorted = new ArrayList<>(changes);
        sorted.removeIf(c -> !c.rolledBack || c.kind == BlockChange.Kind.CONTAINER_ADD
                || c.kind == BlockChange.Kind.CONTAINER_REMOVE);
        sorted.sort((a, b) -> Long.compare(a.time, b.time));
        List<Op> ops = new ArrayList<>();
        for (BlockChange c : sorted) {
            ops.add(new Op(c.world, c.x, c.y, c.z, c.after, null, c));
        }
        return dedupeKeepLast(ops);
    }

    /** When a block changed several times, only the final op for that position matters. */
    private static List<Op> dedupeKeepLast(List<Op> ops) {
        Set<String> seen = new HashSet<>();
        List<Op> out = new ArrayList<>();
        for (int i = ops.size() - 1; i >= 0; i--) {
            Op o = ops.get(i);
            if (seen.add(o.world() + o.x() + "," + o.y() + "," + o.z())) {
                out.add(0, o);
            }
        }
        return out;
    }
}
