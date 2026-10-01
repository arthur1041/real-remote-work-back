-- ============================================================================
-- Feed sources.
--
-- The ATS boards seeded in V2 are big, well-known employers, and they almost
-- never hire worldwide: employing someone abroad means payroll and tax in that
-- country, so they gate on the countries where they already have an entity. Out
-- of 7,871 ATS postings exactly 2 were location-independent, which is not a
-- product.
--
-- Genuinely worldwide roles come from a different shape of source: aggregators
-- that publish an explicit geographic restriction per posting. Those are not
-- per-company boards, so one row in `companies` now means one of two things,
-- distinguished by source_kind:
--
--   ATS   a company's own board, polled at a known URL      (V2 seeds)
--   FEED  an employer discovered inside an aggregator feed  (created on sight)
--
-- The distinction matters most for expiry. An ATS board is fetched in full every
-- run, so a posting's absence means it closed. A feed is paginated and may be
-- crawled partially, so absence means nothing -- those postings carry the feed's
-- own expiry date instead.
-- ============================================================================

alter table companies
    add column source_kind text not null default 'ATS';

alter table companies
    add constraint companies_source_kind_check check (source_kind in ('ATS', 'FEED'));

comment on column companies.source_kind is
    'ATS = own job board, polled in full. FEED = employer seen inside an aggregator feed.';

-- Feed employers are created as they are encountered, so the slug is the natural
-- identity. Partial index: ATS companies keep using (ats_type, ats_token).
create index companies_feed_slug_idx on companies (slug) where source_kind = 'FEED';


alter table jobs
    add column expires_at timestamptz;

comment on column jobs.expires_at is
    'Publisher-stated expiry. Feed postings close on this date rather than on '
    'disappearing from a paginated crawl, where absence carries no information.';

create index jobs_expiry_sweep_idx
    on jobs (expires_at)
    where closed_at is null and expires_at is not null;
