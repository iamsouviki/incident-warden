package com.company.mcp.service;

import io.micrometer.core.instrument.Metrics;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single deterministic safety boundary. Every path that can lead to a real or
 * simulated system change routes through this class, so a term added here takes
 * effect everywhere instead of only on the surface it was noticed on.
 */
@Service
public class GuardrailService {
    private static final Set<String> ALLOWED_ACTIONS = Set.of("restart-approved-service", "clear-printer-queue", "refresh-network-session");

    /**
     * A remediation target names exactly one device, host or ticket. Allow-listing the
     * format is stronger than blocklisting metacharacters: anything not matching — a
     * space, semicolon, pipe, comma, glob, quote, newline — is rejected without having
     * to enumerate what an attacker might send.
     */
    private static final Pattern SINGLE_TARGET = Pattern.compile("^[a-z0-9][a-z0-9._:-]{0,199}$");

    /** Whole-word tokens that name a group rather than one device. */
    private static final Set<String> BROAD_TARGET_TOKENS = Set.of(
            "all", "any", "every", "everything", "wildcard", "cluster", "fleet", "prod", "production");

    /**
     * Destructive command signatures. These are multi-character and specific, so they
     * are safe to match inside prose (SOP text) and inside script bodies without
     * flagging ordinary content.
     */
    private static final List<String> DESTRUCTIVE_TERMS = List.of(
            "rm -rf", "rm -fr", "mkfs", "fdisk", "dd if=", "shred ",
            "drop table", "drop database", "truncate table", "delete from",
            "terraform destroy", "kubectl delete", "docker rm", "docker system prune",
            "shutdown", "reboot", "init 6", "halt -f",
            "chmod 777", "chown -r /", "format c:", "reg delete", "net user ",
            "curl | sh", "curl|sh", "wget | sh", "wget|sh", "curl | bash", "iwr | iex");

    /** Credential and secret material a remediation script has no business touching. */
    private static final List<String> SECRET_TERMS = List.of(
            "/etc/shadow", "/etc/passwd", "id_rsa", ".aws/credentials", ".ssh/authorized_keys",
            "private_key", "aws_secret_access_key");

    /**
     * Phrases used to talk a model out of its instructions. Matched against retrieved
     * SOP text because documents are attacker-influenceable in any real deployment:
     * whoever can get a document ingested can attempt to steer the plan built from it.
     */
    private static final List<String> INJECTION_TERMS = List.of(
            "ignore previous", "ignore the previous", "ignore all previous", "disregard previous",
            "disregard the above", "disregard all", "system prompt", "you are now",
            "new instructions", "override the guardrail", "bypass the guardrail",
            "do not require approval", "does not require approval", "skip approval", "skip the guardrail",
            "no confirmation needed", "no approval needed", "auto-approve", "automatically approve",
            "reveal your", "print your instructions", "developer mode", "jailbreak",
            "<|im_start|>", "<|im_end|>", "</system>", "[system]", "###instruction");

    /**
     * Evaluates the deterministic safety boundary. Advisory outcomes are listed
     * separately; any blocking finding makes a plan ineligible for HITL.
     */
    public Result evaluate(String action, String target, SopEvidence evidence, int activePlansForIncident) {
        List<String> findings = new ArrayList<>();
        if (!ALLOWED_ACTIONS.contains(action)) findings.add("ACTION_NOT_ALLOWLISTED");

        String normalizedTarget = safe(target).toLowerCase(Locale.ROOT).trim();
        if (!SINGLE_TARGET.matcher(normalizedTarget).matches() || hasBroadToken(normalizedTarget)) {
            findings.add("BLAST_RADIUS_EXCEEDED");
        }

        if (evidence == null || !evidence.approvedEvidencePresent()) {
            findings.add("NO_APPROVED_SOP_EVIDENCE:" + (evidence == null ? "MISSING" : evidence.reason()));
        }

        // The action and target are scanned too, not just the SOP excerpt: both derive
        // from incident data an outsider can influence.
        String inspect = (safe(action) + " " + normalizedTarget + " "
                + safe(evidence == null ? "" : evidence.excerpt())).toLowerCase(Locale.ROOT);
        String destructive = firstMatch(inspect, DESTRUCTIVE_TERMS);
        if (destructive != null) findings.add("UNSAFE_CONTENT:" + destructive);
        String secret = firstMatch(inspect, SECRET_TERMS);
        if (secret != null) findings.add("SECRET_ACCESS_ATTEMPT:" + secret);
        String injection = firstMatch(inspect, INJECTION_TERMS);
        if (injection != null) findings.add("PROMPT_INJECTION_SUSPECTED:" + injection);

        if (activePlansForIncident > 0) findings.add("LOOP_DETECTED_ACTIVE_PLAN");
        findings.add("DRY_RUN_REQUIRED");
        findings.add("OUTPUT_SCHEMA_REQUIRED");
        Result result = new Result(findings.stream().noneMatch(this::isBlocking), findings);
        return counted("plan", result.passed() ? "PASS" : "BLOCK", result);
    }

    /**
     * Scans free-form script text. Shared by script validation and script preview so
     * the two surfaces cannot drift apart, which is what happened when each kept its
     * own inline list.
     *
     * Shell metacharacters are deliberately NOT flagged here: pipes, semicolons and
     * subshells are ordinary in a legitimate script. Only specific destructive
     * signatures and secret paths are reported.
     */
    public ScriptScan scanScript(String scriptContent) {
        String lower = safe(scriptContent).toLowerCase(Locale.ROOT);
        List<ScriptFinding> findings = new ArrayList<>();

        for (String term : DESTRUCTIVE_TERMS) {
            if (!lower.contains(term)) continue;
            boolean disruptiveOnly = term.equals("shutdown") || term.equals("reboot")
                    || term.equals("init 6") || term.equals("delete from") || term.equals("truncate table");
            findings.add(new ScriptFinding(disruptiveOnly ? "WARN" : "BLOCK",
                    disruptiveOnly ? "Service Disruption" : "Destructive Command",
                    "Detected '" + term + "' in the script body."));
        }
        for (String term : SECRET_TERMS) {
            if (lower.contains(term)) {
                findings.add(new ScriptFinding("BLOCK", "Credential Access",
                        "Script references secret material '" + term + "'."));
            }
        }
        for (String term : INJECTION_TERMS) {
            if (lower.contains(term)) {
                findings.add(new ScriptFinding("BLOCK", "Prompt Injection",
                        "Script contains instruction-override text '" + term + "'."));
                break;
            }
        }

        String level = findings.stream().anyMatch(f -> "BLOCK".equals(f.level())) ? "BLOCK"
                : findings.isEmpty() ? "PASS" : "WARN";
        return counted("script", level, new ScriptScan(level, findings));
    }

    /**
     * Counts every verdict this class reaches, at the two points where one is produced.
     *
     * <p>Micrometer's global registry rather than an injected one: Spring Boot binds the
     * application's registry into it at startup, and a static call keeps the constructor — and so
     * every test that builds this service by hand — unchanged.
     */
    private static <T> T counted(String lane, String verdict, T result) {
        Metrics.counter("mcp.guardrail.scans", "lane", lane, "verdict", verdict).increment();
        return result;
    }

    /**
     * True when a target names a group rather than one device. Split on separators so
     * "hallway-kiosk-2" passes while "all-devices" does not — a plain substring test
     * matched the "all" inside ordinary hostnames and blocked valid single targets.
     */
    private boolean hasBroadToken(String normalizedTarget) {
        for (String token : normalizedTarget.split("[^a-z0-9]+")) {
            if (BROAD_TARGET_TOKENS.contains(token)) return true;
        }
        return false;
    }

    private String firstMatch(String haystack, List<String> terms) {
        for (String term : terms) if (haystack.contains(term)) return term;
        return null;
    }

    /** Backward-compatible helper used by isolated legacy validator tests. */
    public Result evaluate(String action, String target, String sopEvidence, int activePlansForIncident) {
        SopEvidence evidence = new SopEvidence(true, List.of(java.util.UUID.randomUUID()), sopEvidence, 0.90, "LEGACY_TEST_EVIDENCE");
        return evaluate(action, target, evidence, activePlansForIncident);
    }

    /**
     * Whether a finding stops a plan. Public and static because the planner has to reason
     * about which findings closed the gate, and a second copy of this rule there would be
     * a second place to forget to update.
     */
    public static boolean isBlockingFinding(String finding) {
        return !"DRY_RUN_REQUIRED".equals(finding) && !"OUTPUT_SCHEMA_REQUIRED".equals(finding);
    }

    private boolean isBlocking(String finding) {
        return isBlockingFinding(finding);
    }
    private String safe(String value) { return value == null ? "" : value; }

    public record Result(boolean passed, List<String> findings) {}

    public record ScriptFinding(String level, String layer, String message) {}

    public record ScriptScan(String level, List<ScriptFinding> findings) {
        public boolean blocked() { return "BLOCK".equals(level); }
    }
}
