-- ============================================================================
-- Facets: benefits, normalized employment type, salary, seniority.
--
-- These exist to be filtered on. Benefits and employment kind are closed sets
-- because they become URLs and filter values; letting them follow whatever an
-- employer typed would give a filter list that grows forever and matches nothing.
-- ============================================================================

alter table jobs add column benefits text[] not null default '{}';
alter table jobs add column employment_kind text;
alter table jobs add column seniority text;

comment on column jobs.benefits is
    'Closed-set benefit tags extracted from the description. Absent means not '
    'mentioned, never "not offered" -- roughly half of postings list none.';
comment on column jobs.employment_kind is
    'Normalized from the employer''s own wording: FullTime, Full Time, Full-Time '
    'and CLT all mean one thing and must filter as one thing.';

-- Containment queries: benefits @> '{STOCK_OPTIONS}'
create index jobs_benefits_idx on jobs using gin (benefits);

create index jobs_employment_kind_idx
    on jobs (employment_kind)
    where closed_at is null and is_remote is true;

-- Salary filtering. Partial, because only a minority of postings state one and
-- the index should not carry the rest.
create index jobs_salary_idx
    on jobs (salary_min)
    where closed_at is null and salary_min is not null;
