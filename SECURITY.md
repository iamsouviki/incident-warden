# Security policy

This platform generates remediation scripts and, once a human approves them, dispatches them to an
agent that runs them on your infrastructure. That makes it a security-relevant tool. Please read
this before deploying it anywhere that matters.

## Reporting a vulnerability

Use GitHub's private reporting: **Security → Advisories → Report a vulnerability** on
<https://github.com/iamsouviki/incident-warden>.

Please do not open a public issue for anything exploitable. Include the endpoint or file, the role
you were authenticated as (or none), and the smallest request that reproduces it.

This is a small project, so expect a best-effort response rather than a contractual SLA. Fixes land
on `main`; there is no maintained backport branch yet.

## What the design guarantees

These are the invariants the code enforces, and the ones a security review should be pointed at
first. Each is described in more detail in the [README](README.md#the-invariants).

- **The control plane never runs a shell.** There is no `ProcessBuilder`, `Runtime.exec` or SSH
  client in this application. Approved scripts are POSTed to a separate executor agent
  (`mcp.executor.url`). With that URL unset, "execute" records a simulation.
- **Approval pins a SHA-256 hash** of the script text. Changing one character after approval makes
  dispatch fail — you cannot approve version A and run version B.
- **Guardrails are re-scanned at dispatch**, not only at generation, so adding a blocked term today
  stops a plan that was approved yesterday.
- **`POST /api/v1/scripts/execute` with `dryRun:false` returns 409.** There is no "just run it"
  endpoint reachable from the browser.
- **A mutating plan with no named host is refused.** Nothing runs on a guessed machine.
- **There is no unattended execution and no switch that enables it.** Every dispatch is a person
  reading that specific script for that specific host and approving it, including the hundredth
  time. The per-store approval-inheritance path that used to exist is deleted, not disabled. The one
  `@Scheduled` job in the application pulls tickets *in* from ITSM; it creates rows, never plans and
  never executes.

## Secrets

- **The LLM provider key is read from the environment only** (`MCP_LLM_API_KEY`) — never written by
  the UI, never persisted, never returned by `GET /api/v1/ai/config`. The row it used to live in is
  deleted by the baseline migration. The cost of this is real: switching to a provider that needs a
  key requires a restart with the variable set.
- **The executor bearer token** (`mcp.executor.token`) is a property, not a table row.
- **Target-host credentials are never here.** `connection_method` records *how* to reach a host
  (`SSH`/`WINRM`/`AGENT`); the secret for that method lives with the executor agent on the target
  network.
- **Login passwords are BCrypt hashes.** Everything else configurable — models, thresholds, user
  accounts and roles, SOP procedures, notification recipients — is a database row edited from the UI.
- `MCP_JWT_SECRET` (≥32 bytes) is required by every profile except `local`; the application refuses
  to start without it. `local` ships a committed dev key so a checkout runs with no setup.
- ⚠️ **Known violation: legacy ITSM secrets remain recoverable from the database.**
  The settings endpoint ignores submitted credential fields, but `IntegrationManagerService.getSecret()`
  still reads and Base64-decodes legacy `servicenow_password`, `freshservice_api_key` and
  `jira_api_token` rows. Base64 is not encryption. If such rows exist, **treat the database as holding
  live credentials** and rotate them. Track fixes in the repository issue tracker.

## Known limitations — fix these before production

Stated plainly rather than buried, because a reader deploying this needs them. The full list, with
`file:line` and an ordering, is maintained in the repository issue tracker.

- **Legacy ITSM integration credentials remain in the database** — see the ⚠️ above. Highest severity.
- **The full Compose profile is development-only.** It ships default-style Postgres/Redis credentials,
  Vault dev mode and Keycloak `start-dev`; replace all credentials and harden or remove those services
  before any network exposure.
- **Prompts are logged.** `org.springframework.ai` is at `DEBUG` in the default profile, which writes
  prompt and response payloads — incident text, host names, SOP excerpts — into
  `logs/incident-warden.log`. That file has no rotation policy. Turn it down before handling real
  ticket data.
- **The `local` profile ships a committed dev JWT signing key** and turns separation of duties off
  so a single account can both request and approve a plan. It is a demo profile. Do not run it
  anywhere reachable.
- **Rate limits are in-memory and per instance** (`RateLimiterService`: 10 logins/min, 20 LLM
  calls/min). Behind a load balancer the effective budget multiplies by the replica count, and the
  keying map is never evicted — attacker-supplied usernames grow it without bound.
- **Refresh tokens are held by the browser, not in an httpOnly cookie**, and there is no `jti`
  denylist, so a token cannot be revoked before it expires. Access tokens last 30 minutes, refresh
  tokens 3 hours; disabling an account does not end a session already in flight. The frontend has no
  CSP, so the `localStorage` exposure is unmitigated.
- **CORS defaults to `http://localhost:*`** (`setAllowedOriginPatterns`). Set an explicit origin for
  any real deployment. `csrf.disable()` is correct only while the credential is a `Bearer` header —
  if a cookie is introduced, CSRF protection must return in the same commit.
- **The three ITSM HTTP clients have no timeouts.** A stalled ServiceNow/Freshservice/Jira endpoint
  holds a request thread indefinitely, and `server.tomcat.connection-timeout` is `-1`.
- **Spring Boot 3.2.12 is past OSS support**, and dependency policy must be reviewed before production.
- **The executor agent is the actual blast radius.** `scripts/dev-executor.mjs` deliberately runs
  nothing. Whatever you replace it with holds the credentials and does the work, so its sandboxing,
  its allowlist and its logging are your last line of defence — not this repo's. The dispatch payload
  names a target host but carries no proof the caller is entitled to it: scope the executor's
  credentials to the hosts it is permitted to touch.

## Scope

In scope: authentication and authorisation bypass, guardrail or hash-pinning bypass, anything that
gets a script dispatched without an approval, secret disclosure, injection into the generated script,
one user reading or writing another user's chat history.

Out of scope: findings that require the `local` demo profile, a weak password a user chose for
their own account after the forced first-sign-in change, and missing hardening already listed
above.
