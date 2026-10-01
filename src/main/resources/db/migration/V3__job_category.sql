-- ============================================================================
-- Role categories.
--
-- Raw `department` is what the employer typed: 346 distinct values across 2,500
-- postings, including "Scaling", "GTM" and "Forward Deployed Engineering". Useful
-- to display, useless to navigate by.
--
-- `category` is a small closed set, and it exists for a specific reason: landing
-- pages. An individual job URL dies when the role closes, so it never accumulates
-- search authority. Category and scope pages persist and keep earning while the
-- postings inside them churn, which is the only way a traffic-funded site builds
-- anything durable.
-- ============================================================================

alter table jobs add column category text;

comment on column jobs.category is
    'Normalized role family from title and department. Drives landing pages; see RoleCategorizer.';

-- Serves /remote-jobs/<category> and /remote-jobs/<category>/<scope>.
create index jobs_category_feed_idx
    on jobs (category, posted_at desc nulls last)
    where closed_at is null and is_remote is true;

create index jobs_category_scope_idx
    on jobs (category, geo_scope, posted_at desc nulls last)
    where closed_at is null and is_remote is true;
