-- 1.17 — Recipients come from the incident, not from a registry.
--
-- 1.16 created config.notification_recipient: an admin-maintained list of analyst
-- addresses to copy on every notification. That was the wrong model. Each incident
-- already names everyone who cares — whoever reported it, whoever it is assigned to, and
-- whichever group owns it — so a separate list is a second source of truth that goes
-- stale the moment somebody changes teams. Dropped in favour of one column.

DROP TABLE IF EXISTS config.notification_recipient;

-- The assigned group's mail id: the one recipient not already derivable from data we
-- hold. An assignee resolves to a person through teams.team_employees or auth.users, but
-- "Network Team" is a distribution list with no row of its own to carry an address.
-- Nullable — a team with no address is simply skipped, never guessed at.
ALTER TABLE teams.teams ADD COLUMN IF NOT EXISTS email VARCHAR(320);
