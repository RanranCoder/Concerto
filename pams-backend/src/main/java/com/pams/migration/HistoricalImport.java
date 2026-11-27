package com.pams.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.config.PostgresqlMigration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.api.MigrationVersion;

/** Imports a fixed MySQL V74 snapshot. Business rows, sequence restarts and the admission record commit together. */
public final class HistoricalImport {
    private static final int BATCH_ROWS = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> STATUS_TABLES = List.of("activity", "schedule", "attendance",
            "notification_outbox", "notification_delivery", "notification_reminder", "agent_request", "agent_task", "agent_run");

    private HistoricalImport() {}

    public static void run(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: import-mysql-history <snapshot-id> <new-report.json>");
        }
        String snapshot = snapshotId(args[0]);
        Path path = Path.of(args[1]);
        Files.createFile(path);
        long start = System.nanoTime();
        var report = new LinkedHashMap<String, Object>();
        report.put("snapshotId", snapshot);
        report.put("startedAt", Instant.now().toString());
        try (Connection target = PostgresqlMigration.connect(); Connection source = connectSource()) {
            report.putAll(importSnapshot(source, target, snapshot, report));
            report.put("status", "PASS");
        } catch (Exception error) {
            report.put("status", "FAILED");
            if (error instanceof RejectedRow rejection) {
                report.put("rejected", List.of(rejection.details));
            } else if (error instanceof SQLException database) {
                report.put("error", Map.of("type", "database", "sqlState", sqlState(database)));
            } else {
                report.put("error", error instanceof IllegalStateException ? error.getMessage() : error.getClass().getSimpleName());
            }
        } finally {
            report.put("elapsedMs", elapsed(start));
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
        if (!"PASS".equals(report.get("status"))) {
            throw new IllegalStateException("Historical import failed. See " + path);
        }
        System.out.println("Historical snapshot reconciled. Report: " + path);
    }

    private static Map<String, Object> importSnapshot(Connection source, Connection target, String snapshot,
                                                       Map<String, Object> report) throws Exception {
        PostgresqlMigration.acquireMigrationLock(target);
        target.setAutoCommit(false);
        try {
            requireTarget(target);
            report.put("targetDatabase", target.getCatalog());
            execute(target, "SET LOCAL TIME ZONE 'UTC'");
            String version = currentVersion(source, "`flyway_schema_history`");
            if (!"74".equals(version)) throw new IllegalStateException("MySQL source must be Flyway V74, found " + version);
            assertInnoDb(source);
            String server = scalar(source, "SELECT @@server_uuid");
            String database = source.getCatalog();
            report.put("sourceVersion", version);
            report.put("sourceDatabase", database);
            report.put("sourceServerId", server);
            report.put("targetVersion", "76");
            report.put("transformations", List.of("DATETIME preserves wall time", "TIMESTAMP preserves the UTC instant",
                    "MySQL JSON becomes canonical jsonb for reconciliation", "Boolean values must be 0 or 1",
                    "Identity restarts honor the source AUTO_INCREMENT floor"));

            Existing previous = existing(target);
            requireSameSnapshot(previous, snapshot, server, database);
            boolean firstImport = previous == null;
            var tables = ImportSchema.load(source, target);
            var tablesReport = new ArrayList<Map<String, Object>>();
            var signature = ImportValues.digest();
            report.put("operation", firstImport ? "IMPORT" : "VERIFY");
            report.put("tables", tablesReport);

            String tableNames = String.join(",", tables.stream().map(ImportSchema.Table::target).toList());
            execute(target, "LOCK TABLE " + tableNames + " IN SHARE ROW EXCLUSIVE MODE");
            if (firstImport) {
                requireEmpty(target, tables);
                execute(target, "SET CONSTRAINTS pams.fk_task_dep DEFERRED");
            }
            var nextIds = sourceIdentities(source);
            long rows = 0;
            for (var table : tables) {
                long tableStart = System.nanoTime();
                report.put("currentTable", table.name());
                var expected = firstImport ? transfer(source, target, table) : summarize(source, table, true);
                var actual = summarize(target, table, false);
                var tableResult = new LinkedHashMap<String, Object>();
                tableResult.put("table", table.name());
                tableResult.put("source", expected);
                tableResult.put("target", actual);
                tableResult.put("elapsedMs", elapsed(tableStart));
                tablesReport.add(tableResult);
                if (!expected.equals(actual)) {
                    throw new IllegalStateException("Row reconciliation differs in " + table.name());
                }
                ImportValues.add(signature, table.name());
                ImportValues.add(signature, expected.sha256());
                rows = Math.addExact(rows, expected.rows());
            }
            String sha = ImportValues.hex(signature);
            report.remove("currentTable");
            if (firstImport) {
                execute(target, "SET CONSTRAINTS pams.fk_task_dep IMMEDIATE");
                for (int i = 0; i < tables.size(); i++) {
                    var table = tables.get(i);
                    report.put("currentTable", table.name());
                    restartIdentity(target, table, nextIds.get(table.name()), tablesReport.get(i));
                }
                report.remove("currentTable");
                try (var insert = target.prepareStatement("""
                        INSERT INTO pams_flyway.historical_import_state
                          (snapshot_id,source_server_id,source_database,reconciliation_sha256) VALUES (?,?,?,?)
                        """)) {
                    insert.setString(1, snapshot);
                    insert.setString(2, server);
                    insert.setString(3, database);
                    insert.setString(4, sha);
                    insert.executeUpdate();
                }
            } else if (!previous.sha().equals(sha)) {
                throw new IllegalStateException("Snapshot digest has changed since the historical import");
            }
            target.commit();
            return Map.of("reconciliationSha256", sha, "rowCount", rows, "rejected", List.of());
        } catch (Exception error) {
            target.rollback();
            throw error;
        }
    }

    public static void openWrites(String[] args) throws SQLException {
        if (args.length != 2 || !args[1].matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Usage: open-postgresql-writes <snapshot-id> <reconciliation-sha256>");
        }
        String snapshot = snapshotId(args[0]);
        try (Connection target = PostgresqlMigration.connect()) {
            PostgresqlMigration.acquireMigrationLock(target);
            requireTarget(target);
            try (var update = target.prepareStatement("""
                    UPDATE pams_flyway.historical_import_state SET opened_at=clock_timestamp()
                    WHERE snapshot_id=? AND reconciliation_sha256=? AND opened_at IS NULL
                    RETURNING opened_at AT TIME ZONE 'UTC'
                    """)) {
                update.setString(1, snapshot);
                update.setString(2, args[1]);
                try (var row = update.executeQuery()) {
                    if (!row.next()) throw new IllegalStateException("Unopened snapshot and reconciliation digest must match");
                    System.out.println("PostgreSQL writes admitted at " + row.getString(1) + " UTC");
                }
            }
        }
    }

    private static Connection connectSource() throws SQLException {
        String url = env("PAMS_MYSQL_IMPORT_URL");
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalStateException("Source requires a MySQL JDBC URL");
        var properties = new Properties();
        properties.put("user", env("PAMS_MYSQL_IMPORT_USER"));
        properties.put("password", env("PAMS_MYSQL_IMPORT_PASSWORD"));
        properties.put("useCursorFetch", "true");
        properties.put("zeroDateTimeBehavior", "EXCEPTION");
        Connection source = DriverManager.getConnection(url, properties);
        try {
            execute(source, "SET SESSION time_zone = '+00:00'");
            execute(source, "SET SESSION information_schema_stats_expiry = 0");
            source.setReadOnly(true);
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            source.setAutoCommit(false);
            return source;
        } catch (SQLException error) {
            source.close();
            throw error;
        }
    }

    private static void assertInnoDb(Connection source) throws SQLException {
        try (var step = source.createStatement();
                var row = step.executeQuery("""
                        SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
                        AND table_type='BASE TABLE' AND (engine IS NULL OR engine <> 'InnoDB') LIMIT 1
                        """)) {
            if (row.next()) throw new IllegalStateException("Consistent snapshot requires InnoDB tables");
        }
    }

    private static void requireTarget(Connection target) throws SQLException {
        if (!"historical-import".equals(scalar(target, "SELECT mode FROM pams_flyway.bootstrap_mode"))) {
            throw new IllegalStateException("Destination must be initialized in historical-import mode");
        }
        if (!"76".equals(currentVersion(target, "pams_flyway.flyway_schema_history"))) {
            throw new IllegalStateException("Destination must be migrated to PostgreSQL V76");
        }
    }

    private static String currentVersion(Connection connection, String history) throws SQLException {
        MigrationVersion current = MigrationVersion.EMPTY;
        try (var query = connection.createStatement();
                var rows = query.executeQuery("SELECT version, success FROM " + history)) {
            while (rows.next()) {
                if (!rows.getBoolean(2)) throw new IllegalStateException("Flyway history contains a failed migration");
                String version = rows.getString(1);
                if (version != null) {
                    var entry = MigrationVersion.fromVersion(version);
                    if (entry.compareTo(current) > 0) current = entry;
                }
            }
        }
        return current.getVersion();
    }

    private record Existing(String snapshot, String server, String database, String sha, boolean open) {}

    private static Existing existing(Connection target) throws SQLException {
        try (var query = target.createStatement();
                var row = query.executeQuery("""
                        SELECT snapshot_id,source_server_id,source_database,reconciliation_sha256,opened_at IS NOT NULL
                        FROM pams_flyway.historical_import_state
                        """)) {
            return row.next() ? new Existing(row.getString(1), row.getString(2), row.getString(3),
                    row.getString(4), row.getBoolean(5)) : null;
        }
    }

    private static void requireSameSnapshot(Existing existing, String snapshot, String server, String database) {
        if (existing == null) return;
        if (existing.open()) throw new IllegalStateException("PostgreSQL writes have already been admitted");
        if (!existing.snapshot().equals(snapshot) || !existing.server().equals(server) || !existing.database().equals(database)) {
            throw new IllegalStateException("Existing import belongs to a different source snapshot");
        }
    }

    private static void requireEmpty(Connection target, List<ImportSchema.Table> tables) throws SQLException {
        for (var table : tables) {
            if ("1".equals(scalar(target, "SELECT 1 FROM " + table.target() + " LIMIT 1"))) {
                throw new IllegalStateException("First import requires empty destination table " + table.name());
            }
        }
    }

    private static Map<String, Long> sourceIdentities(Connection source) throws SQLException {
        var next = new TreeMap<String, Long>();
        try (var query = source.createStatement();
                var rows = query.executeQuery("""
                        SELECT table_name,auto_increment FROM information_schema.tables
                        WHERE table_schema=DATABASE() AND auto_increment IS NOT NULL
                        """)) {
            while (rows.next()) next.put(rows.getString(1), rows.getLong(2));
        }
        return next;
    }

    private static void restartIdentity(Connection target, ImportSchema.Table table, Long sourceNext,
                                        Map<String, Object> result) throws SQLException {
        for (var column : table.columns()) {
            if (!column.identity()) continue;
            if (sourceNext == null || sourceNext < 1) {
                throw new IllegalStateException("Missing source AUTO_INCREMENT in " + table.name());
            }
            String quoted = ImportSchema.identifier(column.name(), false);
            String max = scalar(target, "SELECT MAX(" + quoted + ") FROM " + table.target());
            long rowNext = max == null ? 1 : Math.addExact(Long.parseLong(max), 1);
            long next = Math.max(sourceNext, rowNext);
            execute(target, "ALTER TABLE " + table.target() + " ALTER COLUMN " + quoted + " RESTART WITH " + next);
            result.put("identityNext", next);
        }
    }

    private static Summary transfer(Connection source, Connection target, ImportSchema.Table table) throws SQLException {
        var summary = new Accumulator(table);
        try (var read = source.prepareStatement(table.select(true));
                var insert = target.prepareStatement(table.insert())) {
            read.setFetchSize(BATCH_ROWS);
            try (var rows = read.executeQuery()) {
                var batch = new ArrayList<Object[]>(BATCH_ROWS);
                while (rows.next()) {
                    Object[] values = readRow(rows, table, true);
                    summary.add(values);
                    batch.add(values);
                    if (batch.size() == BATCH_ROWS) {
                        writeBatch(target, insert, table, batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) writeBatch(target, insert, table, batch);
            }
        }
        return summary.finish();
    }

    private static Summary summarize(Connection connection, ImportSchema.Table table, boolean mysql) throws SQLException {
        var summary = new Accumulator(table);
        try (var query = connection.prepareStatement(table.select(mysql))) {
            query.setFetchSize(BATCH_ROWS);
            try (var rows = query.executeQuery()) {
                while (rows.next()) summary.add(readRow(rows, table, mysql));
            }
        }
        return summary.finish();
    }

    private static Object[] readRow(ResultSet row, ImportSchema.Table table, boolean mysql) throws SQLException {
        var data = new Object[table.columns().size()];
        for (int i = 0; i < data.length; i++) {
            var column = table.columns().get(i);
            try {
                data[i] = ImportValues.read(row, i + 1, column, mysql);
            } catch (IOException | IllegalArgumentException | java.time.DateTimeException error) {
                var key = ImportValues.digest();
                for (String name : table.keys()) ImportValues.add(key, row.getString(table.index(name) + 1));
                throw new RejectedRow(table.name(), ImportValues.hex(key), column.name(), "UNREPRESENTABLE_VALUE");
            }
        }
        return data;
    }

    private static void writeBatch(Connection target, PreparedStatement insert, ImportSchema.Table table,
                                   List<Object[]> rows) throws SQLException {
        var before = target.setSavepoint();
        try {
            for (var row : rows) {
                bind(insert, row);
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException batchError) {
            target.rollback(before);
            insert.clearBatch();
            // Identify the rejected row without including its payload or database exception text.
            for (var row : rows) {
                try {
                    bind(insert, row);
                    insert.executeUpdate();
                } catch (SQLException rowError) {
                    throw new RejectedRow(table.name(), keyFingerprint(table, row), "", sqlState(rowError));
                }
            }
            throw batchError;
        }
        target.releaseSavepoint(before);
    }

    private static void bind(PreparedStatement insert, Object[] values) throws SQLException {
        insert.clearParameters();
        for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]);
    }

    private record Summary(long rows, List<String> firstKey, List<String> lastKey, String sha256,
                           Map<String, Long> organizationCounts, Map<String, Map<String, Long>> statusCounts) {}

    private static final class Accumulator {
        private final ImportSchema.Table table;
        private final int orgIndex;
        private final int statusIndex;
        private final MessageDigest digest = ImportValues.digest();
        private final Map<String, Long> orgs = new TreeMap<>();
        private final Map<String, Map<String, Long>> statuses = new TreeMap<>();
        private long count;
        private Object[] first;
        private Object[] last;

        private Accumulator(ImportSchema.Table table) {
            this.table = table;
            orgIndex = table.index("org_id");
            statusIndex = STATUS_TABLES.contains(table.name()) ? table.index("status") : -1;
        }

        private void add(Object[] row) {
            for (Object value : row) ImportValues.add(digest, value);
            if (++count == 1) first = row;
            last = row;
            String org = orgIndex >= 0 ? String.valueOf(row[orgIndex]) : "global";
            orgs.merge(org, 1L, Long::sum);
            if (statusIndex >= 0) {
                var counts = statuses.computeIfAbsent(org, ignored -> new TreeMap<>());
                counts.merge(String.valueOf(row[statusIndex]), 1L, Long::sum);
            }
        }

        private Summary finish() {
            return new Summary(count, displayKey(table, first), displayKey(table, last),
                    ImportValues.hex(digest), orgs, statuses);
        }
    }

    private static List<String> displayKey(ImportSchema.Table table, Object[] row) {
        if (row == null) return List.of();
        return table.keys().stream().map(name -> row[table.index(name)]).allMatch(Number.class::isInstance)
                ? table.keys().stream().map(name -> row[table.index(name)].toString()).toList()
                : List.of("sha256:" + keyFingerprint(table, row));
    }

    private static String keyFingerprint(ImportSchema.Table table, Object[] row) {
        var key = ImportValues.digest();
        for (String name : table.keys()) ImportValues.add(key, row[table.index(name)]);
        return ImportValues.hex(key);
    }

    private static final class RejectedRow extends RuntimeException {
        private final Map<String, String> details;

        private RejectedRow(String table, String key, String column, String reason) {
            details = Map.of("table", table, "primaryKeySha256", key, "column", column, "reason", reason);
        }
    }

    private static String sqlState(SQLException error) {
        return error.getSQLState() == null ? "UNKNOWN" : error.getSQLState();
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (var query = connection.createStatement(); var row = query.executeQuery(sql)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String snapshotId(String id) {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IllegalArgumentException("Invalid snapshot ID");
        return id;
    }

    private static String env(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable " + name);
        return value;
    }

    private static long elapsed(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
