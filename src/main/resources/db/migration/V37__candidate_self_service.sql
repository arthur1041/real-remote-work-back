-- ============================================================================
-- The candidate account gets the three things it shipped without: a way back
-- in, a way to change the password, and a way out.
--
-- The employer account has had all three since 21-22 October. The candidate
-- account, built on 24 October from the same machinery and the same column
-- names, got none of them -- and it is the side holding the CVs. The result was
-- an account that could be created and then neither recovered nor left:
-- forgetting the password locked somebody out permanently, and because leaving
-- is deleting (docs/PROJECT.md) and deleting did not exist, there was no exit
-- at all. A product that is easy to join and hard to leave is the thing this
-- site exists not to be, and for a fortnight it was one.
--
-- Three parts, in the order they matter.
-- ============================================================================

-- ----------------------------------------------------------------- recovery --
-- A straight copy of `employer_password_resets`, down to the column names and
-- the fifteen-minute expiry, because that table is in production and has been
-- exercised. A second, subtly different design would be the risk here, not the
-- duplication -- the same argument V33 makes for copying the whole auth scheme.
create table candidate_password_resets (
    id           bigserial   primary key,
    candidate_id bigint      not null references candidates (id) on delete cascade,
    code_hash    text        not null,
    -- The web app writes the row, the pipeline sends it, and the sender nulls
    -- this in the same statement that stamps the send. See V26.
    code_plain   text,
    attempts     int         not null default 0,
    expires_at   timestamptz not null,
    sent_at      timestamptz,
    used_at      timestamptz,
    created_at   timestamptz not null default now()
);

create index candidate_password_resets_open
    on candidate_password_resets (candidate_id, created_at desc)
    where used_at is null;

create index candidate_password_resets_unsent
    on candidate_password_resets (created_at)
    where sent_at is null and code_plain is not null;

-- ----------------------------------------------------------------- leaving --
-- `status` already allowed 'DELETED' -- V33 wrote the value in and nothing
-- could ever set it. This records when, which is what makes the retention rule
-- below measurable rather than a promise.
alter table candidates
    add column deleted_at timestamptz;

comment on column candidates.deleted_at is
    'When the person asked to be removed. Set together with status = DELETED and the anonymisation of every personal field.';

-- The row survives and the person does not.
--
-- Deleting the row outright is the obvious move and it is wrong twice: it
-- cascades through `job_applications` and destroys the record of applications
-- an employer has already read and may be mid-hire on, and it takes the
-- aggregate counts with it. So the row stays and every personal field in it is
-- overwritten -- name, email, password hash, and the whole profile. What is
-- left is a shape with no person in it.
--
-- The address is rewritten rather than nulled, to `deleted+<id>@deleted.invalid`.
-- Two reasons: `candidates_email_unique` is not a partial index, so some unique
-- value has to go there, and .invalid is reserved by RFC 2606 and can therefore
-- never collide with a real mailbox. The consequence is the one docs/TODOs.md
-- asked for: the real address becomes free to register again, which is correct
-- -- somebody who left and came back is a new account, not a resurrected one.

-- ---------------------------------------------------------- the documents --
-- A CV has to leave the bucket, not just the row, and the ones already sent
-- with an application are the hard half: an employer read them, and the
-- application is a record of something that happened.
--
-- The line taken, which docs/TODOs.md proposed and which this implements: the
-- PROFILE document is deleted outright and immediately, and an APPLICATION
-- document is kept for ninety days and then swept. Ninety days is long enough
-- to cover a hiring process that was underway when somebody left and short
-- enough to be a retention period rather than "forever" spelled differently.
--
-- Null means "no retention clock running", which is every row today. A date in
-- the past means the sweep owes it a deletion.
alter table candidate_resumes
    add column delete_after timestamptz;

comment on column candidate_resumes.delete_after is
    'When the bucket object and this row are to be destroyed. Set on an APPLICATION document when its candidate deletes their account; a PROFILE document is deleted on the spot instead and never gets a clock.';

create index candidate_resumes_due
    on candidate_resumes (delete_after)
    where delete_after is not null;
