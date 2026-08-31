# Skills

Skills are the allowlist of things this platform can be asked to do. They live in the database
(`tools.skills`) and are edited in the browser: **Tools & scripts → Skills**, ADMIN only.

There is nothing to edit in this directory, and that is the point.

## Why the nine files that used to be here are gone

This directory held 1,700 lines documenting `REMOTE_EXEC`, `SCALE_UP`, `ROLLBACK`,
`VaultCredentialService`, `RemoteExecutionService`, `ScriptGuardrailValidator`,
`ScriptGeneratorService`, `mcp.vault`, `mcp.remote` and a Flyway migration step. None of those
exist in the source tree — several never did. A catalogue that lists tools the engine will refuse
to run is worse than no catalogue: an SOP author reads it, writes `SCALE_UP:deploy:3` into a
procedure, and finds out at approval time that the key is unknown.

Documentation of an allowlist drifts from the allowlist. The list itself does not.

## The three stages, all editable, all human-approved

| Kind | What the rows decide | Fields used |
|---|---|---|
| `CATEGORIZATION` | which words in a ticket mean which category | `pattern` = keywords, `skill_key` = the category, `action_key` = the action to propose |
| `EXTRACTION` | how a host is named in your estate | `pattern` = a regex whose **first capturing group** is the value, `skill_key` = the field it fills |
| `EXECUTION` | which action keys may run at all | `skill_key` = the tool name, `arg_count` = segments required after it, `mutating` = whether it changes the host |

An `EXECUTION` row is the allowlist `RemediationToolRegistry.parse()` checks. Turning a row's
`mutating` flag off is a privilege escalation, so that write is ADMIN-only and audited.

Rows widen what the platform can *recognise*. They cannot widen what it may *do*: a skill is still
parsed by the registry, still scanned by `GuardrailService`, still hash-pinned, and still refused
without a human approval.

The built-in host regexes in `IncidentTarget` still run first, so a broken `EXTRACTION` row cannot
stop the platform finding a host it used to find.

The four seeded `EXECUTION` rows — `CHECK_URL` (2 args, read-only), `RESTART_SERVICE` (2, mutating),
`CLEAR_CACHE` (3, mutating), `RERUN_JOB` (2, mutating) — are also compiled into
`RemediationToolRegistry.BUILT_IN` as a fallback, so an unmigrated or emptied table keeps
remediation working instead of reporting every tool as unknown.

## Action key format

```
SKILL_NAME:arg1[:arg2...]
```

The argument count is exact. A key with the wrong number of segments is rejected before dispatch
rather than padded with defaults.

```
CHECK_URL:http://localhost:8080/actuator/health:200
RESTART_SERVICE:tomcat:linux
CLEAR_CACHE:redis:localhost:6379
RERUN_JOB:linux:/opt/batch/nightly_report.sh
```

Editing a skill changes what may be *offered*. It does not change who approves it: every run is
still a person reading that specific script for that specific host and clicking yes. See the root
[README](../README.md) for the approval path and the guardrail layers.
