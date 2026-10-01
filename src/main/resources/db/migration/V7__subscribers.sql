-- ============================================================================
-- Newsletter subscribers.
--
-- Collection only, for now. No mail is configured and none is sent, so every row
-- lands and stays in PENDING -- which is the point rather than an oversight:
--
--   PENDING       address captured, ownership NOT proven.
--   CONFIRMED     the owner clicked a confirmation link. Only these may be mailed.
--   UNSUBSCRIBED  opted out. Kept, never deleted, so a later re-import cannot
--                 silently resurrect them.
--   BOUNCED       delivery failed permanently.
--
-- When SMTP is switched on, the first send must be the confirmation, and the
-- audience must be CONFIRMED only. Treating today's PENDING rows as a mailing
-- list later would mean mailing people who never agreed to it -- which is both the
-- fastest way to get a sending domain blocked and, under GDPR/CAN-SPAM, not
-- something to do.
-- ============================================================================

create table subscribers (
    id                bigserial   primary key,
    email             text        not null,
    status            text        not null default 'PENDING',

    -- Random, single-use, and generated at capture time so no flow can later
    -- depend on a token being guessable from the address.
    confirm_token     text        not null,
    unsubscribe_token text        not null,

    -- Which form the address came from, for attribution. Not an identifier.
    source            text,

    created_at        timestamptz not null default now(),
    confirmed_at      timestamptz,
    unsubscribed_at   timestamptz,
    last_sent_at      timestamptz,

    constraint subscribers_status_check
        check (status in ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED', 'BOUNCED'))
);

-- Case-insensitive uniqueness: Foo@Example.com and foo@example.com are one person.
create unique index subscribers_email_unique on subscribers (lower(email));

create unique index subscribers_confirm_token_idx on subscribers (confirm_token);
create unique index subscribers_unsubscribe_token_idx on subscribers (unsubscribe_token);

-- The send audience. Partial, because it is the only set that may ever be mailed.
create index subscribers_sendable_idx on subscribers (created_at)
    where status = 'CONFIRMED';
