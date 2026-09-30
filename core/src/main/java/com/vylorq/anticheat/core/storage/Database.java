package com.vylorq.anticheat.core.storage;

import com.vylorq.anticheat.core.blocklog.BlockChange;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * SQLite by default, MySQL optional (section 27). One connection guarded by a lock: all writes go through
 * {@link LogWriter}'s single thread in batches, reads are short.
 */
public final class Database implements AutoCloseable {
    public enum Dialect { SQLITE, MYSQL }

    private final Connection conn;
    private final Dialect dialect;
    private final Path sqliteFile;
    private final Object lock = new Object();

    private Database(Connection conn, Dialect dialect, Path sqliteFile) {
        this.conn = conn;
        this.dialect = dialect;
        this.sqliteFile = sqliteFile;
    }

    public static Database openSqlite(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            throw new SQLException("SQLite driver not available", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
        }
        Database db = new Database(c, Dialect.SQLITE, file);
        db.createTables();
        return db;
    }

    public static Database openMysql(String url, String user, String password) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        Database db = new Database(c, Dialect.MYSQL, null);
        db.createTables();
        return db;
    }

    public Dialect dialect() {
        return dialect;
    }

    private String autoId() {
        return dialect == Dialect.SQLITE ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    private String text() {
        return dialect == Dialect.SQLITE ? "TEXT" : "MEDIUMTEXT";
    }

    private String shortText() {
        return dialect == Dialect.SQLITE ? "TEXT" : "VARCHAR(191)";
    }

    private void createTables() throws SQLException {
        String t = text();
        String st = shortText();
        String id = autoId();
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS state (k " + (dialect == Dialect.SQLITE ? "TEXT" : "VARCHAR(128)")
                        + " PRIMARY KEY, v " + (dialect == Dialect.SQLITE ? "TEXT" : "LONGTEXT") + ", updated BIGINT)",
                "CREATE TABLE IF NOT EXISTS staff_log (id " + id + ", time BIGINT, actor " + st + ", actor_name " + st
                        + ", action " + st + ", target " + st + ", target_name " + st + ", detail " + t + ")",
                "CREATE TABLE IF NOT EXISTS flags (id " + id + ", time BIGINT, player " + st + ", name " + st
                        + ", check_id " + st + ", points DOUBLE, suspicion INT, detail " + t + ")",
                "CREATE TABLE IF NOT EXISTS chat (id " + id + ", time BIGINT, player " + st + ", name " + st
                        + ", kind " + st + ", message " + t + ")",
                "CREATE TABLE IF NOT EXISTS joins (id " + id + ", time BIGINT, player " + st + ", name " + st
                        + ", ip " + st + ", event " + st + ")",
                "CREATE TABLE IF NOT EXISTS trades (id " + id + ", time BIGINT, kind " + st + ", a " + st + ", a_name "
                        + st + ", b " + st + ", b_name " + st + ", detail " + t + ")",
                "CREATE TABLE IF NOT EXISTS activity (id " + id + ", time BIGINT, player " + st + ", kind " + st
                        + ", detail " + t + ")",
                "CREATE TABLE IF NOT EXISTS block_log (id " + id + ", time BIGINT, actor " + st + ", actor_name " + st
                        + ", world " + st + ", x INT, y INT, z INT, kind " + st + ", before_state " + t
                        + ", after_state " + t + ", before_nbt " + t + ", item " + st + ", amount INT, rolled_back INT)",
        };
        synchronized (lock) {
            try (Statement s = conn.createStatement()) {
                for (String d : ddl) {
                    s.execute(d);
                }
                index(s, "idx_block_pos", "block_log", "world, x, z");
                index(s, "idx_block_actor", "block_log", "actor, time");
                index(s, "idx_staff_time", "staff_log", "time");
                index(s, "idx_flags_player", "flags", "player, time");
                index(s, "idx_chat_player", "chat", "player, time");
                index(s, "idx_joins_player", "joins", "player, time");
                index(s, "idx_trades_a", "trades", "a, time");
                index(s, "idx_trades_b", "trades", "b, time");
                index(s, "idx_activity_player", "activity", "player, time");
            }
        }
    }

    private void index(Statement s, String name, String table, String cols) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            s.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + " (" + cols + ")");
        } else {
            try {
                s.execute("CREATE INDEX " + name + " ON " + table + " (" + cols + ")");
            } catch (SQLException ignored) {
                // already exists
            }
        }
    }

    // ---- State (JSON blobs) ----

    public void putState(String key, String json) throws SQLException {
        String sql = dialect == Dialect.SQLITE
                ? "INSERT INTO state (k, v, updated) VALUES (?, ?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated = excluded.updated"
                : "INSERT INTO state (k, v, updated) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v), updated = VALUES(updated)";
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                ps.setString(2, json);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }
    }

    public String getState(String key) throws SQLException {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT v FROM state WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }
    }

    // ---- Batched writes ----

    /** Runs a batch of log writes in one transaction. */
    public void writeBatch(List<LogWriter.Entry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        synchronized (lock) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                for (LogWriter.Entry e : entries) {
                    try (PreparedStatement ps = conn.prepareStatement(e.sql())) {
                        Object[] a = e.args();
                        for (int i = 0; i < a.length; i++) {
                            ps.setObject(i + 1, a[i]);
                        }
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
    }

    // ---- Queries ----

    public record Row(long id, long time, String a, String b, String c, String d, String e) {
    }

    /** Generic query returning up to 7 columns as strings (first two must be id and time). */
    public List<Row> query(String sql, Object... args) throws SQLException {
        List<Row> out = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    int n = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        String[] s = new String[5];
                        for (int i = 0; i < 5 && i + 3 <= n; i++) {
                            s[i] = rs.getString(i + 3);
                        }
                        out.add(new Row(rs.getLong(1), rs.getLong(2), s[0], s[1], s[2], s[3], s[4]));
                    }
                }
            }
        }
        return out;
    }

    public List<Row> staffLog(int limit, String actorFilter) throws SQLException {
        if (actorFilter == null) {
            return query("SELECT id, time, actor_name, action, target_name, detail FROM staff_log ORDER BY id DESC LIMIT ?", limit);
        }
        return query("SELECT id, time, actor_name, action, target_name, detail FROM staff_log WHERE actor = ? ORDER BY id DESC LIMIT ?",
                actorFilter, limit);
    }

    public List<Row> flags(UUID player, int limit) throws SQLException {
        return query("SELECT id, time, check_id, points, suspicion, detail FROM flags WHERE player = ? ORDER BY id DESC LIMIT ?",
                player.toString(), limit);
    }

    public List<Row> chat(UUID player, String kind, int limit) throws SQLException {
        return query("SELECT id, time, kind, message FROM chat WHERE player = ? AND kind = ? ORDER BY id DESC LIMIT ?",
                player.toString(), kind, limit);
    }

    public List<Row> activity(UUID player, int limit) throws SQLException {
        return query("SELECT id, time, kind, detail FROM activity WHERE player = ? ORDER BY id DESC LIMIT ?",
                player.toString(), limit);
    }

    public List<Row> trades(UUID player, int limit) throws SQLException {
        return query("SELECT id, time, kind, a_name, b_name, detail FROM trades WHERE a = ? OR b = ? ORDER BY id DESC LIMIT ?",
                player.toString(), player.toString(), limit);
    }

    public List<Row> joins(UUID player, int limit) throws SQLException {
        return query("SELECT id, time, event, ip FROM joins WHERE player = ? ORDER BY id DESC LIMIT ?", player.toString(), limit);
    }

    /** Block changes for rollback: by actor (or all when null), since a time, optionally within a radius. */
    public List<BlockChange> blockChanges(UUID actor, long since, String world, Integer cx, Integer cz, Integer radius,
                                          boolean rolledBack, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id, time, actor, actor_name, world, x, y, z, kind, before_state, "
                + "after_state, before_nbt, item, amount, rolled_back FROM block_log WHERE time >= ? AND rolled_back = ?");
        List<Object> args = new ArrayList<>();
        args.add(since);
        args.add(rolledBack ? 1 : 0);
        if (actor != null) {
            sql.append(" AND actor = ?");
            args.add(actor.toString());
        }
        if (world != null) {
            sql.append(" AND world = ?");
            args.add(world);
        }
        if (cx != null && radius != null) {
            sql.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            args.add(cx - radius);
            args.add(cx + radius);
            args.add(cz - radius);
            args.add(cz + radius);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(limit);
        return readChanges(sql.toString(), args.toArray());
    }

    /** History of one block, newest first (inspector tool). */
    public List<BlockChange> blockHistory(String world, int x, int y, int z, int limit) throws SQLException {
        return readChanges("SELECT id, time, actor, actor_name, world, x, y, z, kind, before_state, after_state, before_nbt, "
                + "item, amount, rolled_back FROM block_log WHERE world = ? AND x = ? AND y = ? AND z = ? ORDER BY id DESC LIMIT ?",
                new Object[]{world, x, y, z, limit});
    }

    private List<BlockChange> readChanges(String sql, Object[] args) throws SQLException {
        List<BlockChange> out = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BlockChange c = new BlockChange();
                        c.id = rs.getLong(1);
                        c.time = rs.getLong(2);
                        String a = rs.getString(3);
                        c.actor = a == null || a.isEmpty() ? null : UUID.fromString(a);
                        c.actorName = rs.getString(4);
                        c.world = rs.getString(5);
                        c.x = rs.getInt(6);
                        c.y = rs.getInt(7);
                        c.z = rs.getInt(8);
                        c.kind = BlockChange.Kind.valueOf(rs.getString(9));
                        c.before = rs.getString(10);
                        c.after = rs.getString(11);
                        c.beforeNbt = rs.getString(12);
                        c.item = rs.getString(13);
                        c.amount = rs.getInt(14);
                        c.rolledBack = rs.getInt(15) != 0;
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    public void setRolledBack(List<Long> ids, boolean value) throws SQLException {
        synchronized (lock) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE block_log SET rolled_back = ? WHERE id = ?")) {
                for (Long id : ids) {
                    ps.setInt(1, value ? 1 : 0);
                    ps.setLong(2, id);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        }
    }

    /** Deletes log rows older than the cutoff. */
    public int purge(long cutoff, long blockCutoff) throws SQLException {
        int n = 0;
        synchronized (lock) {
            for (String table : List.of("staff_log", "flags", "chat", "joins", "trades", "activity")) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE time < ?")) {
                    ps.setLong(1, table.equals("staff_log") ? Math.min(cutoff, blockCutoff) : cutoff);
                    n += ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM block_log WHERE time < ?")) {
                ps.setLong(1, blockCutoff);
                n += ps.executeUpdate();
            }
        }
        return n;
    }

    /** SQLite only: writes a consistent copy to the backup folder and keeps the newest {@code keep}. */
    public Path backup(Path dir, int keep) throws Exception {
        if (dialect != Dialect.SQLITE || sqliteFile == null) {
            return null;
        }
        Files.createDirectories(dir);
        Path out = dir.resolve("vigil-" + java.time.LocalDate.now() + "-" + System.currentTimeMillis() % 100000 + ".db");
        synchronized (lock) {
            try (Statement s = conn.createStatement()) {
                s.execute("VACUUM INTO '" + out.toAbsolutePath().toString().replace("'", "''") + "'");
            }
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.filter(p -> p.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            for (int i = keep; i < all.size(); i++) {
                Files.deleteIfExists(all.get(i));
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock) {
            conn.close();
        }
    }
}
