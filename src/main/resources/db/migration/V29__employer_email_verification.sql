-- ============================================================================
-- Confirming that an employer's address can actually receive mail.
--
-- Any address became an active account: no check, no bounce handling, nothing
-- that distinguished a working mailbox from a typo. The first real submission
-- registered as `...@gamil.com`, one character from `gmail.com`, which meant
-- the whole decision-notice loop built the same day ended at a bounce. The
-- employer would never have learned what was decided about their own role, and
-- nothing anywhere would have said why.
--
-- A verification mail at registration catches that while the person is still at
-- the keyboard. It is the only moment the fix is free.
--
-- Same shape as `employer_password_resets`: a six-digit code, hashed at rest,
-- with the plaintext living only until the pipeline sends it. Deliberately the
-- same mechanism rather than a link -- a link in a mail is a credential that
-- travels through previews, proxies and forwarded messages, while a code is
-- useless to anyone without the form it was typed from.
--
-- The address is stored on the verification row as well as on the account. The
-- question being answered is "does this address work", not "does this account
-- exist", and recording which address a code was sent to is what lets the same
-- table serve an address change later without pretending the old answer still
-- holds.
-- ============================================================================

alter table employers add column email_verified_at timestamptz;

-- Existing accounts are grandfathered. There are two, one of them the first
-- real customer with a submission already in the queue, and locking them out of
-- a flow that did not exist when they signed up would break something that
-- currently works to enforce a rule retroactively.
update employers set email_verified_at = created_at;

comment on column employers.email_verified_at is
    'When the address answered a code. Null means unverified: the account can sign in and look around but cannot submit a role.';

create table employer_email_verifications (
    id          bigserial   primary key,
    employer_id bigint      not null references employers (id) on delete cascade,

    -- The address this code was sent to, which is not necessarily the account's
    -- address by the time it is answered.
    email       text        not null,

    code_hash   text        not null,

    -- The only copy in the clear, and only until the mail goes. Null afterwards,
    -- which is also what tells the sender it is done.
    code_plain  text,

    expires_at  timestamptz not null,
    used_at     timestamptz,
    attempts    integer     not null default 0,
    created_at  timestamptz not null default now(),
    sent_at     timestamptz
);

-- The sender's queue: unsent codes, oldest first.
create index employer_email_verifications_unsent_idx
    on employer_email_verifications (created_at)
    where sent_at is null;

-- Verification reads the newest live code for an account.
create index employer_email_verifications_live_idx
    on employer_email_verifications (employer_id, created_at desc)
    where used_at is null;

comment on table employer_email_verifications is
    'One row per code sent to confirm an employer address. The code is hashed; the plaintext exists only until the mail is sent.';
comment on column employer_email_verifications.attempts is
    'Wrong codes entered against this row. A handful ends it, so a six-digit code cannot be guessed.';
