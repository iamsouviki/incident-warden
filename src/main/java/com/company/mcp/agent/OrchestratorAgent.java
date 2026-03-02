package com.company.mcp.agent;

import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Orchestrator Agent — coordinates the incident processing pipeline.
 *
 * Pipeline as per spec:
 *  Phase 1 (parallel, Java 21 virtual threads):
 *      ClassifierAgent | PatternMatcherAgent | SopRankerAgent
 *  Phase 2 (sequential, each waits for previous):
 *      ConfidenceScorerAgent → RiskEvaluatorAgent → GuardrailsAgent →
 *      ActionExecutorAgent → AuditAgent
 *
 * All 9 agents run inside this orchestration. AuditAgent always runs last.
 */
@Slf4j
@Component
public class OrchestratorAgent extends BaseAgent {

    private final IncidentRepository incidentRepository;
    private final List<BaseAgent> agentPipeline;

    // Agents that run in parallel (Phase-1)
    private static final Set<String> PARALLEL_AGENTS = Set.of(
            "ClassifierAgent", "PatternMatcherAgent", "SopRankerAgent");

    // AuditAgent always runs last regardless of decision
    private static final String AUDIT_AGENT = "AuditAgent";

    public OrchestratorAgent(IncidentRepository incidentRepository) {
        super("OrchestratorAgent");
        this.incidentRepository = incidentRepository;
        this.agentPipeline = new ArrayList<>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main entry point
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public AgentContext execute(AgentContext context) throws AgentExecutionException {
        try {
            logExecution(context, "Starting incident processing pipeline");
            markIncidentAsProcessing(context.getIncident());

            List<BaseAgent> sorted = getSortedAgentPipeline();

            // ── Phase 1: parallel dispatch (Classifier + PatternMatcher + SopRanker) ──
            context = runParallelPhase(context, sorted);

            // ── Phase 2: sequential agents (ConfidenceScorer → Risk → Guardrails →
            //             ActionExecutor → Audit) ────────────────────────────────────
            context = runSequentialPhase(context, sorted);

            logExecution(context, "Pipeline completed — decision=" + context.getDecision());
            context.setProcessingCompletedAt(LocalDateTime.now());
            return context;

        } catch (Exception e) {
            handleException(context, e, "orchestration");
            return context;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase-1: run Classifier + PatternMatcher + SopRanker in parallel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Runs the three parallel discovery agents simultaneously using Java 21
     * virtual threads. Merges their results back into a single context.
     * Spec reference: §6 The AI Agent Pipeline — "Launch parallel agents".
     */
    private AgentContext runParallelPhase(AgentContext ctx, List<BaseAgent> sorted) {
        List<BaseAgent> parallel = sorted.stream()
                .filter(a -> PARALLEL_AGENTS.contains(a.getAgentName()))
                .collect(Collectors.toList());

        if (parallel.isEmpty()) {
            return ctx;
        }

        logExecution(ctx, "Phase-1: launching " + parallel.size() +
                " agents in parallel via virtual threads");

        // Java 21 virtual-thread executor — zero overhead per thread
        try (ExecutorService vte = Executors.newVirtualThreadPerTaskExecutor()) {

            // Each agent receives its own *copy* of the context so they don't
            // interfere with each other; results are merged below.
            final AgentContext snapshot = ctx;
            List<CompletableFuture<AgentContext>> futures = parallel.stream()
                    .map(agent -> CompletableFuture.supplyAsync(() -> {
                        try {
                            long t = System.currentTimeMillis();
                            AgentContext result = agent.execute(copyContext(snapshot));
                            logExecution(snapshot, String.format("[parallel] %s completed in %dms",
                                    agent.getAgentName(), System.currentTimeMillis() - t));
                            return result;
                        } catch (AgentExecutionException ex) {
                            logError(snapshot, "Parallel agent " + agent.getAgentName() +
                                    " failed: " + ex.getMessage(), ex);
                            return snapshot; // return snapshot on error
                        }
                    }, vte))
                    .collect(Collectors.toList());

            // Wait for all three
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Merge results into the master context
            for (CompletableFuture<AgentContext> f : futures) {
                ctx = mergeParallelResult(ctx, f.join());
            }
        }

        logExecution(ctx, "Phase-1 complete — category=" + ctx.getClassifiedCategory() +
                " pattern=" + ctx.getMatchedPatternId() +
                " sop=" + ctx.getMatchedSopId());
        return ctx;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase-2: sequential agents
    // ─────────────────────────────────────────────────────────────────────────

    private AgentContext runSequentialPhase(AgentContext ctx, List<BaseAgent> sorted) {
        List<BaseAgent> sequential = sorted.stream()
                .filter(a -> !PARALLEL_AGENTS.contains(a.getAgentName())
                        && !AUDIT_AGENT.equals(a.getAgentName()))
                .collect(Collectors.toList());

        for (BaseAgent agent : sequential) {
            ctx = runSingleAgent(ctx, agent, false);
            if (ctx.hasErrors() && isCriticalAgent(agent.getAgentName())) {
                logWarning(ctx, "Critical agent " + agent.getAgentName() +
                        " failed — aborting pipeline (AuditAgent will still run)");
                break;
            }
        }

        // AuditAgent ALWAYS runs last -- even after failures
        final AgentContext finalCtx = ctx;
        sorted.stream()
                .filter(a -> AUDIT_AGENT.equals(a.getAgentName()))
                .findFirst()
                .ifPresent(audit -> runSingleAgent(finalCtx, audit, true));

        return ctx;
    }

    private AgentContext runSingleAgent(AgentContext ctx, BaseAgent agent, boolean ignoreErrors) {
        if (!agent.canExecute(ctx)) {
            logWarning(ctx, "Agent " + agent.getAgentName() + " canExecute=false, skipping");
            return ctx;
        }
        try {
            long t = System.currentTimeMillis();
            AgentContext result = agent.execute(ctx);
            logExecution(ctx, String.format("[sequential] %s completed in %dms",
                    agent.getAgentName(), System.currentTimeMillis() - t));
            return result;
        } catch (AgentExecutionException e) {
            logError(ctx, "Agent " + agent.getAgentName() + " failed: " + e.getMessage(), e);
            if (!ignoreErrors) ctx.addError(e.getMessage());
            return ctx;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shallow-copy of context for parallel agents so they can write independently.
     */
    private AgentContext copyContext(AgentContext src) {
        return AgentContext.builder()
                .incident(src.getIncident())
                .tenantId(src.getTenantId())
                .traceId(src.getTraceId())
                .classifiedCategory(src.getClassifiedCategory())
                .classifiedSubCategory(src.getClassifiedSubCategory())
                .classificationConfidence(src.getClassificationConfidence())
                .classificationReason(src.getClassificationReason())
                .matchedPatternId(src.getMatchedPatternId())
                .patternSimilarity(src.getPatternSimilarity())
                .matchedSopId(src.getMatchedSopId())
                .sopTitle(src.getSopTitle())
                .sopReliability(src.getSopReliability())
                .actionPlan(src.getActionPlan())
                // dual-source RAG fields
                .kbSuggestedResolution(src.getKbSuggestedResolution())
                .kbMatchedEntries(src.getKbMatchedEntries() != null
                        ? new java.util.ArrayList<>(src.getKbMatchedEntries()) : new java.util.ArrayList<>())
                .combinedRagDocs(src.getCombinedRagDocs() != null
                        ? new java.util.ArrayList<>(src.getCombinedRagDocs()) : new java.util.ArrayList<>())
                .build();
    }

    /**
     * Merge results from a parallel agent back into the master context.
     * Each parallel agent only writes to its own fields, so this is safe.
     */
    private AgentContext mergeParallelResult(AgentContext master, AgentContext result) {
        // ClassifierAgent writes to classification fields
        if (result.getClassifiedCategory() != null && master.getClassifiedCategory() == null) {
            master.setClassifiedCategory(result.getClassifiedCategory());
            master.setClassifiedSubCategory(result.getClassifiedSubCategory());
            master.setClassificationConfidence(result.getClassificationConfidence());
            master.setClassificationReason(result.getClassificationReason());
        }
        // PatternMatcherAgent writes to pattern fields
        if (result.getMatchedPatternId() != null && master.getMatchedPatternId() == null) {
            master.setMatchedPatternId(result.getMatchedPatternId());
            master.setPatternSimilarity(result.getPatternSimilarity());
            master.setPatternDescription(result.getPatternDescription());
        }
        // SopRankerAgent writes to SOP fields
        if (result.getMatchedSopId() != null && master.getMatchedSopId() == null) {
            master.setMatchedSopId(result.getMatchedSopId());
            master.setSopTitle(result.getSopTitle());
            master.setSopReliability(result.getSopReliability());
            master.setActionPlan(result.getActionPlan());
        }
        // SopRankerAgent also writes dual-source RAG enrichment fields
        if (result.getKbSuggestedResolution() != null && master.getKbSuggestedResolution() == null) {
            master.setKbSuggestedResolution(result.getKbSuggestedResolution());
        }
        if (result.getKbMatchedEntries() != null && !result.getKbMatchedEntries().isEmpty()
                && master.getKbMatchedEntries().isEmpty()) {
            master.setKbMatchedEntries(result.getKbMatchedEntries());
        }
        if (result.getCombinedRagDocs() != null && !result.getCombinedRagDocs().isEmpty()
                && master.getCombinedRagDocs().isEmpty()) {
            master.setCombinedRagDocs(result.getCombinedRagDocs());
        }
        // Propagate warnings/errors
        result.getErrors().forEach(master::addError);
        result.getWarnings().forEach(master::addWarning);
        return master;
    }

    private boolean isCriticalAgent(String name) {
        return "ConfidenceScorerAgent".equals(name);
    }

    private List<BaseAgent> getSortedAgentPipeline() {
        return agentPipeline.stream()
                .sorted(Comparator.comparingInt(BaseAgent::getPriority))
                .collect(Collectors.toList());
    }

    private void markIncidentAsProcessing(Incident incident) {
        try {
            incident.setStatus("PROCESSING");
            incident.setProcessingStartedAt(LocalDateTime.now());
            incidentRepository.save(incident);
        } catch (Exception e) {
            log.warn("Failed to mark incident as PROCESSING: {}", e.getMessage());
        }
    }

    public void registerAgent(BaseAgent agent) {
        this.agentPipeline.add(agent);
        logExecution(null, String.format("Registered agent: %s (priority: %d)",
                agent.getAgentName(), agent.getPriority()));
    }

    public List<BaseAgent> getRegisteredAgents() {
        return new ArrayList<>(agentPipeline);
    }

    @Override
    public boolean canExecute(AgentContext context) {
        return context.getIncident() != null && context.getTenantId() != null;
    }

    @Override
    public int getPriority() {
        return 0;
    }
}
