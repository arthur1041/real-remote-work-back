-- When an employer last used the site, not only when they last signed in.
--
-- `last_login_at` is stamped by the sign-in form, and the session cookie
-- outlives it by weeks: an employer who signed in once and has come back every
-- day since read as "last seen 20 days ago" on the admin list, which is the
-- column used to judge who is active (owner, 25 Nov 2026). The site now stamps
-- this on every signed-in request, at most once every five minutes per account,
-- and never from the administrator's read-only view of the account.
--
-- Kept beside `last_login_at` rather than replacing it: when somebody last typed
-- their password is still worth knowing on its own.
alter table employers add column last_seen_at timestamptz;
update employers set last_seen_at = last_login_at;

comment on column employers.last_seen_at is
    'Last signed-in request to the site, stamped at most every five minutes. Not touched by the admin read-only view.';
