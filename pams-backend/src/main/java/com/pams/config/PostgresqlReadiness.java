package com.pams.config;

import java.util.Comparator;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("postgresql")
@RestController
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PostgresqlReadiness implements ApplicationRunner {
    private final JdbcTemplate db;
    private final MigrationVersion minVersion;
    private final MigrationVersion maxVersion;
    private final boolean candidate;

    public PostgresqlReadiness(JdbcTemplate db, Environment environment,
            @Value("${pams.postgresql.min-schema-version}") String min,
            @Value("${pams.postgresql.max-schema-version}") String max) {
        this.db = db;
        this.candidate = environment.matchesProfiles("postgresql-candidate");
        this.minVersion = MigrationVersion.fromVersion(min);
        this.maxVersion = MigrationVersion.fromVersion(max);
        if (minVersion.compareTo(maxVersion) > 0) {
            throw new IllegalArgumentException("Invalid PostgreSQL schema compatibility range");
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        check();
    }

    @GetMapping("/api/ready")
    public ResponseEntity<String> ready() {
        try {
            check();
            return ResponseEntity.ok("ready");
        } catch (DataAccessException | IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("not ready");
        }
    }

    private void check() {
        requireRuntimeRole();
        if (candidate) requireReadOnlyRole();
        requireCompatibleVersion();
    }

    private void requireRuntimeRole() {
        Boolean allowed = db.queryForObject("""
                SELECT current_schema() = 'pams'
                   AND NOT has_database_privilege(current_database(), 'CREATE, TEMP')
                   AND NOT has_schema_privilege('pams', 'CREATE')
                   AND NOT has_schema_privilege('pams_flyway', 'CREATE')
                   AND NOT has_table_privilege('pams_flyway.flyway_schema_history', 'INSERT, UPDATE, DELETE, TRUNCATE')
                   AND has_table_privilege('pams.sys_user', 'INSERT') = ?
                """, Boolean.class, !candidate);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new IllegalStateException("PostgreSQL application requires the configured runtime database role");
        }
    }

    private void requireReadOnlyRole() {
        Boolean readOnly = db.queryForObject("""
                SELECT NOT EXISTS (
                       SELECT 1 FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
                       WHERE n.nspname IN ('pams', 'pams_flyway')
                       AND CASE
                           WHEN c.relkind = 'S' THEN has_sequence_privilege(c.oid, 'USAGE, UPDATE')
                           WHEN c.relkind IN ('r', 'p', 'v', 'm') THEN
                               NOT has_table_privilege(c.oid, 'SELECT')
                               OR has_table_privilege(c.oid, 'INSERT, UPDATE, DELETE, TRUNCATE, TRIGGER')
                           ELSE false
                       END)
                """, Boolean.class);
        if (!Boolean.TRUE.equals(readOnly)) {
            throw new IllegalStateException("PostgreSQL candidate requires the read-only database role");
        }
    }

    private void requireCompatibleVersion() {
        var versions = db.query("""
                SELECT version, success FROM pams_flyway.flyway_schema_history
                """, (rs, row) -> {
            if (!rs.getBoolean("success")) {
                throw new IllegalStateException("PostgreSQL schema history contains a failed migration");
            }
            String version = rs.getString("version");
            return version == null ? MigrationVersion.EMPTY : MigrationVersion.fromVersion(version);
        });
        MigrationVersion actual = versions.stream().max(Comparator.naturalOrder()).orElse(MigrationVersion.EMPTY);
        if (actual.compareTo(minVersion) < 0 || actual.compareTo(maxVersion) > 0) {
            throw new IllegalStateException("PostgreSQL schema version " + actual + " outside "
                    + minVersion + "–" + maxVersion);
        }
        if (!Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pams_flyway.bootstrap_mode)", Boolean.class))) {
            throw new IllegalStateException("PostgreSQL initialization has not finished");
        }
    }
}
