-- Conversations between Actually Remote itself and a candidate (owner,
-- 25 Nov 2026).
--
-- Until now every conversation had an employer and a role behind it. Two
-- things need one without either:
--
--   * occasional notes from the site in the candidate's chat -- the first is
--     the referral program, sent by the pipeline (ReferralChatNote) a few days
--     after sign-up and again every few months -- because the chat is the one
--     place on the site a candidate opens on their own and keeps coming back
--     to;
--   * the candidate's answer to one, and the owner's reply from /admin/messages,
--     so a question about the program has somewhere to go.
--
-- One SITE conversation per candidate, reused for every note: a thread per
-- note would fill the candidate's list with copies of the same sender.
--
-- The SITE rows carry no employer and no job, which is why those columns stop
-- being required -- for SITE only; the check keeps every EMPLOYER row as it
-- was. Queries that join jobs or filter by employer_id (the employer side, the
-- unread-message email, push, discovery) drop SITE rows on their own, and
-- that is what is wanted: a note from the site is not "a message about your
-- application" and must not be mailed or pushed as one.

alter table conversations
    add column kind text not null default 'EMPLOYER'
        check (kind in ('EMPLOYER', 'SITE')),
    -- When the owner last read the thread, the SITE counterpart of
    -- employer_read_at.
    add column site_read_at timestamptz;

alter table conversations alter column employer_id drop not null;
alter table conversations alter column job_id drop not null;

alter table conversations add constraint conversations_kind_shape check (
    (kind = 'EMPLOYER' and employer_id is not null and job_id is not null)
 or (kind = 'SITE' and employer_id is null and job_id is null and application_id is null)
);

create unique index conversations_site_once on conversations (candidate_id)
 where kind = 'SITE';

alter table conversation_messages drop constraint conversation_messages_sender_check;
alter table conversation_messages add constraint conversation_messages_sender_check
    check (sender in ('EMPLOYER', 'CANDIDATE', 'SITE'));

-- Which automatic note a SITE message was ('REFERRAL_INTRO',
-- 'REFERRAL_REMINDER'), so the cadence can be enforced and the admin page can
-- say how many were sent, read and answered. Null for anything typed by a
-- person, on either side.
alter table conversation_messages add column campaign text;
create index conversation_messages_campaign on conversation_messages (campaign, created_at)
 where campaign is not null;
