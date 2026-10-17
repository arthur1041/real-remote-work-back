-- ============================================================================
-- Per-listing counts, rolled up by day.
--
-- An employer who posts here has no way of knowing whether anything came of it,
-- and "did anyone look?" is the first question any of them will ask. These two
-- numbers answer it.
--
-- Rolled up rather than one row per event. A row per view grows without bound
-- for a number nobody reads at that resolution, and the daily grain is still
-- enough to draw a line. One row per listing per active day, and a listing with
-- no activity on a day costs nothing.
--
-- Nothing identifying is stored. There is no visitor id, no address, no agent
-- string -- two integers against a date. An employer learns that forty people
-- looked, never which forty, and the table cannot be turned into something that
-- would say.
-- ============================================================================

create table job_daily_stats (
    job_id       bigint  not null references jobs (id) on delete cascade,
    day          date    not null,
    views        integer not null default 0,
    apply_clicks integer not null default 0,

    primary key (job_id, day)
);

create index job_daily_stats_by_day_idx on job_daily_stats (day desc);

comment on table job_daily_stats is
    'Aggregate per-listing counts by day. No visitor-level data of any kind.';
