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
  (`mcp.autonomy.executor-url`). With that URL unset, "execute" records a simulation.
- **Approval pins a SHA-256 hash** of the script text. Changing one character after approval makes
  dispatch fail — you cannot approve version A and run version B.
- **Guardrails are re-scanned at dispatch**, not only at generation, so adding a blocked term today
  stops a plan that was approved yesterday.
- **`POST /api/v1/scripts/execute` with `dryRun:false` returns 409.** There is no "just run it"
  endpoint reachable from the browser.
- **A mutating plan with no named host is refused.** Nothing runs on a guessed machine.
- **Unattended execution is a per-store inheritance of a human approval**, not a confidence score
  deciding for itself: same store, same tool, already approved and already succeeded, restart or
  read-only, clean guardrail scan, not a P1.

## Secrets

- **No integration credential is stored in the database.** `connection_method` records *how* to
  reach a host (`SSH`/`WINRM`/`AGENT`); the secret for that method lives with the executor agent on
  the target network.
- **The LLM provider key is read from the environment only** (`MCP_LLM_API_KEY`) — never written by
  the UI, never persisted, never returned by `GET /api/v1/ai/config`. Migration `1.16` deletes the
  row where it used to live. The cost of this is real: switching to a provider that needs a key
  requires a restart with the variable set.
- **The executor bearer token** (`mcp.autonomy.executor-token`) is a property, not a table row.
- **Login passwords are BCrypt hashes.** Everything else configurable — models, thresholds, teams,
  SOP procedures, notification recipients — is a database row edited from the UI.
- `MCP_JWT_SECRET` (≥32 bytes) is required by every profile except `local`, which refuses to start
  without it.

## Known limitations — fix these before production

Stated plainly rather than buried, because a reader deploying this needs them:

- **There is no self-service change-password screen yet.** New accounts get the password the server
  issues at creation (`MCP_DEFAULT_PASSWORD` if you set it, otherwise a value `BootstrapPassword`
  generates per boot and shows once), and an admin resets it over the API. Set
  `MCP_DEFAULT_PASSWORD` in any deployment you expect to be able to log back into — a generated
  password is different after every restart.
- **The `local` profile ships a committed dev JWT signing key** and turns separation of duties off
  so a single account can both request and approve a plan. It is a demo profile. Do not run it
  anywhere reachable.
- **Rate limits are in-memory and per instance** (`RateLimiterService`: 10 logins/min, 20 LLM
  calls/min). Behind a load balancer the effective budget multiplies by the replica count.
- **Refresh tokens are held by the browser, not in an httpOnly cookie**, and there is no `jti`
  denylist, so a refresh token cannot be revoked before it expires.
- **CORS defaults to `http://localhost:*`** (`setAllowedOriginPatterns`). Set an explicit origin for
  any real deployment.
- **The executor agent is the actual blast radius.** `scripts/dev-executor.mjs` deliberately runs
  nothing. Whatever you replace it with holds the credentials and does the work, so its sandboxing,
  its allowlist and its logging are your last line of defence — not this repo's.

## Scope

In scope: authentication and authorisation bypass, guardrail or hash-pinning bypass, anything that
gets a script dispatched without an approval, secret disclosure, injection into the generated script,
tenant data leaking across tenants.

Out of scope: findings that require the `local` demo profile, a weak password an operator chose
through `MCP_DEFAULT_PASSWORD`, and missing hardening already listed above.
