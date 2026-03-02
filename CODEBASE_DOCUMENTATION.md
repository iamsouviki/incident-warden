# MCP Incident Automation Platform — Complete Codebase Documentation

> **Generated:** March 2, 2026  
> **Coverage:** Every package, class, and function in the repository — backend (Java/Spring Boot) + frontend (React/TypeScript)

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Technology Stack](#2-technology-stack)
3. [High-Level Architecture Diagram](#3-high-level-architecture-diagram)
4. [Agent Pipeline Flow Diagram](#4-agent-pipeline-flow-diagram)
5. [Request Lifecycle Flow](#5-request-lifecycle-flow)
6. [Database Schema Overview](#6-database-schema-overview)
7. [Backend Code Documentation — Package by Package](#7-backend-code-documentation)
   - 7.1 [Entry Point](#71-entry-point--mcpapplicationjava)
   - 7.2 [Multi-Tenancy Layer](#72-multi-tenancy-layer)
   - 7.3 [Security Layer](#73-security-layer)
   - 7.4 [Configuration Layer](#74-configuration-layer)
   - 7.5 [Domain Models](#75-domain-models)
   - 7.6 [Repositories](#76-repositories)
   - 7.7 [Agent System — Core Engine](#77-agent-system--core-engine)
   - 7.8 [Guardrails System — 9-Layer Safety Gate](#78-guardrails-system--9-layer-safety-gate)
   - 7.9 [Service Layer](#79-service-layer)
   - 7.10 [HITL System](#710-hitl-system)
   - 7.11 [MCP Tool Framework](#711-mcp-tool-framework)
   - 7.12 [REST Controller Layer](#712-rest-controller-layer)
   - 7.13 [Schedulers](#713-schedulers)
8. [Frontend Code Documentation](#8-frontend-code-documentation)
   - 8.1 [Entry Point & Routing](#81-entry-point--routing)
   - 8.2 [API Service Layer](#82-api-service-layer)
   - 8.3 [Pages](#83-pages)
   - 8.4 [Components](#84-components)
9. [Configuration Reference](#9-configuration-reference)
10. [Data Flow Scenarios](#10-data-flow-scenarios)

---

## 1. System Overview

The **MCP Incident Automation Platform** is an enterprise-grade, AI-driven incident response system that automates the detection, classification, and resolution of infrastructure incidents. It eliminates manual triage for up to 80% of repeating incidents via a **9-agent AI pipeline** backed by **Retrieval-Augmented Generation (RAG)** and a **9-layer guardrail safety framework**.

### Key Capabilities

| Capability | Description |
|---|---|
| **Multi-Agent AI Pipeline** | 9 specialised agents process each incident in parallel (Phase-1) then sequentially (Phase-2) |
| **RAG-Powered SOP Matching** | Embeddings & pgvector similarity search surface the best Standard Operating Procedure |
| **Resolved Incident Knowledge Base** | Past resolutions are archived and indexed for future RAG retrieval |
| **9-Layer Guardrails** | Safety gate that blocks any risky automated action before it executes |
| **Human-in-the-Loop (HITL)** | Severity-based SLA timeouts route uncertain decisions to human operators |
| **Multi-Tenancy** | Full tenant isolation via UUID-scoped data and per-request `ThreadLocal` context |
| **Pluggable LLM Providers** | Ollama (default/local), OpenAI, Anthropic, Vertex AI — switched via config |
| **Real Remediation Tools** | Actual OS-level actions: restart services, scale K8s pods, clear caches, run scripts |
| **External Source Integration** | Polls ServiceNow, Freshservice, Prometheus, PagerDuty every 60 s |
| **Immutable Audit Trail** | SHA-256 hashed, append-only audit events for every pipeline step |

---

## 2. Technology Stack

### Backend
| Layer | Technology |
|---|---|
| Runtime | Java 21 (virtual threads for parallel agents) |
| Framework | Spring Boot 3.x |
| AI / RAG | Spring AI 1.0.0 GA |
| LLM Providers | Ollama (phi4 / llama3.2), OpenAI GPT-4o, Anthropic Claude, Vertex AI Gemini |
| Embedding / Vector DB | pgvector (PostgreSQL extension) — 1536-dim vectors |
| Database ORM | Spring Data JPA / Hibernate |
| Schema Migration | Flyway (V1–V11 migrations) |
| Security | Spring Security + JWT (HS512) |
| Scheduling | Spring `@Scheduled` with `@EnableScheduling` |
| Retry | Spring Retry `@EnableRetry` |
| HTTP | Spring MVC `@RestController` |
| Build | Maven |
| Container | Docker / Docker Compose |

### Frontend
| Layer | Technology |
|---|---|
| Framework | React 18 + TypeScript |
| Build Tool | Vite |
| HTTP | Fetch API + JWT Bearer header |
| UI Style | Custom CSS (dark theme, terminal aesthetic) |

### Infrastructure
| Component | Technology |
|---|---|
| Database | PostgreSQL 15 + pgvector extension |
| Monitoring | Prometheus |
| Container Orchestration | Docker Compose |

---

## 3. High-Level Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              MCP INCIDENT AUTOMATION PLATFORM                               │
│                                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐  │
│  │                           EXTERNAL INCIDENT SOURCES                                  │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐           │  │
│  │  │  ServiceNow  │  │ Freshservice  │  │   Prometheus   │  │  PagerDuty   │           │  │
│  │  └──────┬──────┘  └──────┬───────┘  └───────┬────────┘  └──────┬───────┘           │  │
│  │         └────────────────┴──────────────────┴──────────────────┘                   │  │
│  │                                     │ (polling every 60s)                            │  │
│  └─────────────────────────────────────┼──────────────────────────────────────────────┘  │
│                                        │                                                    │
│  ┌─────────────────────────────────────▼──────────────────────────────────────────────────┐ │
│  │                           SPRING BOOT BACKEND  (port 8080)                             │ │
│  │                                                                                        │ │
│  │  ┌────────────────────────────────────────────────────────────────────────────────┐   │ │
│  │  │  SECURITY LAYER (JWT + Spring Security)                                        │   │ │
│  │  │  JwtAuthFilter → TenantInterceptor → ThreadLocal TenantContext                 │   │ │
│  │  └──────────────────────────────────────────────────────────────────────────────┬─┘   │ │
│  │                                                                                  │     │ │
│  │  ┌───────────────────────────────────────────────────────────────┐               │     │ │
│  │  │  REST CONTROLLERS                                             │               │     │ │
│  │  │  IncidentController │ HitlController │ SopController          │               │     │ │
│  │  │  KnowledgeBaseController │ AnalyticsController │ AuditController│              │     │ │
│  │  │  ToolController │ AuthController │ HealthController           │               │     │ │
│  │  └────────────────────────────┬──────────────────────────────────┘               │     │ │
│  │                               │                                                  │     │ │
│  │  ┌────────────────────────────▼──────────────────────────────────┐               │     │ │
│  │  │  SERVICE LAYER                                                 │               │     │ │
│  │  │  IncidentService │ RagService │ EmbeddingService               │               │     │ │
│  │  │  KnowledgeBaseService │ AuditService │ HitlService             │               │     │ │
│  │  │  RemediationToolRegistry │ VaultCredentialService             │               │     │ │
│  │  └────────────────────────────┬──────────────────────────────────┘               │     │ │
│  │                               │                                                  │     │ │
│  │  ┌────────────────────────────▼──────────────────────────────────┐               │     │ │
│  │  │  AGENT PIPELINE  (AgentPipeline → OrchestratorAgent)          │               │     │ │
│  │  │                                                               │               │     │ │
│  │  │  ┌─── PHASE 1: PARALLEL (Java 21 Virtual Threads) ──────────┐ │               │     │ │
│  │  │  │  ClassifierAgent │ PatternMatcherAgent │ SopRankerAgent   │ │               │     │ │
│  │  │  └──────────────────────────────────────────────────────────┘ │               │     │ │
│  │  │                                                               │               │     │ │
│  │  │  ┌─── PHASE 2: SEQUENTIAL ──────────────────────────────────┐ │               │     │ │
│  │  │  │  ConfidenceScorerAgent                                   │ │               │     │ │
│  │  │  │       ↓                                                  │ │               │     │ │
│  │  │  │  RiskEvaluatorAgent (9-risk-layers)                      │ │               │     │ │
│  │  │  │       ↓                                                  │ │               │     │ │
│  │  │  │  GuardrailsAgent (9-safety-validators)                   │ │               │     │ │
│  │  │  │       ↓                                                  │ │               │     │ │
│  │  │  │  ActionExecutorAgent → RemediationToolRegistry           │ │               │     │ │
│  │  │  │       ↓                                                  │ │               │     │ │
│  │  │  │  AuditAgent (always last, even on failure)               │ │               │     │ │
│  │  │  └──────────────────────────────────────────────────────────┘ │               │     │ │
│  │  └───────────────────────────────────────────────────────────────┘               │     │ │
│  │                                                                                  │     │ │
│  │  ┌───────────────────────────────┐  ┌────────────────────────────────────────┐   │     │ │
│  │  │  GUARDRAILS SYSTEM (9 layers) │  │  HITL WORKFLOW                         │   │     │ │
│  │  │  PromptInjectionGuard         │  │  HitlService → HitlNotificationService │   │     │ │
│  │  │  RoleAuthorizationValidator   │  │  HitlTimeoutScheduler (SLA enforcement)│   │     │ │
│  │  │  BlastRadiusGate              │  └────────────────────────────────────────┘   │     │ │
│  │  │  CircuitBreakerGuard          │                                               │     │ │
│  │  │  DryRunSimulator              │  ┌────────────────────────────────────────┐   │     │ │
│  │  │  LoopDetector                 │  │  SCHEDULERS                             │   │     │ │
│  │  │  RateLimitGuard               │  │  IncidentPollingScheduler (60s)        │   │     │ │
│  │  │  OutputSchemaValidator        │  │  IncidentProcessingScheduler           │   │     │ │
│  │  │  SchemaValidator              │  │  HitlTimeoutScheduler                  │   │     │ │
│  │  └───────────────────────────────┘  │  ConfidenceCalibrationJob              │   │     │ │
│  │                                     │  StaleJobRecoveryScheduler             │   │     │ │
│  │                                     └────────────────────────────────────────┘   │     │ │
│  │                                                                                  │     │ │
│  │  ┌───────────────────────────────────────────────────────────────────────────┐   │     │ │
│  │  │  MCP TOOL FRAMEWORK                                                       │   │     │ │
│  │  │  McpToolRegistry → McpToolExecutor → InfraTools, DatabaseTools,           │   │     │ │
│  │  │  MonitoringTools, ItsmTools, NotificationTools + CustomToolLoader         │   │     │ │
│  │  └───────────────────────────────────────────────────────────────────────────┘   │     │ │
│  └────────────────────────────────────────────────────────────────────────────────┘     │ │
│                                                                                          │ │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┘ │
│  │  RAG / VECTOR LAYER                                                                    │
│  │  EmbeddingService (Ollama/OpenAI) → VectorStore (pgvector) → RagService               │
│  │  Spring AI QuestionAnswerAdvisor → ChatClient → LLM Provider                          │
│  └────────────────────────────────────────────────────────────────────────────────────────┘
│                                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  DATA LAYER (PostgreSQL 15 + pgvector)                                               │  │
│  │  incidents │ tenants │ sop_procedures │ incident_patterns │ resolved_incident_kb      │  │
│  │  hitl_requests │ audit_events │ confidence_logs │ action_execution_logs               │  │
│  │  classification_rules │ custom_tools │ server_credentials │ scheduler_state          │  │
│  └──────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  REACT FRONTEND  (Vite, port 3000 → proxy to 8080)                                  │  │
│  │  LoginPage │ OverviewPage │ HitlPage │ SopPage │ KnowledgeBasePage                   │  │
│  │  AnalyticsPage │ AuditLogPage │ ToolsPage                                            │  │
│  └──────────────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Agent Pipeline Flow Diagram

```
                          ┌─────────────────────────────────┐
                          │       INCIDENT   (PENDING)       │
                          │   title, description, severity   │
                          └──────────────┬──────────────────┘
                                         │
                          ┌──────────────▼──────────────────┐
                          │  AgentPipeline.processIncident() │
                          │  Creates AgentContext + traceId  │
                          └──────────────┬──────────────────┘
                                         │
                          ┌──────────────▼──────────────────┐
                          │      OrchestratorAgent           │
                          │   Manages Phase-1 and Phase-2    │
                          └──────────────┬──────────────────┘
                                         │
         ┌───────────────────────────────┼──────────────────────────────────┐
         │              PHASE 1 — PARALLEL (Java 21 Virtual Threads)        │
         │  ┌─────────────────┐  ┌──────────────────┐  ┌──────────────────┐│
         │  │ ClassifierAgent │  │PatternMatcherAgent│  │  SopRankerAgent  ││
         │  │                 │  │                  │  │                  ││
         │  │ 1. Try regex    │  │ 1. Generate text │  │ 1. Build query   ││
         │  │    rules (DB)   │  │    embedding     │  │    embedding     ││
         │  │ 2. Semantic     │  │ 2. pgvector      │  │ 2. pgvector SOP  ││
         │  │    heuristics   │  │    similarity    │  │    similarity    ││
         │  │ 3. Set category │  │    search (TOP5) │  │    search (TOP10)││
         │  │    confidence   │  │ 3. Weight by     │  │ 3. Weight:       ││
         │  │    reason       │  │    reliability   │  │  70% similarity  ││
         │  │                 │  │ 4. Set matched   │  │  30% reliability ││
         │  │                 │  │    patternId,    │  │ 4. Extract action││
         │  │                 │  │    similarity    │  │    plan JSON     ││
         │  │                 │  │                  │  │ 5. Dual RAG:     ││
         │  │                 │  │                  │  │  SOP + KB search ││
         │  └────────┬────────┘  └────────┬─────────┘  └────────┬─────────┘│
         │           └─────────────────────┴─────────────────────┘          │
         │               mergeParallelResult() — merge all 3 contexts        │
         └───────────────────────────────────────────────────────────────────┘
                                         │
         ┌───────────────────────────────┼──────────────────────────────────┐
         │               PHASE 2 — SEQUENTIAL                               │
         │                                                                   │
         │  ┌──────────────────────────────────────────────────────────┐    │
         │  │ ConfidenceScorerAgent  (Priority 4)                      │    │
         │  │                                                          │    │
         │  │  Score = (patternSim × 0.35)  +  (historical × 0.25)    │    │
         │  │        + (sopReliability × 0.20) + (sysHealth × 0.15)   │    │
         │  │        - (riskPenalty × 0.05 per factor)                 │    │
         │  │                                                          │    │
         │  │  Decision:                                               │    │
         │  │    score ≥ 0.95 (configurable) → AUTO_RESOLVE            │    │
         │  │    score ≥ 0.80               → HITL_REQUIRED            │    │
         │  │    score < 0.80               → ESCALATE_TO_HUMAN        │    │
         │  │                                                          │    │
         │  │  Persists ConfidenceLog to DB                            │    │
         │  └───────────────────────────┬──────────────────────────────┘    │
         │                              │                                    │
         │  ┌───────────────────────────▼──────────────────────────────┐    │
         │  │ RiskEvaluatorAgent  (Priority 5)                         │    │
         │  │                                                          │    │
         │  │  9 Risk Layers (weighted sum):                           │    │
         │  │  Layer 1 (0.15): Production environment risk             │    │
         │  │  Layer 2 (0.15): Customer impact assessment              │    │
         │  │  Layer 3 (0.12): Data sensitivity                        │    │
         │  │  Layer 4 (0.12): Transaction consistency                 │    │
         │  │  Layer 5 (0.10): Backup/recovery availability            │    │
         │  │  Layer 6 (0.10): Change window validation                │    │
         │  │  Layer 7 (0.12): System health metrics                   │    │
         │  │  Layer 8 (0.08): Current deployments in-flight           │    │
         │  │  Layer 9 (0.06): Recent incident frequency               │    │
         │  │                                                          │    │
         │  │  P1 + riskScore > 0.75 → override ESCALATE_TO_HUMAN     │    │
         │  └───────────────────────────┬──────────────────────────────┘    │
         │                              │                                    │
         │  ┌───────────────────────────▼──────────────────────────────┐    │
         │  │ GuardrailsAgent  (Priority 6)                            │    │
         │  │                                                          │    │
         │  │  Runs GuardrailsService.runAll()                         │    │
         │  │  Validators (ordered, fail-fast):                        │    │
         │  │    L1: RoleAuthorizationValidator                        │    │
         │  │    L2: RateLimitGuard                                    │    │
         │  │    L3: PromptInjectionGuard  (Layer 3 → always ESCALATE) │    │
         │  │    L4: BlastRadiusGate                                   │    │
         │  │    L5: DryRunSimulator                                   │    │
         │  │    L6: SchemaValidator                                   │    │
         │  │    L7: LoopDetector          (Layer 7 → always ESCALATE) │    │
         │  │    L8: CircuitBreakerGuard                               │    │
         │  │    L9: OutputSchemaValidator                             │    │
         │  │                                                          │    │
         │  │  If ANY layer fails → block + override decision          │    │
         │  └───────────────────────────┬──────────────────────────────┘    │
         │                              │                                    │
         │  ┌───────────────────────────▼──────────────────────────────┐    │
         │  │ ActionExecutorAgent  (Priority 7)                        │    │
         │  │                                                          │    │
         │  │  Only runs if decision == AUTO_RESOLVE                   │    │
         │  │  or (decidedByHuman=true && decision==APPROVED)          │    │
         │  │                                                          │    │
         │  │  1. Extract actions from SOP JSON                        │    │
         │  │  2. For each action:                                     │    │
         │  │     a. Dry-run validation                                │    │
         │  │     b. Execute via RemediationToolRegistry               │    │
         │  │     c. On failure → rollback                             │    │
         │  │  3. If success rate < 50% → decision = ACTION_FAILED     │    │
         │  └───────────────────────────┬──────────────────────────────┘    │
         │                              │                                    │
         │  ┌───────────────────────────▼──────────────────────────────┐    │
         │  │ AuditAgent  (Priority 8 — ALWAYS RUNS, even on failure)  │    │
         │  │                                                          │    │
         │  │  1. Build audit payload (all context fields)             │    │
         │  │  2. SHA-256 hash → create AuditEvent                     │    │
         │  │  3. If HITL_REQUIRED / ESCALATE_TO_HUMAN:                │    │
         │  │     → Create HitlRequest with SLA expiry                 │    │
         │  │  4. Log classification event                             │    │
         │  │  5. Persist to audit_events table                        │    │
         │  └──────────────────────────────────────────────────────────┘    │
         └───────────────────────────────────────────────────────────────────┘
                                         │
                          ┌──────────────▼──────────────────┐
                          │  AgentPipeline.persistResults()  │
                          │                                  │
                          │  Set incident status:            │
                          │  AUTO_RESOLVE  → AUTO_RESOLVED   │
                          │  HITL_REQUIRED → HITL_PENDING    │
                          │  ESCALATE      → ESCALATED       │
                          │  GUARDRAIL BLOCKED              │
                          │                                  │
                          │  Save to incidents table         │
                          └──────────────────────────────────┘
```

---

## 5. Request Lifecycle Flow

```
User / External System
        │
        │  POST /api/v1/incidents
        ▼
  JwtAuthFilter
  • Extracts Bearer token
  • Validates JWT signature + expiry
  • Sets SecurityContext
        │
  TenantInterceptor
  • Reads X-Tenant-ID header (or JWT claim)
  • Sets TenantContext.set(tenantId) [ThreadLocal]
        │
  IncidentController.createIncident()
  • Validates request body
  • Delegates to IncidentService
        │
  IncidentService.createIncident()
  • Assigns UUID, sets status = PENDING
  • Saves to incidents table
        │
  IncidentProcessingScheduler (async, every 30s)
  • Calls IncidentService.processBatch()
  • Claims batch with SELECT ... FOR UPDATE SKIP LOCKED
  • Calls AgentPipeline.processBatch()
        │
  AgentPipeline.processIncident()
  • Creates AgentContext with traceId (UUID)
  • Calls OrchestratorAgent.execute()
        │
        ├── [Phase 1 Parallel] ──────────────────────────────────────┐
        │   ClassifierAgent        PatternMatcherAgent    SopRankerAgent
        │   → category             → patternId            → sopId
        │   → confidence           → similarity           → actionPlan
        │                          (all run simultaneously via Virtual Threads)
        ◄── merge results ──────────────────────────────────────────┘
        │
        ├── [Phase 2 Sequential]
        │   ConfidenceScorerAgent → decision (AUTO_RESOLVE / HITL / ESCALATE)
        │   RiskEvaluatorAgent   → riskScore, violations
        │   GuardrailsAgent      → pass/block
        │   ActionExecutorAgent  → execute tools (if AUTO_RESOLVE)
        │   AuditAgent           → persist audit + create HITL if needed
        │
  AgentPipeline.persistResults()
  • Updates incident.status, finalDecision
  • Saves to DB
        │
  [If HITL_REQUIRED / ESCALATE]
        │
  HitlService.createRequest()
  • Creates HitlRequest with SLA expiry
  • Fires Slack/email via HitlNotificationService
        │
  HitlTimeoutScheduler (every 60s)
  • Auto-escalates expired PENDING requests
        │
  [Human approves via UI]
        │
  HitlController.approve()
  • HitlService.approve()
  • Updates HitlRequest.status = APPROVED
  • Re-runs ActionExecutorAgent with decidedByHuman = true
        │
  KnowledgeBaseService.archiveResolved()
  • Archives completed incident to resolved_incident_kb
  • Queues for embedding ingestion into pgvector VectorStore
```

---

## 6. Database Schema Overview

The schema uses **16 tables**, evolving across **11 Flyway migration scripts** (V1–V11).

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           DATABASE SCHEMA                                        │
│                                                                                  │
│ tenants ─────────────────────────────────────────────────────────────────────┐  │
│  id (PK), name, plan, auto_resolve_threshold (0.95), hitl_threshold (0.80)   │  │
│  allow_p1_auto_resolve, max_blast_radius_pct, hitl_timeout_p1_min/p2/p3/p4   │  │
│  can_use_shared_sops, can_publish_sops, max_monthly_incidents, max_sops       │  │
│                                                                               │  │
│ incidents ────────────────────────────────────────────────────────────────── │  │
│  id (PK), tenant_id (FK→tenants), source_system, source_ticket_id (UNIQUE)   │  │
│  title, description, category, sub_category, severity (P1-P4)                │  │
│  affected_systems (TEXT[]), status, final_decision, retry_count               │  │
│  confidence_score, matched_sop_id, matched_pattern_id, pattern_similarity    │  │
│  processing_started_at, resolved_at, created_at, updated_at                  │  │
│  IDX: (tenant_id, severity, created_at) WHERE status='PENDING'               │  │
│  UNIQUE: (source_system, source_ticket_id)                                   │  │
│                                                                               │  │
│ sop_procedures ───────────────────────────────────────────────────────────── │  │
│  id (PK), tenant_id (FK), scope (PRIVATE/SHARED/PLATFORM)                    │  │
│  title, version, category, description                                        │  │
│  embedding (vector(1536)) ← pgvector                                         │  │
│  action_plan_json, preconditions_json, rollback_steps_json                   │  │
│  reliability_score, times_used, times_succeeded, status (ACTIVE/DRAFT)       │  │
│  approved_by, approved_at, created_by, created_at, updated_at                │  │
│                                                                               │  │
│ incident_patterns ────────────────────────────────────────────────────────── │  │
│  id (PK), tenant_id (FK), description, category, tags (TEXT[])               │  │
│  embedding (vector(1536)) ← pgvector                                         │  │
│  reliability_score, frequency_count, last_seen_at                            │  │
│                                                                               │  │
│ resolved_incident_kb ─────────────────────────────────────────────────────── │  │
│  id (PK), incident_id, tenant_id (FK)                                        │  │
│  title, description, category, severity                                       │  │
│  resolution_summary, root_cause                                               │  │
│  resolution_steps (JSONB), comments (JSONB)                                  │  │
│  resolved_by, resolved_at, source_ticket_id                                  │  │
│  source_system, embedding_ingested (BOOLEAN)                                 │  │
│                                                                               │  │
│ hitl_requests ────────────────────────────────────────────────────────────── │  │
│  id (PK), incident_id (FK→incidents), tenant_id (FK)                         │  │
│  status (PENDING/APPROVED/REJECTED/EXPIRED), decision                        │  │
│  decision_reason, decided_by, decided_at                                     │  │
│  approval_payload (JSONB) ← full context snapshot                            │  │
│  expires_at (SLA), created_at                                                │  │
│                                                                               │  │
│ audit_events ─────────────────────────────────────────────────────────────── │  │
│  id (PK), incident_id, tenant_id, trace_id                                   │  │
│  event_type, agent_name, payload (JSONB)                                     │  │
│  record_hash (SHA-256), created_at                                           │  │
│                                                                               │  │
│ confidence_logs ──────────────────────────────────────────────────────────── │  │
│  id (PK), incident_id, pattern_id, sop_id                                    │  │
│  score_pattern_sim, score_historical, score_sop_reliability                  │  │
│  score_system_health, penalty_risk_factor, final_score                       │  │
│  decision, reasoning_text                                                    │  │
│                                                                               │  │
│ action_execution_logs ────────────────────────────────────────────────────── │  │
│  id (PK), incident_id, tool_name, status, result (JSONB)                     │  │
│  executed_at, roll_back_triggered                                            │  │
│                                                                               │  │
│ classification_rules ─────────────────────────────────────────────────────── │  │
│  id (PK), tenant_id (FK), name, pattern (regex), category, sub_category      │  │
│  confidence, priority (lower = higher), active (BOOLEAN)                     │  │
│                                                                               │  │
│ custom_tools ─────────────────────────────────────────────────────────────── │  │
│  id (PK), tenant_id (FK), name, description, category                        │  │
│  script_content, script_type, enabled                                        │  │
│                                                                               │  │
│ server_credentials ───────────────────────────────────────────────────────── │  │
│  id (PK), tenant_id (FK), hostname, username, credential_type                │  │
│  encrypted_credential, port                                                  │  │
│                                                                               │  │
│ scheduler_state ──────────────────────────────────────────────────────────── │  │
│  source_system (PK), last_polled_at, last_run_at, consecutive_errors         │  │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Backend Code Documentation

### 7.1 Entry Point — `McpApplication.java`

**Package:** `com.company.mcp`

```java
@SpringBootApplication  // Enables component scan, auto-configuration
@EnableScheduling       // Activates all @Scheduled beans (pollers, HITL timeout, etc.)
@EnableRetry            // Activates @Retryable support across services
public class McpApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }
}
```

**Purpose:** Application bootstrap. The three annotations collectively activate the entire Spring ecosystem. `@EnableScheduling` is critical — without it none of the poller schedulers start.

---

### 7.2 Multi-Tenancy Layer

**Package:** `com.company.mcp.tenant`

#### `TenantContext.java`

A utility class using `ThreadLocal<String>` to carry the tenant ID through each request thread without explicit parameter passing.

| Method | Purpose |
|---|---|
| `set(tenantId)` | Called by `TenantInterceptor` at request start |
| `get()` | Called by any component that needs the current tenant |
| `clear()` | Called at request end to prevent ThreadLocal leaks in thread pools |

**Critical:** `clear()` must always be called (in a `finally` block) to avoid thread pool contamination.

#### `TenantInterceptor.java`

Spring MVC `HandlerInterceptor` that:
1. Reads `X-Tenant-ID` header (or JWT claim) on `preHandle()`
2. Calls `TenantContext.set(tenantId)`
3. Calls `TenantContext.clear()` in `afterCompletion()` (always, even on error)

---

### 7.3 Security Layer

**Package:** `com.company.mcp.config` and `com.company.mcp.config.security`

#### `SecurityConfig.java`

Configures Spring Security for **stateless JWT authentication**:

| Feature | Configuration |
|---|---|
| Session | `SessionCreationPolicy.STATELESS` (no HTTP sessions) |
| Public paths | `/api/auth/**` and `/actuator/**` |
| Protected paths | All other endpoints require valid `Authorization: Bearer <jwt>` |
| CSRF | Disabled (stateless API, JWT handles CSRF protection) |
| CORS | `allowedOriginPatterns("*")` with credentials; supports all HTTP methods |

**Filter chain:** `JwtAuthFilter` → `UsernamePasswordAuthenticationFilter`

#### `JwtAuthFilter.java`

`OncePerRequestFilter` that:
1. Extracts `Authorization: Bearer <token>` header
2. Calls `JwtUtil.validateToken()` to verify signature + expiry
3. Extracts subject (username), sets `UsernamePasswordAuthenticationToken` in `SecurityContextHolder`
4. On validation failure: returns 401 immediately

#### `JwtUtil.java`

| Method | Purpose |
|---|---|
| `generateToken(username, role, tenantId)` | Creates HS512-signed JWT with configurable expiry |
| `validateToken(token)` | Returns true if signature valid and token not expired |
| `extractUsername(token)` | Parses subject claim |
| `extractTenantId(token)` | Parses custom `tenantId` claim |
| `extractRole(token)` | Parses custom `role` claim |

**Configuration:** `mcp.jwt.secret` (env: `JWT_SECRET`), `mcp.jwt.expiration-ms` (default: 86400000 ms = 24h)

#### `AuthController.java`

**Path:** `POST /api/auth/login`

Accepts `{username, password}`, validates against `users` table (or in-memory map for dev), returns `{token, username, role, tenantId, expiresIn}`.

---

### 7.4 Configuration Layer

**Package:** `com.company.mcp.config`

#### `AgentConfig.java`

Spring `@Configuration` that constructs and registers all 9 agent beans:
- `ClassifierAgent`, `PatternMatcherAgent`, `SopRankerAgent` (Phase-1)
- `ConfidenceScorerAgent`, `RiskEvaluatorAgent`, `GuardrailsAgent`, `ActionExecutorAgent`, `AuditAgent` (Phase-2)
- `OrchestratorAgent` (wires all agents together)

Also feeds the agent list into `AgentPipeline`.

#### `LlmProperties.java`

`@ConfigurationProperties("mcp.llm")` POJO:

| Property | Default | Purpose |
|---|---|---|
| `provider` | `ollama` | Active LLM provider |
| `model` | `phi4` | Chat model name |
| `embedModel` | `nomic-embed-text` | Embedding model name |
| `maxTokens` | `2048` | Max tokens per LLM call |
| `temperature` | `0.0` | Deterministic output |

#### `LlmProviderConfig.java`

Creates the `ChatClient` Spring AI bean configured for the active provider. Supports graceful degradation — if no API key / Ollama is unavailable, no bean is created and `RagService` operates in stub mode.

#### `SchedulerConfig.java`

Configures the `ThreadPoolTaskScheduler` with a pool size of 10, enabling concurrent scheduler execution.

#### `ApplicationConfig.java`

General beans: `ObjectMapper` (Jackson), `PasswordEncoder` (BCrypt), Flyway custom configuration.

#### `DataSourceConfig.java`

HikariCP data source configuration. Pool: 20 max, 5 min idle, 30s connection timeout.

#### `FlywayConfig.java`

Customises Flyway with `baseline-on-migrate: false` to enforce clean migration from scratch, and registers the pgvector type mapping so Flyway can diff vector columns.

#### `WebMvcConfig.java`

Registers `TenantInterceptor` for all API paths (`/api/**`).

---

### 7.5 Domain Models

**Package:** `com.company.mcp.model`

#### `Incident.java` — Core Entity + Job Queue

The central entity that serves **dual purpose**: incident record AND distributed job queue item. Claimed atomically using `SELECT FOR UPDATE SKIP LOCKED`.

| Field | Type | Purpose |
|---|---|---|
| `id` | UUID | Primary key |
| `tenantId` | UUID | Multi-tenancy scope |
| `sourceSystem` | String(50) | `servicenow`, `prometheus`, etc. |
| `sourceTicketId` | String(100) | Unique external ID (UNIQUE constraint) |
| `title` | TEXT | Incident title |
| `description` | TEXT | Detailed description |
| `severity` | String(5) | `P1`, `P2`, `P3`, `P4` |
| `affectedSystems` | String[] | PostgreSQL TEXT[] array |
| `status` | String(40) | `PENDING`, `PROCESSING`, `AUTO_RESOLVED`, `HITL_PENDING`, `ESCALATED` |
| `finalDecision` | String(20) | `AUTO_RESOLVE`, `HITL_REQUIRED`, `ESCALATE_TO_HUMAN` |
| `confidenceScore` | Double | Final confidence from `ConfidenceScorerAgent` |
| `matchedSopId` | UUID | Best SOP from `SopRankerAgent` |
| `matchedPatternId` | UUID | Best pattern from `PatternMatcherAgent` |
| `retryCount` | Integer | Incremented on processing failure |
| `processingStartedAt` | LocalDateTime | Claimed timestamp |
| `resolvedAt` | LocalDateTime | Resolution timestamp |

**Indexes:** `(tenant_id, severity, created_at) WHERE status='PENDING'` — ensures O(log n) queue queries even with millions of historical rows.

#### `SopProcedure.java` — Standard Operating Procedure

| Field | Purpose |
|---|---|
| `embedding` | `vector(1536)` — pgvector column for semantic search |
| `actionPlanJson` | JSON string: array of action steps with tool names + params |
| `preconditionsJson` | JSON conditions that must be true before SOP executes |
| `rollbackStepsJson` | JSON rollback steps if execution fails |
| `reliabilityScore` | Float 0–1, updated after each execution |
| `scope` | `PRIVATE` (tenant only), `SHARED` (cross-tenant), `PLATFORM` (global) |
| `status` | `DRAFT` or `ACTIVE` |

#### `HitlRequest.java` — Human Approval Request

| Field | Purpose |
|---|---|
| `status` | `PENDING`, `APPROVED`, `REJECTED`, `EXPIRED` |
| `approvalPayload` | JSONB snapshot of full `AgentContext` for UI rendering |
| `expiresAt` | SLA deadline (P1=15min, P2=30min, P3=120min, P4=480min) |
| `decidedBy` | Username of the operator who approved/rejected |
| `decisionReason` | Free-text notes from operator |

#### `AuditEvent.java` — Immutable Audit Record

| Field | Purpose |
|---|---|
| `eventType` | `INCIDENT_CLASSIFIED`, `AUTO_RESOLVED`, `HITL_REQUIRED`, `ESCALATED`, `ACTION_EXECUTED` |
| `payload` | JSONB — full context snapshot at time of event |
| `recordHash` | SHA-256 of payload — tamper detection |
| `traceId` | Distributed trace correlation ID |

#### `ConfidenceLog.java` — Scoring Audit Trail

Stores each component score for model calibration and debugging:
- `scorePatternSim`, `scoreHistorical`, `scoreSopReliability`, `scoreSystemHealth`, `penaltyRiskFactor`
- `finalScore`, `decision`, `reasoningText`

#### `IncidentPattern.java`

Historical incident pattern with `vector(1536)` embedding for pgvector similarity search. Tracks `reliabilityScore` and `frequencyCount`.

#### `ResolvedIncidentKb.java`

Archived resolved incident — the knowledge base entry. Contains `resolution_steps` (JSONB), `root_cause`, `resolution_summary`. Has `embeddingIngested` flag for async ingestion scheduling.

#### `ClassificationRule.java`

Regex-based classification rule: `pattern` (Java regex), `category`, `subCategory`, `confidence`, `priority`. Lower priority integer = higher precedence.

#### `CustomTool.java`

Tenant-defined tool: `scriptContent`, `scriptType` (bash/python/powershell), `enabled`. Loaded at startup by `CustomToolLoader`.

#### `ServerCredentials.java`

Encrypted server credentials for remote agent actions: `hostname`, `username`, `credentialType`, `encryptedCredential`.

#### `Tenant.java`

Per-tenant configuration: `autoResolveThreshold`, `hitlThreshold`, `allowP1AutoResolve`, `maxBlastRadiusPct`, SLA timeouts, resource quotas.

#### Domain Enums — `com.company.mcp.domain`

| Enum | Values |
|---|---|
| `Severity` | `P1`, `P2`, `P3`, `P4` |
| `IncidentStatus` | `PENDING`, `PROCESSING`, `AUTO_RESOLVED`, `HITL_PENDING`, `ESCALATED`, ... |
| `Decision` | `AUTO_RESOLVE`, `HITL_REQUIRED`, `ESCALATE_TO_HUMAN`, `ACTION_FAILED` |

---

### 7.6 Repositories

**Package:** `com.company.mcp.repository`

All repositories extend `JpaRepository<Entity, UUID>` and add custom JPQL / native queries.

#### `IncidentRepository.java`

| Method | Purpose |
|---|---|
| `claimNextBatch(batchSize, tenantId)` | `SELECT ... FOR UPDATE SKIP LOCKED` — atomic claim of PENDING incidents; prevents duplicate processing |
| `findByStatus(status)` | Find all incidents in a given status |
| `findByTenantIdAndStatus(tenantId, status, pageable)` | Paginated filtered query |
| `countByTenantIdAndStatus(tenantId, status)` | Dashboard counters |

#### `SopProcedureRepository.java`

| Method | Purpose |
|---|---|
| `findSimilarSOPs(embedding, tenantId, topK)` | Native pgvector cosine distance query: `ORDER BY embedding <=> :embedding::vector LIMIT :topK` |
| `findActivePrioritized(tenantId)` | Active SOPs ordered by reliability |

#### `PatternRepository.java`

| Method | Purpose |
|---|---|
| `findSimilarPatterns(embedding, tenantId, category, topK)` | pgvector similarity search scoped by category |

#### `ResolvedIncidentKbRepository.java`

| Method | Purpose |
|---|---|
| `findByIncidentId(id)` | Upsert lookup |
| `findBySourceTicketId(ticketId)` | Dedup check |
| `findByEmbeddingIngestedFalse()` | Batch embedding ingestion job |
| `searchByText(query, tenantId, pageable)` | ILIKE full-text search |

#### `HitlRequestRepository.java`

| Method | Purpose |
|---|---|
| `findByIncidentIdAndStatus(id, status)` | Find pending HITL for incident |
| `findByExpiresAtBefore(datetime)` | Find expired requests for SLA enforcement |
| `countByTenantIdAndStatus(tenantId, status)` | Dashboard badge counter |

#### `AuditEventRepository.java`

Append-only; no update methods exposed by design (immutable audit trail).

#### `ConfidenceLogRepository.java`

| Method | Purpose |
|---|---|
| `findByIncidentId(id)` | Retrieve scoring breakdown |
| `findRecentForCalibration(since)` | Used by `ConfidenceCalibrationJob` |

#### `ClassificationRulesRepository.java`

| Method | Purpose |
|---|---|
| `findActivePrioritized(tenantId)` | Returns rules ordered by priority ASC (lower = evaluated first) |

#### `TenantRepository.java`

Standard CRUD + `findByName()` for tenant lookup.

---

### 7.7 Agent System — Core Engine

**Package:** `com.company.mcp.agent`

#### `BaseAgent.java` — Abstract Foundation

All 9 agents extend this class. It enforces a uniform contract:

| Abstract Method | Purpose |
|---|---|
| `execute(AgentContext)` | Core agent logic — reads and enriches context |
| `canExecute(AgentContext)` | Pre-condition check — orchestrator skips agent if `false` |
| `getPriority()` | Integer 1–9; lower = higher priority in pipeline ordering |

**Utility methods provided to all agents:**
- `logExecution(context, message)` — structured log with `[AgentName] IncidentId=... TraceId=...`
- `logError(context, message, throwable)` — error log with same structured format
- `logWarning(context, message)` — warn-level structured log
- `handleException(context, exception, operation)` — logs + adds to `context.errors` + throws `AgentExecutionException`

**Inner class:**
- `AgentExecutionException` — checked exception used for critical failures that abort the pipeline

---

#### `AgentContext.java` — Pipeline State Object

The **shared blackboard** passed between all agents. Immutable input fields (incident, tenantId, traceId) are set at pipeline start; all enrichment fields are populated by agents:

| Field Group | Fields | Set By |
|---|---|---|
| **Incident input** | `incident`, `tenantId`, `traceId` | `AgentPipeline` |
| **Classification** | `classifiedCategory`, `classifiedSubCategory`, `classificationConfidence`, `classificationReason` | `ClassifierAgent` |
| **Pattern match** | `matchedPatternId`, `patternSimilarity`, `patternDescription` | `PatternMatcherAgent` |
| **SOP match** | `matchedSopId`, `sopTitle`, `sopReliability`, `actionPlan` | `SopRankerAgent` |
| **RAG results** | `kbSuggestedResolution`, `kbMatchedEntries`, `combinedRagDocs` | `SopRankerAgent` |
| **Confidence** | `confidenceLog`, `finalConfidenceScore`, `decision` | `ConfidenceScorerAgent` |
| **Risk** | `riskScore`, `riskFactors`, `guardrailsTriggered`, `guardRailViolations` | `RiskEvaluatorAgent` |
| **Guardrails** | `guardrailsTriggered` (updated) | `GuardrailsAgent` |
| **Actions** | `executedSteps`, `rollbackTriggered`, `rollbackReason` | `ActionExecutorAgent` |
| **HITL flag** | `decidedByHuman` | `HitlService` (post-approval) |
| **Timeline** | `processingStartedAt`, `processingCompletedAt` | `AgentPipeline` / `OrchestratorAgent` |
| **Errors/Warnings** | `errors`, `warnings` | Any agent |

**Helper methods:** `addError()`, `addWarning()`, `hasErrors()`, `hasWarnings()`

**Inner record:** `ActionExecutionStep` — tracks each tool execution: `toolName`, `executedAt`, `status`, `result` Map.

---

#### `AgentPipeline.java` — Entry Point Service

The service called by `IncidentService` to kick off processing.

| Method | Purpose |
|---|---|
| `processIncident(incident, tenantId)` | Creates `AgentContext` → calls `OrchestratorAgent.execute()` → calls `persistResults()` |
| `processBatch(incidents, tenantId)` | Iterates list, calls `processIncident()` for each, counts successes |
| `persistResults(incident, context)` | Maps `context.decision` → `incident.status`, saves to DB |
| `markIncidentAsEscalated(incident, reason)` | Called on unhandled pipeline exceptions |

---

#### `OrchestratorAgent.java` — Pipeline Coordinator

The brain that drives Phase-1 and Phase-2 execution:

| Method | Purpose |
|---|---|
| `execute(context)` | Top-level entry: calls `runParallelPhase()` then `runSequentialPhase()` |
| `runParallelPhase(context, sorted)` | Filters for `PARALLEL_AGENTS` set; uses `Executors.newVirtualThreadPerTaskExecutor()` to run 3 agents simultaneously; calls `CompletableFuture.allOf()` to await; merges results |
| `runSequentialPhase(context, sorted)` | Runs remaining agents in priority order; always runs `AuditAgent` last |
| `runSingleAgent(context, agent, ignoreErrors)` | Checks `canExecute()` → calls `agent.execute()` → logs timing |
| `mergeParallelResult(master, result)` | Merges classification, pattern, SOP fields from parallel agent copies into master context |
| `copyContext(src)` | Shallow copies context for parallel agents (prevents shared-state mutation) |
| `getSortedAgentPipeline()` | Sorts all injected agents by `getPriority()` ascending |
| `isCriticalAgent(name)` | Returns `true` for `ConfidenceScorerAgent` / `RiskEvaluatorAgent` — pipeline aborts on their failure |

**Parallel execution model:** Each parallel agent receives a copy of the context, runs in its own virtual thread, and writes to its copy. After all futures complete, results are merged field-by-field.

---

#### `ClassifierAgent.java` — Phase 1a: Incident Categorisation

**Priority:** 1 | **Runs:** Parallel (Phase-1)

| Method | Purpose |
|---|---|
| `execute(context)` | Step 1: `matchRegexRules()` → if no match, Step 2: `semanticClassification()` |
| `matchRegexRules(incident, tenantId)` | Iterates `ClassificationRulesRepository.findActivePrioritized()`, compiles each rule's regex, tests against `incident.title` and `incident.description`. Returns on first match. |
| `semanticClassification(incident)` | Keyword heuristics (database→DATABASE, network→NETWORK, cpu/memory→INFRASTRUCTURE, deployment→DEPLOYMENT). Phase-5+ would call LLM. |

**Output fields set:** `classifiedCategory`, `classifiedSubCategory`, `classificationConfidence`, `classificationReason`

---

#### `PatternMatcherAgent.java` — Phase 1b: Historical Pattern Matching

**Priority:** 2 | **Runs:** Parallel (Phase-1)

| Method | Purpose |
|---|---|
| `execute(context)` | 1. Generates embedding for `title + description`. 2. Calls `PatternRepository.findSimilarPatterns()` (pgvector). 3. Scores each: `adjustedScore = similarity × reliability`. 4. Selects best above `MIN_SIMILARITY=0.6`. |
| `canExecute(context)` | Only requires `incident != null` (no pre-classification dependency) |

**Constants:** `TOP_K = 5`, `MIN_SIMILARITY = 0.6`

**Output fields set:** `matchedPatternId`, `patternSimilarity`, `patternDescription`

---

#### `SopRankerAgent.java` — Phase 1c: SOP Retrieval via RAG

**Priority:** 3 | **Runs:** Parallel (Phase-1)

| Method | Purpose |
|---|---|
| `execute(context)` | 1. `buildQueryText()`. 2. Generate embedding. 3. `SopProcedureRepository.findSimilarSOPs()`. 4. Filter ACTIVE; score: `0.7×similarity + 0.3×reliability`. 5. `enrichWithCombinedRag()`. |
| `buildQueryText(context)` | Concatenates title + description + category + subCategory + tags for richer embedding |
| `enrichWithCombinedRag(context, query)` | Calls `RagService.retrieveForIncident()` combining SOP and Resolved-KB vector search; populates `kbSuggestedResolution`, `kbMatchedEntries`, `combinedRagDocs` |

**Constants:** `TOP_K = 10`, `MIN_SIMILARITY = 0.5`, `MIN_RELIABILITY = 0.6`, `KB_TOP_K = 3`

**Output fields set:** `matchedSopId`, `sopTitle`, `sopReliability`, `actionPlan`, `kbSuggestedResolution`, `kbMatchedEntries`, `combinedRagDocs`

---

#### `ConfidenceScorerAgent.java` — Phase 2a: Confidence Calculation

**Priority:** 4 | **Runs:** Sequential (Phase-2)

**Scoring formula:**
```
finalScore = (patternSim × 0.35) + (historical × 0.25) + (sopReliability × 0.20)
           + (systemHealth × 0.15) - (riskPenalty × 0.05 per factor)
```

| Method | Purpose |
|---|---|
| `execute(context)` | Computes all 5 components → weighted sum → clamped to [0,1] → determines decision → persists `ConfidenceLog` |
| `calculatePatternSimilarityScore(context)` | Returns `patternSimilarity` clamped to [0,1]; default 0.3 if no match |
| `calculateHistoricalScore(context)` | Estimates historical success rate from pattern reliability; default 0.5 |
| `calculateSopReliabilityScore(context)` | Returns `sopReliability`; default 0.4 if no SOP |
| `calculateSystemHealthScore(context)` | Fixed 0.8 (placeholder; would integrate Prometheus metrics in Phase-5+) |
| `calculateRiskPenalty(context)` | P1 → +0.20 penalty; P2 → +0.10; destructive actions → +0.05 |
| `determineDecision(score, context)` | `score ≥ autoResolveThreshold` (configurable, default 0.95) → `AUTO_RESOLVE`; `≥ 0.80` → `HITL_REQUIRED`; else `ESCALATE_TO_HUMAN` |

**Configuration:** `mcp.confidence.auto-resolve-threshold` (default: `0.95`). Set to `1.0` to require 100% certainty (effectively disabling auto-resolve).

---

#### `RiskEvaluatorAgent.java` — Phase 2b: 9-Layer Risk Assessment

**Priority:** 5 | **Runs:** Sequential (Phase-2)

Each layer contributes a weighted component to the total risk score:

| Layer | Weight | Evaluates |
|---|---|---|
| L1: Production Environment | 0.15 | Is incident in prod? Is it customer-facing? |
| L2: Customer Impact | 0.15 | Estimated blast radius as % of customers |
| L3: Data Sensitivity | 0.12 | Involves PII, financial, or healthcare data? |
| L4: Transaction Consistency | 0.12 | Risk of data corruption or inconsistency |
| L5: Backup/Recovery | 0.10 | Recovery options available if action fails? |
| L6: Change Window | 0.10 | Is action within approved maintenance window? |
| L7: System Health | 0.12 | Current CPU/memory/disk health metrics |
| L8: Active Deployments | 0.08 | Any deployments in-flight on affected systems? |
| L9: Incident Frequency | 0.06 | Recent recurrence rate (last 1 hour) |

**Override rules:**
- `P1 + riskScore > 0.75` → force `ESCALATE_TO_HUMAN`
- Any layer violation added to `context.guardRailViolations`

**Methods:** `evaluateProductionRisk()`, `evaluateCustomerImpact()`, `evaluateDataSensitivity()`, `evaluateTransactionConsistency()`, `evaluateBackupRecovery()`, `evaluateChangeWindow()`, `evaluateSystemHealth()`, `evaluateCurrentDeployments()`, `evaluateIncidentFrequency()`

---

#### `GuardrailsAgent.java` — Phase 2c: 9-Layer Safety Gate

**Priority:** 6 | **Runs:** Sequential (Phase-2)

**Rule:** If even ONE layer fails, the action is blocked — no bypass, no override, no exceptions.

| Method | Purpose |
|---|---|
| `execute(context)` | Calls `GuardrailsService.runAll(context)`. If not passing: calls `determineOverrideDecision()`, sets `context.decision`, adds warning |
| `determineOverrideDecision(ctx, result)` | Layer 3 → `ESCALATE_TO_HUMAN`; Layer 7 → `ESCALATE_TO_HUMAN`; THROTTLE/QUEUE → `HITL_REQUIRED`; P1 → `HITL_REQUIRED`; default: `ESCALATE_TO_HUMAN` |
| `canExecute(context)` | Skips if already `ESCALATE_TO_HUMAN` (no point running gates) |

---

#### `ActionExecutorAgent.java` — Phase 2d: Remediation Execution

**Priority:** 7 | **Runs:** Sequential (Phase-2)

Executes only when `decision == AUTO_RESOLVE` or `decidedByHuman=true && decision==APPROVED`.

| Method | Purpose |
|---|---|
| `execute(context)` | Extracts action list → for each: dry-run → actual execution → rollback on failure |
| `extractActionsFromSop(context)` | Parses `actionPlan["actions"]` JSON array from SOP |
| `buildDefaultActions(context)` | Fallback: builds action list from classification category (e.g., `DATABASE` → `RESTART_SERVICE:postgres`) |
| `executeTool(action, context, dryRun)` | Delegates to `RemediationToolRegistry.execute(action, context, dryRun)` |
| `performRollback(action, context)` | Extracts rollback steps from `actionPlan["rollback"]` and reverses the action |
| `extractToolName(action)` | Parses `TOOL_NAME:param1:param2` format |

**Success threshold:** If `successRate < 0.5` → sets `decision = ACTION_FAILED`

---

#### `AuditAgent.java` — Phase 2e: Immutable Audit Trail (Always Last)

**Priority:** 8 | `canExecute()` → always `true`

| Method | Purpose |
|---|---|
| `execute(context)` | Builds payload → creates `AuditEvent` → creates `HitlRequest` if needed → logs classification audit. Failures are caught and logged but do NOT abort the pipeline. |
| `buildAuditPayload(context)` | Serialises all context fields into a Map for JSONB storage |
| `createAuditEvent(incidentId, tenantId, traceId, agentName, eventType, payload)` | SHA-256 hashes the payload, creates and persists `AuditEvent` |
| `createHitlRequest(context)` | Creates `HitlRequest` with SLA expiry, reason, approval payload; persists and returns |
| `logClassificationAudit(context)` | Creates secondary audit event for classification decision |
| `resolveEventType(decision)` | Maps decision string to `AuditEvent.EventType` enum |
| `hashPayload(payload)` | `MessageDigest.getInstance("SHA-256")` → hex string |
| `computeSlaExpiry(severity)` | P1=15min, P2=30min, P3=120min, P4=480min from now |

---

#### `AgentRegistry.java`

Spring `@Component` that provides a named lookup map of all agents. Used by `OrchestratorAgent.getSortedAgentPipeline()` to get the ordered list.

---

### 7.8 Guardrails System — 9-Layer Safety Gate

**Package:** `com.company.mcp.guardrails`

#### `GuardrailResult.java`

Value object returned by each validator:

| Field | Purpose |
|---|---|
| `status` | `PASS`, `BLOCK`, `THROTTLE`, `QUEUE` |
| `layer` | Integer 1–9 |
| `layerName` | Human-readable name |
| `reason` | Explanation for non-PASS results |

Static factories: `GuardrailResult.pass(layer, name)`, `GuardrailResult.block(layer, name, reason)`, etc.

`isPassing()` returns `true` only for `PASS` status.

#### `GuardrailsService.java`

Chain-of-responsibility runner:
- Constructor auto-injects all `GuardrailValidator` beans, sorts by `getLayer()` ascending
- `runAll(context)`: iterates in order, stops at first non-PASS, returns that result (fail-fast)
- If all pass, returns synthetic `GuardrailResult.pass(0, "ALL_LAYERS_PASSED")`

#### `GuardrailValidator.java` — Interface

```java
public interface GuardrailValidator {
    GuardrailResult validate(AgentContext context);
    int getLayer();
    String getLayerName();
}
```

#### Validators (`com.company.mcp.guardrails.validators`)

| Class | Layer | What It Checks |
|---|---|---|
| `RoleAuthorizationValidator` | 1 | Tenant is active; calling user has permission for the action category |
| `RateLimitGuard` | 2 | Incident processing rate per tenant (prevents runaway automation) |
| `PromptInjectionGuard` | 3 | Scans incident `title`/`description` for prompt injection patterns (system prompts, jailbreaks). **Hard block → ESCALATE** |
| `BlastRadiusGate` | 4 | `affectedSystems.length / totalSystems > tenant.maxBlastRadiusPct` → BLOCK |
| `DryRunSimulator` | 5 | Simulates action in dry-run mode; checks for expected success signals |
| `SchemaValidator` | 6 | Validates action plan JSON schema against expected structure |
| `LoopDetector` | 7 | Detects if same incident type has been processed >N times in last 1 hour. **Hard block → ESCALATE** |
| `CircuitBreakerGuard` | 8 | If >X% of recent automated actions failed for this category, circuit-breaks further automation |
| `OutputSchemaValidator` | 9 | Validates that resolved action output matches expected schema before marking resolved |

---

### 7.9 Service Layer

**Package:** `com.company.mcp.service`

#### `IncidentService.java`

Central service coordinating incident lifecycle:

| Method | Purpose |
|---|---|
| `createIncident(incident)` | Assigns UUID + timestamps, sets `status=PENDING`, persists |
| `getIncidentById(id)` | Repository lookup |
| `processIncident(incidentId, tenantId)` | Fetch + delegate to `AgentPipeline.processIncident()` |
| `claimNextBatch(tenantId)` | Uses `IncidentRepository.claimNextBatch()` with `SELECT FOR UPDATE SKIP LOCKED` |
| `processBatch(tenantId)` | Claim + process batch; returns success count |
| `retryIncident(incidentId, tenantId)` | Resets `status=PENDING`, increments `retryCount`, re-processes |
| `archiveIfTerminal(incident)` | If `status ∈ TERMINAL_STATUSES`, calls `KnowledgeBaseService.archiveResolved()` |
| `getIncidentsByStatus(tenantId, status, pageable)` | Paginated filtered list |
| `getIncidentStatistics(tenantId)` | Count by status for dashboard |

---

#### `EmbeddingService.java`

Provides pgvector-compatible embedding strings:

| Method | Purpose |
|---|---|
| `generateEmbedding(text)` | If `EmbeddingModel` available: calls provider API, returns `"[0.12,-0.34,...]"` string. Otherwise: `generateMockEmbedding()` |
| `cosineSimilarity(embedding1, embedding2)` | Parses vector strings → computes dot product / magnitudes |
| `generateMockEmbedding(text)` | Deterministic 1536-dim hash-based vector for dev/CI (no API key needed) |

**Mode detection:** `@PostConstruct resolveEmbeddingModel()` — if `embeddingModels` list is empty, stays in mock mode. If multiple models present (multiple starters), picks model matching `mcp.llm.provider`.

---

#### `RagService.java`

Retrieval-Augmented Generation orchestration using Spring AI 1.0.0:

| Method | Purpose |
|---|---|
| `ingest(id, content, type, metadata)` | Wraps content in `Document`, calls `vectorStore.add()` |
| `ingestBatch(documents)` | Batch ingest with error count |
| `ingestSop(sop)` | Convenience: formats SOP as Document with metadata (tenantId, category, sopId, type=SOP) |
| `ingestResolvedIncident(kb)` | Formats KB entry as Document with type=RESOLVED_INCIDENT |
| `retrieve(query, topK, filter)` | `vectorStore.similaritySearch(SearchRequest)` — returns `List<Document>` |
| `retrieveForIncident(incidentText, tenantId, topK)` | Combined SOP + KB search; merges results |
| `query(question, context)` | Full RAG: attaches `QuestionAnswerAdvisor` to `ChatClient`, returns LLM answer |
| `generateIncidentResolution(incident, sopDocs, kbDocs)` | Builds structured prompt → LLM → returns resolution suggestion string |
| `isVectorStoreAvailable()` | Null-check on `vectorStore` bean |

**Document types:** `TYPE_SOP`, `TYPE_PATTERN`, `TYPE_RUNBOOK`, `TYPE_RESOLVED_INCIDENT`

**Graceful degradation:** All methods check `isVectorStoreAvailable()` and return empty/false if no vector store. Existing pgvector JPA queries remain the fallback.

---

#### `KnowledgeBaseService.java`

Manages the Resolved Incident Knowledge Base:

| Method | Purpose |
|---|---|
| `archiveResolved(incident, summary, rootCause, steps, comments, resolvedBy)` | Upserts `ResolvedIncidentKb` entry (by incidentId or sourceTicketId to avoid duplicates) |
| `search(query, tenantId, pageable)` | ILIKE text search + optional semantic RAG search |
| `getById(id)` | Single entry lookup |
| `list(tenantId, pageable)` | Paginated list |
| `ingestPendingEmbeddings()` | `@Scheduled` job: finds `embeddingIngested=false` → calls `RagService.ingestResolvedIncident()` → sets flag to `true` |
| `toRagDocuments(entries)` | Converts KB entries to Spring AI `Document` objects |

---

#### `AuditService.java`

| Method | Purpose |
|---|---|
| `logEvent(incidentId, tenantId, traceId, agentName, eventType, payload)` | Creates and persists `AuditEvent` with SHA-256 hash |
| `getAuditTrail(incidentId)` | Returns all audit events for an incident, chronologically |
| `verifyIntegrity(auditEventId)` | Re-computes hash and compares — tamper detection |
| `getRecentEvents(tenantId, limit)` | Recent events for audit log page |

---

#### `RemediationToolRegistry.java`

Real action executor. Parses `TOOL_NAME:param1:param2:...` action strings and executes OS-level commands:

| Action String | What It Executes |
|---|---|
| `CHECK_URL:https://host/health` | HTTP GET, passes if 2xx/3xx, returns latency |
| `RESTART_SERVICE:tomcat` | Linux: `systemctl restart tomcat`; Windows: `net stop/start` |
| `RESTART_SERVICE:tomcat:CATALINA_HOME=/opt/tomcat` | `shutdown.sh` + `startup.sh` via CATALINA_HOME |
| `CLEAR_CACHE:redis` | `redis-cli FLUSHDB localhost:6379` |
| `CLEAR_CACHE:redis:host:port:pattern` | `redis-cli -h host -p port DEL matching-keys` |
| `CLEAR_CACHE:memcached:host:port` | TCP `flush_all\r\n` command |
| `RERUN_JOB:/path/script.sh` | Shell/bat script execution |
| `RERUN_JOB:taskname:windows` | Windows Task Scheduler: `schtasks /run /tn` |
| `RERUN_JOB:jobname:jenkins:http://ci/build` | Jenkins POST to build API |
| `SCALE_UP:deployment:replicas` | `kubectl scale` |
| `ROLLBACK_DEPLOY:release` | `helm rollback` or `kubectl rollout undo` |
| `DRAIN_QUEUE:redis-list:key` | `redis-cli DEL <key>` |

**Security model:** Commands are parameterised arrays passed to `ProcessBuilder` — no shell injection possible. Allowlist of permitted tool names enforced. Timeout: `mcp.tools.timeout-seconds` (default: 30s).

**Dry-run mode:** `mcp.tools.dry-run=true` → validates but does not execute.

---

#### `RemoteExecutionService.java`

Handles execution on remote hosts via configured agents. Uses `ServerCredentials` from the DB for authentication.

#### `ScriptGeneratorService.java`

Generates remediation scripts from SOP action plans using LLM. Used when `RUN_SCRIPT` action is selected.

#### `ScriptGuardrailValidator.java`

Validates generated scripts against safety rules before execution. Throws `GuardrailBlockException` for dangerous script patterns (e.g., `rm -rf`, `DROP TABLE`).

#### `SopDocumentParser.java`

Parses uploaded SOP documents (PDF, Markdown, plain text) into structured `SopProcedure` objects with action plan JSON.

#### `VaultCredentialService.java`

Integrates with HashiCorp Vault (or DB-encrypted fallback) for secure credential storage and retrieval.

---

### 7.10 HITL System

**Package:** `com.company.mcp.hitl`

#### `HitlService.java`

Central service for Human-in-the-Loop lifecycle:

| Method | Purpose |
|---|---|
| `createRequest(context)` | Builds `HitlRequest` from context, sets SLA expiry, persists, calls `notificationService.notifyPendingApproval()` |
| `approve(requestId, decidedBy, notes)` | Sets `status=APPROVED`, `decidedAt=now()`, persists, fires `notifyDecision()` |
| `reject(requestId, decidedBy, reason)` | Sets `status=REJECTED`, persists, fires notification |
| `escalate(requestId, reason)` | Forces `status=ESCALATED`, updates parent incident to `ESCALATED` |
| `prepareApprovalPackage(context)` | Serialises full AgentContext to JSONB for UI display (confidence breakdown, SOP details, KB suggestions, risk factors) |
| `slaMinutes(severity)` | P1→15, P2→30, P3→120, P4→480 (configurable via `mcp.hitl.sla.*` properties) |
| `getPendingRequests(tenantId, pageable)` | For HITL dashboard |
| `requirePending(requestId)` | Fetches + asserts status is PENDING; throws if expired/decided |

**SLA configuration properties:**
- `mcp.hitl.sla.p1-minutes` (default: 15)
- `mcp.hitl.sla.p2-minutes` (default: 30)
- `mcp.hitl.sla.p3-minutes` (default: 120)
- `mcp.hitl.sla.p4-minutes` (default: 480)

#### `HitlNotificationService.java`

| Method | Purpose |
|---|---|
| `notifyPendingApproval(request, incident)` | Sends Slack message + email to on-call team with incident summary, SOP recommendation, confidence score, and approval UI deep-link |
| `notifyDecision(request, incident)` | Notifies team of approve/reject outcome |
| `notifySlaBreachWarning(request, incident)` | Sends warning when approaching SLA deadline |

---

### 7.11 MCP Tool Framework

**Package:** `com.company.mcp.tool`

#### `McpToolRegistry.java`

Thread-safe central registry using `ConcurrentHashMap`.

| Method | Purpose |
|---|---|
| `register(definition, handler)` | Registers a tool with its metadata and execution function |
| `getHandler(toolName)` | Case-insensitive lookup of `ToolHandler` |
| `getDefinition(toolName)` | Lookup of `ToolDefinition` record |
| `isRegistered(toolName)` | Existence check |
| `allDefinitions()` | All tools — used by `/api/v1/tools` endpoint |
| `unregister(toolName)` | Called when custom tool is disabled/deleted |

**`ToolHandler`:** Functional interface `(Map<String, Object> params, boolean dryRun) → Map<String, Object>`

**`ToolDefinition`:** Immutable record: `name`, `description`, `category`, `requiredParams`, `dangerous` (requires HITL if true)

#### `McpToolExecutor.java`

Resolves tool name from registry, validates required params, executes handler, wraps result in standard response envelope.

#### `CustomToolLoader.java`

`@PostConstruct` startup loader that:
1. Queries `CustomToolRepository.findAllEnabled()`
2. For each, compiles the `scriptContent` into a `ToolHandler`
3. Registers in `McpToolRegistry`

Also listens for runtime enables/disables to dynamically register/unregister.

#### Tool Definitions (`com.company.mcp.tool.definitions`)

| Class | Category | Tools Registered |
|---|---|---|
| `InfraTools` | INFRA | `RESTART_SERVICE`, `SCALE_UP`, `ROLLBACK_DEPLOY`, `DRAIN_QUEUE` |
| `DatabaseTools` | DATABASE | `CLEAR_CACHE`, `RUN_DB_COMMAND`, `VACUUM_DB` |
| `MonitoringTools` | MONITORING | `CHECK_URL`, `CHECK_SERVICE_HEALTH`, `QUERY_PROMETHEUS` |
| `ItsmTools` | ITSM | `CREATE_TICKET`, `UPDATE_TICKET`, `CLOSE_TICKET` |
| `NotificationTools` | NOTIFICATION | `SEND_SLACK`, `SEND_EMAIL`, `PAGE_ONCALL` |

Each class is `@Component` and registers its tools in `@PostConstruct` against `McpToolRegistry`.

---

### 7.12 REST Controller Layer

**Package:** `com.company.mcp.controller`

All controllers: `@RestController`, `@RequiredArgsConstructor`, stateless, return `ResponseEntity<?>`.

#### `IncidentController.java` — `/api/v1/incidents`

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/` | Create incident, returns created entity |
| `GET` | `/{id}` | Get single incident |
| `POST` | `/{id}/process` | Trigger pipeline for incident |
| `POST` | `/{id}/retry` | Retry failed incident |
| `GET` | `/` | List incidents (filtered by status, tenantId, paginated) |
| `GET` | `/stats` | Return count-by-status statistics |

#### `HitlController.java` — `/api/v1/hitl`

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/pending` | List pending HITL requests + count |
| `POST` | `/{id}/approve` | Record approval with decided_by and notes |
| `POST` | `/{id}/reject` | Record rejection with reason |
| `GET` | `/{id}` | Get single HITL request with full approval payload |

#### `SopController.java` — `/api/v1/sops`

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/` | List SOPs (paginated, filtered by category) |
| `GET` | `/{id}` | Get single SOP with full JSON |
| `POST` | `/` | Create new SOP |
| `PUT` | `/{id}` | Update SOP |
| `POST` | `/{id}/publish` | Move SOP from DRAFT → ACTIVE |
| `DELETE` | `/{id}` | Soft-delete (set status=DEPRECATED) |

#### `KnowledgeBaseController.java` — `/api/v1/kb`

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/` | Search knowledge base (text search + optional semantic) |
| `GET` | `/{id}` | Get single KB entry |
| `PUT` | `/{id}` | Update resolution notes |

#### `AnalyticsController.java` — `/api/v1/analytics`

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/dashboard` | KPIs: total incidents, auto-resolved %, HITL %, escalated %, avg resolution time |
| `GET` | `/trends` | Daily incident count grouped by status (last 30 days) |
| `GET` | `/sop-performance` | SOP usage stats, reliability scores |
| `GET` | `/confidence-distribution` | Histogram of confidence scores |

#### `AuditController.java` — `/api/v1/audit`

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/` | Paginated audit log |
| `GET` | `/incident/{id}` | Full audit trail for one incident |
| `GET` | `/{id}/verify` | Verify record hash integrity |

#### `ToolController.java` — `/api/v1/tools`

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/` | List all registered tools |
| `POST` | `/{name}/test` | Execute tool in dry-run mode |
| `GET` | `/health` | Health check all registered tools |
| `POST` | `/custom` | Register custom tool |
| `DELETE` | `/custom/{id}` | Unregister and disable custom tool |

#### `AuthController.java` — `/api/auth`

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/login` | Authenticate, return JWT |
| `POST` | `/refresh` | Refresh JWT before expiry |
| `POST` | `/logout` | Token invalidation (client-side) |

#### `HealthController.java` — `/api/health`

Returns system health: DB connectivity, Ollama availability, vector store status, scheduler state.

---

### 7.13 Schedulers

**Package:** `com.company.mcp.scheduler`

#### `IncidentPollingScheduler.java`

**Interval:** `mcp.polling.interval-ms` (default: 60000 ms)

Polls external ticketing systems for new incidents and creates `Incident` records.

| Method | Purpose |
|---|---|
| `pollAllSources()` | Main loop: calls all 4 source pollers |
| `pollServiceNow()` | ServiceNow REST Table API — polls since `watermarks["servicenow"]` high-water mark |
| `pollFreshservice()` | Freshservice v2 Tickets API |
| `pollPrometheus()` | Alertmanager `/api/v2/alerts` |
| `pollPagerDuty()` | PagerDuty Events v2 |
| `createIncidentFromPayload(source, payload)` | Maps external payload to `Incident` entity; calls `IncidentService.createIncident()` |
| `saveWatermark(source, timestamp)` | Updates in-memory `ConcurrentHashMap<String, Instant>` high-water marks |

**Deduplication:** UNIQUE constraint on `(source_system, source_ticket_id)` in DB. Duplicate inserts are silently ignored.

#### `IncidentProcessingScheduler.java`

**Interval:** Configurable (default: 30s)

Claims and processes batches of PENDING incidents. Delegates to `IncidentService.processBatch()`.

#### `HitlTimeoutScheduler.java`

**Interval:** `mcp.hitl.timeout-check-interval-ms` (default: 60000 ms)

| Method | Purpose |
|---|---|
| `expireTimedOutRequests()` | Finds all PENDING `HitlRequest` where `expiresAt < now()` → calls `escalate()` |
| `escalate(request, now)` | Sets `request.status=EXPIRED`, updates incident to `ESCALATED`, logs SLA breach |

#### `ConfidenceCalibrationJob.java`

Periodic job that recalculates and adjusts confidence weights based on outcome data (resolved incidents vs. predicted decisions). Runs weekly.

#### `StaleJobRecoveryScheduler.java`

Recovers incidents stuck in `PROCESSING` state for longer than a configured timeout (default: 5 minutes). Resets them to `PENDING` for reprocessing. Prevents infinite processing-lock.

---

## 8. Frontend Code Documentation

### 8.1 Entry Point & Routing

**Files:** `src/main.tsx`, `src/App.tsx`

#### `main.tsx`

Standard React 18 `ReactDOM.createRoot()` entry point. Renders `<App />` wrapped in `<React.StrictMode>`.

#### `App.tsx`

Single-page application shell with manual page routing (no router library — uses `useState<Page>`):

**State:**
| State | Type | Purpose |
|---|---|---|
| `user` | `AuthUser \| null` | Current logged-in user; read from `localStorage` on init |
| `page` | `Page` | Current page enum: `overview`, `hitl`, `sop`, `kb`, `analytics`, `audit`, `health`, `tools` |
| `now` | `Date` | Live clock — updated every 1s via `setInterval` |
| `hitlCount` | `number` | Badge counter — polled every 15s from `/api/v1/hitl/pending` |
| `creating` | `boolean` | Loading state for "Create Test Incident" button |

**Effects:**
- Clock: `setInterval(() => setNow(new Date()), 1000)`
- HITL badge: polls `/api/v1/hitl/pending` every 15s when user is logged in

**Key functions:**
- `handleLogout()` — calls `clearAuth()`, resets `user` to null
- `handleCreateIncident()` — creates a random test incident via `POST /api/v1/incidents` with random severity and title; auto-triggers processing

**Routing:** Navigation sidebar maps page labels to page components via `switch(page)` in the render.

**TENANT_ID:** Hardcoded `'00000000-0000-0000-0000-000000000001'` for demo — would be read from the JWT in production.

---

### 8.2 API Service Layer

**File:** `src/services/api.ts`

JWT token lifecycle management + centralised fetch wrapper:

| Export | Type | Purpose |
|---|---|---|
| `AuthUser` | Interface | `{username, role, tenantId, token, expiresIn}` |
| `LoginResponse` | Interface | Server response shape for `/api/auth/login` |
| `setAuth(user)` | Function | Persists JWT to `localStorage ['mcp_jwt_token']` and user to `['mcp_user']` |
| `clearAuth()` | Function | Removes both localStorage keys |
| `getToken()` | Function | Returns token string or null |
| `getStoredUser()` | Function | Parses and returns `AuthUser` from localStorage |
| `isAuthenticated()` | Function | `!!getToken()` |
| `login(username, password)` | Async | `POST /api/auth/login` → validate → `setAuth()` → return `AuthUser`. Throws on 401/error. |
| `authFetch(url, options)` | Async | Wrapper around `fetch` that automatically injects `Authorization: Bearer <token>` header; auto-calls `clearAuth()` on 401 |

---

### 8.3 Pages

**Directory:** `src/pages/`

#### `LoginPage.tsx` + `LoginPage.css`

Clean login form with username/password fields. On submit: calls `login()`, on success calls `onLogin(user)` prop callback. Shows error message on failure. Terminal-aesthetic dark theme.

#### `OverviewPage.tsx` (and `Dashboard.tsx`)

Operations dashboard showing:
- Real-time incident counts by status (PENDING, PROCESSING, AUTO_RESOLVED, HITL_PENDING, ESCALATED)
- Recent incidents table with severity badges and status indicators
- Auto-refresh every 10 seconds via `setInterval`

Fetches from:
- `GET /api/v1/incidents/stats?tenantId=...`
- `GET /api/v1/incidents?tenantId=...&limit=10`

#### `HitlPage.tsx`

Hosts the `HitlApprovalQueue` component. Passes `tenantId` prop.

#### `SopPage.tsx`

SOP Library management:
- Paginated list of SOPs with title, category, reliability score, status
- SOP detail modal showing action plan JSON, rollback steps
- ACTIVE/DRAFT badge coloring

Fetches from `GET /api/v1/sops?tenantId=...`

#### `KnowledgeBasePage.tsx`

Resolved Incident Knowledge Base viewer:
- Search bar (text search) → `GET /api/v1/kb?query=...&tenantId=...`
- Result cards with resolution summary, root cause, resolved by
- Click-through to full incident details

#### `AnalyticsPage.tsx` + `AnalyticsPage.css`

KPI dashboard:
- Summary cards: total incidents, auto-resolved %, HITL %, escalated %, avg resolution time
- Data grid layout with CSS grid

Fetches from `GET /api/v1/analytics/dashboard?tenantId=...`

#### `AuditLogPage.tsx` + `AuditLogPage.css`

Paginated, filterable audit event log:
- Filter by event type
- Hash displayed (first 8 chars) for tamper-detection visibility
- Timestamp, agent name, incident ID columns

Fetches from `GET /api/v1/audit?tenantId=...&page=...`

#### `ToolsPage.tsx`

MCP Tool health monitor:
- Table of all registered tools with category, description
- `Test` button → `POST /api/v1/tools/{name}/test` (dry-run)
- Health status badges

---

### 8.4 Components

**Directory:** `src/components/`

#### `HitlApprovalQueue.tsx` + `HitlApprovalQueue.css`

The most complex component — the human approval interface:

**State:** `requests[]`, `selected HitlRequest | null`, `approveReason`, `rejectReason`, `loading`, `error`

**Polling:** Fetches PENDING requests every 15s.

**Functions:**
| Function | Purpose |
|---|---|
| `fetchRequests()` | `GET /api/v1/hitl/pending?tenantId=...` → updates `requests` |
| `handleApprove(id)` | `POST /api/v1/hitl/{id}/approve` with `{decidedBy, notes}` → refreshes list |
| `handleReject(id)` | `POST /api/v1/hitl/{id}/reject` with `{decidedBy, reason}` → refreshes list |
| `renderApprovalPayload(payload)` | Renders full context: confidence breakdown, SOP details, RAG suggestions, risk factors |

**SLA countdown:** Computes remaining time from `expiresAt`, shows color-coded badge (green → yellow → red as SLA approaches).

**Severity color coding:** P1=red, P2=orange, P3=yellow, P4=blue

#### `SopReviewInterface.tsx` + `SopReviewInterface.css`

Side panel component for reviewing SOP details before approving a HITL request:
- Displays SOP title, version, reliability score
- Renders action plan steps with tool names and parameters
- Shows rollback steps
- Shows KB suggested resolution from RAG

---

## 9. Configuration Reference

**File:** `src/main/resources/application.yml`

### Database
```yaml
spring.datasource:
  url:      jdbc:postgresql://localhost:5432/mcp_db
  username: mcp_user
  password: ${DB_PASSWORD:changeme}
  hikari.maximum-pool-size: 20
  hikari.minimum-idle: 5
```

### LLM Providers
```yaml
# Default: Ollama (local, no API key needed)
spring.ai.ollama:
  base-url: ${OLLAMA_BASE_URL:http://localhost:11434}
  chat.options.model: ${OLLAMA_CHAT_MODEL:phi4}          # phi4 | llama3.2 | mistral
  embedding.options.model: ${OLLAMA_EMBED_MODEL:nomic-embed-text}

# OpenAI (set OPENAI_API_KEY env var)
spring.ai.openai:
  api-key: ${OPENAI_API_KEY:}
  chat.options.model: ${OPENAI_CHAT_MODEL:gpt-4o}

# Anthropic (set ANTHROPIC_API_KEY)
# Vertex AI Gemini (set GOOGLE credentials)
```

### MCP Business Logic
| Property | Default | Purpose |
|---|---|---|
| `mcp.confidence.auto-resolve-threshold` | `0.95` | Score threshold for AUTO_RESOLVE |
| `mcp.polling.enabled` | `true` | Enable/disable external source polling |
| `mcp.polling.interval-ms` | `60000` | Polling interval |
| `mcp.polling.default-tenant-id` | (UUID) | Default tenant for polled incidents |
| `mcp.hitl.sla.p1-minutes` | `15` | P1 HITL SLA timeout |
| `mcp.hitl.sla.p2-minutes` | `30` | P2 HITL SLA timeout |
| `mcp.hitl.sla.p3-minutes` | `120` | P3 HITL SLA timeout |
| `mcp.hitl.sla.p4-minutes` | `480` | P4 HITL SLA timeout |
| `mcp.hitl.timeout-check-interval-ms` | `60000` | SLA check frequency |
| `mcp.rag.top-k` | `5` | Default RAG retrieval count |
| `mcp.rag.similarity-threshold` | `0.6` | Minimum similarity for RAG results |
| `mcp.rag.enabled` | `true` | Enable/disable RAG |
| `mcp.tools.timeout-seconds` | `30` | Tool execution timeout |
| `mcp.tools.dry-run` | `false` | Global dry-run mode for all tools |
| `mcp.tools.url-check-timeout-ms` | `5000` | URL health check timeout |
| `mcp.llm.provider` | `ollama` | Active LLM provider |

### JWT Security
| Property | Default | Purpose |
|---|---|---|
| `mcp.jwt.secret` | (env: `JWT_SECRET`) | HS512 signing secret |
| `mcp.jwt.expiration-ms` | `86400000` | Token validity (24h) |

---

## 10. Data Flow Scenarios

### Scenario A: Fully Automated Resolution (Happy Path)

```
1. Prometheus fires alert: "CPU > 90% on api-gateway"
2. IncidentPollingScheduler.pollPrometheus() creates Incident{severity=P2, status=PENDING}
3. IncidentProcessingScheduler claims it (SKIP LOCKED)
4. AgentPipeline.processIncident() → OrchestratorAgent
5. [Parallel] ClassifierAgent → category=INFRASTRUCTURE/RESOURCE, confidence=0.80
   [Parallel] PatternMatcherAgent → matches "High CPU" pattern, similarity=0.87
   [Parallel] SopRankerAgent → matches "CPU Scale-Up SOP", reliability=0.92
             [RAG] Retrieves 3 similar KB entries showing past CPU incidents resolved by scaling
6. ConfidenceScorerAgent:
   - patternSim=0.87×0.35 = 0.305
   - historical=0.90×0.25 = 0.225
   - sopReliability=0.92×0.20 = 0.184
   - sysHealth=0.80×0.15 = 0.120
   - No risk penalty (P2, no prod blast)
   - finalScore = 0.834 → but P2 with riskScore<0.50 → passes threshold check
   Actually finalScore = 0.834 < 0.95 → HITL_REQUIRED
   (or if tenant config has lower threshold → AUTO_RESOLVE)
7. [If AUTO_RESOLVE] GuardrailsAgent: all 9 layers PASS
8. ActionExecutorAgent: dry-run SCALE_UP → success → execute SCALE_UP:api-gateway:3
9. AuditAgent: writes audit event, no HITL created
10. Status → AUTO_RESOLVED
11. KnowledgeBaseService.archiveResolved() archives to KB
```

### Scenario B: HITL Required — Human Approves

```
1–6. Same as above, but finalScore = 0.83 → HITL_REQUIRED
7. AuditAgent creates HitlRequest{expiresAt = now + 30min (P2)}
8. HitlNotificationService sends Slack alert to #on-call
9. Human opens HitlPage → sees request with full approval package:
   - SOP recommendation + action plan
   - RAG-surfaced similar past incidents
   - Confidence breakdown: 83%
   - Risk factors: none high
10. Human clicks "Approve" with notes "Confirmed, proceed with scale-up"
11. HitlController.approve() → HitlService.approve()
    - HitlRequest.status = APPROVED
    - decidedBy = "john.doe@company.com"
12. HitlService re-runs ActionExecutorAgent with decidedByHuman=true
13. ActionExecutorAgent executes SCALE_UP:api-gateway:3
14. Status → HITL_RESOLVED
```

### Scenario C: Guardrail Block (Safety Gate Triggered)

```
1. Incident: "Database cleanup required on prod-db-primary"
2. Pipeline runs...
3. ClassifierAgent → DATABASE/MAINTENANCE
4. SopRankerAgent → matches "DB Cleanup SOP" with action: DRAIN_QUEUE:prod-db
5. ConfidenceScorerAgent → score=0.97 → AUTO_RESOLVE
6. RiskEvaluatorAgent:
   - Layer 1 (Production): HIGH risk (prod-db-primary)
   - Layer 3 (Data sensitivity): HIGH (database = sensitive data)
   - riskScore = 0.82
   - P1 override → ESCALATE_TO_HUMAN
7. GuardrailsAgent: context.decision already ESCALATE_TO_HUMAN → canExecute=false → SKIP
8. ActionExecutorAgent → canExecute=false (not AUTO_RESOLVE) → SKIP
9. AuditAgent → creates HITL with escalation reason
10. Status → ESCALATED
```

### Scenario D: HITL SLA Timeout — Auto-Escalation

```
1. Incident created with severity P1
2. ConfidenceScorerAgent → HITL_REQUIRED
3. HITL request created with expiresAt = now + 15min
4. 15 minutes pass — on-call team doesn't respond
5. HitlTimeoutScheduler.expireTimedOutRequests() runs (every 60s)
6. Finds request where expiresAt < now AND status = PENDING
7. request.status → EXPIRED
8. incident.status → ESCALATED, finalDecision → ESCALATE_TO_HUMAN
9. SLA breach logged to Prometheus counter (and Slack alert if configured)
```

---

*End of Documentation*
