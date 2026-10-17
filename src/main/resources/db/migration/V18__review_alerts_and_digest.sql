-- ============================================================================
-- Two things that need to know they already happened.
--
-- review_notified_at: a submission waits for a person, and the person has no
-- reason to be looking. Without a stamp the alert either never fires or fires
-- every minute until the posting is reviewed, and the second failure is the one
-- that trains someone to ignore the mail.
--
-- newsletter_issues: what was sent, and when. The digest selects roles first
-- seen since the last issue, so "when was the last issue" has to be a fact
-- rather than something inferred from subscriber rows -- those move
-- independently as people confirm and unsubscribe, and reading the cutoff from
-- them would send a new subscriber a week of roles they never asked about or
-- skip a week for everyone if one send failed halfway.
-- ============================================================================

alter table employer_postings add column review_notified_at timestamptz;

create index employer_postings_unnotified_idx
    on employer_postings (created_at)
    where status = 'PENDING' and review_notified_at is null;

comment on column employer_postings.review_notified_at is
    'When the moderator was told this was waiting. Null means not yet told.';

create table newsletter_issues (
    id          bigserial   primary key,
    sent_at     timestamptz not null default now(),
    -- The window this issue covered, so the next one can start where it ended.
    covers_from timestamptz not null,
    job_count   integer     not null,
    recipients  integer     not null
);

create index newsletter_issues_recent_idx on newsletter_issues (sent_at desc);

comment on table newsletter_issues is
    'One row per digest actually sent. The newest row is the cutoff for the next.';
