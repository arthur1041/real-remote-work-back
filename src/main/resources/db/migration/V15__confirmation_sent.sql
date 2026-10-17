-- ============================================================================
-- When the confirmation for an address was sent.
--
-- Distinct from `last_sent_at`, which records newsletter delivery. The two
-- answer different questions and collapsing them would make "has this person
-- been asked to confirm?" unanswerable the moment a newsletter goes out.
--
-- Null means the confirmation has not been sent, which is what the sender
-- selects on. It is stamped before the send rather than after, so a crash
-- mid-send cannot produce a loop that mails the same person repeatedly: the
-- cost of that failure is one unsent confirmation, recoverable by hand, versus
-- a mailbox full of duplicates and a burnt sending reputation.
-- ============================================================================

alter table subscribers add column confirmation_sent_at timestamptz;

create index subscribers_awaiting_confirmation_idx
    on subscribers (created_at)
    where status = 'PENDING' and confirmation_sent_at is null;

comment on column subscribers.confirmation_sent_at is
    'When the double opt-in confirmation was sent. Null means not yet sent.';
