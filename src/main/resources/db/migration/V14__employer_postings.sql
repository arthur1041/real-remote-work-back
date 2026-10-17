-- ============================================================================
-- Employer accounts and the roles they post directly.
--
-- Every other posting on this board was read from somewhere else, which forces
-- the whole classification apparatus: the pipeline has to work out from prose
-- whether a role is open worldwide, and refuses to guess when the posting is
-- unclear. A role submitted by the employer inverts that. They are asked the
-- eligibility question directly and their answer is recorded as an answer, not
-- as an inference. It is the only inventory on the site where the premise holds
-- by construction rather than by careful reading.
--
-- That is also why nothing here publishes itself. A board whose selling point is
-- that the WORLDWIDE badge means something cannot hand the badge to whoever fills
-- in a form. Every submission waits for a human, and the approval is the moment a
-- row appears in `jobs`.
-- ============================================================================

-- A company that can sign in. Deliberately separate from `companies`: that table
-- is keyed on (ats_type, ats_token) and is written by the ingest, and an account
-- must not depend on whether we happen to already read that employer's board.
-- The link is made on first approval instead.
create table employers (
    id            bigserial   primary key,
    email         text        not null,
    password_hash text        not null,
    company_name  text        not null,
    website       text,

    -- Set when their first posting is approved, so that direct roles land under
    -- the same company page as anything we already read from them.
    company_id    bigint      references companies (id) on delete set null,

    status        text        not null default 'ACTIVE',
    created_at    timestamptz not null default now(),
    last_login_at timestamptz,

    constraint employers_status_check check (status in ('ACTIVE', 'SUSPENDED'))
);

create unique index employers_email_unique on employers (lower(email));

-- The submission, and the moderation record for it. Kept after approval rather
-- than being consumed: when a listing is later disputed, what the employer
-- actually claimed at submission time is the thing worth having.
create table employer_postings (
    id               bigserial   primary key,
    employer_id      bigint      not null references employers (id) on delete cascade,

    title            text        not null,
    description_html text        not null,
    apply_url        text        not null,

    -- Declared, never derived. `WORLDWIDE` here is the employer stating that
    -- anyone, anywhere may apply, and the review is what holds them to it.
    geo_scope        text        not null,
    geo_detail       text,
    timezone_requirement text,

    employment_kind  text,
    category         text,
    salary_min       numeric(12,2),
    salary_max       numeric(12,2),
    salary_currency  text,
    salary_period    text,

    status           text        not null default 'PENDING',
    review_note      text,

    -- The published row, once there is one.
    job_id           bigint      references jobs (id) on delete set null,

    created_at       timestamptz not null default now(),
    reviewed_at      timestamptz,

    constraint employer_postings_status_check
        check (status in ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    -- Same vocabulary as `jobs`, minus UNKNOWN: a form that offers "I would
    -- rather not say" is a form that collects it.
    constraint employer_postings_scope_check
        check (geo_scope in ('WORLDWIDE', 'REGION', 'COUNTRY'))
);

create index employer_postings_queue_idx
    on employer_postings (created_at) where status = 'PENDING';

create index employer_postings_by_employer_idx
    on employer_postings (employer_id, created_at desc);

-- Direct postings are a third kind of source alongside the boards we read and
-- the aggregator feeds.
alter table companies drop constraint companies_source_kind_check;
alter table companies
    add constraint companies_source_kind_check
    check (source_kind in ('ATS', 'FEED', 'DIRECT'));

-- How long a role keeps its place in the homepage's direct-postings section.
--
-- Not a second kind of listing: when the window closes the role stays on the
-- board, in search and on its company page exactly as before, and only loses the
-- promoted slot. Expiring the slot rather than the listing is what keeps the
-- section honest as volume grows, and it is the thing that can later be sold
-- without changing what a listing is.
alter table jobs add column featured_until timestamptz;

create index jobs_featured_idx on jobs (featured_until desc)
    where closed_at is null and featured_until is not null;

comment on column jobs.featured_until is
    'While in the future, this role appears in the homepage section for roles posted directly by employers. Expiry removes the promotion, never the listing.';
