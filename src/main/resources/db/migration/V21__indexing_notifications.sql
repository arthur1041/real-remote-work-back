-- ============================================================================
-- Which postings Google has been told about.
--
-- The Indexing API's default quota is a couple of hundred calls a day and the
-- board holds sixteen thousand postings, so "notify everything on every run" is
-- not a strategy -- it would spend the day's quota on the first slice of the
-- corpus and never reach the roles that actually changed.
--
-- This column is what makes the notifier incremental: a row with no stamp has
-- never been announced, a row whose stamp predates its content change needs
-- announcing again, and a closed row that was once announced needs the opposite
-- call. Everything else is left alone.
-- ============================================================================

alter table jobs add column indexing_notified_at timestamptz;

-- Open worldwide roles waiting to be announced. Partial, because the notifier
-- only ever looks at roles that belong in the sitemap.
create index jobs_indexing_pending_idx
    on jobs (content_changed_at)
    where closed_at is null and is_remote is true and geo_scope = 'WORLDWIDE';

-- Roles that were announced and have since closed: these need URL_DELETED.
create index jobs_indexing_closed_idx
    on jobs (closed_at)
    where closed_at is not null and indexing_notified_at is not null;

comment on column jobs.indexing_notified_at is
    'When Google was last notified about this URL through the Indexing API. Null means never.';
