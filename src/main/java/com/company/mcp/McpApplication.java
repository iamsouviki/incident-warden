package com.company.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * @EnableScheduling and @EnableRetry were both here with nothing using them — no
 * {@code @Scheduled} method and no {@code @Retryable} exists in this codebase, and
 * remediation deliberately does not retry (see RemediationToolRegistry: a lost response
 * does not mean a script did not run). @EnableCaching stays; RagService caches answers.
 *
 * Method security is deliberately not enabled. All authorization is route-based in
 * SecurityConfig, so there is one place to read the access rules rather than two.
 */
@SpringBootApplication
@EnableCaching
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }
}
