-- ============================================================================
-- Newsletter settings, editable without a deploy.
--
-- Cadence and audience were compile-time constants, which meant changing either
-- was a build and a ship. They are operational decisions somebody makes while
-- looking at how the list is behaving, so they live in a row.
--
-- A single row, pinned by a check constraint. A settings table that can hold two
-- rows eventually holds two rows, and then the behaviour depends on which one
-- the query happened to read.
-- ============================================================================

create table newsletter_settings (
    id          integer     primary key default 1,
    enabled     boolean     not null default true,

    -- How often an issue may go out, in hours. The sender wakes hourly and
    -- compares this against the last issue, so changing it takes effect at the
    -- next wake-up rather than at the next deploy.
    interval_hours integer  not null default 168,

    -- CONFIRMED: only addresses whose owner clicked the confirmation link.
    -- ALL: every address held that has not unsubscribed or been blocked.
    --
    -- Defaults to ALL, which is the operator's decision rather than this file's
    -- recommendation. Unconfirmed addresses carry a higher complaint rate, and
    -- complaints land on the domain that also carries the confirmation mails and
    -- the moderation alerts. The switch exists so the choice can be revisited
    -- from the admin area the moment that shows up in the numbers.
    audience    text        not null default 'ALL',

    min_jobs    integer     not null default 5,
    max_jobs    integer     not null default 15,
    updated_at  timestamptz not null default now(),

    constraint newsletter_settings_single_row check (id = 1),
    constraint newsletter_settings_audience_check check (audience in ('CONFIRMED', 'ALL')),
    constraint newsletter_settings_interval_check check (interval_hours between 1 and 8760)
);

insert into newsletter_settings (id) values (1) on conflict do nothing;

-- Blocking an address by hand. Distinct from UNSUBSCRIBED, which is the person's
-- own decision and must never be reversed by us; BLOCKED is ours, for an address
-- that bounces, complains, or is obviously not a person.
alter table subscribers drop constraint subscribers_status_check;
alter table subscribers
    add constraint subscribers_status_check
    check (status in ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED', 'BOUNCED', 'BLOCKED'));

comment on table newsletter_settings is
    'One row. Cadence, audience and size limits for the digest, editable from the admin area.';
