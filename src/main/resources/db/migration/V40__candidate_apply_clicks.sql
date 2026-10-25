-- ============================================================================
-- Which roles a signed-in candidate actually went off to apply for.
--
-- The board has counted apply clicks since the beginning, but only in
-- `job_daily_stats`, which is (job_id, day, views, apply_clicks) -- an
-- aggregate per listing, with nobody's name on it. So /admin/candidates has
-- been showing an "applications" number that counts only the applications
-- taken *here*, through `job_applications`, and silently ignores every click
-- that left for an employer's own site. For a board whose listings are 99%
-- external, that is the large majority of what a candidate actually did.
--
-- This records the other half, per person and per role.
--
-- Three things it deliberately is not:
--
-- * **Not a replacement for `job_applications`.** An application taken here is
--   a document, an answer set and a conversation with an employer. A click is
--   an intention. They are counted side by side in the admin, never summed
--   into one undifferentiated number, because an employer being told "twelve
--   applications" when eleven were clicks would be the same lie the old number
--   told in the other direction.
--
-- * **Not a record of everybody.** Applying needs no account on this board
--   (docs/PROJECT.md), so an anonymous click cannot be attributed to anyone
--   and is not stored here at all. The number in the admin is therefore "what
--   this person did while signed in", and the page says so rather than
--   implying it is everything.
--
-- * **Not a second apply-click counter.** The aggregate in `job_daily_stats`
--   stays exactly as it is, including its rate limit, because that number is
--   what an employer is shown and what a price may one day rest on. This table
--   answers a different question for one operator.
-- ============================================================================

create table candidate_apply_clicks (
    candidate_id bigint      not null references candidates (id) on delete cascade,
    job_id       bigint      not null references jobs (id) on delete cascade,
    -- One row per person per role, counting repeats rather than adding rows.
    -- Somebody who opens a listing twice before applying has not applied
    -- twice, and the question the admin asks is "which roles", so the distinct
    -- pair is the unit. `clicks` is kept because a role opened six times is a
    -- different signal from one opened once.
    clicks       int         not null default 1,
    first_at     timestamptz not null default now(),
    last_at      timestamptz not null default now(),
    primary key (candidate_id, job_id)
);

-- Reads one person's history newest-first, which is the only way the admin
-- page asks for it.
create index candidate_apply_clicks_person
    on candidate_apply_clicks (candidate_id, last_at desc);

-- `on delete cascade` against `jobs` is safe because nothing in this system
-- deletes a job: a listing that closes keeps its row and gets `closed_at`, so
-- the join below always resolves and a candidate's history survives the role
-- being taken down. The cascade is there for the case that does happen --
-- a candidate deleting their account, where the whole point is that their
-- trail goes with them.
