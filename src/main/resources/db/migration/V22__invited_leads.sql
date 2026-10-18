-- ============================================================================
-- Addresses we contacted, who never asked us to.
--
-- A lead is not a subscriber and must not be stored as one. The difference is
-- the whole point: a PENDING row is somebody who typed their address into the
-- form and has not clicked the confirmation yet, while an INVITED row is
-- somebody who did nothing at all. Treating the second as the first would turn
-- a single invitation into a subscription nobody asked for.
--
-- That distinction is load-bearing in code, not just in naming. The digest
-- audience setting offers CONFIRMED or ALL, and ALL means PENDING plus
-- CONFIRMED. If a lead were stored as PENDING it would start receiving the
-- weekly digest on the next run, with no decision from anyone -- the system
-- would quietly do the thing the invitation exists to avoid.
--
-- invited_at records that the one contact has happened. There is exactly one:
-- somebody who ignores an invitation has answered it, and asking again is how
-- an invitation becomes a nuisance.
-- ============================================================================

alter table subscribers drop constraint subscribers_status_check;
alter table subscribers
    add constraint subscribers_status_check
    check (status in ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED', 'BOUNCED', 'BLOCKED', 'INVITED'));

alter table subscribers add column invited_at timestamptz;

-- Leads waiting for their one invitation to be sent.
create index subscribers_awaiting_invite_idx
    on subscribers (created_at)
    where status = 'INVITED' and invited_at is null;

comment on column subscribers.invited_at is
    'When the single invitation was sent. Null means not sent yet; set means never send another.';
