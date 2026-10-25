-- ============================================================================
-- Asking somebody for the CV they never uploaded.
--
-- The gap between an account and a document is the whole candidate funnel, and
-- /admin/candidates has been counting it since 24 October without offering any
-- way to act on it. Roughly half the accounts on the board have no CV, which
-- is half a candidate base that cannot be shown to an employer: the profile
-- says what somebody does, the document is what gets read.
--
-- Written the way every other message in this system is written -- the web app
-- inserts a row, the pipeline sends it. See V26 and V37. Nothing in the web
-- app holds SMTP and nothing is sent inside a request.
-- ============================================================================

-- --------------------------------------------------------------- the queue --
create table candidate_cv_requests (
    id           bigserial   primary key,
    candidate_id bigint      not null references candidates (id) on delete cascade,
    -- The address as it was when the ask was made. Kept on the row rather than
    -- read through the join at send time, so the record says where the message
    -- actually went even after somebody changes their address.
    email        text        not null,
    sent_at      timestamptz,
    -- Queued and then not sent, with the reason. The conditions are checked
    -- twice, here and again at send time, because seconds pass in between and
    -- the one thing this message must never do is ask for a CV that arrived
    -- while it was waiting in the queue.
    skipped_at   timestamptz,
    skip_reason  text,
    created_at   timestamptz not null default now()
);

create index candidate_cv_requests_pending
    on candidate_cv_requests (created_at)
    where sent_at is null and skipped_at is null;

-- Reads the last ask for one person, which is what the repeat guard and the
-- admin table both want.
create index candidate_cv_requests_recent
    on candidate_cv_requests (candidate_id, created_at desc);

-- At most one ask in flight per person, enforced here rather than in the
-- application. The repeat guard in the web app reads the table and then writes
-- to it in one statement, which is atomic but not serialisable: two presses a
-- few milliseconds apart -- a double click, or two tabs -- both see no earlier
-- row in their own snapshot and both insert. The result is the same person
-- receiving the same message twice, which is the exact thing that turns a
-- reminder into a complaint. A partial unique index is the only place that
-- race can be settled, and it costs one `on conflict do nothing`.
create unique index candidate_cv_requests_one_in_flight
    on candidate_cv_requests (candidate_id)
    where sent_at is null and skipped_at is null;

-- ------------------------------------------------------------- the way out --
-- A candidate has had no way to stop mail that is not a code they asked for,
-- because until now there was none. There is now, so there is an opt-out, and
-- it exists before the first message rather than after the first complaint:
-- `EmailLayout.footer` refuses to render without one, and the reason is in the
-- comment there -- a reader who cannot find the way out presses "report spam"
-- instead, and that costs the same domain reputation the confirmation codes
-- and the employer mail are spending.
--
-- Deliberately not a preference page. One token, one press, everything that is
-- not a reply to something they just did stops.
alter table candidates
    add column mail_opt_out_at    timestamptz,
    add column mail_opt_out_token text;

-- gen_random_uuid() is in core since Postgres 13, so this needs no extension.
-- Hyphens stripped: the value travels in a URL and in a List-Unsubscribe
-- header, where 32 hex characters are easier to read back out of a log.
update candidates
   set mail_opt_out_token = replace(gen_random_uuid()::text, '-', '')
 where mail_opt_out_token is null;

alter table candidates
    alter column mail_opt_out_token set default replace(gen_random_uuid()::text, '-', ''),
    alter column mail_opt_out_token set not null;

create unique index candidates_mail_opt_out_token
    on candidates (mail_opt_out_token);
