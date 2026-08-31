package com.company.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Ensures the target PostgreSQL database exists before Spring Data / Liquibase connects.
 */
public class DatabaseInitializer implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty("spring.datasource.url");
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password", "");

        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            return;
        }

        try {
            // Strip "jdbc:" to parse the URI cleanly
            URI cleanUri = URI.create(url.substring(5));
            String host = cleanUri.getHost() != null ? cleanUri.getHost() : "localhost";
            int port = cleanUri.getPort() > 0 ? cleanUri.getPort() : 5432;
            String path = cleanUri.getPath();
            if (path == null || path.length() <= 1) {
                return;
            }
            String targetDb = path.substring(1);
            if (targetDb.contains("?")) {
                targetDb = targetDb.substring(0, targetDb.indexOf('?'));
            }

            if ("postgres".equalsIgnoreCase(targetDb)) {
                return;
            }

            String maintenanceUrl = String.format("jdbc:postgresql://%s:%d/postgres", host, port);
            try (Connection conn = DriverManager.getConnection(maintenanceUrl, username, password);
                 Statement stmt = conn.createStatement()) {

                ResultSet rs = stmt.executeQuery(
                        String.format("SELECT 1 FROM pg_database WHERE datname = '%s'", targetDb.replace("'", "''"))
                );

                if (!rs.next()) {
                    log.info("[DB-INIT] Database '{}' does not exist. Creating automatically...", targetDb);
                    stmt.executeUpdate(String.format("CREATE DATABASE \"%s\"", targetDb.replace("\"", "\"\"")));
                    log.info("[DB-INIT] Database '{}' created successfully.", targetDb);
                }
            }

            // Ensure initial schemas and extensions exist in target database before Liquibase initializes
            String targetUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, targetDb);
            try (Connection conn = DriverManager.getConnection(targetUrl, username, password);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS mcp_rag;");
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector;");
                log.info("[DB-INIT] Base schema 'mcp_rag' and extension 'vector' verified in '{}'.", targetDb);
            }
        } catch (Exception e) {
            log.warn("[DB-INIT] Database pre-check/creation skipped: {}", e.getMessage());
        }
    }
}
