# Contributing

Thanks for looking. This project is a human-in-the-loop incident automation platform, which means a
bad patch can end with a script running on someone's production server. The rules below exist for
that reason and not for ceremony.

## Getting it running

You need **JDK 21**, **Maven**, **Node 20+**, and **PostgreSQL 16 with the `vector` extension** on
`localhost:5432` (database `mcp_db`, user `mcp_user`). Postgres + pgvector is the only supported
store — the old H2 + fake vector store were removed because a fake `similaritySearch` returned every
document unranked and made retrieval look like it worked while proving nothing.

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true
```

```bash
npm run dev --prefix frontend
```

Then <http://localhost:5173>, sign in as `admin`. Set `MCP_DEFAULT_PASSWORD` before you start the
backend, or read the password it generated out of its own startup log (`grep BOOTSTRAP
logs/backend-local.log`). The two dev stand-ins
(`scripts/dev-executor.mjs`, `scripts/dev-smtp.mjs`) make the execution and notification legs
observable without anything leaving your machine — see [Quick start](README.md#quick-start).

## Before you open a PR

```bash
mvn -o test
```

```bash
npx tsc --noEmit --project frontend
```

Both must pass. `mvn -q` hides the test summary, so if you use it, read
`target/surefire-reports/*.xml` instead of trusting a silent exit code.

## Things that must not break

If your change touches the remediation path, re-read [the invariants](README.md#the-invariants)
first. In particular, a PR will not be merged if it:

- introduces `ProcessBuilder`, `Runtime.exec`, or an SSH/WinRM client into this application — the
  control plane dispatches to an executor agent and does not run commands itself;
- lets a script run without a matching approved SHA-256 hash;
- skips the guardrail re-scan at dispatch;
- adds a path where a mutating action runs on a host the platform inferred rather than confirmed;
- widens unattended execution beyond "same store, same tool, already human-approved, already
  succeeded, restart-or-read-only, clean scan, not P1";
- stores an integration credential, provider API key, or token in the database, or returns one from
  an API;
- adds a configuration knob that can only be set by editing a properties file when it is something
  an operator needs to change at runtime. Operator-facing settings belong in the UI.

## Code style

Match the file you are editing. Two habits are consistent across this codebase and worth keeping:

- **Comments explain why, not what.** The interesting comments here record the failure that caused
  the code to look the way it does — a cached provider timeout that broke a question permanently, a
  real Kafka ticket refused as out of scope. That is the part a future reader cannot recover from the
  diff.
- **A deliberate shortcut is labelled.** Where something cuts a real corner with a known ceiling, it
  carries a `ponytail:` comment naming the ceiling and the upgrade path — for example the in-memory
  rate limiter noting that the deques move to Redis if this is ever load-balanced. Add the note
  rather than the abstraction.

Non-trivial logic needs one test that fails if the logic breaks. Not a suite — one test. The scope
gate, the cache-exclusion predicate and the guardrail matcher are each covered by exactly one, and
that is the bar.

## Where the useful gaps are

[Known gaps](README.md#known-gaps) is the honest list. The ones most worth a contribution:

- **MCP tool access.** The registry and `/api/v1/mcp/*` exist; nothing wires the agent to actually
  call an MCP server yet.
- **A real executor agent.** `scripts/dev-executor.mjs` runs nothing on purpose. A sandboxed agent
  that does run scripts, with its own allowlist and audit log, is the highest-value missing piece.
- **Self-service password change**, which the default-password situation makes overdue.
- **A background poller** for incident intake, which needs a distributed lock before it can run on
  more than one instance.

## Licensing of contributions

By opening a pull request you agree your contribution is licensed under
[Apache 2.0](LICENSE), same as the rest of the project. There is no CLA.

Be decent to each other in issues and reviews. That is the whole code of conduct.
