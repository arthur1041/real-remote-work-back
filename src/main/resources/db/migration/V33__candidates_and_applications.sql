-- ============================================================================
-- Candidates: people with an account, a profile and a CV.
--
-- Until now the only account on this board belonged to an employer, and the
-- only thing a job seeker could do was leave. Every listing ends in a link to
-- somewhere else, which is honest and also means the site learns nothing about
-- the person it just helped and has nothing to offer the employer who asked for
-- candidates rather than for an advert.
--
-- Deliberately a separate table from `employers`, exactly as `employers` is a
-- separate table from `companies` and for the same reason: these are different
-- things that happen to both have an email and a password. One person may be
-- both, on the same address, without either account knowing about the other.
-- A single unified identity is the better end state and is written up in
-- docs/actually-remote-candidate-marketplace-mvp.md; it is a migration of every
-- existing employer account and does not belong in the same change as the first
-- candidate being able to sign in.
--
-- The authentication is the employers' authentication, down to the column
-- names: scrypt hashes in `password_hash`, a signed stateless cookie keyed on
-- that hash so a password change invalidates every session, and a six-digit
-- code in a verifications table. That machinery is in production and has been
-- exercised; a second, subtly different copy of it would be the risk, not the
-- reuse.
-- ============================================================================

create table candidates (
    id            bigserial   primary key,
    email         text        not null,
    password_hash text        not null,
    full_name     text        not null,

    -- The profile. Everything below is optional at sign-up on purpose: the
    -- moment of highest intent is when somebody has just read a role they want,
    -- and a form that asks for fifteen things then is a form they abandon. The
    -- account exists after three fields; the rest is filled in from /account,
    -- which is also where the CV goes when it did not come with the sign-up.
    country           text,
    headline          text,
    seniority         text,
    skills            text[],
    years_experience  int,
    languages         text[],
    timezone          text,
    linkedin_url      text,
    portfolio_url     text,
    salary_expectation      bigint,
    salary_currency         text,
    employment_kinds        text[],
    available_from          date,

    -- Whether recruiters may find this person. On by default, because the only
    -- reason to hand over a CV here is to be found, and a default that
    -- contradicts the promise made at the point of upload would be a dark
    -- pattern in the other direction. Switchable from /account at any time.
    --
    -- NOT on by default in the EEA, the UK and Switzerland, where prior consent
    -- is the law -- the web app decides that from the same region test that
    -- governs the analytics consent, and writes the answer here.
    discoverable      boolean     not null default true,

    email_verified_at timestamptz,
    status            text        not null default 'ACTIVE',
    created_at        timestamptz not null default now(),
    last_login_at     timestamptz,

    constraint candidates_status_check check (status in ('ACTIVE', 'SUSPENDED', 'DELETED'))
);

create unique index candidates_email_unique on candidates (lower(email));
create index candidates_discoverable on candidates (discoverable) where status = 'ACTIVE';

-- Same shape as employer_email_verifications, including `code_plain`, which
-- exists there so a code can be read back while no mail provider is configured.
create table candidate_email_verifications (
    id           bigserial   primary key,
    candidate_id bigint      not null references candidates (id) on delete cascade,
    email        text        not null,
    code_hash    text        not null,
    code_plain   text,
    expires_at   timestamptz not null,
    used_at      timestamptz,
    attempts     int         not null default 0,
    created_at   timestamptz not null default now(),
    sent_at      timestamptz
);

create index candidate_verifications_pending
    on candidate_email_verifications (candidate_id)
    where used_at is null;

-- ============================================================================
-- The CV itself lives in object storage, never here.
--
-- Postgres would have been less work and the nightly pg_dump would have covered
-- it for free, which is the argument that nearly won. It loses on what the
-- column would become: a backup that doubles in size with every thousand
-- uploads, a dump that has to be moved around to deploy, and a row that cannot
-- be served without the web process reading the whole file into memory first.
--
-- So this table holds the key and the facts about the file, and the bytes sit
-- in an S3-compatible bucket reached only with short-lived signed URLs minted
-- after an entitlement check. `sha256` is here to recognise a re-upload of the
-- same document, and `byte_size` so a listing can be rendered without touching
-- the bucket at all.
-- ============================================================================
create table candidate_resumes (
    id            bigserial   primary key,
    candidate_id  bigint      not null references candidates (id) on delete cascade,
    object_key    text        not null,
    file_name     text        not null,
    byte_size     bigint      not null,
    content_type  text        not null,
    sha256        text        not null,
    uploaded_at   timestamptz not null default now(),
    -- Replacing a CV does not destroy the old one: an application already sent
    -- points at the exact document the employer was shown, and rewriting that
    -- under them would change what they read after they read it.
    replaced_at   timestamptz,

    constraint candidate_resumes_size_check check (byte_size > 0 and byte_size <= 10485760)
);

create unique index candidate_resumes_current
    on candidate_resumes (candidate_id)
    where replaced_at is null;

create index candidate_resumes_by_candidate on candidate_resumes (candidate_id, uploaded_at desc);

-- ============================================================================
-- Applications taken on this site.
--
-- A third answer to "where should people apply?", beside a page and a mailbox.
-- The first two send the person away and tell us nothing; this one keeps them
-- here and hands the employer a structured application with a CV attached.
--
-- `apply_url` stays NOT NULL and is set to this site's own /jobs/<id>/apply for
-- these roles, which is a real URL that already works: the apply button, the
-- click counter and the JobPosting structured data all go on pointing at the
-- same place they always did, and the route decides whether that means a
-- redirect outwards or a form. Nothing downstream had to learn a new shape.
-- ============================================================================
-- Null for a role that takes applications here: before approval there is no
-- job row and therefore no /jobs/<id>/apply to point at, and inventing an
-- external URL to satisfy the column would put a lie on the review screen.
alter table employer_postings alter column apply_url drop not null;

alter table employer_postings
    add column apply_via_platform boolean not null default false,
    -- Which fields the employer wants asked, from a fixed catalogue the web app
    -- owns. A list and not free text, for the same reason eligibility is a list:
    -- anything typed here would have to be matched by every reader of it.
    add column application_fields text[];

alter table jobs
    add column apply_via_platform boolean not null default false,
    add column application_fields text[];

create table job_applications (
    id           bigserial   primary key,
    posting_id   bigint      not null references employer_postings (id) on delete cascade,
    job_id       bigint      references jobs (id) on delete set null,
    candidate_id bigint      not null references candidates (id) on delete cascade,
    -- The exact document the employer was shown, pinned. See the note on
    -- `replaced_at` above.
    resume_id    bigint      references candidate_resumes (id) on delete set null,

    -- Answers to the fields the employer asked for, keyed by the catalogue's
    -- own codes. JSONB because the question set is per-posting and a column per
    -- possible question would be a schema change every time the catalogue grows.
    answers      jsonb       not null default '{}'::jsonb,
    cover_note   text,

    status       text        not null default 'NEW',
    created_at   timestamptz not null default now(),
    read_at      timestamptz,

    constraint job_applications_status_check
        check (status in ('NEW', 'READ', 'SHORTLISTED', 'REJECTED', 'WITHDRAWN'))
);

-- One application per person per role. Applying twice is almost always a
-- double-submitted form, and an employer reading the same person twice loses
-- confidence in the whole inbox.
create unique index job_applications_once
    on job_applications (posting_id, candidate_id);

create index job_applications_inbox
    on job_applications (posting_id, created_at desc);

create index job_applications_by_candidate
    on job_applications (candidate_id, created_at desc);
