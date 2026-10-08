package com.vylorq.anticheat.feature;

import com.vylorq.anticheat.util.ItemConv;
import com.vylorq.anticheat.Ac;
import com.vylorq.anticheat.core.blocklog.BlockChange;
import com.vylorq.anticheat.core.blocklog.RollbackPlanner;
import com.vylorq.anticheat.util.Mc;
import com.vylorq.anticheat.util.Msg;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Block logging, rollback/restore and the inspector tool (section 15). */
public final class BlockLog {
    private BlockLog() {
    }

    public static String encode(BlockState s) {
        return NbtHelper.fromBlockState(s).toString();
    }

    public static BlockState decode(String s) {
        try {
            return NbtHelper.toBlockState(Mc.blockLookup(), ItemConv.parseSnbt(s));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readable(String encoded) {
        BlockState st = decode(encoded);
        return st == null ? "?" : Mc.blockId(st.getBlock()).replace("minecraft:", "");
    }

    public static void log(ServerPlayerEntity actor, String actorName, ServerWorld w, BlockPos pos, BlockChange.Kind kind,
                           BlockState before, BlockState after, BlockEntity beforeEntity) {
        BlockChange c = new BlockChange();
        c.time = System.currentTimeMillis();
        c.actor = actor == null ? null : actor.getUuid();
        c.actorName = actor == null ? actorName : actor.getGameProfile().name();
        c.world = Mc.worldId(w);
        c.x = pos.getX();
        c.y = pos.getY();
        c.z = pos.getZ();
        c.kind = kind;
        c.before = encode(before);
        c.after = encode(after);
        if (beforeEntity != null) {
            c.beforeNbt = beforeEntity.createNbtWithIdentifyingData(w.getRegistryManager()).toString();
        }
        Ac.get().logs.block(c);
    }

    public static void logContainer(ServerPlayerEntity actor, ServerWorld w, BlockPos pos, String item, int amount) {
        BlockChange c = new BlockChange();
        c.time = System.currentTimeMillis();
        c.actor = actor.getUuid();
        c.actorName = actor.getGameProfile().name();
        c.world = Mc.worldId(w);
        c.x = pos.getX();
        c.y = pos.getY();
        c.z = pos.getZ();
        c.kind = amount >= 0 ? BlockChange.Kind.CONTAINER_ADD : BlockChange.Kind.CONTAINER_REMOVE;
        c.item = item;
        c.amount = Math.abs(amount);
        Ac.get().logs.block(c);
    }

    /**
     * Undo (or redo) a player's changes. Runs the query off-thread, applies blocks on the server thread.
     */
    public static void rollback(ServerPlayerEntity admin, UUID target, String targetName, long sinceMillis, Integer radius,
                                boolean restore) {
        Ac ac = Ac.get();
        ac.logs.flush();
        String world = admin == null ? null : Mc.worldId(admin.getEntityWorld());
        Integer cx = admin == null || radius == null ? null : admin.getBlockX();
        Integer cz = admin == null || radius == null ? null : admin.getBlockZ();
        long since = System.currentTimeMillis() - sinceMillis;
        Thread t = new Thread(() -> {
            try {
                List<BlockChange> changes = ac.db.blockChanges(target, since, radius == null ? null : world, cx, cz, radius, restore, 200_000);
                ac.server.execute(() -> apply(admin, targetName, changes, restore));
            } catch (Exception e) {
                Ac.LOG.error("Rollback query failed", e);
                ac.server.execute(() -> {
                    if (admin != null) {
                        Msg.send(admin, "general.error");
                    }
                });
            }
        }, "Vigil-Rollback");
        t.setDaemon(true);
        t.start();
    }

    private static void apply(ServerPlayerEntity admin, String targetName, List<BlockChange> changes, boolean restore) {
        Ac ac = Ac.get();
        List<RollbackPlanner.Op> ops = restore ? RollbackPlanner.restore(changes) : RollbackPlanner.rollback(changes);
        int done = 0;
        for (RollbackPlanner.Op op : ops) {
            ServerWorld w = Mc.world(ac.server, op.world());
            BlockState st = decode(op.state());
            if (w == null || st == null) {
                continue;
            }
            BlockPos pos = new BlockPos(op.x(), op.y(), op.z());
            w.setBlockState(pos, st, Block.NOTIFY_ALL | Block.FORCE_STATE);
            if (op.nbt() != null && !op.nbt().isEmpty()) {
                BlockEntity be = w.getBlockEntity(pos);
                if (be != null) {
                    try {
                        NbtCompound n = ItemConv.parseSnbt(op.nbt());
                        Mc.loadBlockEntity(be, n, w.getRegistryManager());
                        be.markDirty();
                    } catch (Exception ignored) {
                        // keep the block, skip its contents
                    }
                }
            }
            done++;
        }
        List<Long> ids = new ArrayList<>();
        for (BlockChange c : changes) {
            ids.add(c.id);
        }
        Thread t = new Thread(() -> {
            try {
                ac.db.setRolledBack(ids, !restore);
            } catch (Exception e) {
                Ac.LOG.error("Could not mark rollback", e);
            }
        }, "Vigil-RollbackMark");
        t.setDaemon(true);
        t.start();
        Staff.log(admin, restore ? "restore" : "rollback", null, targetName, done + " blocks");
        if (admin != null) {
            Msg.send(admin, restore ? "rollback.restored" : "rollback.done", done, targetName);
        }
    }

    /** Inspector tool: who placed or broke this block and when. */
    public static void inspect(ServerPlayerEntity admin, ServerWorld w, BlockPos pos) {
        Ac ac = Ac.get();
        ac.logs.flush();
        String world = Mc.worldId(w);
        Thread t = new Thread(() -> {
            try {
                List<BlockChange> list = ac.db.blockHistory(world, pos.getX(), pos.getY(), pos.getZ(), 10);
                ac.server.execute(() -> {
                    Msg.send(admin, "inspector.header", pos.toShortString());
                    if (list.isEmpty()) {
                        Msg.send(admin, "inspector.none");
                    }
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    for (BlockChange c : list) {
                        String what = switch (c.kind) {
                            case PLACE -> "§aplaced §f" + readable(c.after);
                            case BREAK -> "§cbroke §f" + readable(c.before);
                            case EXPLODE -> "§6exploded §f" + readable(c.before);
                            case BURN -> "§6burned §f" + readable(c.before);
                            default -> "§7changed §f" + readable(c.before) + " → " + readable(c.after);
                        };
                        admin.sendMessage(Msg.text("§7" + f.format(new Date(c.time)) + " §e" + c.actorName + " " + what
                                + (c.rolledBack ? " §8(rolled back)" : "")));
                    }
                    // One click: undo everything each of these players did around here (the last 7 days, 20 blocks).
                    if (com.vylorq.anticheat.perm.Perms.has(admin, com.vylorq.anticheat.core.perm.Perm.ROLLBACK)) {
                        java.util.Set<String> who = new java.util.LinkedHashSet<>();
                        for (BlockChange c : list) {
                            if (c.actor != null && c.actorName != null && !c.rolledBack) {
                                who.add(c.actorName);
                            }
                        }
                        for (String name : who) {
                            admin.sendMessage(Msg.prefixed("§7" + Msg.trFor(admin, "inspector.undo-line", name) + " ")
                                    .append(Msg.button("§c[" + Msg.trFor(admin, "inspector.undo") + "]",
                                            "/rollback " + Msg.q(name) + " 7d 20", Msg.trFor(admin, "inspector.undo-hover", name))));
                        }
                    }
                });
            } catch (Exception e) {
                Ac.LOG.error("Inspector query failed", e);
            }
        }, "Vigil-Inspector");
        t.setDaemon(true);
        t.start();
    }
}
