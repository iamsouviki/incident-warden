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

1. Open <http://localhost:5173>, log in as `admin` / `admin123`.
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
5. **The script** — source `SOP_TEMPLATE`. Read the eight lines out loud. It is
   `systemctl restart tomcat` with a health check either side. Nothing the model wrote
   freehand.

What to say:

> Two independent questions get answered here. *Are we allowed to do this?* — the approved
> SOP says yes. *Has it worked before?* — a past incident here says yes, and here is that
> ticket. Both are shown to the approver, and both are stored on the plan, so an auditor six
> months from now sees the same two answers.

---

## Act 4 — Human approval, then a real run (4 min)

**HITL queue** → open the plan.

1. **Approve this script.** Say: *"The approval is pinned to a hash of this exact script.
   If one character of it changes after approval, the run is refused — you cannot approve
   version A and have version B execute."*
2. **Dry run.** Result: `DRY_RUN_PASSED` — *"Reachability: REACHABLE. Nothing was
   dispatched."* The executor window prints
   `[PROBE] target='store-0042-app-01' via default path -> REACHABLE`.
   Say: *"'Default path' means we tried to reach the machine with no credential of its own
   first. No token, no key, nothing stored in our database. Only if that fails do we ask a
   human how to connect."*
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
* **Teams** — who owns which store, who may approve, who gets the email.
* **Tools & scripts** — the saved tools and their run history.

> Nothing in this demo required a config file, a restart, or a developer. And no
> integration token or password for a target system is stored in this database at all — the
> executor reaches machines over their existing trusted path, and user passwords are BCrypt
> hashes.

---

## Honest answers to the four questions they will ask

Have these ready, verbatim. Being straight about the edges is what makes the rest credible.

**"So the AI decides whether to run something?"**
No. The decision is arithmetic over bounded inputs — SOP match, precedent similarity,
priority, a scan — and you can read every term on the plan. The model helps write prose and
match text. It cannot grant itself permission, and it cannot supply the script that runs:
that came from your approved procedure template.

**"What if the same incident is worded differently next time?"**
Then it does not clear the 60% / 3-term precedent bar and it goes to a human. We would
rather lose an automation than fire the wrong one. (Worth naming: the classifier's
vocabulary list is hand-maintained today. Adding a procedure does not yet teach it new
words — that's on the roadmap, and it's why we test the wording your stores actually use.)

**"Did it actually restart Tomcat just now?"**
No — and here is exactly why. That process on the right is a stand-in executor. It logged
the script instead of running it. In your environment it is replaced with an agent on the
store server, and the only thing that changes is a URL. The control plane never runs a
shell command itself, by design.

**"What are you not showing me?"**
Three things. Separation of duties is switched off in this local build so I can approve my
own plan — in a real deployment the requester cannot be the approver. Authoring a *new*
approved procedure is still a database task, not a screen. And the prose SOP search index is
empty here; the six approved procedures are what drive the decisions you just watched.

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
