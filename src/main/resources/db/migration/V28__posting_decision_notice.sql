-- ============================================================================
-- Telling the employer what was decided about their role.
--
-- Until now a decision was visible only to whoever went looking for it. An
-- employer submitted a role, was told it is usually reviewed within a day, and
-- then had to come back and refresh a page to find out whether it happened.
-- For a rejection that is worse than silence: the note explaining what to fix
-- was written, stored, and shown to nobody.
--
-- Same shape as `review_notified_at` above it, and for the same reason: the
-- stamp lives on the row the decision belongs to rather than in a queue table,
-- so a decision and the record of having announced it cannot drift apart.
--
-- Existing rows are backfilled as already announced. Without that, shipping
-- this mails every employer about every decision ever made -- including roles
-- withdrawn months ago -- which is the first impression this is meant to fix,
-- inverted.
-- ============================================================================

alter table employer_postings add column decision_notified_at timestamptz;

update employer_postings
   set decision_notified_at = coalesce(reviewed_at, created_at)
 where status in ('APPROVED', 'REJECTED');

-- The sender's queue: decided, not yet announced.
create index employer_postings_undecided_notice_idx
    on employer_postings (reviewed_at)
    where decision_notified_at is null and status in ('APPROVED', 'REJECTED');

comment on column employer_postings.decision_notified_at is
    'When the employer was told the outcome. Null on a fresh decision, which is what the sender looks for. Cleared again when an edit sends the role back for review, so the next decision is announced too.';
