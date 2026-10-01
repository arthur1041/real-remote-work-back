-- ============================================================================
-- Company slugs, for durable /companies/<slug> landing pages.
--
-- Derived from the domain rather than the name, which has the useful side effect
-- of merging boards that belong to one company: a company running both a Lever and
-- an Ashby board becomes one page showing every open role, instead of two thin
-- pages competing with each other in search results.
--
-- A generated column keeps it honest -- there is no way to insert a company whose
-- slug disagrees with its domain. Not unique: merging on domain is the point.
-- ============================================================================

alter table companies
    add column slug text generated always as (
        trim(both '-' from regexp_replace(lower(coalesce(domain, name)), '[^a-z0-9]+', '-', 'g'))
    ) stored;

create index companies_slug_idx on companies (slug);
