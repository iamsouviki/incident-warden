package com.company.mcp.config;

import com.company.mcp.model.AppUser;
import com.company.mcp.model.SopProcedure;
import com.company.mcp.repository.SopProcedureRepository;
import com.company.mcp.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Configuration
@Profile("local")
public class LocalDemoDataConfig {

    /**
     * The tenant every seeded account actually belongs to.
     *
     * This used to be "tenant-local", which no user had: the Liquibase seed inserts
     * admin/analyst/viewer under tenant-1, so the approved procedures below were owned by
     * a tenant nobody could log into. Every plan therefore fell through to the ungrounded
     * LLM lane with NO_APPROVED_TENANT_SOP_MATCH — the SOP grounding, the tool allow-list
     * and the unattended lane all looked broken when only this string was.
     */
    private static final String DEMO_TENANT = "tenant-1";

    @Bean
    CommandLineRunner seedLocalDemoUser(UserRepository users, PasswordEncoder encoder,
                                       BootstrapPassword bootstrapPassword) {
        return args -> {
            if (users.findByUsername("admin").isPresent()) return;

            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setEmail("admin@localhost");
            admin.setPasswordHash(encoder.encode(bootstrapPassword.value()));
            admin.setRole("ADMIN");
            admin.setTenantId(DEMO_TENANT);
            admin.setTenantName("Local Demo Workspace");
            admin.setEnabled(true);
            admin.setCreatedAt(OffsetDateTime.now());
            admin.setUpdatedAt(OffsetDateTime.now());
            users.save(admin);
        };
    }

    /**
     * Seeds the approved procedures the HITL gate treats as authority to act.
     *
     * These are written out rather than derived from any document: a procedure row is an
     * authorisation record, and deriving authority from prose would fabricate authority that
     * no operator granted — which is the exact failure the SOP-evidence mock had.
     *
     * Deliberately incomplete: there is no procedure for a network outage or a database
     * failure, so those incidents produce NO_APPROVED_TENANT_SOP_MATCH and escalate. A
     * demo where every incident is automatable does not demonstrate the gate.
     */
    @Bean
    CommandLineRunner seedLocalSopProcedures(SopProcedureRepository procedures) {
        return args -> {
            if (!procedures.findByTenantIdOrderBySopIdAscStepNumberAsc(DEMO_TENANT).isEmpty()) return;

            procedures.saveAll(List.of(
                    procedure("SOP-PRINT-01", 1, "Store printer offline — clear queue and restart spooler",
                            "Printer shows offline in the POS application. Confirm power and network, clear the stuck print "
                                    + "queue, then restart the print spooler service on the store controller.",
                            "printer print spooler queue offline paper receipt pos jam",
                            "RESTART_SERVICE:spooler:windows", 0.92),

                    procedure("SOP-TOMCAT-01", 1, "Tomcat application unresponsive — restart service",
                            "Application returns 502 or times out while the host responds to ping. Restart the Tomcat "
                                    + "service, then confirm the health endpoint returns 200 before closing.",
                            "tomcat application unresponsive 502 timeout java webapp hung service down",
                            "RESTART_SERVICE:tomcat:linux", 0.88),

                    procedure("SOP-TOMCAT-01", 2, "Tomcat restart — verify health endpoint",
                            "Confirm the application answers on its health endpoint after the restart.",
                            "tomcat health check verify endpoint 200",
                            "CHECK_URL:http://localhost:8080/actuator/health:200", 0.97),

                    procedure("SOP-CACHE-01", 1, "Stale cache causing wrong prices — flush cache tier",
                            "Application serves stale data after a catalogue update. Flush the Redis cache tier so the "
                                    + "next request repopulates it from the source of truth.",
                            "cache stale redis price catalogue outdated data refresh flush memcached",
                            "CLEAR_CACHE:redis:localhost:6379", 0.85),

                    procedure("SOP-BATCH-01", 1, "Nightly report job failed — rerun",
                            "The nightly reporting job exited non-zero. Confirm the upstream extract completed, then "
                                    + "rerun the job once. A second failure must be escalated, not retried.",
                            "batch job nightly report failed etl sync schedule cron did not run",
                            "RERUN_JOB:linux:/opt/batch/nightly_report.sh", 0.78),

                    procedure("SOP-WEB-01", 1, "Website reported down — confirm before acting",
                            "A report that a site is down is confirmed by a read-only probe first. This step mutates "
                                    + "nothing and is safe to run before any remediation is proposed.",
                            "website site down unreachable url http https endpoint availability check monitor",
                            "CHECK_URL:https://status.internal.example.com:200", 0.99)
            ));
        };
    }

    private static SopProcedure procedure(String sopId, int step, String title, String description,
                                         String keywords, String actionKey, double reliability) {
        SopProcedure p = new SopProcedure();
        p.setId(UUID.randomUUID());
        p.setTenantId(DEMO_TENANT);
        p.setSopId(sopId);
        p.setStepNumber(step);
        p.setTitle(title);
        p.setDescription(description);
        p.setMatchKeywords(keywords);
        p.setActionKey(actionKey);
        p.setApprovalStatus("APPROVED");
        p.setApprovedBy("seed:local-demo");
        p.setRequiresApproval(true);
        p.setExecutionOrder(step * 10);
        p.setReliability(reliability);
        p.setCreatedAt(OffsetDateTime.now());
        p.setUpdatedAt(OffsetDateTime.now());
        return p;
    }
}
