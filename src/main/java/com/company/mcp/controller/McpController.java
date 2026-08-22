package com.company.mcp.controller;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.service.HitlWorkflowService;
import com.company.mcp.service.McpServerRegistryService;
import com.company.mcp.service.RemediationToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Model Context Protocol server: exposes this platform's incident tooling to an external
 * agent over JSON-RPC 2.0.
 *
 * Hand-rolled rather than pulled from an SDK. MCP over HTTP is a small, stable protocol —
 * three methods matter here — and the request is authenticated and authorised by the same
 * Spring Security chain as every other route, which is the property that matters most.
 *
 * The security model is the point of this class:
 *
 *   - An MCP client is a caller like any other. It presents the same JWT, is bound to the
 *     same tenant, and hits the same role rules. There is no separate agent identity with
 *     elevated rights.
 *   - No tool here executes a remediation directly. {@code execute_approved_plan} runs a
 *     plan a human already approved, re-validating the approval and the plan hash
 *     server-side. An agent cannot invent a plan and run it, or run a plan approved for a
 *     different tenant.
 *   - Everything else is read-only.
 *
 * ponytail: HTTP POST only, no SSE stream and no session negotiation. Request/response
 * covers every call an agent makes against these tools. Add the streaming transport when
 * a client needs server-initiated notifications, which none of these tools produce.
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** JSON-RPC 2.0 reserved codes. */
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final HitlWorkflowService workflow;
    private final RemediationToolRegistry tools;
    private final McpServerRegistryService servers;
    private final CurrentUser currentUser;

    public McpController(HitlWorkflowService workflow, RemediationToolRegistry tools,
                        McpServerRegistryService servers, CurrentUser currentUser) {
        this.workflow = workflow;
        this.tools = tools;
        this.servers = servers;
        this.currentUser = currentUser;
    }

    @PostMapping("/rpc")
    public ResponseEntity<Map<String, Object>> rpc(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        if (!"2.0".equals(request.get("jsonrpc"))) {
            return ResponseEntity.ok(error(id, INVALID_REQUEST, "jsonrpc must be \"2.0\""));
        }
        String method = request.get("method") instanceof String s ? s : "";
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> m ? castParams(m) : Map.of();

        try {
            return ResponseEntity.ok(switch (method) {
                case "initialize" -> result(id, initialize());
                case "tools/list" -> result(id, Map.of("tools", toolDescriptors()));
                case "tools/call" -> result(id, callTool(params));
                case "ping" -> result(id, Map.of());
                default -> error(id, METHOD_NOT_FOUND, "Unknown method: " + method);
            });
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(id, INVALID_PARAMS, e.getMessage()));
        } catch (Exception e) {
            log.warn("[MCP] {} failed: {}", method, e.getMessage());
            // The message is returned because the caller is an authenticated operator's
            // agent, but it is a domain message, never a stack trace.
            return ResponseEntity.ok(error(id, INTERNAL_ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private Map<String, Object> initialize() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("protocolVersion", PROTOCOL_VERSION);
        info.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        info.put("serverInfo", Map.of("name", "mcp-incident-automation", "version", "1.0"));
        info.put("instructions", "Incident remediation tools for the authenticated operator's tenant. "
                + "Remediation cannot be executed without a human approval recorded in this system: "
                + "call create_remediation_plan to raise one, then wait for a reviewer.");
        return info;
    }

    /**
     * The tool manifest.
     *
     * Every description states the safety boundary explicitly, because a client model
     * reads these to decide what to call. "This does not execute anything" in the
     * description prevents a class of confused-agent behaviour that no amount of
     * server-side validation makes pleasant to debug.
     */
    private List<Map<String, Object>> toolDescriptors() {
        return List.of(
                tool("list_open_incidents",
                        "List open incidents in the caller's tenant. Read-only.",
                        Map.of()),
                tool("get_incident",
                        "Fetch one incident by id, including its confidence score and status. Read-only.",
                        Map.of("incidentId", stringParam("Incident UUID"))),
                tool("list_remediation_tools",
                        "List the remediation actions this platform can run, and which of them mutate a system. Read-only.",
                        Map.of()),
                tool("create_remediation_plan",
                        "Assess an incident against the tenant's approved procedures and raise a plan for human "
                                + "approval. This does NOT execute anything. It returns whether the plan is eligible "
                                + "and, if not, the reason it was escalated instead.",
                        Map.of("incidentId", stringParam("Incident UUID"))),
                tool("get_pending_approvals",
                        "List remediation plans awaiting human review in the caller's tenant. Read-only.",
                        Map.of()),
                tool("execute_approved_plan",
                        "Execute a plan a human has already approved. Requires the approval id and the plan hash "
                                + "that was approved; both are re-verified server-side and the call is refused if the "
                                + "plan has changed since approval. Cannot approve a plan, and cannot run an "
                                + "unapproved one.",
                        Map.of("requestId", stringParam("HITL approval request UUID"),
                                "planHash", stringParam("The plan hash recorded at approval"),
                                "dryRun", Map.of("type", "boolean",
                                        "description", "True to simulate. Defaults to true; pass false deliberately."))));
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = params.get("name") instanceof String s ? s : "";
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m ? castParams(m) : Map.of();

        Object payload = switch (name) {
            case "list_open_incidents" -> workflow.openIncidentsForAgent();
            case "get_incident" -> workflow.incidentForAgent(uuid(args, "incidentId"));
            case "list_remediation_tools" -> Map.of("tools", tools.tools());
            case "create_remediation_plan" -> workflow.createPlan(uuid(args, "incidentId"));
            case "get_pending_approvals" -> workflow.pendingReviewItems();
            case "execute_approved_plan" -> executeApproved(args);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };

        // MCP returns tool output as content blocks. Structured data goes in
        // structuredContent; the text block is what a model without structured-output
        // support reads.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of("type", "text", "text", String.valueOf(payload))));
        response.put("structuredContent", payload instanceof Map || payload instanceof List
                ? payload : Map.of("value", String.valueOf(payload)));
        response.put("isError", false);
        return response;
    }

    /**
     * The one tool that can change a system.
     *
     * The plan hash supplied by the caller must match the hash the approver signed. This
     * is checked here in addition to the check inside the workflow service: an agent that
     * has been talked into running "the plan for incident X" by injected text will not
     * have the right hash, because the hash is only knowable by reading the approval.
     */
    private Object executeApproved(Map<String, Object> args) {
        UUID requestId = uuid(args, "requestId");
        String planHash = args.get("planHash") instanceof String s ? s : "";
        // Defaults to a simulation: an agent that omits the flag must not mutate anything.
        boolean dryRun = !Boolean.FALSE.equals(args.get("dryRun"));

        Map<String, Object> detail = workflow.reviewDetail(requestId);
        Object plan = detail.get("plan");
        String approvedHash = plan instanceof com.company.mcp.model.RemediationPlan p ? p.getPlanHash() : "";
        if (planHash.isBlank() || !planHash.equals(approvedHash)) {
            throw new IllegalArgumentException("planHash does not match the approved plan. "
                    + "Read the approval with get_pending_approvals and pass its recorded hash.");
        }
        log.info("[MCP] Agent-initiated {} of request {} by {}", dryRun ? "dry run" : "execution",
                requestId, currentUser.username());
        return dryRun ? workflow.dryRunAndExecute(requestId) : workflow.execute(requestId);
    }

    // ── External MCP servers this platform can itself connect to ────────────────────
    //
    // Registry only: the platform records where a server lives and whether it is enabled.
    // Outbound calls to these servers are not implemented yet, which is why nothing here
    // dials them — a stored URL that is never called cannot be used to reach an internal
    // service, so registration is safe to ship ahead of the client.

    @GetMapping("/servers")
    public ResponseEntity<?> listServers() {
        return ResponseEntity.ok(Map.of("servers", servers.list()));
    }

    @PostMapping("/servers")
    public ResponseEntity<?> saveServer(@RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(servers.save(body));
    }

    @DeleteMapping("/servers/{id}")
    public ResponseEntity<?> deleteServer(@PathVariable UUID id) {
        return servers.delete(id)
                ? ResponseEntity.ok(Map.of("message", "Server removed"))
                : ResponseEntity.notFound().build();
    }

    // ── JSON-RPC plumbing ───────────────────────────────────────────────────────────

    private Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", properties.keySet().stream().filter(k -> !"dryRun".equals(k)).toList());
        return Map.of("name", name, "description", description, "inputSchema", schema);
    }

    private Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    private UUID uuid(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) throw new IllegalArgumentException("Missing required parameter: " + key);
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must be a UUID");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castParams(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private Map<String, Object> result(Object id, Object payload) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", payload);
        return response;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "Request failed" : message));
        return response;
    }
}
