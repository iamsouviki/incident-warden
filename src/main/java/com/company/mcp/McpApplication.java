package com.company.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} is required, not decorative: two methods depend on it, and without it
 * both silently never run — {@code IntegrationManagerService.scheduledSync} (ITSM intake, gated by
 * a distributed lock so several replicas still sync once per interval) and
 * {@code ChatSessionService.purgeExpiredSessions} (the 30-day chat retention limit).
 *
 * {@code @EnableRetry} is deliberately absent: remediation must not retry, because a lost response
 * does not mean the script did not run (see RemediationToolRegistry). Outbound ITSM retries are
 * bounded and idempotent-only, configured on the HTTP client itself in IntegrationHttpConfig.
 *
 * Method security is deliberately not enabled. All authorization is route-based in
 * SecurityConfig, so there is one place to read the access rules rather than two.
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }
}
