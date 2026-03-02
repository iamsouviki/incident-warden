# Agent Pipeline — How All 9 Agents Process an Incident

> **TL;DR** — Every **60 seconds**, the `IncidentPollingScheduler` polls ServiceNow and/or FreshService for new incidents. New tickets are saved as `PENDING` rows in the database. Every **10 seconds**, the `IncidentProcessingScheduler` claims a batch of PENDING incidents and runs each one through a **9-agent pipeline**: 3 agents run **in parallel**, followed by 5 agents running **sequentially**. The entire flow is fully automated.

---

## 1. Incident Intake (Every 60 Seconds)

```
┌───────────────┐       REST API          ┌──────────────────────────┐
│  ServiceNow   │ ─── (Table API) ──────▶ │                          │
│  FreshService │ ─── (Tickets v2) ─────▶ │  IncidentPollingScheduler│
└───────────────┘                         │  (runs every 60s)        │
                                          └────────────┬─────────────┘
                                                       │
                                                       ▼
                                          ┌──────────────────────────┐
                                          │  incidents table         │
                                          │  status = PENDING        │
                                          │  (deduped by ticket ID)  │
                                          └──────────────────────────┘
```

### How it works:

| Step | What Happens |
|------|-------------|
| 1 | `IncidentPollingScheduler.pollAllSources()` fires every 60 seconds |
| 2 | Calls `ServiceNowClient.getUpdatedIncidents(watermark)` — fetches incidents updated since last poll |
| 3 | Calls `FreshServiceClient.getUpdatedTickets(watermark)` — fetches tickets updated since last poll |
| 4 | Each ticket is mapped to an `Incident` entity (title, description, severity P1-P4, category) |
| 5 | `ingest()` saves each new incident with `status=PENDING` (duplicates skipped via UNIQUE constraint) |
| 6 | Watermark updated to `now()` for next cycle |

**Configuration** (`application.yml`):
```yaml
mcp:
  servicenow:
    enabled: true
    instance-url: https://your-instance.service-now.com
    username: admin
    password: ${SERVICENOW_PASSWORD}
  freshservice:
    enabled: true
    domain: your-company
    api-key: ${FRESHSERVICE_API_KEY}
  polling:
    interval-ms: 60000   # ← poll every 60 seconds
```

---

## 2. Incident Processing (Every 10 Seconds)

```
┌──────────────────────────────┐
│  IncidentProcessingScheduler │
│  (runs every 10s)            │
└──────────────┬───────────────┘
               │
               ▼
   SELECT ... WHERE status='PENDING'
   ORDER BY severity ASC, created_at ASC
   LIMIT 5  FOR UPDATE SKIP LOCKED
               │
               ▼
   ┌───────────────────────┐
   │  AgentPipeline        │
   │  .processIncident()   │
   └───────────┬───────────┘
               │
               ▼
   ┌───────────────────────┐
   │  OrchestratorAgent    │
   │  .execute(context)    │
   └───────────────────────┘
```

| Step | What Happens |
|------|-------------|
| 1 | `IncidentProcessingScheduler` fires every 10 seconds |
| 2 | `IncidentService.claimNextBatch()` atomically claims up to 5 PENDING incidents using `SKIP LOCKED` |
| 3 | Incidents are prioritized: P1 first, then P2, then P3, P4 (FIFO within same priority) |
| 4 | Each claimed incident → `AgentPipeline.processIncident(incident, tenantId)` |
| 5 | Pipeline creates an `AgentContext` and hands it to the **OrchestratorAgent** |

---

## 3. The 9-Agent Pipeline

The `OrchestratorAgent` coordinates **8 registered agents** (+ itself = 9 total). The pipeline has **two phases**:

```
                              ┌──────────────────────────────────────┐
                              │        ORCHESTRATOR AGENT            │
                              │        (Agent #1, priority=0)        │
                              └──────────────────┬───────────────────┘
                                                 │
                     ┌───────────────────────────┼───────────────────────────┐
                     │            PHASE 1: PARALLEL (Virtual Threads)        │
                     │                                                       │
          ┌──────────┴──────────┐  ┌──────────┴──────────┐  ┌──────────┴──────────┐
          │  CLASSIFIER AGENT   │  │ PATTERN MATCHER AGENT│  │  SOP RANKER AGENT   │
          │  (Agent #2, pri=1)  │  │  (Agent #3, pri=2)   │  │  (Agent #4, pri=2)  │
          │                     │  │                       │  │                     │
          │ • Regex rules first │  │ • Generate embedding  │  │ • Build query text  │
          │ • Semantic fallback │  │ • pgvector search     │  │ • pgvector SOP search│
          │ • Sets: category,   │  │ • Cosine similarity   │  │ • Rank by relevance │
          │   subCategory,      │  │ • Sets: patternId,    │  │ • Dual-source RAG   │
          │   confidence        │  │   similarity          │  │ • Sets: sopId, plan │
          └─────────────────────┘  └───────────────────────┘  └─────────────────────┘
                     │                                                       │
                     └───────────────────────────┼───────────────────────────┘
                                                 │  (merge results)
                                                 ▼
                     ┌───────────────────────────────────────────────────────┐
                     │                PHASE 2: SEQUENTIAL                    │
                     │                                                       │
                     │  ┌─────────────────────────────────────────────┐      │
                     │  │  CONFIDENCE SCORER AGENT (Agent #5, pri=4)  │      │
                     │  │  • Weighted scoring (pattern 35%, history   │      │
                     │  │    25%, SOP 20%, health 15%)                │      │
                     │  │  • Decision: AUTO_RESOLVE / HITL / ESCALATE │      │
                     │  └──────────────────────┬──────────────────────┘      │
                     │                         ▼                             │
                     │  ┌─────────────────────────────────────────────┐      │
                     │  │  RISK EVALUATOR AGENT (Agent #6, pri=5)     │      │
                     │  │  • 9-layer risk evaluation                  │      │
                     │  │  • Production, customer, data, transaction, │      │
                     │  │    backup, change-window, health, deploy,   │      │
                     │  │    frequency checks                         │      │
                     │  │  • Can override decision to HITL/ESCALATE   │      │
                     │  └──────────────────────┬──────────────────────┘      │
                     │                         ▼                             │
                     │  ┌─────────────────────────────────────────────┐      │
                     │  │  GUARDRAILS AGENT (Agent #7, pri=6)         │      │
                     │  │  • 9-layer safety gate via GuardrailsService│      │
                     │  │  • ALL layers must pass — no bypass         │      │
                     │  │  • Prompt injection, blast radius, rate     │      │
                     │  │    limit, loop detection checks             │      │
                     │  │  • Blocks action if any layer fails         │      │
                     │  └──────────────────────┬──────────────────────┘      │
                     │                         ▼                             │
                     │  ┌─────────────────────────────────────────────┐      │
                     │  │  ACTION EXECUTOR AGENT (Agent #8, pri=7)    │      │
                     │  │  • Only runs for AUTO_RESOLVE decisions     │      │
                     │  │  • Dry-run validation first                 │      │
                     │  │  • Tool calls: RESTART_SERVICE, SCALE_UP,   │      │
                     │  │    CLEAR_CACHE, ROLLBACK_DEPLOY, RUN_SCRIPT │      │
                     │  │  • Automatic rollback on failure            │      │
                     │  └──────────────────────┬──────────────────────┘      │
                     │                         ▼                             │
                     │  ┌─────────────────────────────────────────────┐      │
                     │  │  AUDIT AGENT (Agent #9, pri=8) — ALWAYS RUNS│      │
                     │  │  • Immutable audit trail (SHA-256 hashed)   │      │
                     │  │  • Creates HITL request if needed           │      │
                     │  │  • Logs all decisions + context             │      │
                     │  │  • Runs even if pipeline fails              │      │
                     │  └─────────────────────────────────────────────┘      │
                     └───────────────────────────────────────────────────────┘
```

---

## 4. Detailed Agent Descriptions

### Agent #1: OrchestratorAgent (Priority: 0)
**Role:** Conductor of the entire pipeline.
- Marks incident as `PROCESSING`
- Launches Phase 1 agents in parallel using **Java 21 virtual threads**
- Merges parallel results into a single `AgentContext`
- Runs Phase 2 agents sequentially
- Ensures AuditAgent **always** runs last (even after failures)
- If a critical agent (ConfidenceScorer) fails, pipeline aborts but AuditAgent still runs

### Agent #2: ClassifierAgent (Priority: 1) — Phase 1 Parallel
**Role:** Categorize the incident.
- **Step 1:** Try regex-based classification rules from the database (fast, deterministic)
- **Step 2:** Fall back to semantic/heuristic classification (keyword matching)
- **Output:** `classifiedCategory`, `classifiedSubCategory`, `classificationConfidence`
- **Categories:** DATABASE, NETWORK, INFRASTRUCTURE, DEPLOYMENT, OTHER
- **Confidence:** 0.50 (default) to 0.95 (strong regex match)

### Agent #3: PatternMatcherAgent (Priority: 2) — Phase 1 Parallel
**Role:** Find similar historical incidents using vector search.
- **Step 1:** Generate text embedding for incident (title + description)
- **Step 2:** Search pgvector for similar historical patterns (top-5, cosine distance)
- **Step 3:** Score matches weighted by reliability
- **Step 4:** Select best match above 0.6 similarity threshold
- **Output:** `matchedPatternId`, `patternSimilarity`, `patternDescription`

### Agent #4: SopRankerAgent (Priority: 2) — Phase 1 Parallel
**Role:** Find and rank the best Standard Operating Procedure for this incident.
- **Step 1:** Build enriched query text from incident + classification context
- **Step 2:** Search pgvector for similar SOPs (top-10, cosine distance)
- **Step 3:** Rank by weighted score: 70% similarity + 30% reliability
- **Step 4:** Extract action plan JSON for ActionExecutorAgent
- **Step 5:** Dual-source RAG enrichment — search both SOPs and Resolved Incident Knowledge Base
- **Output:** `matchedSopId`, `sopTitle`, `sopReliability`, `actionPlan`, `kbSuggestedResolution`

### Agent #5: ConfidenceScorerAgent (Priority: 4) — Phase 2 Sequential
**Role:** Calculate confidence score and determine initial decision.
- **Scoring Formula (weighted):**
  | Component | Weight |
  |-----------|--------|
  | Pattern Similarity | 35% |
  | Historical Success Rate | 25% |
  | SOP Reliability | 20% |
  | System Health | 15% |
  | Risk Penalties | -5% each |

- **Decision Thresholds:**
  | Score Range | Decision |
  |-------------|----------|
  | ≥ 0.95 (configurable) | `AUTO_RESOLVE` |
  | ≥ 0.80 | `HITL_REQUIRED` |
  | < 0.80 | `ESCALATE_TO_HUMAN` |

- **P1 Override:** P1 severity incidents always require HITL unless score exceeds auto-resolve threshold
- **Output:** `finalConfidenceScore`, `decision`, `confidenceLog`

### Agent #6: RiskEvaluatorAgent (Priority: 5) — Phase 2 Sequential
**Role:** Evaluate 9 layers of risk and apply guardrails.
- **9 Risk Layers:**

  | Layer | Check | Weight |
  |-------|-------|--------|
  | 1 | Production environment protection | 15% |
  | 2 | Customer impact assessment | 15% |
  | 3 | Data sensitivity validation | 12% |
  | 4 | Transaction consistency | 12% |
  | 5 | Backup/recovery availability | 10% |
  | 6 | Change window validation | 10% |
  | 7 | System health metrics | 12% |
  | 8 | Current deployments check | 8% |
  | 9 | Recent incident frequency | 6% |

- **Risk Thresholds:**
  - High risk (≥ 0.75) → `ESCALATE_TO_HUMAN`
  - Medium risk (≥ 0.50) → `HITL_REQUIRED`
- **Can override** the ConfidenceScorer's decision upward (more restrictive)
- **Output:** `riskScore`, `guardRailViolations`, updated `decision`

### Agent #7: GuardrailsAgent (Priority: 6) — Phase 2 Sequential
**Role:** Final safety gate — ALL 9 layers must pass.
- Delegates to `GuardrailsService.runAll(context)`
- **Hard Rules:**
  - Layer 3 (Prompt Injection) → always `ESCALATE_TO_HUMAN`
  - Layer 7 (Loop Detection) → always `ESCALATE_TO_HUMAN`
  - THROTTLE/QUEUE results → `HITL_REQUIRED` (retry next cycle)
- **If any layer fails:** action is **BLOCKED**, decision overridden
- **If all pass:** `guardrailsTriggered=false`, action approved to proceed
- **Skips** if decision is already `ESCALATE_TO_HUMAN`
- **Output:** `guardrailsTriggered`, possibly updated `decision`

### Agent #8: ActionExecutorAgent (Priority: 7) — Phase 2 Sequential
**Role:** Execute remediation actions with rollback capability.
- **Only runs** for `AUTO_RESOLVE` decisions or HITL-approved actions
- **Execution Flow:**
  1. Extract action steps from matched SOP's `actionPlanJson`
  2. **Dry-run** each action first for validation
  3. **Execute** actual tool call if dry-run passes
  4. **Rollback** automatically if action fails
- **Supported Tools:**
  - `RESTART_SERVICE` — systemctl restart / Windows service restart
  - `SCALE_UP` — kubectl scale deployment
  - `CLEAR_CACHE` — Redis FLUSHDB / Memcached flush
  - `ROLLBACK_DEPLOY` — Helm rollback
  - `DRAIN_QUEUE` — Clear message queue
  - `CHECK_URL` — HTTP health check
  - `RERUN_JOB` — Execute shell script / Jenkins job
- **Output:** `executedSteps`, `rollbackTriggered`, possibly `ACTION_FAILED`

### Agent #9: AuditAgent (Priority: 8) — **ALWAYS RUNS LAST**
**Role:** Create immutable audit trail + HITL requests.
- **Always runs** — even if the pipeline fails or other agents error
- **Creates:**
  - SHA-256 hashed audit event for tamper detection
  - HITL request if decision is `HITL_REQUIRED` or `ESCALATE_TO_HUMAN`
  - Classification audit event
- **HITL Request:** expires in 2 hours, contains approval payload with all agent context
- **Output:** Persisted `AuditEvent` records, `HitlRequest` if needed

---

## 5. Complete End-to-End Timeline

```
Time       Event
────────   ──────────────────────────────────────────────────────────
T+0s       ServiceNow creates incident INC0012345 (P2 severity)

T+60s      IncidentPollingScheduler polls ServiceNow
           → Fetches INC0012345 via REST API
           → Maps to Incident entity: title, description, severity=P2
           → Saves to DB with status=PENDING

T+70s      IncidentProcessingScheduler claims INC0012345
           → SELECT ... WHERE status='PENDING' FOR UPDATE SKIP LOCKED
           → Marks as PROCESSING

T+70s      OrchestratorAgent starts pipeline
           ├─ Phase 1 (parallel, ~200ms each):
           │  ├─ ClassifierAgent → category=DATABASE, confidence=0.85
           │  ├─ PatternMatcherAgent → matched pattern #42, similarity=0.87
           │  └─ SopRankerAgent → matched SOP "DB Restart Procedure", reliability=0.90
           │
           └─ Phase 2 (sequential):
              ├─ ConfidenceScorerAgent → score=0.88, decision=HITL_REQUIRED
              ├─ RiskEvaluatorAgent → riskScore=0.45, 0 violations
              ├─ GuardrailsAgent → all 9 layers PASSED
              ├─ ActionExecutorAgent → skipped (not AUTO_RESOLVE)
              └─ AuditAgent → audit event + HITL request created

T+71s      Incident status updated to HITL_PENDING
           HITL request visible in approval queue (expires in 2h)

T+??       Human approves → ActionExecutorAgent runs remediation
           → RESTART_SERVICE:postgresql (dry-run → execute)
           → Incident status = AUTO_RESOLVED
           → Archived to Knowledge Base for future pattern matching
```

---

## 6. Agent Registration

All agents are wired together at startup by `AgentRegistry`:

```java
@PostConstruct
public void registerAllAgents() {
    orchestrator.registerAgent(classifierAgent);       // pri=1
    orchestrator.registerAgent(patternMatcherAgent);    // pri=2
    orchestrator.registerAgent(sopRankerAgent);         // pri=2
    orchestrator.registerAgent(confidenceScorerAgent);  // pri=4
    orchestrator.registerAgent(riskEvaluatorAgent);     // pri=5
    orchestrator.registerAgent(actionExecutorAgent);    // pri=7
    orchestrator.registerAgent(auditAgent);             // pri=8
}
```

The Orchestrator itself (pri=0) is the entry point — it calls the 7 registered agents in the correct order: 3 parallel, then 4 sequential + AuditAgent always last.

**Total: 1 Orchestrator + 7 registered agents = 8 classes = 9 agents in the pipeline.**

---

## 7. Key Configuration Points

| Config Property | Default | Description |
|----------------|---------|-------------|
| `mcp.polling.interval-ms` | 60000 | How often to poll ITSM sources (ms) |
| `mcp.polling.enabled` | true | Master switch for polling |
| `mcp.servicenow.enabled` | false | Enable ServiceNow polling |
| `mcp.freshservice.enabled` | false | Enable FreshService polling |
| `mcp.scheduler.processing-interval-ms` | 10000 | How often to claim PENDING incidents |
| `mcp.scheduler.batch-size` | 5 | Max incidents per processing batch |
| `mcp.confidence.auto-resolve-threshold` | 1.00 | Confidence needed for auto-resolve |
| `mcp.confidence.hitl-threshold` | 0.80 | Confidence needed for HITL (vs escalate) |
| `mcp.guardrails.enabled` | true | Enable 9-layer safety gate |
| `mcp.guardrails.blast-radius-threshold` | 0.40 | Max blast radius before blocking |

---

## 8. Decision Outcomes

| Final Decision | Meaning | What Happens |
|---------------|---------|--------------|
| `AUTO_RESOLVE` | High confidence, all guardrails pass | ActionExecutorAgent runs remediation tools |
| `HITL_REQUIRED` | Medium confidence or risk detected | HITL request created, human reviews in queue |
| `ESCALATE_TO_HUMAN` | Low confidence or critical failure | Escalated to senior analyst, no automation |
| `GUARDRAILS_BLOCKED` | Safety gate rejected the action | Action blocked, requires manual intervention |
| `ACTION_FAILED` | Remediation tools failed | Rollback attempted, incident escalated |

