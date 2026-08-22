-- 1.18 — Which machine, at which store, and how to reach it.
--
-- Until now the "target" handed to the executor agent was the incident's own ticket
-- number (INC000000004). That is not a machine. It only ever worked because the local
-- stub executor ignores the field; a real agent would have to guess which host the
-- ticket meant, and an agent that guesses which box to restart is the exact failure
-- this platform exists to prevent.
--
-- Three columns, no new table. They live on the incident because the incident is what a
-- person files and what the precedent lookup already reads: a resolved past ticket IS
-- the record of "we fixed store 0042 this way, over this connection, and it worked".
--
-- No credential column, here or anywhere. connection_method names HOW to connect
-- (SSH / WINRM / AGENT); the secret for that method stays with the executor agent on the
-- target network, which is the only component that ever needs it. That is what keeps
-- "configure everything from the UI" and "no auth details in the database" both true.

-- The store this incident belongs to. Nullable: not every incident is a store incident,
-- and a blank store must keep behaving exactly as it did before this changeset.
-- Autonomy is proven per store — AutoRemediationService inherits a past human approval
-- only when the past ticket carries the SAME store number — so this column is a
-- permission boundary, not a label.
ALTER TABLE incident.incidents ADD COLUMN IF NOT EXISTS store_number      VARCHAR(32);

-- The machine an approved script will actually be run on. 253 is the DNS name limit.
-- Nullable, and a null is a hard stop rather than a default: a mutating plan with no
-- named host is refused and the operator is asked, never assigned "probably that one".
ALTER TABLE incident.incidents ADD COLUMN IF NOT EXISTS target_host       VARCHAR(253);

-- Empty/null means "executor, use your own default path to that host" — the attempt made
-- before anybody is asked for anything. Only when that fails does a human fill this in.
ALTER TABLE incident.incidents ADD COLUMN IF NOT EXISTS connection_method VARCHAR(16);
