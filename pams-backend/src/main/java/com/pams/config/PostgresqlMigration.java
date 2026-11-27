package com.pams.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;

/** One-shot entry point that shares the application's migration resources and JDBC driver. */
public final class PostgresqlMigration {
    private static final String HISTORY_SCHEMA = "pams_flyway";
    private static final String EMPTY_SEED = "empty-seed";
    private static final String HISTORICAL_IMPORT = "historical-import";

    private PostgresqlMigration() {}

    public static void run(String[] args) throws SQLException, IOException {
        if (args.length != 1 || !(EMPTY_SEED.equals(args[0]) || HISTORICAL_IMPORT.equals(args[0]))) {
            throw new IllegalArgumentException("Usage: migrate-postgresql empty-seed|historical-import");
        }
        String mode = args[0];
        String url = requiredEnv("PAMS_PG_MIGRATION_URL");
        String user = requiredEnv("PAMS_PG_MIGRATION_USER");
        String password = requiredEnv("PAMS_PG_MIGRATION_PASSWORD");
        if (!url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PAMS_PG_MIGRATION_URL must be a PostgreSQL JDBC URL");
        }
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            acquireMigrationLock(conn);
            String previousMode = recordedMode(conn);
            if (previousMode != null && !previousMode.equals(mode)) {
                throw new IllegalStateException("Database was initialized in mode " + previousMode);
            }
            Flyway flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration-postgresql")
                    .defaultSchema(HISTORY_SCHEMA)
                    .schemas(HISTORY_SCHEMA)
                    .createSchemas(false)
                    .load();
            flyway.migrate();
            flyway.validate();
            if (previousMode == null) {
                recordMode(conn, mode);
            }
            System.out.println("PostgreSQL migration complete. Version "
                    + flyway.info().current().getVersion() + ", mode " + mode);
        }
    }

    private static void acquireMigrationLock(Connection conn) throws SQLException {
        // Session lock also covers reference data initialization and deployments from other hosts.
        try (var statement = conn.createStatement();
                var rows = statement.executeQuery("SELECT pg_try_advisory_lock(1129270866, 1)")) {
            if (!rows.next() || !rows.getBoolean(1)) {
                throw new IllegalStateException("Another Concerto migration holds the database lock");
            }
        }
    }

    private static String recordedMode(Connection conn) throws SQLException {
        try (var statement = conn.createStatement();
                var rows = statement.executeQuery("SELECT to_regclass('pams_flyway.bootstrap_mode')")) {
            if (!rows.next() || rows.getString(1) == null) {
                return null;
            }
        }
        try (var statement = conn.createStatement();
                var rows = statement.executeQuery("SELECT mode FROM pams_flyway.bootstrap_mode")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static void recordMode(Connection conn, String mode) throws SQLException, IOException {
        conn.setAutoCommit(false);
        try {
            if (EMPTY_SEED.equals(mode)) {
                try (var referenceData = PostgresqlMigration.class.getResourceAsStream(
                        "/db/seed-postgresql/v74_reference_data.sql");
                        var statement = conn.createStatement()) {
                    if (referenceData == null) {
                        throw new IllegalStateException("PostgreSQL reference data resource is missing");
                    }
                    statement.execute(new String(referenceData.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            try (var insert = conn.prepareStatement("INSERT INTO pams_flyway.bootstrap_mode (mode) VALUES (?)")) {
                insert.setString(1, mode);
                insert.executeUpdate();
            }
            conn.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            conn.rollback();
            throw e;
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value;
    }
}
