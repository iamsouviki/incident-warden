package com.company.mcp.guardrails;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a single guardrail layer check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailResult {

    public enum Status { PASS, FAIL, THROTTLE, QUEUE }

    /** Which layer fired this result */
    private int layer;
    private String layerName;

    /** PASS / FAIL / THROTTLE / QUEUE */
    private Status status;

    /** Human-readable reason (populated on non-PASS) */
    private String reason;

    // ── factory helpers ──────────────────────────────────────────────────────

    public static GuardrailResult pass(int layer, String name) {
        return GuardrailResult.builder().layer(layer).layerName(name)
                .status(Status.PASS).build();
    }

    public static GuardrailResult fail(int layer, String name, String reason) {
        return GuardrailResult.builder().layer(layer).layerName(name)
                .status(Status.FAIL).reason(reason).build();
    }

    public static GuardrailResult throttle(int layer, String name, String reason) {
        return GuardrailResult.builder().layer(layer).layerName(name)
                .status(Status.THROTTLE).reason(reason).build();
    }

    public static GuardrailResult queue(int layer, String name, String reason) {
        return GuardrailResult.builder().layer(layer).layerName(name)
                .status(Status.QUEUE).reason(reason).build();
    }

    public boolean isPassing() {
        return status == Status.PASS;
    }
}
