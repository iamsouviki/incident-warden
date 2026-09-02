# Client POC demo — run sheet

Follow this top to bottom and the client sees the whole product: **ticket → analysed against
approved SOPs *and* your own incident history → script written for the actual machine → a human
reads it and approves → it runs, and the log says exactly what happened.**

The thing they are buying is the **gate**, not the automation. Say that early and the rest of the
demo lands.

Total run time: ~15 minutes. Two acts do the work (Act 4 and Act 5); everything else is a
30-second look.

> **What changed since the earlier version of this sheet.** It used to end with an act where the
> second identical ticket fixed itself without asking. **That feature is deleted, not disabled** —
> along with the switch that turned it on. If a client saw the old demo, the honest line is: *"we
> took it out. An platform that can act on its own 'only if a setting says so' has to be re-audited
> every time somebody touches that setting. This one gets audited once."* That answer sells better
> than the feature did.

---

## 0. Pre-flight (do this before they join)

Four processes. Start them in this order and leave them visible on a second screen — the two log
windows *are* the proof that something real happened.

```bash
node scripts/dev-smtp.mjs
```

```bash
node scripts/dev-executor.mjs store-0042-pos-01,store-0042-app-01,store-0099-pos-01
```

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true -Dspring-boot.run.jvmArguments="-Dmcp.executor.enabled=true -Dmcp.executor.url=http://localhost:9099"
```

```bash
npm run dev --prefix frontend
```

(Or start all four from the `smtp` / `executor` / `backend` / `frontend` entries in
`.claude/launch.json`.)

Then:

1. Open <http://localhost:5173> and sign in as `admin` / `admin` — the username is the starter
   password — and the first screen is a **forced password change**. Do that now, before the client
   is watching, and remember what you set it to.
2. Turn notifications on. **There is no form for this** — the SMTP card was removed from the Settings
   page and its API was left behind, so this is a `curl` and it is a known defect (see the note at the
   end of this section). With your token in `$T`:

   ```bash
   curl -s -X POST localhost:8080/api/v1/ai/config/notifications -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{"enabled":true,"host":"localhost","port":1025,"from":"incident-warden@demo.local"}'
   ```

   ```bash
   curl -s -X POST "localhost:8080/api/v1/ai/config/notifications/test?to=demo@demo.local" -H "Authorization: Bearer $T"
   ```

   Confirm the SMTP window prints a `[MAIL]` line. Now the email beat is guaranteed to land. If it
   does not, skip the email beat rather than debugging a relay in front of a client.
3. Load a handful of historical tickets so Act 4 has precedent to find — **Incident Dump → Import**
   with a CSV, or `POST /api/v1/intake/incidents`. At least one should be a resolved Tomcat
   incident at store 0042.
4. Clear both log windows so the demo output starts on a clean screen.

> **Do not offer to show the client the notification settings screen.** There isn't one. The API is
> real and the emails are real; the form that used to drive it was deleted along with the threshold
> sliders and never replaced. If they ask, that is the honest answer, and it is on the gap list.

Say once, up front: *"The executor process on the right is standing in for the store server. It
logs the exact script it was asked to run instead of running it, so nothing in this room can break
anything. Every other component is the real one."*

---

## Act 1 — What a stranger can see (1 min)

Open a private window at <http://localhost:5173>. **Do not sign in.**

Type: *"how many incidents are open?"* — a real count comes back.

Then type: *"fix the printer at store 42"* — and it stops, with a sign-in card.

> Anyone can ask this platform what is happening. Nobody can ask it to *do* anything without an
> account. And the anonymous view is a fixed six-field projection — reference, subject, description,
> status, priority, timestamp. **No assignee, no reporter address, no target host**: those are absent
> from the type, so the redaction is the shape of the query rather than a filter someone remembered
> to apply, and a unit test fails if a seventh field is ever added.
>
> The description *is* included, and that is the field to be precise about in the room: it goes
> through a masker first, so IP addresses, email addresses, credentials, card numbers and internal
> host names come back as `****`. Show them a masked row rather than asserting it — masking free text
> is a best-effort control, not a guarantee, and a client who has been told "we mask it" and then
> spots something is a client you have lost. If their tickets carry sensitive free text, the honest
> recommendation is to turn the anonymous surface off (`public_read_enabled`) and let them enable it
> after reviewing their own data.

---

## Act 2 — "Where does the platform get its authority?" (1 min)

**SOP library → Procedures.**

Point at **SOP-TOMCAT-01** and its action key `restart-approved-service`.

> The platform is never allowed to invent a fix. It can only act inside a procedure your team has
> already approved. This panel is that list, each entry mapped to one action key. A `DRAFT`
> procedure can be read; only `APPROVED` grants authority.

Then the part that surprises people: **approving a procedure here also teaches the classifier its
words.** The match keywords on the procedure are what the platform reads a ticket against. A
workspace that calls its tills "lanes" edits a row; it does not wait for a release.

That pre-answers the question every client asks ("will the AI go rogue?"), so answer it before they
ask it.

---

## Act 3 — The ticket arrives from the system of record (2 min)

**Incident Dump.** Show the imported tickets.

There is deliberately **no "New incident" form**, and this is worth 20 seconds:

> A ticket somebody types into this platform is a ticket that does not exist in the system of record
> everyone else is watching. The moment those two lists disagree, the audit trail is worth nothing.
> So incidents only arrive three ways: the intake API, a bulk import, or an ITSM sync. Not by hand.

Open the Tomcat incident at store 0042 and point at the **Target Infrastructure** line in the detail
panel. If nobody has set a host, it falls back to whatever the extractor read out of the ticket text
and labels it **Auto-extracted**.

> That line is read-only, and the distinction it draws is the point: a host somebody *confirmed* and a
> host the platform *guessed* are two different values, and the panel tells you which one you are
> looking at. The OS is deliberately not shown as a guess at all — we would rather ask the machine
> than extract it from prose.

There is no editable target form on this page. Where an operator answers the "which machine?"
question — and the one case where they currently cannot — is Act 9.

---

## Act 4 — Analysis: SOPs *and* incident history (3 min)

**Create guarded remediation plan.** Walk them through what comes back, in this order:

1. **Approved SOP evidence** — SOP-TOMCAT-01 matched, reason `APPROVED_SOP_MATCH`.
2. **Precedent** — an earlier resolved incident with similar wording, with its ticket number and
   similarity score. *This is the "our own history" half.* A past ticket only qualifies if a human
   approved it, its execution actually `SUCCEEDED`, and its plan pinned a parseable action key.
3. **Confidence** — the number against the local band of 70. It is arithmetic, not vibes: pattern
   similarity 0.35, historical success 0.25, SOP reliability 0.20, system health 0.15, minus a risk
   penalty for priority. Every term is on the plan.
4. **Guardrails** — `PASS`. The action is on the allow-list, the script is scanned, the target host
   is confirmed.
5. **The script** — source `SOP_TEMPLATE`. Read the `language` field *before* the body: `bash` on a
   Linux target, `powershell` on a Windows one. Then read the plain-language explanation above the
   code out loud.

> Two independent questions get answered here. *Are we allowed to do this?* — the approved SOP says
> yes. *Has it worked before?* — a past incident here says yes, and here is that ticket. Both are
> shown to the approver and both are stored on the plan, so an auditor six months from now sees the
> same two answers.

And on the explanation, which reviewers notice:

> The sentence above the script comes from two different places — the effect is read from the
> *authorised action*, the steps are read from the *script text*. If those two ever disagree, the
> reviewer sees the disagreement rather than a confident summary of the wrong thing. Any line the
> explainer does not recognise is quoted verbatim instead of dropped, so the explanation can never
> make a script look smaller than it is.

### Optional 60-second sidebar: the script is written for the machine, not for the SOP author

Worth doing if the client runs a mixed estate — Windows tills, Linux back-office servers.

The approved procedure is `RESTART_SERVICE:tomcat:linux`. Note the `linux`. Now restart the executor
pretending to be a till, and create the plan again:

```bash
EXECUTOR_PLATFORM=windows node scripts/dev-executor.mjs store-0042-pos-01,store-0042-app-01,store-0099-pos-01
```

Same procedure, same ticket, and the plan now reads:

```
scriptLanguage : powershell
targetPlatform : windows  (source: HOST_REPORTED)

# SOP-approved remediation: restart the 'tomcat' service.
$ErrorActionPreference = 'Stop'
Write-Output "Before: $((Get-Service -Name 'tomcat').Status)"
Restart-Service -Name 'tomcat'
Start-Sleep -Seconds 5
$after = (Get-Service -Name 'tomcat').Status
Write-Output "After: $after"
if ($after -ne 'Running') { exit 1 }
```

> The person who wrote that procedure typed `linux`, because that is the machine they had in front
> of them. They were never going to be asked about every till in the estate. So we don't ask them:
> the reachability check is also where the machine tells us what it is, and the script gets written
> for that answer. `targetPlatformSource` records which rung of evidence was used, and it is inside
> the approval hash — so the reviewer approved a platform as well as a script.

If they ask *"what if your detection is wrong?"* — override it. The OS select sits on the review
console card described in Act 9, which appears whenever nobody has confirmed the host is reachable;
with no executor running, that is every plan. Set it to **OS: Windows** on a Linux host, press **Save
answer**, and create the plan again:

```
targetPlatform : windows  (source: OPERATOR_OVERRODE_HOST)
```

The badge in the review console turns **red** and reads `windows (operator override)`.

If the executor *is* running and the probe succeeded, that card is not on screen — the platform came
from the machine, and there is no UI to overrule it. Overriding then means one call:

```bash
curl -u admin:PASSWORD -X PUT "localhost:8080/api/v1/incidents/<id>?username=admin" -H 'Content-Type: application/json' -d '{"targetPlatform":"windows"}'
```

Do not demo that path. Show the card instead — and if they push, the honest line is that a
confirmed-reachable host is the case where nobody has yet needed to overrule the probe.

> A person can overrule the machine, because sometimes the machine cannot be probed and sometimes
> the detection is simply wrong. What a person cannot do is overrule it *quietly*.

---

## Act 5 — Human approval, then a real run (4 min)

**HITL queue** → open the plan. Note there is **no approve button on the queue row**:

> Approving from a table row is approving a script you have not read. Approval only exists next to
> the script text.

1. **Approve this script.** *"The approval is pinned to a SHA-256 hash of this exact script. Change
   one character afterwards and the run is refused — you cannot approve version A and run version
   B."*
2. **Dry run.** Result `DRY_RUN_PASSED` — *"Reachability: REACHABLE. Nothing was dispatched."* The
   executor window prints
   `[PROBE] target='store-0042-app-01' via default path -> REACHABLE platform=linux`.

   *"'Default path' means we tried to reach the machine with no credential of its own first. Only if
   that fails do we ask a human how to connect. And notice the machine told us what it is — that is
   what decided the language of the script you just approved."*

   Dry run is **mandatory**. There is no route to a real run that skips it.
3. **Execute for real** — and point out that this button needs **ADMIN**, while the approve button
   needed **ANALYST**. Two different people, by design. Result `LIVE` / `SUCCEEDED`. The executor
   window prints the script it was handed:

   ```
   [EXEC] target='store-0042-app-01' via default path language=bash lines=8
   #!/usr/bin/env bash
   # SOP-approved remediation: restart the 'tomcat' service.
   set -euo pipefail
   systemctl is-active 'tomcat' || true
   systemctl restart 'tomcat'
   sleep 5
   systemctl is-active 'tomcat'
   ```

4. The incident flips to **RESOLVED**, and the SMTP window shows the notification.

> Note what that says: `LIVE`. If no executor were configured it would say `SIMULATED`, and the
> platform would record that it pretended. It never claims to have fixed something it did not fix.
> And there is **no retry** — a lost response does not mean the script did not run, so retrying
> could double-apply a change. An unknown outcome is recorded as a *failure* and the reviewer is
> told to verify on the target.

---

## Act 6 — The same ticket again, and why it still asks (2 min)

File the *identical* ticket again — same subject, same description, P3, store 0042, same host — and
create the plan.

It goes to the queue. **Again.** A human is asked. **Again.**

Sit with that for a second, because a client's first reaction is that it looks like a missing
feature. It is the product:

> This platform used to inherit that earlier approval and re-run the fix by itself. We deleted that
> path — not the switch, the path. Here is the reasoning. A platform whose answer to "can this run
> without me?" is *"only if a config row says so"* has to be re-audited every time somebody changes
> that row, and you can never be sure who changed it last. A platform whose answer is *"no"* gets
> audited once.
>
> What the precedent buys you is not a skipped click. It is a reviewer who opens this plan and sees
> "we did exactly this on INC-8 and it worked" — so the click takes four seconds instead of forty
> minutes. That is where the time actually goes.

Then say the honest cost: *"if you want unattended remediation, that is a real conversation and a
real audit, and it belongs in the executor's allowlist rather than in this approval gate."*

---

## Act 7 — Prove the blast radius is bounded (2 min)

The act that closes the deal with a risk-averse client. Don't skip it.

**Change the priority to P1** on the same wording and re-plan. It escalates:

```
CONFIDENCE_BELOW_HITL_BAND:24
```

> That is arithmetic, not a policy toggle. Every other term is already maxed out in that number —
> perfect pattern match, perfect history, perfect SOP reliability. A P1's risk penalty (0.60) and its
> system-health term (0.30) still cap the score at **24.5 %**, and a P2 at **58.25 %**, against a band
> of 70 % here and 80 % in production. So **no P1 or P2 can reach the approval band at any band above
> 58.25 %, however good the evidence** — and a unit test fails if somebody re-weights it, so that
> claim cannot quietly go stale.
>
> The one lever that changes it: the band is a config row, writable with
> `POST /api/v1/ai/config {"hitlThreshold":"0.40"}`, and setting it below 58.25 % *would* let a P2
> through. That is deliberate — it is your risk appetite, not ours — and it is a single audited row
> rather than a code change. The slider that used to write it was removed from the page, so today it
> is API-only; that is on the gap list. What no value of it does is skip the human.

Now the guardrails. Edit the target on a scratch incident to `all-store-servers` and re-plan:

```
GUARDRAIL_BLOCKED
```

> One class, `GuardrailService`, every path, no off switch — a `guardrails.enabled: false` knob would
> be a footgun. Targets are allow-listed **by shape**, not by blocklisting metacharacters: a space, a
> semicolon, a pipe, a glob, a quote or a newline is rejected without anyone having to enumerate the
> attacks. Group words — `all`, `every`, `cluster`, `fleet`, `prod` — are refused outright. And the
> scan runs **again at dispatch**, so a term you add to the blocklist this morning stops a plan that
> was approved yesterday.

The other refusals worth naming, all of which produce "back to a human":

| Reason | What it stops |
|---|---|
| `TARGET_HOST_UNKNOWN` | a mutating action with no confirmed machine |
| `TOOL_NOT_ALLOWLISTED:x` | a procedure declaring an action key no tool answers to |
| `NO_APPROVED_SOP_MATCH` | no approved procedure, with ungrounded scripts switched off |
| `SCRIPT_GENERATION_UNAVAILABLE` | no template matched and no model was reachable |
| `PLAN_ALREADY_AWAITING_DECISION` | a second plan for an incident already in the queue |

> And note that `CONFIDENCE_BELOW_HITL_BAND` is a *different* message from `GUARDRAIL_BLOCKED`. It
> used to be reported as the latter, which sent engineers hunting for a dangerous script that did not
> exist. The screen now says which of the two it was.

---

## Act 8 — Everything is configured in the UI (1 min)

Click through and say little more than this. The Settings page is three cards:

* **AI Core Engine Settings** — provider, base URL, chat model, embedding model. The API key is the
  one thing you *cannot* set here: it is read from the environment and never stored or returned.
  Switching to a keyed provider costs a restart, deliberately.
* **Accounts & Access** — who can sign in and with what role. Creating an account requires a valid
  email (an account nothing can email is one the platform would still report as "notified") and an
  unknown role is refused, not quietly downgraded to read-only. Your own row shows as **owner** and
  is read-only — you cannot demote yourself out of the ability to fix it.
* **External ITSM & Bug Tracker Integrations** — ServiceNow, Freshservice and Jira sync. The settings
   form does not persist submitted credentials, but legacy credential rows can still be read through
   a reversible Base64 fallback. Show the card, do not enter a real credential, and say so — see S1
   in the readiness review.

Then, elsewhere:

* **SOP library → Procedures → Author Approved Procedure** — the approved procedure *and* its action key. This screen used to
  be a database insert.
* **Skills & Tools → 🎯 3 Core Skills Engine** — the three agent stages as editable rows: which words mean which
  category, how a host is named in your estate, which tools may run at all. Turning a row's
  `mutating` flag off is a privilege escalation, so that write is ADMIN-only and audited.
* **Skills & Tools → 🛠 Custom Scripts & Sandbox → Run Logs** — every run, including the HITL one you just did.

> Nothing in this demo required a properties-file edit or a developer. Two things still require an
> API call rather than a screen — the notification relay and the confidence band — and both are on
> the gap list rather than being presented as finished.

---

## Act 9 — When the agent needs something, it asks on the screen (1 min)

A strong beat, because every ops team has lived the opposite. Open a plan in the **HITL queue** that
was raised while no executor was running, and show the card at the top of the review console. Its
title is, in these words:

> ⚠ **We need one answer from you**

Not a stack trace and not a validation error. It states the situation in a sentence — *"Nobody has
confirmed that `store-0042-app-01` is reachable, so a dry run may be the first thing to find out.
Correct the server name if it is wrong, or name the connection method if the default path does not
reach it."* — and then tells the reviewer what to leave alone: *"Leave the connection method on the
default unless a dry run has already failed — the default means the executor reaches the host over
its own trusted path, with no credential stored here."*

Directly under it are the three controls that answer it: a **server hostname** box, a connection
select defaulting to **Executor default**, and an OS select defaulting to **OS: auto-detect**. Change
what needs changing and press **Save answer**. The confirmation is honest about what it did and did
not do:

> *Saved. Create the plan again on the incident to re-evaluate with this target.*

So go back to the incident and press **Create guarded remediation plan** again.

Say: *"Every refusal an operator can fix is shaped like a question with the answer box next to it.
And saving the answer does not silently re-run the analysis — it tells you to ask again, because a
plan built on an answer typed thirty seconds ago should be a plan somebody asked for."*

Two details worth knowing. The card appears only while a finding actually names the target, so it is
not a permanent form cluttering the console. And it is driven by findings beginning `TARGET_`, so a
plan whose host probed clean does not show it.

> ⚠ **What this act cannot show — read this before you promise it.** The case above is
> `TARGET_REACHABILITY_UNKNOWN`, where a host *is* named. The harder case, `TARGET_HOST_UNKNOWN` with
> no host at all, **has no UI answer today.** That plan is saved `BLOCKED` and no HITL request is
> created for it (`HitlWorkflowService.java:183,230-284`), so the review console — the only screen
> carrying those three controls — is never reachable for it. The escalation says *"Enter the server
> this affects, then create the plan again"* and there is nowhere in the UI to enter it. The way out is
> the API (`PUT /api/v1/incidents/{id}` accepts `targetHost`) or an ITSM sync that fills the field.
>
> Do not plan a host-less incident on stage. If a client asks what happens, say it plainly: the
> platform refuses correctly and the fix-it-yourself affordance for that one refusal is missing. It is
> filed as a known UI gap in the project issue tracker.

---

## Honest answers to the questions they will ask

Have these ready, verbatim. Being straight about the edges is what makes the rest credible.

**"So the AI decides whether to run something?"**
No. The decision is arithmetic over bounded inputs — SOP match, precedent similarity, priority, a
scan — and you can read every term on the plan. The model helps write prose and match text. It
cannot grant itself permission, and on this ticket it did not even supply the script: that came from
your approved procedure's template.

**"What if there is no SOP for something?"**
Then it can write a script from model knowledge — labelled `UNGROUNDED_LLM_SCRIPT`, held to a
*stricter* bar (a `WARN` is fatal where a grounded script survives one), shown with a red banner, and
the reviewer must tick "I read the whole script" before Approve enables. If you would rather it
refuse outright, that is one setting: `mcp.hitl.allow-ungrounded-scripts: false`.

**"Did it actually restart Tomcat just now?"**
No — and here is exactly why. That process on the right is a stand-in executor. It logged the script
instead of running it. In your environment it is replaced with an agent on the store server, and the
only thing that changes is a URL. **The control plane never runs a shell command itself.** There is
no `ProcessBuilder`, no `Runtime.exec`, no SSH client in it. The executor holds the credentials; we
hold the approvals. That split is why a compromised control plane is not a compromised fleet.

**"What happens to a P1?"**
It always goes to a person — arithmetic, not a policy toggle. Its best possible score is 24.5 % and
the band is 70–80 %, so it cannot be offered for approval. The screen says so in words, and the
evidence, script and score are all still on the page for the engineer who picks it up. Note the band
is a settable config row (`POST /api/v1/ai/config`, no screen for it today): dropping it below
58.25 % would let a P2 in. That is your risk appetite to set, in the open, in one audited row — and
it still does not skip the human.

**"What are you not showing me?"** — answer this one fully; it is the one that earns trust.
Five things.
1. Separation of duties is off in this local build so I can approve my own plan. In a real
   deployment the requester cannot be the approver.
2. The prose SOP search index is empty here; the approved procedures are what drove every decision
   you watched.
3. **There is no real executor agent yet.** The stand-in runs nothing on purpose. Building the
   locked-down one — its own allowlist, its own audit log, its own sandbox — is the largest piece of
   work left, and it is the piece that actually touches your machines.
4. **The starting password is the username**, single-use: no hash is committed (the migration seeds
   a NULL one, which cannot authenticate) and none is ever logged, and first login cannot be
   completed without replacing it.
5. **This is not yet production-hardened**, and the list is written down rather than glossed:
   The public security policy and README list the current production blockers. The four that matter
   most, in order:
   * legacy ITSM integration credentials remain recoverable from the database (**S1**);
   * refresh-token rotation is replayable (**S2**);
   * a refusal that says "name the server" has no field in the UI to name it, because a blocked plan
     never reaches the console that holds the input (**C13**) — the platform is right to refuse and
     wrong about where you fix it;
    * login rate-limit fallback state can grow without bound (**S3**).

   The last two are frontend work measured in hours, not architecture.

If they ask why you are volunteering item 5: *"because you would find it in the second week, and
then you would wonder what else I hadn't mentioned."*

---

## If a demo beat misbehaves

| Symptom | Cause | Fix in front of them |
|---|---|---|
| Plan comes back `BLOCKED` / `ACTION_NOT_ALLOWLISTED` | the ticket wording matched no action key | reword the subject to name the service ("Tomcat", "not responding"), or add the word to the procedure's match keywords in **SOP library → Procedures** — which is the better demo |
| Dry run says `UNREACHABLE` | host not in the executor's known list | use `store-0042-app-01`, or add the host to the executor's arguments |
| Dry run says `TARGET_REACHABILITY_UNKNOWN` | the executor process is down, not the host | this is deliberate — an executor restart must not block every plan. Restart the stub |
| Execute button greyed out | it needs ADMIN; approve needs ANALYST | sign in as the admin, and name the split as the feature it is |
| No email in the SMTP window | notifications disabled or `notify_smtp_host` unset | run the two `curl` calls from pre-flight step 2 first; there is no screen for this |
| Approve button disabled | separation of duties — you filed it | approve as a second user, or note it as the real-deployment behaviour |
| Precedent panel is empty | no matching resolved incident with a human-approved, `SUCCEEDED` execution | run Act 5 once to create one, then re-plan the second ticket |
| Login rejected on `:5174` | Vite took a second port because 5173 was busy | nothing to fix — CORS accepts any loopback port |
| Forced-password screen on login | expected on a fresh database | change it; do this before the client is watching |
| Scripts unavailable, `SCRIPT_GENERATION_UNAVAILABLE` | Ollama is down and no SOP template matched | the deterministic `SOP_TEMPLATE` path needs no model — demo with a ticket that matches an approved procedure |
