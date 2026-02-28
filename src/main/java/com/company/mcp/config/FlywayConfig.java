package com.company.mcp.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway database migration configuration.
 * Handles automatic schema versioning and migration on application startup.
 */
@Configuration
public class FlywayConfig {

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String migrationLocations;

    @Value("${spring.flyway.baseline-on-migrate:false}")
    private boolean baselineOnMigrate;

    /**
     * Configures Flyway for database migrations.
     * Ensures schema is up-to-date before application starts processing incidents.
     * 
     * @param dataSource PostgreSQL datasource
     * @return Configured Flyway instance
     */
    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway
            .configure()
            .dataSource(dataSource)
            .locations(migrationLocations)
            .baselineOnMigrate(baselineOnMigrate)
            .load();

        // Auto-migrate on startup (validate is done implicitly during migrate)
        flyway.migrate();

        return flyway;
    }
}
