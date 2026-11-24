-- The newsletter box on the candidate sign-up form.
--
-- A candidate who ticks it is added to `subscribers` as PENDING, tied to their
-- account here, with `confirmation_sent_at` set so ConfirmationSender does not
-- mail a second "confirm your subscription" message: the six-digit code that
-- confirms the account's address is the proof that the mailbox is theirs, and
-- the tick is the request. When that code is accepted, the row tied to the
-- account becomes CONFIRMED -- and only that row, so a box that was not ticked
-- never confirms anything.
alter table subscribers
    add column signup_candidate_id bigint references candidates (id) on delete set null;
create index subscribers_signup_candidate on subscribers (signup_candidate_id)
 where signup_candidate_id is not null;
