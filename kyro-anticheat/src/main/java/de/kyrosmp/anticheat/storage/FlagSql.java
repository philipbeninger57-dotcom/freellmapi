/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.flag.FlagStatus;
import de.kyrosmp.anticheat.storage.FlagQuery;
import de.kyrosmp.anticheat.storage.SqlStatement;
import de.kyrosmp.anticheat.storage.SummaryQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class FlagSql {
    public static final int SCHEMA_VERSION = 2;
    public static final List<String> PRAGMAS = List.of("PRAGMA journal_mode=WAL", "PRAGMA synchronous=NORMAL", "PRAGMA foreign_keys=ON", "PRAGMA busy_timeout=5000");
    public static final String CREATE_META = "CREATE TABLE IF NOT EXISTS kyro_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)";
    public static final String SELECT_SCHEMA_VERSION = "SELECT meta_value FROM kyro_meta WHERE meta_key = 'schema_version'";
    public static final String UPSERT_SCHEMA_VERSION = "INSERT INTO kyro_meta (meta_key, meta_value) VALUES ('schema_version', ?) ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value";
    public static final List<String> MIGRATION_V1 = List.of("CREATE TABLE IF NOT EXISTS kyro_flags (flag_id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, category TEXT NOT NULL, check_name TEXT NOT NULL, vl REAL NOT NULL, created_at INTEGER NOT NULL, reason TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', ping INTEGER NOT NULL DEFAULT -1, confidence TEXT NOT NULL, world TEXT, x REAL, y REAL, z REAL, status TEXT NOT NULL DEFAULT 'NEW', merged_count INTEGER NOT NULL DEFAULT 1, reviewed_by TEXT, reviewed_at INTEGER NOT NULL DEFAULT 0)", "CREATE INDEX IF NOT EXISTS idx_kyro_flags_player_time ON kyro_flags (player_uuid, created_at DESC)", "CREATE INDEX IF NOT EXISTS idx_kyro_flags_time ON kyro_flags (created_at DESC)", "CREATE INDEX IF NOT EXISTS idx_kyro_flags_check ON kyro_flags (check_name)", "CREATE INDEX IF NOT EXISTS idx_kyro_flags_name ON kyro_flags (player_name COLLATE NOCASE)", "CREATE TABLE IF NOT EXISTS kyro_audit (id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, actor_uuid TEXT, actor_name TEXT NOT NULL, action TEXT NOT NULL, target_uuid TEXT, detail TEXT NOT NULL DEFAULT '')", "CREATE INDEX IF NOT EXISTS idx_kyro_audit_target ON kyro_audit (target_uuid, created_at DESC)");
    public static final List<String> MIGRATION_V2 = List.of("ALTER TABLE kyro_flags ADD COLUMN actions TEXT NOT NULL DEFAULT ''", "ALTER TABLE kyro_flags ADD COLUMN previous_flag_id TEXT", "ALTER TABLE kyro_flags ADD COLUMN tps REAL NOT NULL DEFAULT -1");
    public static final String FLAG_COLUMNS = "flag_id, player_uuid, player_name, category, check_name, vl, created_at, reason, details, ping, confidence, world, x, y, z, status, merged_count, reviewed_by, reviewed_at, actions, previous_flag_id, tps";
    public static final String INSERT_FLAG = "INSERT OR IGNORE INTO kyro_flags (flag_id, player_uuid, player_name, category, check_name, vl, created_at, reason, details, ping, confidence, world, x, y, z, status, merged_count, reviewed_by, reviewed_at, actions, previous_flag_id, tps) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
    public static final String MERGE_FLAG = "UPDATE kyro_flags SET merged_count = merged_count + 1, vl = MAX(vl, ?), confidence = ? WHERE flag_id = ?";
    public static final String APPEND_ACTION = "UPDATE kyro_flags SET actions = CASE WHEN actions = '' THEN ? ELSE actions || '; ' || ? END WHERE flag_id = ?";
    public static final String UPDATE_STATUS = "UPDATE kyro_flags SET status = ?, reviewed_by = ?, reviewed_at = ? WHERE flag_id = ?";
    public static final String SELECT_FLAG_BY_ID = "SELECT flag_id, player_uuid, player_name, category, check_name, vl, created_at, reason, details, ping, confidence, world, x, y, z, status, merged_count, reviewed_by, reviewed_at, actions, previous_flag_id, tps FROM kyro_flags WHERE flag_id = ?";
    public static final String SELECT_RECENT_FLAGS = "SELECT flag_id, player_uuid, player_name, category, check_name, vl, created_at, reason, details, ping, confidence, world, x, y, z, status, merged_count, reviewed_by, reviewed_at, actions, previous_flag_id, tps FROM kyro_flags WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?";
    public static final String SELECT_PLAYER_STATS = "SELECT COUNT(*), COALESCE(SUM(merged_count), 0), COALESCE(SUM(CASE WHEN status = 'NEW' THEN 1 ELSE 0 END), 0), COALESCE(SUM(CASE WHEN status = 'REVIEWED' THEN 1 ELSE 0 END), 0), COALESCE(SUM(CASE WHEN status = 'RESOLVED' THEN 1 ELSE 0 END), 0), COALESCE(MAX(created_at), 0) FROM kyro_flags WHERE player_uuid = ?";
    public static final String SELECT_UUID_BY_NAME = "SELECT player_uuid, player_name FROM kyro_flags WHERE player_name = ? COLLATE NOCASE ORDER BY created_at DESC LIMIT 1";
    public static final String DELETE_PLAYER_FLAGS = "DELETE FROM kyro_flags WHERE player_uuid = ?";
    public static final String INSERT_AUDIT = "INSERT INTO kyro_audit (created_at, actor_uuid, actor_name, action, target_uuid, detail) VALUES (?,?,?,?,?,?)";

    private FlagSql() {
    }

    public static SqlStatement bulkStatus(UUID player, Collection<FlagStatus> from, FlagStatus to, String actor, long now) {
        ArrayList<Object> params = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder("UPDATE kyro_flags SET status = ?, reviewed_by = ?, reviewed_at = ? WHERE player_uuid = ? AND status IN (");
        params.add(to.name());
        params.add(to == FlagStatus.NEW ? null : actor);
        params.add(to == FlagStatus.NEW ? 0L : now);
        params.add(player.toString());
        boolean first = true;
        for (FlagStatus s : from) {
            sql.append(first ? "?" : ",?");
            params.add(s.name());
            first = false;
        }
        if (first) {
            throw new IllegalArgumentException("at least one source status required");
        }
        sql.append(")");
        return new SqlStatement(sql.toString(), params);
    }

    public static SqlStatement summaries(SummaryQuery q) {
        ArrayList<Object> params = new ArrayList<Object>();
        String categoryFilter = q.category() == null ? "" : " AND x.category = ?";
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT f.player_uuid, ").append("(SELECT x.player_name FROM kyro_flags x WHERE x.player_uuid = f.player_uuid ").append("ORDER BY x.created_at DESC LIMIT 1) AS player_name, ").append("COUNT(*) AS flag_count, ").append("SUM(f.merged_count) AS trigger_count, ").append("SUM(CASE WHEN f.created_at >= ? THEN 1 ELSE 0 END) AS current_count, ").append("SUM(CASE WHEN f.status = 'NEW' THEN 1 ELSE 0 END) AS new_count, ").append("SUM(CASE WHEN f.status = 'REVIEWED' THEN 1 ELSE 0 END) AS reviewed_count, ").append("MAX(CASE WHEN f.status <> 'RESOLVED' THEN f.vl END) AS max_open_vl, ").append("MAX(f.created_at) AS last_flag_at, ").append("(SELECT x.check_name FROM kyro_flags x WHERE x.player_uuid = f.player_uuid").append(categoryFilter).append(" ORDER BY x.created_at DESC LIMIT 1) AS last_check ").append("FROM kyro_flags f WHERE 1 = 1");
        params.add(q.currentSince());
        if (q.category() != null) {
            params.add(q.category().name());
        }
        if (q.category() != null) {
            sql.append(" AND f.category = ?");
            params.add(q.category().name());
        }
        if (q.online() != SummaryQuery.OnlineFilter.ALL && !q.onlineUuids().isEmpty()) {
            sql.append(q.online() == SummaryQuery.OnlineFilter.ONLINE ? " AND f.player_uuid IN (" : " AND f.player_uuid NOT IN (");
            boolean first = true;
            for (UUID id : q.onlineUuids()) {
                sql.append(first ? "?" : ",?");
                params.add(id.toString());
                first = false;
            }
            sql.append(")");
        }
        sql.append(" GROUP BY f.player_uuid");
        ArrayList<String> having = new ArrayList<String>();
        if (q.onlyNew()) {
            having.add("new_count > 0");
        }
        if (q.onlyCurrent()) {
            having.add("last_flag_at >= ?");
            params.add(q.currentSince());
        }
        if (!having.isEmpty()) {
            sql.append(" HAVING ").append(String.join((CharSequence)" AND ", having));
        }
        StringBuilder stringBuilder = sql.append(" ORDER BY ");
        stringBuilder.append(switch (q.sort()) {
            default -> throw new MatchException(null, null);
            case SummaryQuery.Sort.LAST_FLAG -> "last_flag_at DESC";
            case SummaryQuery.Sort.MAX_VL -> "COALESCE(max_open_vl, -1) DESC, last_flag_at DESC";
            case SummaryQuery.Sort.FLAG_COUNT -> "flag_count DESC, last_flag_at DESC";
            case SummaryQuery.Sort.NAME -> "player_name COLLATE NOCASE ASC";
        }).append(", f.player_uuid ASC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(q.pageSize() + 1);
        params.add((long)q.page() * (long)q.pageSize());
        return new SqlStatement(sql.toString(), params);
    }

    public static SqlStatement playerFlags(UUID player, FlagQuery q) {
        ArrayList<Object> params = new ArrayList<Object>();
        StringBuilder sql = new StringBuilder("SELECT ").append(FLAG_COLUMNS).append(" FROM kyro_flags WHERE player_uuid = ?");
        params.add(player.toString());
        if (q.category() != null) {
            sql.append(" AND category = ?");
            params.add(q.category().name());
        }
        if (q.onlyNew()) {
            sql.append(" AND status = 'NEW'");
        }
        sql.append(q.sort() == FlagQuery.Sort.VL ? " ORDER BY vl DESC, created_at DESC, flag_id ASC" : " ORDER BY created_at DESC, flag_id ASC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(q.pageSize() + 1);
        params.add((long)q.page() * (long)q.pageSize());
        return new SqlStatement(sql.toString(), params);
    }
}

