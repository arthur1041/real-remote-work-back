-- ============================================================================
-- An application that arrives tells somebody.
--
-- `job_applications` shipped with `read_at`, which records that the employer
-- opened their inbox, and with nothing that records whether they were ever told
-- there was anything in it. Nothing in the pipeline read the table at all. So a
-- role taking applications here collected them in a dashboard the employer had
-- no reason to open: every other event in this system announces itself --
-- registration, verification, a moderation decision, a password reset -- and
-- the one that matters most to the person who posted the role did not.
--
-- The stamp means "this arrival was announced", exactly like
-- `employer_postings.decision_notified_at` does for a moderation decision, and
-- it is set by the sender in the same statement that spends the budget. One
-- message per application and never a second: an employer who deletes the mail
-- has the dashboard, and a mailer that can re-send on a crash is a mailer that
-- will eventually send the same notice every fifteen seconds all night.
-- ============================================================================

alter table job_applications
    add column notified_at timestamptz;

comment on column job_applications.notified_at is
    'When the employer was told this application existed. Null means the notice is still owed.';

-- The sender's query: the oldest unannounced application, nothing else. Partial
-- so it stays the size of the backlog rather than the size of the table, which
-- for a notice that is almost always already sent is the difference between an
-- index worth having and one that is read once a day for nothing.
create index job_applications_unnotified
    on job_applications (created_at)
    where notified_at is null;
