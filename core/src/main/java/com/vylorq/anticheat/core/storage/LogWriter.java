package com.vylorq.anticheat.core.storage;

import com.vylorq.anticheat.core.blocklog.BlockChange;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Collects log rows and writes them in batches off the server thread (section 27, performance).
 */
public final class LogWriter implements AutoCloseable {
    public record Entry(String sql, Object[] args) {
    }

    private final Database db;
    private final ConcurrentLinkedQueue<Entry> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService exec;
    private final Consumer<Exception> errors;
    private static final int MAX_QUEUE = 200_000;

    public LogWriter(Database db, Consumer<Exception> errors) {
        this.db = db;
        this.errors = errors;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Vigil-LogWriter");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::flush, 1, 1, TimeUnit.SECONDS);
    }

    private void add(String sql, Object... args) {
        if (queue.size() < MAX_QUEUE) {
            queue.add(new Entry(sql, args));
        }
    }

    private static String s(UUID id) {
        return id == null ? "" : id.toString();
    }

    public void staff(long time, UUID actor, String actorName, String action, UUID target, String targetName, String detail) {
        add("INSERT INTO staff_log (time, actor, actor_name, action, target, target_name, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                time, s(actor), actorName, action, s(target), targetName, detail);
    }

    public void flag(long time, UUID player, String name, String check, double points, int suspicion, String detail) {
        add("INSERT INTO flags (time, player, name, check_id, points, suspicion, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                time, s(player), name, check, points, suspicion, detail);
    }

    public void chat(long time, UUID player, String name, String kind, String message) {
        add("INSERT INTO chat (time, player, name, kind, message) VALUES (?, ?, ?, ?, ?)", time, s(player), name, kind, message);
    }

    public void join(long time, UUID player, String name, String ip, String event) {
        add("INSERT INTO joins (time, player, name, ip, event) VALUES (?, ?, ?, ?, ?)", time, s(player), name, ip, event);
    }

    public void trade(long time, String kind, UUID a, String aName, UUID b, String bName, String detail) {
        add("INSERT INTO trades (time, kind, a, a_name, b, b_name, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                time, kind, s(a), aName, s(b), bName, detail);
    }

    public void activity(long time, UUID player, String kind, String detail) {
        add("INSERT INTO activity (time, player, kind, detail) VALUES (?, ?, ?, ?)", time, s(player), kind, detail);
    }

    public void block(BlockChange c) {
        add("INSERT INTO block_log (time, actor, actor_name, world, x, y, z, kind, before_state, after_state, before_nbt, item, amount, rolled_back) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                c.time, s(c.actor), c.actorName, c.world, c.x, c.y, c.z, c.kind.name(), c.before, c.after, c.beforeNbt,
                c.item, c.amount);
    }

    public int pending() {
        return queue.size();
    }

    /** Writes everything queued so far. Safe to call from any thread. */
    public synchronized void flush() {
        List<Entry> batch = new ArrayList<>();
        Entry e;
        while ((e = queue.poll()) != null) {
            batch.add(e);
            if (batch.size() >= 2000) {
                write(batch);
                batch = new ArrayList<>();
            }
        }
        write(batch);
    }

    private void write(List<Entry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            db.writeBatch(batch);
        } catch (Exception ex) {
            errors.accept(ex);
        }
    }

    @Override
    public void close() {
        exec.shutdown();
        try {
            exec.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        flush();
    }
}
