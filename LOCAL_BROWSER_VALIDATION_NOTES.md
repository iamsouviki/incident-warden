# Local Browser Validation Notes

## Scope

This validation ran against the local H2 profile at `http://127.0.0.1:5173` with the Spring Boot API on port `8080`. The local profile deliberately disables pgvector and Ollama, so it can validate browser wiring and **fail-closed** behavior, but it cannot validate approved-SOP retrieval or the positive approval/simulation path.

| Browser scenario | Observed result | Status |
|---|---|---|
| POC sign-in screen | The local role selector rendered with **Viewer**, **Analyst**, and **Admin** choices. | Pass |
| Local CORS policy | Browser-origin login initially failed because `127.0.0.1:5173` was absent from the local allowlist. The local-only profile setting now permits that exact origin; a preflight returned `Access-Control-Allow-Origin: http://127.0.0.1:5173`. | Fixed and verified |
| Incident creation | An Admin POC user created `INC000000001`, a single-device P3 printer-queue incident. | Pass |
| Incident details | Selecting the incident initially exposed a stale legacy-tool state reference and blanked the panel. The stale calls were removed; the detail panel now renders. | Fixed and verified |
| Guarded plan UI | The create-plan control now renders even while the optional local AI copilot is unavailable. No direct script execution control appears in the incident panel. | Pass |
| SOP-unavailable plan attempt | The app displayed **“Plan blocked and escalated … SOP_SERVICE_UNAVAILABLE”**. No approval was created. | Pass, fail-closed |
| HITL queue after blocked plan | The queue showed **0 pending approvals** and stated that plans lacking approved SOP evidence are blocked and escalated. | Pass, fail-closed |

> The positive workflow—approved SOP evidence, HITL approval, and recorded simulation—must be validated under the PostgreSQL + pgvector + Ollama deployment profile. The local H2 profile is intentionally not a substitute for that environment.

## Test data created

The local H2 database contains one browser-test incident and one blocked remediation plan for `tenant-local`. Neither operation executed a script, connected to external systems, or performed a system mutation.

## Follow-up validation required

The full client-demo rehearsal should use the real local PostgreSQL/pgvector and Ollama deployment, upload an approved tenant SOP that matches a supported incident class, then verify plan evidence, guardrail findings, Admin approval, and the recorded **simulation-only** action output.
