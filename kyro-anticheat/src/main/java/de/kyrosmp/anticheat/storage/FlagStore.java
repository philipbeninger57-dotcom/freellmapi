package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.Confidence;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FlagStore {
    private final Logger log;
    private final ThreadPoolExecutor executor;
    private final AtomicLong droppedWrites = new AtomicLong();
    private final AtomicLong failedOps = new AtomicLong();
    private volatile long lastDropWarning;
    private volatile boolean available;
    private volatile Connection connection;

    public FlagStore(Logger log, int queueCapacity) {
        this.log = log;
        this.executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue(Math.max(100, queueCapacity)),
                        r -> {
                            Thread t = new Thread(r, "KYRO-FlagStore");
                            t.setDaemon(false);
                            return t;
                        },
                        new AbortPolicy()) {
                    @Override
                    protected void terminated() {
                        FlagStore.this.closeOnDbThread();
                    }
                };
    }

    public CompletableFuture<Void> start(File dbFile) {
        return this.enqueue(
                () -> {
                    Connection c = null;
                    boolean opened = false;

                    try {
                        Class.forName("org.sqlite.JDBC");
                        File parent = dbFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            throw new SQLException("Could not create folder: " + parent);
                        }

                        c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                        int existing = readSchemaVersion(c);
                        if (existing > 2) {
                            throw new SQLException(
                                    "Database is from a newer plugin version (schema "
                                            + existing
                                            + "). Downgrades are not supported; file left"
                                            + " unchanged.");
                        }

                        try (Statement st = c.createStatement()) {
                            for (String pragma : FlagSql.PRAGMAS) {
                                st.execute(pragma);
                            }
                        }

                        this.migrate(c);
                        this.connection = c;
                        this.available = true;
                        opened = true;
                        this.log.info("Flag database ready: " + dbFile.getName());
                    } catch (ClassNotFoundException ignored) {
                        this.log.severe(
                                "SQLite driver not found. Flags will NOT be stored (alerts only).");
                    } catch (SQLException ex) {
                        this.log.log(
                                Level.SEVERE,
                                "Could not open flag database "
                                        + dbFile.getName()
                                        + ". Flags will NOT be stored (alerts only).",
                                ex);
                    } finally {
                        if (!opened && c != null) {
                            try {
                                c.close();
                            } catch (SQLException ex) {
                                this.log.log(
                                        Level.WARNING,
                                        "Could not close failed database connection",
                                        ex);
                            }
                        }
                    }

                    return null;
                });
    }

    private static int readSchemaVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
                ResultSet rs =
                        st.executeQuery(
                                "SELECT name FROM sqlite_master WHERE type = 'table' AND name ="
                                        + " 'kyro_meta'"); ) {
            if (!rs.next()) {
                return 0;
            }
        }

        try (Statement st = c.createStatement();
                ResultSet rs =
                        st.executeQuery(
                                "SELECT meta_value FROM kyro_meta WHERE meta_key ="
                                        + " 'schema_version'"); ) {
            if (!rs.next()) {
                return 0;
            }

            try {
                int version = Integer.parseInt(rs.getString(1));
                if (version < 0) {
                    throw new NumberFormatException("negative version");
                }
                return version;
            } catch (NumberFormatException ex) {
                throw new SQLException("Invalid schema version in kyro_meta", ex);
            }
        }
    }

    private void migrate(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS kyro_meta (meta_key TEXT PRIMARY KEY, meta_value"
                            + " TEXT NOT NULL)");
        }

        int version = readSchemaVersion(c);
        if (version > 2) {
            throw new SQLException(
                    "Database is from a newer plugin version (schema "
                            + version
                            + "). Downgrades are not supported; file left unchanged.");
        } else {
            if (version < 1) {
                inTransaction(
                        c,
                        () -> {
                            try (Statement st = c.createStatement()) {
                                for (String sql : FlagSql.MIGRATION_V1) {
                                    st.execute(sql);
                                }
                            }

                            setVersion(c, 1);
                        });
                this.log.info("Flag database migrated to schema version 1.");
            }

            if (version < 2) {
                inTransaction(
                        c,
                        () -> {
                            try (Statement st = c.createStatement()) {
                                for (String sql : FlagSql.MIGRATION_V2) {
                                    st.execute(sql);
                                }
                            }

                            setVersion(c, 2);
                        });
                this.log.info(
                        "Flag database migrated to schema version 2 (evidence columns, existing"
                                + " flags preserved).");
            }
        }
    }

    private static void setVersion(Connection c, int version) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO kyro_meta (meta_key, meta_value) VALUES ('schema_version', ?)"
                                + " ON CONFLICT(meta_key) DO UPDATE SET meta_value ="
                                + " excluded.meta_value")) {
            ps.setString(1, Integer.toString(version));
            ps.executeUpdate();
        }
    }

    public void shutdown(long timeoutSeconds) {
        this.executor.shutdown();

        try {
            if (!this.executor.awaitTermination(Math.max(0L, timeoutSeconds), TimeUnit.SECONDS)) {
                this.log.warning(
                        "Flag database: "
                                + this.executor.getQueue().size()
                                + " operations not finished in time; they are discarded.");
                this.discardQueuedTasks();
                this.executor.awaitTermination(2L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) {
            this.discardQueuedTasks();
            Thread.currentThread().interrupt();
        }
    }

    private void discardQueuedTasks() {
        for (Runnable task : this.executor.shutdownNow()) {
            if (task instanceof FlagStore.PendingTask<?> pending) {
                pending.future.completeExceptionally(
                        new RejectedExecutionException(
                                "Flag database shut down before operation ran"));
            }
        }
    }

    private void closeOnDbThread() {
        this.available = false;
        Connection c = this.connection;
        this.connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ex) {
                this.log.log(Level.WARNING, "Error while closing the flag database", ex);
            }
        }
    }

    public boolean isAvailable() {
        return this.available && !this.executor.isShutdown();
    }

    public long droppedWrites() {
        return this.droppedWrites.get();
    }

    public long failedOperations() {
        return this.failedOps.get();
    }

    public int queueSize() {
        return this.executor.getQueue().size();
    }

    public CompletableFuture<Void> insert(FlagRecord r) {
        return this.write(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT OR IGNORE INTO kyro_flags (flag_id, player_uuid,"
                                            + " player_name, category, check_name, vl, created_at,"
                                            + " reason, details, ping, confidence, world, x, y, z,"
                                            + " status, merged_count, reviewed_by, reviewed_at,"
                                            + " actions, previous_flag_id, tps) VALUES"
                                            + " (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        bindFlag(ps, r);
                        ps.executeUpdate();
                    }

                    return null;
                });
    }

    public CompletableFuture<Void> merge(String flagId, double vl, Confidence confidence) {
        return this.write(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE kyro_flags SET merged_count = merged_count + 1, vl ="
                                            + " MAX(vl, ?), confidence = ? WHERE flag_id = ?")) {
                        ps.setDouble(1, vl);
                        ps.setString(2, confidence.name());
                        ps.setString(3, flagId);
                        ps.executeUpdate();
                    }

                    return null;
                });
    }

    public CompletableFuture<Void> appendAction(String flagId, String action) {
        return this.write(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE kyro_flags SET actions = CASE WHEN actions = '' THEN ?"
                                            + " ELSE actions || '; ' || ? END WHERE flag_id = ?")) {
                        ps.setString(1, action);
                        ps.setString(2, action);
                        ps.setString(3, flagId);
                        ps.executeUpdate();
                    }

                    return null;
                });
    }

    public CompletableFuture<Integer> updatePlayerStatus(
            UUID player,
            Collection<FlagStatus> from,
            FlagStatus to,
            UUID actorUuid,
            String actorName,
            String auditAction,
            long now) {
        SqlStatement stmt = FlagSql.bulkStatus(player, from, to, actorName, now);
        return this.submit(
                c -> {
                    int[] changed = new int[1];
                    inTransaction(
                            c,
                            () -> {
                                try (PreparedStatement ps = prepare(c, stmt)) {
                                    changed[0] = ps.executeUpdate();
                                }

                                insertAudit(
                                        c,
                                        now,
                                        actorUuid,
                                        actorName,
                                        auditAction,
                                        player,
                                        "changed=" + changed[0] + "; to=" + to.name());
                            });
                    return changed[0];
                });
    }

    public CompletableFuture<Boolean> updateStatus(
            String flagId, FlagStatus status, UUID actorUuid, String actorName, long now) {
        return this.submit(
                c -> {
                    int[] changed = new int[1];
                    inTransaction(
                            c,
                            () -> {
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "UPDATE kyro_flags SET status = ?, reviewed_by = ?,"
                                                        + " reviewed_at = ? WHERE flag_id = ?")) {
                                    ps.setString(1, status.name());
                                    if (status == FlagStatus.NEW) {
                                        ps.setNull(2, 12);
                                        ps.setLong(3, 0L);
                                    } else {
                                        ps.setString(2, actorName);
                                        ps.setLong(3, now);
                                    }

                                    ps.setString(4, flagId);
                                    changed[0] = ps.executeUpdate();
                                }

                                if (changed[0] > 0) {
                                    insertAudit(
                                            c,
                                            now,
                                            actorUuid,
                                            actorName,
                                            "FLAG_STATUS_" + status.name(),
                                            null,
                                            "flag=" + flagId);
                                }
                            });
                    return changed[0] > 0;
                });
    }

    public CompletableFuture<Integer> deletePlayer(
            UUID player, UUID actorUuid, String actorName, long now) {
        return this.submit(
                c -> {
                    int[] deleted = new int[1];
                    inTransaction(
                            c,
                            () -> {
                                try (PreparedStatement ps =
                                        c.prepareStatement(
                                                "DELETE FROM kyro_flags WHERE player_uuid = ?")) {
                                    ps.setString(1, player.toString());
                                    deleted[0] = ps.executeUpdate();
                                }

                                insertAudit(
                                        c,
                                        now,
                                        actorUuid,
                                        actorName,
                                        "PURGE_HISTORY",
                                        player,
                                        "deleted=" + deleted[0]);
                            });
                    return deleted[0];
                });
    }

    public CompletableFuture<Integer> pruneBefore(long cutoff) {
        return cutoff <= 0L
                ? CompletableFuture.completedFuture(0)
                : this.submit(
                        c -> {
                            int[] deleted = new int[1];
                            String candidates =
                                    "SELECT flag_id FROM kyro_flags WHERE status = 'RESOLVED' AND"
                                        + " created_at < ? AND reviewed_at > 0 AND reviewed_at < ?"
                                        + " ORDER BY created_at ASC, flag_id ASC LIMIT 1000";
                            inTransaction(
                                    c,
                                    () -> {
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "UPDATE kyro_flags SET previous_flag_id ="
                                                            + " NULL WHERE previous_flag_id IN ("
                                                                + candidates
                                                                + ")")) {
                                            ps.setLong(1, cutoff);
                                            ps.setLong(2, cutoff);
                                            ps.executeUpdate();
                                        }

                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "DELETE FROM kyro_flags WHERE flag_id IN ("
                                                                + candidates
                                                                + ")")) {
                                            ps.setLong(1, cutoff);
                                            ps.setLong(2, cutoff);
                                            deleted[0] = ps.executeUpdate();
                                        }

                                        if (deleted[0] > 0) {
                                            insertAudit(
                                                    c,
                                                    System.currentTimeMillis(),
                                                    null,
                                                    "SYSTEM",
                                                    "RETENTION_PURGE",
                                                    null,
                                                    "deleted=" + deleted[0] + "; cutoff=" + cutoff);
                                        }
                                    });
                            return deleted[0];
                        });
    }

    public CompletableFuture<Void> audit(
            UUID actorUuid, String actorName, String action, UUID target, String detail) {
        long now = System.currentTimeMillis();
        return this.write(
                c -> {
                    insertAudit(c, now, actorUuid, actorName, action, target, detail);
                    return null;
                });
    }

    public CompletableFuture<List<PlayerSummary>> summaries(SummaryQuery q) {
        if (q.online() == SummaryQuery.OnlineFilter.ONLINE && q.onlineUuids().isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        } else {
            SqlStatement stmt = FlagSql.summaries(q);
            return this.submit(
                    c -> {
                        ArrayList<PlayerSummary> out = new ArrayList<>();

                        try (PreparedStatement ps = prepare(c, stmt);
                                ResultSet rs = ps.executeQuery(); ) {
                            while (rs.next()) {
                                double maxVl = rs.getDouble("max_open_vl");
                                if (rs.wasNull()) {
                                    maxVl = Double.NaN;
                                }

                                out.add(
                                        new PlayerSummary(
                                                UUID.fromString(rs.getString("player_uuid")),
                                                rs.getString("player_name"),
                                                rs.getInt("flag_count"),
                                                rs.getInt("trigger_count"),
                                                rs.getInt("current_count"),
                                                rs.getInt("new_count"),
                                                rs.getInt("reviewed_count"),
                                                maxVl,
                                                rs.getLong("last_flag_at"),
                                                rs.getString("last_check")));
                            }
                        }

                        return out;
                    });
        }
    }

    public CompletableFuture<List<FlagRecord>> playerFlags(UUID player, FlagQuery q) {
        SqlStatement stmt = FlagSql.playerFlags(player, q);
        return this.submit(
                c -> {
                    try (PreparedStatement ps = prepare(c, stmt);
                            ResultSet rs = ps.executeQuery()) {
                        return readFlags(rs);
                    }
                });
    }

    public CompletableFuture<Optional<FlagRecord>> flag(String flagId) {
        return this.submit(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT flag_id, player_uuid, player_name, category,"
                                        + " check_name, vl, created_at, reason, details, ping,"
                                        + " confidence, world, x, y, z, status, merged_count,"
                                        + " reviewed_by, reviewed_at, actions, previous_flag_id,"
                                        + " tps FROM kyro_flags WHERE flag_id = ?")) {
                        ps.setString(1, flagId);
                        try (ResultSet rs = ps.executeQuery()) {
                            List<FlagRecord> list = readFlags(rs);
                            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
                        }
                    }
                });
    }

    public CompletableFuture<List<FlagRecord>> recentFlags(UUID player, int limit) {
        return this.submit(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT flag_id, player_uuid, player_name, category,"
                                        + " check_name, vl, created_at, reason, details, ping,"
                                        + " confidence, world, x, y, z, status, merged_count,"
                                        + " reviewed_by, reviewed_at, actions, previous_flag_id,"
                                        + " tps FROM kyro_flags WHERE player_uuid = ? ORDER BY"
                                        + " created_at DESC LIMIT ?")) {
                        ps.setString(1, player.toString());
                        ps.setInt(2, Math.max(1, limit));
                        try (ResultSet rs = ps.executeQuery()) {
                            return readFlags(rs);
                        }
                    }
                });
    }

    public CompletableFuture<PlayerFlagStats> stats(UUID player) {
        return this.submit(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT COUNT(*), COALESCE(SUM(merged_count), 0),"
                                        + " COALESCE(SUM(CASE WHEN status = 'NEW' THEN 1 ELSE 0"
                                        + " END), 0), COALESCE(SUM(CASE WHEN status = 'REVIEWED'"
                                        + " THEN 1 ELSE 0 END), 0), COALESCE(SUM(CASE WHEN status ="
                                        + " 'RESOLVED' THEN 1 ELSE 0 END), 0),"
                                        + " COALESCE(MAX(created_at), 0) FROM kyro_flags WHERE"
                                        + " player_uuid = ?")) {
                        ps.setString(1, player.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return PlayerFlagStats.EMPTY;
                            }
                            return new PlayerFlagStats(
                                    rs.getInt(1),
                                    rs.getInt(2),
                                    rs.getInt(3),
                                    rs.getInt(4),
                                    rs.getInt(5),
                                    rs.getLong(6));
                        }
                    }
                });
    }

    public CompletableFuture<Optional<Entry<UUID, String>>> findByName(String name) {
        return this.submit(
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT player_uuid, player_name FROM kyro_flags WHERE"
                                        + " player_name = ? COLLATE NOCASE ORDER BY created_at DESC"
                                        + " LIMIT 1")) {
                        ps.setString(1, name);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.<Entry<UUID, String>>empty();
                            }
                            return Optional.of(
                                    Map.entry(UUID.fromString(rs.getString(1)), rs.getString(2)));
                        }
                    }
                });
    }

    private CompletableFuture<Void> write(FlagStore.SqlWork<Void> work) {
        return this.submit(work);
    }

    private <T> CompletableFuture<T> submit(FlagStore.SqlWork<T> work) {
        return this.enqueue(
                () -> {
                    if (this.available && this.connection != null) {
                        try {
                            return work.run(this.connection);
                        } catch (SQLException | RuntimeException ex) {
                            this.failedOps.incrementAndGet();
                            this.log.log(Level.WARNING, "Database operation failed", ex);
                            throw ex;
                        }
                    } else {
                        throw new IllegalStateException("Flag database unavailable");
                    }
                });
    }

    private <T> CompletableFuture<T> enqueue(Callable<T> work) {
        FlagStore.PendingTask<T> task = new FlagStore.PendingTask<>(work);

        try {
            this.executor.execute(task);
        } catch (RejectedExecutionException ex) {
            long dropped = this.droppedWrites.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - this.lastDropWarning > 60000L) {
                this.lastDropWarning = now;
                this.log.warning(
                        "Flag database overloaded or shut down - operation dropped (total "
                                + dropped
                                + ").");
            }

            task.future.completeExceptionally(ex);
        }

        return task.future;
    }

    private static void inTransaction(Connection c, FlagStore.TxBody body) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);

        try {
            body.run();
            c.commit();
        } catch (SQLException | RuntimeException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static PreparedStatement prepare(Connection c, SqlStatement stmt) throws SQLException {
        PreparedStatement ps = c.prepareStatement(stmt.sql());

        try {
            int i = 1;

            for (Object p : stmt.params()) {
                if (p == null) {
                    ps.setNull(i++, 0);
                } else if (p instanceof Integer v) {
                    ps.setInt(i++, v);
                } else if (p instanceof Long v) {
                    ps.setLong(i++, v);
                } else if (p instanceof Double v) {
                    ps.setDouble(i++, v);
                } else {
                    ps.setString(i++, String.valueOf(p));
                }
            }

            return ps;
        } catch (SQLException ex) {
            ps.close();
            throw ex;
        }
    }

    private static void insertAudit(
            Connection c,
            long now,
            UUID actorUuid,
            String actorName,
            String action,
            UUID target,
            String detail)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO kyro_audit (created_at, actor_uuid, actor_name, action,"
                                + " target_uuid, detail) VALUES (?,?,?,?,?,?)")) {
            ps.setLong(1, now);
            if (actorUuid == null) {
                ps.setNull(2, 12);
            } else {
                ps.setString(2, actorUuid.toString());
            }

            ps.setString(3, actorName == null ? "CONSOLE" : actorName);
            ps.setString(4, action);
            if (target == null) {
                ps.setNull(5, 12);
            } else {
                ps.setString(5, target.toString());
            }

            ps.setString(6, detail == null ? "" : detail);
            ps.executeUpdate();
        }
    }

    private static void bindFlag(PreparedStatement ps, FlagRecord r) throws SQLException {
        ps.setString(1, r.flagId());
        ps.setString(2, r.playerUuid().toString());
        ps.setString(3, r.playerName());
        ps.setString(4, r.category().name());
        ps.setString(5, r.checkName());
        ps.setDouble(6, r.vl());
        ps.setLong(7, r.createdAt());
        ps.setString(8, r.reason());
        ps.setString(9, r.details());
        ps.setInt(10, r.ping());
        ps.setString(11, r.confidence().name());
        if (r.world() == null) {
            ps.setNull(12, 12);
            ps.setNull(13, 7);
            ps.setNull(14, 7);
            ps.setNull(15, 7);
        } else {
            ps.setString(12, r.world());
            ps.setDouble(13, r.x());
            ps.setDouble(14, r.y());
            ps.setDouble(15, r.z());
        }

        ps.setString(16, r.status().name());
        ps.setInt(17, r.mergedCount());
        if (r.reviewedBy() == null) {
            ps.setNull(18, 12);
        } else {
            ps.setString(18, r.reviewedBy());
        }

        ps.setLong(19, r.reviewedAt());
        ps.setString(20, r.actions());
        if (r.previousFlagId() == null) {
            ps.setNull(21, 12);
        } else {
            ps.setString(21, r.previousFlagId());
        }

        ps.setDouble(22, r.tps());
    }

    private static List<FlagRecord> readFlags(ResultSet rs) throws SQLException {
        ArrayList<FlagRecord> out = new ArrayList<>();

        while (rs.next()) {
            String world = rs.getString("world");
            out.add(
                    new FlagRecord(
                            rs.getString("flag_id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            CheckCategory.parse(rs.getString("category")),
                            rs.getString("check_name"),
                            rs.getDouble("vl"),
                            rs.getLong("created_at"),
                            rs.getString("reason"),
                            rs.getString("details"),
                            rs.getInt("ping"),
                            Confidence.parse(rs.getString("confidence")),
                            world,
                            world == null ? 0.0 : rs.getDouble("x"),
                            world == null ? 0.0 : rs.getDouble("y"),
                            world == null ? 0.0 : rs.getDouble("z"),
                            FlagStatus.parse(rs.getString("status")),
                            rs.getInt("merged_count"),
                            rs.getString("reviewed_by"),
                            rs.getLong("reviewed_at"),
                            rs.getString("actions"),
                            rs.getString("previous_flag_id"),
                            rs.getDouble("tps")));
        }

        return out;
    }

    private static final class PendingTask<T> implements Runnable {
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final Callable<T> work;

        private PendingTask(Callable<T> work) {
            this.work = work;
        }

        @Override
        public void run() {
            try {
                this.future.complete(this.work.call());
            } catch (Throwable ex) {
                this.future.completeExceptionally(ex);
                if (ex instanceof Error error) {
                    throw error;
                }
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface TxBody {
        void run() throws SQLException;
    }
}
