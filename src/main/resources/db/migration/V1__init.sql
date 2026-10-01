-- ============================================================================
-- Remote Roles :: core schema
--
-- Three layers, deliberately:
--   companies     the curated list of boards we poll. The crown jewel.
--   raw_postings  append-only capture of exactly what an ATS returned.
--   jobs          the normalized, classified, user-facing record.
--
-- The raw layer exists so that improving the classifier never requires
-- re-crawling: we re-run normalization over history instead.
-- ============================================================================

create table companies (
    id                   bigserial primary key,
    name                 text        not null,
    domain               text,
    ats_type             text        not null,
    ats_token            text        not null,
    logo_url             text,
    -- ACTIVE: polled normally. PAUSED: skipped by hand. DEAD: auto-retired
    -- after repeated failures, kept for the record rather than deleted.
    status               text        not null default 'ACTIVE',
    consecutive_failures int         not null default 0,
    last_fetched_at      timestamptz,
    last_success_at      timestamptz,
    last_error           text,
    created_at           timestamptz not null default now(),

    constraint companies_ats_identity_unique unique (ats_type, ats_token),
    constraint companies_status_check check (status in ('ACTIVE', 'PAUSED', 'DEAD'))
);

comment on column companies.ats_token is
    'The board slug in the ATS URL, e.g. "doximity" in boards-api.greenhouse.io/v1/boards/doximity/jobs';

create index companies_due_for_fetch_idx
    on companies (last_fetched_at nulls first)
    where status = 'ACTIVE';


-- ----------------------------------------------------------------------------
-- raw_postings: append-only. One row per distinct version of a posting.
--
-- The unique constraint on (company_id, external_id, content_hash) means an
-- unchanged posting re-seen on tomorrow's run does NOT write a new row, while
-- an edited posting does -- giving us free revision history at no storage cost
-- for the common case.
-- ----------------------------------------------------------------------------
create table raw_postings (
    id           bigserial   primary key,
    company_id   bigint      not null references companies (id) on delete cascade,
    ats_type     text        not null,
    external_id  text        not null,
    content_hash text        not null,
    payload      jsonb       not null,
    fetched_at   timestamptz not null default now(),

    constraint raw_postings_version_unique unique (company_id, external_id, content_hash)
);

create index raw_postings_lookup_idx on raw_postings (company_id, external_id);


-- ----------------------------------------------------------------------------
-- jobs: what the site reads. One row per posting, updated in place.
-- ----------------------------------------------------------------------------
create table jobs (
    id              bigserial primary key,
    company_id      bigint    not null references companies (id) on delete cascade,
    ats_type        text      not null,
    external_id     text      not null,
    content_hash    text      not null,

    title           text      not null,
    description_html text,
    apply_url       text      not null,

    -- --- the classification that IS the product -----------------------------
    location_raw    text,
    is_remote       boolean,
    -- WORLDWIDE   truly location-independent: what this site is actually for
    -- REGION      "Remote (EMEA)", "Remote - LATAM"
    -- COUNTRY     "Remote, US only"
    -- UNKNOWN     could not be determined with confidence
    geo_scope       text,
    geo_detail      text,
    timezone_requirement text,
    classification_confidence real,
    classified_by   text,

    -- --- enrichment ---------------------------------------------------------
    salary_min      numeric(12, 2),
    salary_max      numeric(12, 2),
    salary_currency text,
    salary_period   text,
    employment_type text,
    department      text,

    -- --- lifecycle ----------------------------------------------------------
    posted_at       timestamptz,
    first_seen_at   timestamptz not null default now(),
    last_seen_at    timestamptz not null default now(),
    -- Set when a posting disappears from its board. A job board's credibility
    -- lives and dies on this column being honest.
    closed_at       timestamptz,

    -- Cross-source identity: lets the same role arriving from a second source
    -- collapse onto one listing.
    dedupe_key      text      not null,

    search_vector tsvector generated always as (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(department, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(location_raw, '')), 'C')
    ) stored,

    constraint jobs_ats_identity_unique unique (company_id, external_id),
    constraint jobs_geo_scope_check check (
        geo_scope is null or geo_scope in ('WORLDWIDE', 'REGION', 'COUNTRY', 'UNKNOWN')
    )
);

create index jobs_search_idx on jobs using gin (search_vector);
create index jobs_dedupe_idx on jobs (dedupe_key);

-- The hot query: open, genuinely-remote roles, newest first. Partial index so
-- closed postings cost nothing.
create index jobs_live_feed_idx
    on jobs (posted_at desc nulls last)
    where closed_at is null and is_remote is true;

create index jobs_geo_filter_idx
    on jobs (geo_scope, posted_at desc nulls last)
    where closed_at is null and is_remote is true;

-- Drives the expiry sweep.
create index jobs_staleness_idx
    on jobs (company_id, last_seen_at)
    where closed_at is null;


-- ----------------------------------------------------------------------------
-- ingest_runs: observability. Without this you cannot tell "no new jobs today"
-- apart from "the pipeline silently broke three days ago".
-- ----------------------------------------------------------------------------
create table ingest_runs (
    id                bigserial primary key,
    started_at        timestamptz not null default now(),
    finished_at       timestamptz,
    companies_total   int not null default 0,
    companies_ok      int not null default 0,
    companies_failed  int not null default 0,
    jobs_created      int not null default 0,
    jobs_updated      int not null default 0,
    jobs_closed       int not null default 0,
    notes             text
);
