-- ============================================================================
-- The admin gate stops being a password and becomes an account.
--
-- Until now there was one shared secret in `ADMIN_PASSWORD` and nothing else:
-- anybody holding it was the operator, from anywhere, with no second factor and
-- no record of which address was being used. Two things were wrong with that at
-- once. The password is the only thing between the internet and every
-- subscriber address and every CV on the board, and the rate limiter that was
-- supposed to slow a guess down was keyed on a header the client controls --
-- Caddy appends to X-Forwarded-For rather than replacing it, so the first entry
-- is whatever the attacker typed and every request landed in a fresh bucket.
-- The limiter is fixed in the web app; this is the other half.
--
-- So the gate now asks for an address as well, checks it against ADMIN_EMAIL,
-- and on a device it has not seen before sends a six-digit code to that address
-- before it lets anybody in. Knowing the password is no longer enough; you have
-- to be able to read the mailbox too.
--
-- Why a remembered device rather than a code every time: a code on every login
-- to a dashboard opened several times a day is a code somebody will shortcut,
-- and the shortcut is leaving the session open forever on an unlocked laptop.
-- One month was chosen because it is the same window the employer and candidate
-- sessions already use, so there is one number to reason about rather than
-- three.
--
-- `code_plain` is here for the same reason it is in the three verification
-- tables that came before: the web app writes the row and the pipeline sends
-- it, so the plaintext has to survive exactly long enough to reach the mailer,
-- which nulls it in the same statement that stamps the send.
-- ============================================================================

create table admin_login_codes (
    id          bigserial   primary key,
    -- The address the code went to, recorded rather than assumed. If ADMIN_EMAIL
    -- is changed, a code issued under the old one must not open the new one --
    -- the same check `employer_email_verifications` makes with its own `email`
    -- column, and for the same reason.
    email       text        not null,
    code_hash   text        not null,
    code_plain  text,
    -- Which attempt this belongs to, so a code cannot be used to authorise a
    -- different browser than the one that asked for it. The web app holds the
    -- other half in a short-lived cookie.
    challenge   text        not null,
    attempts    int         not null default 0,
    expires_at  timestamptz not null,
    sent_at     timestamptz,
    used_at     timestamptz,
    created_at  timestamptz not null default now()
);

create index admin_login_codes_open
    on admin_login_codes (challenge)
    where used_at is null;

create index admin_login_codes_unsent
    on admin_login_codes (created_at)
    where sent_at is null and code_plain is not null;

-- ============================================================================
-- Devices that have already answered a code.
--
-- The token lives in a cookie and only its digest is stored here, so the table
-- is not itself a set of working credentials -- the same reasoning as the
-- password hashes two tables over. A stolen database does not hand anybody a
-- trusted browser.
-- ============================================================================
create table admin_trusted_devices (
    id          bigserial   primary key,
    token_hash  text        not null unique,
    -- Free text, written by the operator at no point: it is the user agent,
    -- truncated, purely so a list of devices can be read by a person deciding
    -- which one to revoke.
    label       text,
    ip_hash     text,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    last_seen_at timestamptz
);

-- Plain, not partial. `where expires_at > now()` is the predicate you reach for
-- and Postgres refuses it: now() is not immutable, so it cannot appear in an
-- index predicate. The lookup is by token_hash anyway, which the unique
-- constraint already covers; this one exists for the sweep.
create index admin_trusted_devices_expiry
    on admin_trusted_devices (expires_at);
