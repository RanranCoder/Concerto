package com.pams.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/** Selects database-specific SQL for statements that do not go through Hibernate. */
public final class SqlDialect {
    private SqlDialect() {}

    public static boolean postgresql(JdbcTemplate jdbc) {
        String url = switch (jdbc.getDataSource()) {
            case HikariDataSource pool -> pool.getJdbcUrl();
            case AbstractDriverBasedDataSource dataSource -> dataSource.getUrl();
            case null, default -> null;
        };
        return url != null && url.startsWith("jdbc:postgresql:");
    }

    public static String upsert(JdbcTemplate jdbc, String conflictColumns, String assignments) {
        return postgresql(jdbc)
                ? " ON CONFLICT (" + conflictColumns + ") DO UPDATE SET " + assignments
                : " ON DUPLICATE KEY UPDATE " + assignments;
    }

    public static String insertOnce(JdbcTemplate jdbc, String conflictColumns, String unchangedColumn) {
        return postgresql(jdbc)
                ? " ON CONFLICT (" + conflictColumns + ") DO NOTHING"
                : " ON DUPLICATE KEY UPDATE " + unchangedColumn + "=" + unchangedColumn;
    }
}
