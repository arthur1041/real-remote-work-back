-- ============================================================================
-- When a posting's content last actually changed.
--
-- The sitemap was publishing `last_seen_at` as <lastmod>. That column answers
-- "when did the ingest last see this on a board", which it does on every run
-- whether or not a word changed -- so a page byte-for-byte identical to
-- yesterday announced itself as modified today.
--
-- Google uses <lastmod> to decide whether re-fetching is worth it. A site whose
-- pages all claim to change daily and never do teaches it to disregard the
-- signal, and then an honest one later is worth nothing either. On a domain
-- being granted a few hundred crawls that is budget spent re-reading pages that
-- did not move.
--
-- The two cannot be the same column: `last_seen_at` has to keep updating on
-- every sighting because closeStale detects a withdrawn posting by its absence
-- from a run.
--
-- Backfilled from first_seen_at rather than from last_seen_at. A posting that
-- has not changed since it appeared is honestly described by the date it
-- appeared; seeding from last_seen_at would bake today's lie in permanently.
-- ============================================================================

alter table jobs add column content_changed_at timestamptz;

update jobs set content_changed_at = coalesce(posted_at, first_seen_at);

alter table jobs alter column content_changed_at set not null;
alter table jobs alter column content_changed_at set default now();

comment on column jobs.content_changed_at is
    'When the posting content last actually changed. Drives sitemap <lastmod>; distinct from last_seen_at, which records every sighting.';
