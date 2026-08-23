# Client POC demo — run sheet

Everything below was executed on this build and the receipts are real (ticket numbers,
executor log lines, email bodies). Follow it top to bottom and the client sees the whole
product goal: **ticket → analysed against SOPs and past incidents → tool built → human
approves → and the second time, it fixes itself and emails.**

Total run time: ~15 minutes. Two acts do the work (Act 3 and Act 4); everything else is a
30-second look.

---

## 0. Pre-flight (do this before they join)

Four processes. Start them in this order and leave them visible on a second screen — the
two log windows *are* the proof that something real happened.

```bash
node scripts/dev-smtp.mjs
```

```bash
node scripts/dev-executor.mjs store-0042-pos-01,store-0042-app-01,store-0099-pos-01
```

```bash
mvn -o spring-boot:run -Dspring-boot.run.profiles=local -Dmaven.test.skip=true -Dspring-boot.run.jvmArguments="-Dmcp.autonomy.execution-enabled=true -Dmcp.autonomy.executor-url=http://localhost:9099"
```

```bash
npm run dev --prefix frontend
```

(Or start all four from the `smtp` / `executor` / `backend` / `frontend` entries in
`.claude/launch.json`.)

Then:

1. Open <http://localhost:5173>, log in as `admin` / `michaels@1`.
2. **AI configuration → Unattended Remediation** → toggle **ON** (amber).
3. **AI configuration → Notifications** → host `localhost`, port `1025`, from
   `incident-automation@demo.local`, enable, **Send test message**. Confirm the SMTP window
   prints `[MAIL] subject=Incident automation: test message`. Now the email beat is
   guaranteed to land during the demo.
4. Clear both log windows so the demo output starts on a clean screen.

Say once, up front: *"The executor process on the right is standing in for the store
server. It logs the exact script it was asked to run instead of running it, so nothing
in this room can break anything. Every other component is the real one."*

---

## Act 1 — "Where does the platform get its authority?" (1 min)

**SOP library → Approved procedures (6).**

Point at **SOP-TOMCAT-01** and its action key `restart-approved-service`.

What to say:

> The platform is never allowed to invent a fix. It can only act inside a procedure your
> team has already approved. This panel is that list — six procedures, each mapped to one
> action key. If an incident does not map to an approved procedure, the platform is not
> permitted to touch it, no matter how confident the model is.

That single sentence pre-answers the question every client asks ("will the AI go rogue?"),
so answer it before they ask it.

---

## Act 2 — File the ticket the way a store manager would (2 min)

**Incidents → New incident.**

| Field | Value |
|---|---|
| Subject | `Tomcat application unresponsive at store 0042` |
| Description | `Back-office app is not responding. Tomcat appears down on the application server.` |
| Priority | `P3` |
| Store Number | `0042` |
| Operating system | leave on **Auto-detect** — the machine gets asked |
| Server / Host | *leave blank on purpose* |

Submit. The form refuses and says the ticket names no server. **This is a feature — show
it.**

> A remediation script has to run *somewhere*. If the description does not name a machine,
> we ask the person who filed it rather than guessing. Guessing which box to restart is how
> automation earns a ban.

Now fill **Server / Host** = `store-0042-app-01` and submit. The ticket is saved in
Postgres with the store number and the target host attached.

If you would rather demo the ask-a-human path later instead, you can also set the target
after the fact: open the incident and use the **🖥 Remediation target** panel (Server /
Host + connection method), which saves on its own button.

---

## Act 3 — Analysis: SOPs *and* incident history (3 min)

Open the new incident. Scroll to **Guarded remediation workflow** →
**Create guarded remediation plan.**

Walk them through what comes back, in this order:

1. **Approved SOP evidence** — SOP-TOMCAT-01 matched, reason `APPROVED_TENANT_SOP_MATCH`.
2. **Precedent** — the platform found an earlier resolved incident with similar wording and
   shows its ticket number and similarity score (in our run: `INC000000004 @ 0.58`).
   *This is the "old incident notes and history" half of the analysis.*
3. **Confidence** — `78.5` in our run, against the local HITL band of 70. The number is
   arithmetic, not vibes: pattern similarity 0.35, historical success 0.25, SOP reliability
   0.20, system health 0.15, minus a risk penalty for priority.
4. **Guardrails** — `PASS`. The action is on the allow-list, the script is scanned, the
   target host is set.
5. **The script** — source `SOP_TEMPLATE`, and read the language field before the body:
   the plan says `bash` on a Linux target and `powershell` on a Windows one. Read the lines
   out loud. It is a service restart with a health check either side. Nothing the model
   wrote freehand.

   On a Mac laptop the executor stub reports `darwin`, so the same procedure renders
   `launchctl kickstart -k` instead of `systemctl restart`. That is the point, not a quirk —
   see the sidebar below.

What to say:

> Two independent questions get answered here. *Are we allowed to do this?* — the approved
> SOP says yes. *Has it worked before?* — a past incident here says yes, and here is that
> ticket. Both are shown to the approver, and both are stored on the plan, so an auditor six
> months from now sees the same two answers.

### Optional 60-second sidebar: the script is written for the machine, not for the SOP author

Worth doing if the client runs a mixed estate — Windows tills and Linux back-office servers.

The approved procedure behind this plan is `RESTART_SERVICE:tomcat:linux`. Note the `linux`.
Now stop the executor, restart it pretending to be a till, and create the plan again:

```bash
EXECUTOR_PLATFORM=windows node scripts/dev-executor.mjs store-0042-pos-01,store-0042-app-01,store-0099-pos-01
```

The same procedure, the same ticket, and the plan now reads:

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

> The person who wrote that procedure typed `linux`, because that is the machine they had in
> front of them. They were never going to be asked about every till in the estate. So we
> don't ask them: the reachability check is also where the machine tells us what it is, and
> the script gets written for that answer. `targetPlatformSource` on the plan records which
> rung of evidence was used — the host's own answer, the WinRM connection method the operator
> chose, the procedure's guess, or our default — and it is inside the approval hash, so the
> reviewer approved a platform as well as a script.

And if the operator knows better than the detection does, they say so: **🖥 Remediation target
→ Operating system** on the incident (also on the create form and in the HITL answer panel) is
normally on *Auto-detect*, and setting it outranks the probe. Do this one if they ask "what if
your detection is wrong?" — set it to Windows on a Linux host and create the plan again:

```
targetPlatform : windows  (source: OPERATOR_OVERRODE_HOST)
```

and the badge next to the script in the HITL console turns **red** and reads
`windows (operator override)`.

> A person can overrule the machine, because sometimes the machine cannot be probed at all and
> sometimes the detection is simply wrong. What a person cannot do is overrule it quietly. The
> reviewer is told, in red, that the host said something different — and the field is audited,
> so "who declared this a Windows box, and when" has an answer.

---

## Act 4 — Human approval, then a real run (4 min)

**HITL queue** → open the plan.

1. **Approve this script.** Say: *"The approval is pinned to a hash of this exact script.
   If one character of it changes after approval, the run is refused — you cannot approve
   version A and have version B execute."*
2. **Dry run.** Result: `DRY_RUN_PASSED` — *"Reachability: REACHABLE. Nothing was
   dispatched."* The executor window prints
   `[PROBE] target='store-0042-app-01' via default path -> REACHABLE platform=linux`.
   Say: *"'Default path' means we tried to reach the machine with no credential of its own
   first. No token, no key, nothing stored in our database. Only if that fails do we ask a
   human how to connect. And notice the machine told us what it is — that is what decided
   the language of the script you just approved."*
3. **Execute for real.** Result: `LIVE` / `SUCCEEDED` — *"Executor responded 200 … over
   default path."* The executor window prints the script it was handed:

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

   (`language=powershell` and a `Restart-Service` body if the executor reported a Windows
   host — the executor is handed the interpreter, it does not guess.)

4. The incident flips to **RESOLVED**.

In our run this was **INC000000008**. Leave it on screen — the next act depends on it.

---

## Act 5 — The payoff: the same incident, second time (3 min)

**New incident.** File the *identical* ticket again: same subject, same description, P3,
store `0042`, host `store-0042-app-01`.

Submit — and stop talking for a second. It comes back **RESOLVED** immediately. No queue,
no approval, no click.

Then show the two receipts:

* **Backend log:**
  `[AUTORUN] INC000000009 handled without approval via RESTART_SERVICE:tomcat:linux (precedent INC000000008)`
* **SMTP window** — the notification, addressed to the recipients configured for that store:

  ```
  [MAIL] from=incident-automation@demo.local rcpt=store0042.manager@example.com, analyst@mcp.local
  [MAIL] subject=[Resolved] Automatic remediation completed: Tomcat application

  Incident   : Tomcat application unresponsive at store 0042
  Reference  : INC000000011
  Priority   : P3
  Action     : restart-approved-service on store-0042-app-01
  Saved tool : RESTART_SERVICE
  Outcome    : completed

  Why this ran without approval:
    A human previously reviewed, approved and successfully ran this exact saved
    tool for a matching incident (INC000000008). ...
  ```

What to say:

> This is the whole business case. Your team approved this fix once, on
> INC000000008. The platform did not ask them to approve the same restart a second time —
> it repeated the tool they already blessed, on this store's own server, and told them it
> had done so. The email explains *which past approval* it inherited, so nobody has to
> wonder where the authority came from.

**Then say the important half:** it only did that because the saved tool is a restart. A
cache flush or a job rerun with the same precedent would still be sitting in the approval
queue.

---

## Act 6 — Prove the blast radius is bounded (2 min)

This is the act that closes the deal with a risk-averse client. Don't skip it.

**New incident**, same wording, but **store `0099`**, host `store-0099-pos-01`.

It comes back **New** — parked for a human. Backend log:

```
[AUTORUN] INC000000010 left for human approval: STORE_MISMATCH:0099!=0042
```

> The proof came from store 42. It does not transfer to store 99. A different store gets a
> human the first time, exactly like store 42 did — and once someone approves it there, that
> store earns its own automation.

Then read out the other gates that produce the same "back to the queue" result, all
visible in **AI configuration → Unattended Remediation**:

| Gate | What it stops |
|---|---|
| `P1_ALWAYS_NEEDS_A_HUMAN` | P1 never runs unattended, regardless of precedent |
| `PRECEDENT_NOT_SOP_BACKED` | the past fix wasn't cited to an approved SOP |
| `SCRIPT_SOURCE_NOT_TRUSTED` | the past script came from the model, not the SOP template |
| `TOOL_NOT_AUTO_RUNNABLE` | anything beyond read-only / restart |
| `SCRIPT_SCAN_NOT_CLEAN` / `GUARDRAIL_BLOCKED` | fresh scan on every run, not just at approval |
| `PRECEDENT_TOO_WEAK` / `TOO_THIN` | <60% wording overlap or <3 distinct matching terms |
| `PLATFORM_MISMATCH` | the saved script is bash and this machine answered Windows (or the reverse) |
| `AUTORUN_DISABLED` | the master switch |

And the master switch itself: toggle **Act without approval on proven precedent** to
**OFF** in front of them.

> One switch, stored in the database, effective on the very next ticket. There is no cycle
> to wait for and no redeploy. If you want the platform to ask every single time on day one,
> this is how you run it — and you can turn it on store by store as trust builds.

---

## Act 7 — Everything is configured in the UI (1 min)

Click through and say nothing more than this:

* **AI configuration → Notifications** — SMTP host, port, from-address, recipients. **No
  properties file was edited to make this demo work.**
* **AI configuration → Unattended Remediation** — the kill switch.
* **SOP library → Procedures → New procedure** — the approved procedure *and* its action key.
  Approving it here is what teaches the classifier its words and gives the planner its
  authority. This is the screen that used to be a database insert.
* **Teams → Add Team**, then **Create User Account** — who owns which store, who may approve,
  who gets the email. The new account's starting password is shown on the confirmation, and
  the role you pick is the role they get (an unknown role is refused, not downgraded to
  read-only). An email address is required, because an account nothing can email is an
  account the platform would otherwise report as "notified".
* **Tools & scripts** — the saved tools and their run history, including HITL runs.

> Nothing in this demo required a config file, a restart, or a developer. And no
> integration token or password for a target system is stored in this database at all — the
> executor reaches machines over their existing trusted path, and user passwords are BCrypt
> hashes.

---

## Act 8 — When the agent needs something, it asks on the screen (1 min)

The strongest single beat, because every ops team has lived the opposite. File a ticket with
**no server named** — "Tomcat not responding at the store", P3 — and press **Create guarded
remediation plan**.

It refuses, and the refusal is a question, not a stack trace:

> **Plan blocked and escalated.** The agent escalated this incident: `TARGET_HOST_UNKNOWN`
> No server is named on this incident or in its description. Enter the server this affects,
> then create the plan again.

Underneath it, the fields to answer it appear: **Server / host**, **Connect via** and
**Operating system**. Type `store-0042-app-01`, leave the connection on *Executor default (try
first)* — that is the "try without a token first" path — leave the OS on *Auto-detect* so the
machine gets to answer it, and press **Save answer and plan again**. One click writes the
answer to the ticket and re-plans:

> **Plan ready for HITL review.** A tenant-scoped SOP-backed plan passed the deterministic
> guardrails and was sent to the HITL queue.

Say: *"It did not guess a machine, and it did not make me file a second ticket. Every refusal
an operator can fix is shaped like a question with the answer box next to it."*

The same panel is in the HITL review console, so the reviewer can answer it without going
back to the incident page.

---

## Honest answers to the questions they will ask

Have these ready, verbatim. Being straight about the edges is what makes the rest credible.

**"So the AI decides whether to run something?"**
No. The decision is arithmetic over bounded inputs — SOP match, precedent similarity,
priority, a scan — and you can read every term on the plan. The model helps write prose and
match text. It cannot grant itself permission, and it cannot supply the script that runs:
that came from your approved procedure template.

**"What if the same incident is worded differently next time?"**
Then it does not clear the 60% / 3-term precedent bar and it goes to a human. We would
rather lose an automation than fire the wrong one. (Worth naming: the classifier reads the
match keywords off your **approved procedures**, so adding a procedure in the SOP library
teaches it those words — no code change. The built-in list is only a fallback for a
workspace with no procedures loaded yet.)

**"Did it actually restart Tomcat just now?"**
No — and here is exactly why. That process on the right is a stand-in executor. It logged
the script instead of running it. In your environment it is replaced with an agent on the
store server, and the only thing that changes is a URL. The control plane never runs a
shell command itself, by design.

**"What are you not showing me?"**
Three things. Separation of duties is switched off in this local build so I can approve my
own plan — in a real deployment the requester cannot be the approver. The prose SOP search
index is empty here; the six approved procedures are what drive the decisions you just
watched. And there is no change-password screen yet — every account starts on one default
password and an admin resets it.

**"What happens to a P1?"**
It always goes to a person, and that is arithmetic, not a policy toggle. The risk penalty on
a P1 (0.60) and its system-health term (0.30) cap the score at 24.5 %, against a band of
70 % here and 80 % in production; a P2 caps at 58.25 %. So no P1 or P2 can ever be routed for
unattended approval, however good the evidence. The screen says exactly that —
`CONFIDENCE_BELOW_HITL_BAND:24`, with the required band and the sentence "P1 carries a risk
penalty that holds it below the band deliberately" — and the evidence, script and score are
all still on the page for the engineer who picks it up. If you want a reviewable P1, that is
a deliberate re-weighting we do together, in the open.

---

## Receipts from the run this sheet was written from

| Ticket | Store | Priority | Path | Outcome |
|---|---|---|---|---|
| `INC000000008` | 0042 | P3 | full HITL: plan → approve → dry run → execute | RESOLVED, executor got the script |
| `INC000000009` | 0042 | P3 | auto-run from INC000000008 | RESOLVED at creation |
| `INC000000010` | 0099 | P3 | auto-run refused: `STORE_MISMATCH:0099!=0042` | New — waiting for a human |
| `INC000000011` | 0042 | P3 | auto-run, notifications live | RESOLVED at creation + email sent |
| `INC000000007` | 0042 | P2 | same wording, P2 risk penalty vs the 0.70 band | ESCALATED |

`INC000000007` is a useful spare: it shows that raising the priority on the same words is
enough to stop the automation, without touching a switch.

---

## If a demo beat misbehaves

| Symptom | Cause | Fix in front of them |
|---|---|---|
| Plan comes back `BLOCKED` / `ACTION_NOT_ALLOWLISTED` | the ticket wording matched no action key | reword the subject to name the service ("Tomcat", "not responding") |
| Dry run says `UNREACHABLE` | host not in the executor's known list | use `store-0042-app-01`, or add the host to the executor's argument |
| Second ticket is *not* auto-resolved | kill switch off, or the first run never reached `SUCCEEDED` | AI configuration → toggle ON; confirm the precedent incident is RESOLVED |
| No email in the SMTP window | notifications disabled or `notify_smtp_host` unset | AI configuration → Notifications → **Send test message** first |
| Approve button disabled | separation of duties — you filed it | approve as a second user, or note it as the real-deployment behaviour |
| Login rejected on `:5174` | Vite took a second port because 5173 was busy | nothing to fix — CORS accepts any loopback port. If it really is a bad password, it is `admin` / `michaels@1` |
| HITL assignee list looks short | only accounts in `auth.users` can be handed a review; roster-only people show as `· current, no login` | **Teams → Create User Account**, then reassign |
| "Save answer and plan again" leaves the box blank | stale build — this was fixed by keying the detail panel on the incident id | hard-reload the page |
