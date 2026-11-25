-- "Needs a change": a rejection that is really a request for one fix.
--
-- Most refusals in the queue are a single field set wrong -- the case that
-- prompted this (25 Nov 2026) was a role declared "Anyone, anywhere" whose
-- description required living in Israel. Telling that employer their role was
-- "not published" reads as final, and the people most likely to pay are the
-- ones still trying to post. So a refusal now says which kind it is.
--
-- A flag beside the status rather than a third status, on purpose. Everything
-- that reads `status` -- the Free plan's allowance, the dashboard filters, the
-- admin counts, the decision email's sweep -- already treats REJECTED as "off
-- the board, editable, resubmittable", which is exactly right for both kinds.
-- The flag changes only what the employer is told. It is meaningful only while
-- the status is REJECTED, and every rejection sets it explicitly.
alter table employer_postings add column needs_work boolean not null default false;
alter table posting_reviews  add column needs_work boolean not null default false;

comment on column employer_postings.needs_work is
    'With status REJECTED: true when the reviewer asked for a change rather than refusing the role. Changes the label and the email, nothing else.';
comment on column posting_reviews.needs_work is
    'On a REJECTED decision: it was a request for a change, not a refusal.';
