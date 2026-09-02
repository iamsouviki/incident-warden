# Production Readiness Remediation Query

Audit and fix the current Incident Warden codebase for a single-organization production deployment.
This document is an implementation query for an evaluator or coding agent. Do not modify the code
while evaluating unless explicitly instructed to implement the fixes.

The deployment has one organization. Remove the tenant concept completely from the application,
database schema, migrations, JWT claims, repositories, services, controllers, frontend state, API
contracts, tests, documentation, and configuration. Do not replace it with another multi-tenant
abstraction. Preserve user-level authorization and all HITL safety controls.

## Required fixes

1. Remove insecure production defaults:
   - Require `DB_PASSWORD`, integration credentials, JWT secret, and executor credentials in non-local profiles.
   - Remove the seeded `admin` password from production migrations and documentation.
   - Keep development defaults only under an explicitly local profile.
   - When creating or resetting a user, use the username as the initial password, return it only
     once to the authorized administrator, and require a password change at first sign-in.

2. Harden outbound integrations:
   - Ensure ServiceNow, Freshservice, Jira, executor, and LLM clients all use mandatory connect and read timeouts.
   - Add bounded retry policy with exponential backoff only for safe/idempotent requests.
   - Never report success when an integration is disabled or unreachable.
   - Add tests for timeout, authentication failure, rate limiting, malformed responses, and partial failure.

3. Make synchronization reliable:
   - Remove contradictory configuration comments.
   - Ensure scheduled synchronization runs exactly once per deployment interval using the existing distributed lock.
   - Make imports transactional per batch or clearly report partial completion.
   - Add idempotency and duplicate handling tests.

4. Strengthen secrets and privacy:
   - Review every controller response, DTO, log, exception, audit payload, trace, and frontend state for passwords, tokens, API keys, credentials, emails, IPs, and internal hostnames.
   - Replace regex-only masking where structured secret handling is practical.
   - Ensure integration settings return presence/status metadata, never secret values.
   - Add negative tests proving secrets cannot appear in logs or API responses.

5. Preserve and verify HITL safety:
   - Require an approved plan, exact plan hash, successful dry run, and authorized executor for real execution.
   - Ensure blocked plans expose actionable correction fields in the UI.
   - Ensure post-execution source-ticket updates distinguish success, failure, and not-configured states.
   - Add tests for replay, stale approval, changed script, rejected plan, failed dry run, and executor timeout.

6. Harden RAG and web guidance:
   - Remove web search completely from backend services, controllers, configuration, frontend,
     prompts, tests, documentation, and dependencies. No fallback web-search path may remain.
   - Keep all SOP and incident retrieval scoped to the single configured workspace after tenant
     removal.
   - Add graph-enhanced RAG alongside vector and keyword retrieval. Use the incident knowledge
     graph to expand related services, devices, dependencies, recurring incidents, SOPs, and
     remediation history before final ranking.
   - Make graph retrieval bounded, deterministic, explainable, and resistant to unrelated-node
     expansion. Include graph provenance in the answer and audit record.
   - Reduce unnecessary incident context and chunk overlap.
   - Add source attribution, bounded response size, graph/vector fusion tests, retrieval-quality
     tests, and prompt-injection tests against incident text, SOP text, graph labels, and metadata.

7. Verify UI/backend parity and remove unsupported features:
   - Enumerate every backend route, request field, response field, scheduled job, configuration
     value, and database feature.
   - Map each one to an actual UI flow or an explicitly documented machine-to-machine contract.
   - Remove backend endpoints, DTO fields, database columns, navigation routes, components, and
     configuration that have no supported UI or approved integration consumer.
   - Verify the full flows end to end: login, first-password change, incident intake, CSV/XLSX
     import, incident search, SOP ingestion, graph retrieval, plan creation, missing-parameter
     collection, approval/rejection, dry run, execution, live logs, failure escalation, and
     source-ticket update.
   - Add contract tests proving frontend payloads and backend responses remain compatible.

8. Complete production observability:
   - Propagate request and correlation IDs from HTTP entrypoints through logs, audit events, outbound calls, and frontend error reports.
   - Add metrics for intake, RAG calls, LLM failures, plan creation, approvals, dry runs, executions, guardrail blocks, source-ticket updates, and provider usage/cost.
   - Configure separate liveness and readiness probes.
   - Do not expose health details publicly.
   - Set tracing sampling appropriate for production and document retention/redaction.

9. Strengthen guardrails:
   - Enforce strict scope checks before every LLM call and reject non-technical or unrelated
     requests deterministically.
   - Treat incident descriptions, SOP content, graph attributes, retrieved documents, tool output,
     and user-supplied parameters as untrusted input.
   - Test defenses against prompt injection, instruction smuggling, secret extraction, tool
     hallucination, action-key manipulation, shell metacharacters, path traversal, SSRF, unsafe
     targets, destructive commands, and oversized payloads.
   - Ensure web search is absent and cannot be re-enabled through a configuration flag.
   - Require an approved allowlisted action, validated parameters, target validation, clean guardrail
     scan, exact plan hash, authorized reviewer, successful dry run, and executor authorization
     before mutation.
   - Ensure guardrail failures are fail-closed, auditable, user-visible, and recoverable without
     silently changing incident state.
   - Add negative tests for every guardrail and verify no secret or raw prompt is returned to the UI.

10. Improve delivery controls:
   - Run CI against PostgreSQL with pgvector and Liquibase migrations.
   - Add dependency vulnerability scanning, secret scanning, SAST, container scanning, and migration tests.
   - Add frontend tests for chat planning, dynamic parameters, HITL approval, execution states, and error handling.
   - Keep `mvn test`, frontend typecheck, frontend build, and packaging green.

11. Make all chat output production-safe and user-friendly:
   - Define one shared presentation contract for assistant text, errors, escalation messages, tool
     plans, missing-parameter forms, execution stages, logs, and source-system update results.
   - Render supported Markdown through a maintained sanitizer/parser rather than hand-written HTML
     replacement. Prove that model output, incident text, SOP text, graph labels, tool output, and
     API errors cannot inject HTML, scripts, links, or unsafe attributes.
   - Preserve code blocks, lists, tables, line breaks, emphasis, citations, provenance, and status
     labels consistently across chat, incident pages, HITL review, and execution history.
   - Never expose raw exception messages, stack traces, provider responses, SQL, credentials,
     internal URLs, or implementation details to users. Map failures to stable error codes and
     actionable operator messages while retaining sanitized diagnostic context in logs.
   - Ensure loading, retry, timeout, partial-result, cancelled, rejected, blocked, dry-run, failed,
     and completed states are explicit and cannot be confused with success.
   - Make dynamic parameter forms validate type, format, length, allowed values, target safety, and
     required fields both client-side and server-side.
   - Add accessibility tests for keyboard navigation, focus management, screen-reader labels,
     live-region announcements, color contrast, expandable logs, and error summaries.
   - Add responsive/mobile tests, localization-safe date/time formatting, bounded message length,
     copy/download controls with secret redaction, and protection against browser storage leakage.
   - Add snapshot and end-to-end tests covering every assistant response shape and every remediation
     state transition.

## Acceptance criteria

- No high-severity findings remain.
- No tenant terminology, tenant columns, tenant claims, tenant parameters, or tenant-scoped
  repository methods remain.
- No web-search implementation, configuration, prompt text, dependency, or documentation remains.
- Initial user and reset passwords equal the username, are forced to change, and are never logged.
- Every retained backend capability has a verified UI or approved machine consumer.
- Graph-enhanced RAG improves relevance without unbounded graph traversal or cross-domain noise.
- A disabled or unavailable dependency cannot produce a false success.
- No production credential is committed, logged, returned to the browser, or stored in plaintext.
- Every real remediation is demonstrably approval-gated and hash-pinned.
- PostgreSQL/pgvector migrations and container startup pass in CI.
- The test suite covers failure paths, not only successful happy paths.
- Guardrail tests demonstrate fail-closed behavior for prompt injection, unsafe tools, unsafe targets,
  secrets, and malformed input.
- Observability tests demonstrate correlation across HTTP, persistence, LLM/RAG, HITL, executor,
  and source-system update flows without sensitive data leakage.
- Update `docs/enterprise-readiness.md` with the final evidence, commands, and residual risks.

Implement the smallest coherent set of changes, preserve existing user changes, and report each
modified file, test command, result, and any remaining blocker.
