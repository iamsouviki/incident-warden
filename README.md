# README - MCP Incident Automation Platform

## Overview

**MCP Incident Automation** is a production-grade AI-powered IT incident automation platform built with:
- **Java 21** with Spring Boot 3.3+
- **PostgreSQL 16** with pgvector for semantic search
- **Spring AI** for LLM integration — supports **OpenAI, Anthropic (Claude), Google Gemini, Ollama**
- **Redis** for rate limiting and loop detection
- **Keycloak** for OAuth2/OIDC security
- **React 18** for the frontend dashboard

---

## 🤖 LLM Provider Configuration (Enterprise Customization)

The platform supports **5 provider options**: 4 first-class providers via their native
Spring AI Maven starters, plus a `custom` option for any OpenAI-compatible endpoint.

| `mcp.llm.provider` | Integration | Config block | Embeddings |
|---|---|---|---|
| `ollama` *(default)* | Spring AI Ollama starter | `spring.ai.ollama.*` | Native (`OllamaEmbeddingModel`) |
| `openai` | Spring AI OpenAI starter | `spring.ai.openai.*` | Native (`OpenAiEmbeddingModel`) |
| `anthropic` | Spring AI Anthropic starter | `spring.ai.anthropic.*` | OpenAI fallback |
| `gemini` | Spring AI Vertex AI starter | `spring.ai.vertex.ai.gemini.*` | OpenAI fallback |
| `custom` | Programmatic OpenAI-compatible client | `mcp.llm.custom.*` | From custom endpoint |

### How it works

**Known providers** (ollama / openai / anthropic / gemini) use the Spring AI auto-configured
beans from their Maven starters. You configure them in the `spring.ai.*` section of
`application.yml`. The platform selects the correct bean based on `mcp.llm.provider`.

**Custom provider** builds an OpenAI-compatible HTTP client at startup using only the
`mcp.llm.custom.api-url` and `mcp.llm.custom.api-key` you supply. No extra Maven
dependency is needed — it reuses the `spring-ai-openai-spring-boot-starter` internally.

---

### Step 1 — Set `mcp.llm.provider`

```yaml
# src/main/resources/application.yml
mcp:
  llm:
    provider: ollama   # ← change to: openai | anthropic | gemini | custom
```

### Step 2 — Fill the matching `spring.ai.*` block (known providers only)

#### Ollama (default)

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434  # your Ollama server
      chat.options.model: llama3.2      # mistral | phi3 | gemma2 | qwen2.5 …
      embedding.options.model: nomic-embed-text
```

Pull models once on the Ollama server:
```bash
ollama pull llama3.2 && ollama pull nomic-embed-text
```

---

#### OpenAI

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat.options.model: gpt-4o                   # gpt-4-turbo | gpt-3.5-turbo
      embedding.options.model: text-embedding-3-small
```

---

#### Anthropic / Claude

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat.options.model: claude-3-5-sonnet-20241022  # claude-3-opus | claude-3-haiku
```

> Anthropic has no embeddings API. The platform automatically falls back to the
> `OpenAiEmbeddingModel` for pgvector RAG — add `spring.ai.openai.api-key` too.

---

#### Google Gemini (Vertex AI)

```yaml
spring:
  ai:
    vertex.ai.gemini:
      project-id: my-gcp-project   # your GCP project
      location:   us-central1
      chat.options.model: gemini-1.5-pro  # gemini-1.5-flash | gemini-1.0-pro
```

Set GCP credentials via environment variable:
```bash
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
```

> Gemini has no embeddings API. The platform falls back to `OpenAiEmbeddingModel` —
> add `spring.ai.openai.api-key` too for RAG embeddings.

---

#### Custom / Other OpenAI-compatible endpoint

Use this for: LM Studio, vLLM, Azure OpenAI, LocalAI, Groq, Together AI, or any
server that speaks the OpenAI API format.

```yaml
mcp:
  llm:
    provider: custom
    custom:
      api-url:     http://my-vllm-server:8000/v1       # required
      api-key:     my-secret-token                     # required (any non-empty value if no auth)
      chat-model:  meta-llama/Llama-3.1-70B-Instruct   # required
      embed-model: nomic-embed-text                    # optional; leave blank to skip RAG

# No spring.ai.* block needed for the custom provider.
```

Azure OpenAI example:
```yaml
mcp:
  llm:
    provider: custom
    custom:
      api-url:     https://<resource>.openai.azure.com
      api-key:     <azure-api-key>
      chat-model:  gpt-4o          # your Azure deployment name
      embed-model: text-embedding-3-small
```

---

### Step 3 — Rebuild and redeploy

```bash
# Local
./mvnw clean package -DskipTests
java -jar target/incident-automation-1.0.0.jar

# Docker
docker-compose up --build
```

No environment variables are required beyond what the chosen provider needs
(e.g. `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`).
Those can stay as env vars in production — never commit secrets to YAML.

---


## Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21+ (for local development)
- Maven 3.9+
- Node.js 18+ (for frontend)
- LLM credentials matching `mcp.llm.provider` in `application.yml`
  (default is **Ollama** — no API key needed)

### Run with Docker Compose

```bash
# Clone the repository
cd mcp-incident-automation

# Provider is already set to Ollama in application.yml (no API key needed).
# If you changed provider to openai/anthropic/gemini, supply the secret here:
# export OPENAI_API_KEY=sk-...
# export ANTHROPIC_API_KEY=sk-ant-...
# export GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json

# Start all services
docker-compose up -d

# Wait for services to be healthy (about 30 seconds)
docker-compose ps
```

### Access the Services

| Service | URL | Credentials |
|---------|-----|-------------|
| MCP API | http://localhost:8080 | |
| Keycloak | http://localhost:8180 | admin/admin |
| Prometheus | http://localhost:9090 | |
| Grafana | http://localhost:3000 | admin/admin |
| Jaeger | http://localhost:16686 | |
| Kibana | http://localhost:5601 | |

## API Examples

### Create an Incident

```bash
curl -X POST http://localhost:8080/api/v1/incidents \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "title": "Database connection timeout",
    "description": "Connection pool exhausted",
    "category": "Database",
    "severity": "P2",
    "sourceSystem": "servicenow",
    "sourceTicketId": "INC0123456"
  }'
```

### List Incidents

```bash
curl -X GET http://localhost:8080/api/v1/incidents?status=PENDING \
  -H "Authorization: Bearer <token>"
```

### Get Incident Status

```bash
curl -X GET http://localhost:8080/api/v1/incidents/{incidentId} \
  -H "Authorization: Bearer <token>"
```

## Architecture

### Core Components

1. **Incident Polling Scheduler** (60s interval)
   - Polls ServiceNow, Freshservice, Prometheus, Dynatrace
   - Creates incidents in queue

2. **Incident Processing Pipeline**
   - Classification Agent (rule-based + LLM)
   - Pattern Matcher Agent (pgvector similarity)
   - SOP Matcher Agent (retrieves procedures)
   - Confidence Scorer Agent (multi-factor scoring)
   - Risk Evaluator Agent (blast radius, rollback analysis)
   - Guardrails Service (9-layer validation)

3. **Decision Router**
   - AUTO_RESOLVE: confidence = 100% (after guardrails)
   - HITL_REQUIRED: 80-99% confidence → human approval
   - ESCALATE: < 80% confidence → on-call engineer

4. **Action Executor**
   - Executes MCP tools (Kubernetes, ArgoCD, Terraform, etc.)
   - Dry-run simulation before execution
   - Rollback capability

5. **Audit Trail**
   - SHA-256 tamper-evident records
   - Row-level security for multi-tenancy
   - Immutable append-only design

### Database Schema

Key tables:
- `incidents` - Main incident queue (job-based processing)
- `incident_patterns` - Learned patterns with embeddings
- `sop_procedures` - Standard operating procedures
- `confidence_logs` - Scoring history
- `hitl_requests` - Human-in-the-loop approvals
- `audit_events` - Immutable decision log
- `action_execution_log` - Tool execution history

## Guardrails (9 Layers)

The system implements comprehensive safety checks:

1. **Role Authorization** - RBAC validation
2. **Context Schema** - JSON schema validation
3. **Prompt Injection Guard** - Regex + Bloom filter detection
4. **Blast Radius Gate** - Maximum 40% impact threshold
5. **Dry Run Simulator** - Pre-execution validation
6. **Rate Limiter** - Throttling thresholds
7. **Loop Detector** - Circular dependency prevention
8. **Circuit Breaker** - Resilience4J integration
9. **Output Schema** - Result validation

## Configuration

### Tenant Thresholds

Per-tenant settings in database:
- `autoResolveThreshold` - Score for auto-resolution (default 100%)
- `hitlThreshold` - Score for HITL routing (default 80%)
- `maxBlastRadiusPct` - Maximum blast radius (default 40%)
- `allowP1AutoResolve` - P1 auto-resolution policy (default false)

### Confidence Scoring Formula

```
score = (0.35 * patternSimilarity) 
      + (0.25 * historicalSuccess)
      + (0.20 * sopReliability)
      + (0.15 * systemHealth)
      - riskPenalty
```

## Monitoring

### Key Metrics

- `mcp.incidents.total` - Total incidents processed
- `mcp.incidents.pending.count` - Current pending queue depth
- `mcp.automation.rate` - Percentage auto-resolved
- `mcp.hitl.pending.count` - HITL approvals waiting
- `mcp.processing.duration` - End-to-end processing time
- `mcp.tool.calls.total` - Tool execution count
- `mcp.circuit.breaker.state` - Circuit breaker status

### Dashboards

Grafana dashboards available at http://localhost:3000:
- Incident Processing KPIs
- Confidence Score Distribution
- Tool Execution Performance
- Error Rate Tracking

## Development

### Build Locally

```bash
# Build with Maven
mvn clean package

# Run with Spring Boot
mvn spring-boot:run
```

### Testing

```bash
# Run all tests
mvn test

# Run integration tests
mvn verify

# Run specific test
mvn test -Dtest=ConfidenceScorerAgentTest
```

## Deployment

### Kubernetes (Phase 2)

```bash
# Install Helm chart
helm install mcp ./k8s/helm-chart \
  --namespace incidents \
  --values values.yaml

# Check deployment
kubectl get pods -n incidents
```

## Support & Documentation

- API Docs: http://localhost:8080/swagger-ui.html
- Keycloak Admin: http://localhost:8180/admin
- Database: postgres://mcp_user@localhost:5432/mcp_db
- Logs: ELK Stack at http://localhost:5601

## License

© 2024 MCP Incident Automation. All Rights Reserved.
