-- ============================================================================
-- Why a promoted role is promoted.
--
-- `featured_until` says a role holds a slot and until when. It cannot say why,
-- and three different reasons are about to share that space: an employer who
-- paid for the position, an employer who posted for free, and a role the board
-- itself selected to fill the section while there are not enough of either.
--
-- Without this column they are indistinguishable, which breaks two things at
-- once. Ordering, because paid has to outrank free and free has to outrank a
-- pick nobody asked for. And honesty, because a role the board chose must never
-- be presented as one a company paid to put there.
--
-- Null means not promoted at all, which is every aggregated role until it is
-- selected. The editorial picks are not written here: they are computed at read
-- time from the live corpus, so they cannot go stale and cannot leave the
-- section empty when the roles behind them close.
-- ============================================================================

alter table jobs
    add column featured_kind text;

alter table jobs
    add constraint jobs_featured_kind_check
    check (featured_kind is null or featured_kind in ('PAID', 'FREE'));

-- Everything already holding a slot got there by being approved, for free.
update jobs
   set featured_kind = 'FREE'
 where featured_until is not null;

-- The read path asks for live promoted roles in precedence order.
create index jobs_featured_kind_idx
    on jobs (featured_kind, featured_until desc)
    where featured_until is not null;

comment on column jobs.featured_kind is
    'PAID: the employer bought the position. FREE: an approved posting''s own window. Null: not promoted. Editorial picks are computed at read time and never stored here.';
