-- ── An account handed a known starter password must not keep it ──────────────
-- Accounts created from Access & users start on michaels@1, which is written in this
-- repository and therefore known to everyone who can read it. The flag is what makes that
-- acceptable: the first successful sign-in cannot get anywhere until the password is
-- replaced, so the published value is an enrolment token rather than a credential.
--
-- Default false, so no existing account is locked behind a reset dialog it was never told
-- about. The two accounts that DO carry a published hash are handled in Java, by
-- BootstrapPassword, because the check there is a BCrypt comparison and not a SQL one.
ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS must_change_password boolean NOT NULL DEFAULT false;
