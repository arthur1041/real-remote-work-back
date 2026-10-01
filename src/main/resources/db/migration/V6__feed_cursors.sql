-- ============================================================================
-- Resumable crawl position per feed.
--
-- Himalayas sits behind Cloudflare and returns 429 after roughly sixty requests
-- in a window. Its catalogue is ~93,000 postings at 20 per page, so a full pass
-- is about 4,700 requests and cannot be done in one run — and must not be
-- attempted, since answering a bot challenge is not something we do.
--
-- So the crawl is budgeted and resumable: each run walks a small slice from
-- where the last one stopped and records where it got to, and the back catalogue
-- is covered over successive runs rather than in one sitting.
-- ============================================================================

create table feed_cursors (
    source       text        primary key,
    next_offset  int         not null default 0,
    -- Set when a pass reaches the end and wraps, so it is visible whether the
    -- back catalogue has been covered even once.
    laps         int         not null default 0,
    last_run_at  timestamptz,
    last_status  text
);

comment on table feed_cursors is
    'Where each paginated feed crawl resumes. Feeds that are cheap to read in '
    'full (an RSS file) do not appear here.';
