-- ============================================================================
-- Eligibility with holes in it.
--
-- The form offered three answers: anyone anywhere, a region, one country. Real
-- postings routinely mean a fourth and a fifth -- open to anyone except a
-- handful of countries, or open across a region except a few of its members --
-- and an employer with one of those had to round it to something untrue.
-- Rounding down to "one country" buries the role; rounding up to "anyone,
-- anywhere" is the exact lie this board exists to catch, told by the form
-- itself.
--
-- Two decisions are worth writing down.
--
-- `WORLDWIDE_EXCEPT` is its own scope rather than WORLDWIDE with a list beside
-- it. Dozens of queries across the site select `geo_scope = 'WORLDWIDE'`, and
-- that predicate is the site's promise expressed in SQL: no country
-- requirement at all. A role excluding two countries has a country
-- requirement. Storing it as WORLDWIDE would have put it on the homepage
-- through every one of those queries, silently, and the promise would have
-- become false everywhere at once. As a separate value, every existing query
-- stays correct without being touched.
--
-- "A region, except" needs no new scope at all: it is REGION with a non-empty
-- exclusion list, and the two are the same statement. The form reads the
-- distinction back out of the list when an employer edits a posting.
-- ============================================================================

alter table jobs              add column geo_excluded text[];
alter table employer_postings add column geo_excluded text[];

comment on column jobs.geo_excluded is
    'Place codes carved out of the stated eligibility. Meaningful with WORLDWIDE_EXCEPT and with REGION; null or empty everywhere else.';
comment on column employer_postings.geo_excluded is
    'What the employer excluded, as place codes. Non-empty with REGION means "that region, except these".';

alter table jobs drop constraint jobs_geo_scope_check;
alter table jobs add constraint jobs_geo_scope_check check (
    geo_scope is null
    or geo_scope in ('WORLDWIDE', 'WORLDWIDE_EXCEPT', 'REGION', 'COUNTRY', 'UNKNOWN')
);

alter table employer_postings drop constraint employer_postings_scope_check;
alter table employer_postings add constraint employer_postings_scope_check check (
    geo_scope in ('WORLDWIDE', 'WORLDWIDE_EXCEPT', 'REGION', 'COUNTRY')
);

-- An exception list only means something against a scope that can have holes.
-- WORLDWIDE with exclusions is the contradiction described above, and COUNTRY
-- with exclusions is a country minus itself.
alter table employer_postings add constraint employer_postings_excluded_check check (
    geo_excluded is null
    or cardinality(geo_excluded) = 0
    or geo_scope in ('WORLDWIDE_EXCEPT', 'REGION')
);

-- And a scope that is nothing but holes is not a scope.
alter table employer_postings add constraint employer_postings_except_needs_list check (
    geo_scope <> 'WORLDWIDE_EXCEPT'
    or (geo_excluded is not null and cardinality(geo_excluded) > 0)
);
