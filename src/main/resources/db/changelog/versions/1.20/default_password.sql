-- ── One default password for the whole product ───────────────────────────────
-- The seeded admin used to be admin/admin123 while every account created from the
-- Teams page got michaels@1. Two "default passwords" meant an operator who read the
-- create-user dialog, then tried to sign in as admin, got 401 and reported the login
-- as broken. There is now one default, michaels@1, shared by both paths.
--
-- Guarded on the old seeded hash: an environment where somebody has already changed
-- the admin password keeps it. This resets an untouched seed, it does not reset a
-- chosen password.
UPDATE auth.users
   SET password_hash = '$2a$10$YxcGXgC5cSAQRpjtBy6FVOOcoQwqVHrQNIFgYut9gBWAWgMJeVQWO',
       updated_at    = CURRENT_TIMESTAMP
 WHERE username = 'admin'
   AND password_hash = '$2a$10$W9jPu.BKQ7IFJoaE86m3Sun.d4qqKfD4gRd24EikE6Cjp5xbkh3f.';
