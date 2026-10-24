-- ============================================================================
-- When a candidate profile first became searchable.
--
-- The site has measured traffic since the beginning and has never measured a
-- single thing somebody *did*: `gtag` is called for `config` and for consent,
-- and for nothing else. So the one number that says whether the candidate side
-- works — how many people arrive, start an account, and finish one an employer
-- could actually find — does not exist. This column is what makes it countable.
--
-- **It is a stamp, not a flag, and it is written once.** The analytics event
-- fires on the transition from null to a timestamp, so the question "has this
-- already been counted?" and the act of counting it are the same statement:
--
--     update candidates set profile_completed_at = now()
--      where id = :id and profile_completed_at is null and <complete>
--
-- A refresh, a double-submitted form, two open tabs and a revisit a month later
-- all lose that race and come back with no row, which is precisely the
-- duplicate-conversion problem that made this a column rather than something in
-- localStorage. A browser-side guard would also be per-device, and somebody who
-- finishes their profile on a phone and opens the account page on a laptop is
-- one conversion, not two.
--
-- Never cleared. A profile that later loses its CV is not a new conversion when
-- the CV comes back; the thing being counted is the first time this person
-- became findable, and counting it twice would make the number mean nothing.
-- If "re-completed after lapsing" is ever worth knowing, it is a different
-- question and deserves its own column.
--
-- Null on every existing row, including profiles that are already complete.
-- That is correct and deliberate: this records an event that was observed, and
-- nothing observed the ones that completed before it existed. Back-filling it
-- would invent conversions on a date they did not happen.
-- ============================================================================

alter table candidates
    add column profile_completed_at timestamptz;

comment on column candidates.profile_completed_at is
    'When this profile first satisfied every completeness condition at once. Set exactly once, never cleared; the analytics conversion fires on the transition.';

-- The reporting query is "how many completed, and when", so the index is on the
-- column itself and partial, because the rows that matter are the non-null ones
-- and for a long time most rows will not be.
create index candidates_profile_completed
    on candidates (profile_completed_at)
    where profile_completed_at is not null;
