-- ============================================================================
-- Password recovery for employer accounts.
--
-- Until now a forgotten password was a mailto link and a person at the other
-- end sorting it out by hand. That is fine for one account and impossible for
-- any number, and it is the kind of thing an employer who arrived from an
-- advert reads as a site that is not finished.
--
-- The code is stored hashed, the same reasoning as a password: this table is
-- read by the pipeline to send the mail and by the website to check an answer,
-- and a readable code in either place is a readable code in a backup. The
-- sender gets the plaintext once, from the row the website wrote, and never
-- again -- so the column holds the digest and `code_plain` holds the only copy
-- until it is sent, after which it is cleared.
--
-- Everything here expires. A reset that is never used stops being usable after
-- fifteen minutes, and a row that was used is kept rather than deleted so that
-- a second attempt with the same code is refused rather than silently working.
-- ============================================================================

create table employer_password_resets (
    id          bigserial   primary key,
    employer_id bigint      not null references employers(id) on delete cascade,

    -- The digest of the six-digit code. Verified, never displayed.
    code_hash   text        not null,

    -- The only copy of the code in the clear, and only until the mail goes.
    -- Null afterwards, which is also what tells the sender it is done.
    code_plain  text,

    expires_at  timestamptz not null,
    used_at     timestamptz,
    attempts    integer     not null default 0,
    created_at  timestamptz not null default now(),
    sent_at     timestamptz
);

-- The sender's queue: unsent codes, oldest first.
create index employer_password_resets_unsent_idx
    on employer_password_resets (created_at)
    where sent_at is null;

-- Verification reads the newest live code for an account.
create index employer_password_resets_live_idx
    on employer_password_resets (employer_id, created_at desc)
    where used_at is null;

comment on table employer_password_resets is
    'One row per reset request. The code is hashed; the plaintext exists only until the mail is sent.';
comment on column employer_password_resets.attempts is
    'Wrong codes entered against this row. A handful ends it, so a six-digit code cannot be guessed.';
