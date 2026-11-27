package com.pams.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** Database fixtures may only connect to an explicitly named, local acceptance database. */
public final class PostgresqlAcceptanceDatabase {
    private PostgresqlAcceptanceDatabase() {}

    public static void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("PAMS_PG_ACCEPTANCE_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/pams_acceptance_[a-zA-Z0-9_]+")) {
            throw new IllegalStateException("Provide the JDBC URL of an isolated local pams_acceptance_* database");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("PAMS_PG_ACCEPTANCE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("PAMS_PG_ACCEPTANCE_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO pams");
        registry.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("spring.flyway.enabled", () -> false);
    }
}
