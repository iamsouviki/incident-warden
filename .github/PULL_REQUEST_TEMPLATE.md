## What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## Why

<!-- The failure or gap this fixes. If it's a bug, say what the wrong behaviour was — that's
     the part a future reader cannot recover from the diff. -->

## Checks

- [ ] `mvn -B test` passes
- [ ] `npm run typecheck --prefix frontend` passes
- [ ] Non-trivial logic has one test that fails if the logic breaks

## If this touches the remediation path

Tick the ones that apply, or write "n/a" — see [the invariants](../README.md#the-invariants).

- [ ] No `ProcessBuilder`, `Runtime.exec`, or SSH/WinRM client was added to this application
- [ ] A script still cannot run without a matching approved SHA-256 hash
- [ ] Guardrails are still re-scanned at dispatch, not only at generation
- [ ] A mutating action still cannot run on a host the platform inferred rather than confirmed
- [ ] Nothing dispatches a script without a person approving that exact script — no scheduler, no
      confidence score, no inherited approval
- [ ] No integration credential, provider key, or token is written to the database or returned
      by an API
- [ ] Any new operator-facing setting is editable from the UI, not only from a properties file
