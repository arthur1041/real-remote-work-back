-- ============================================================================
-- The day's sending allowance, moved from configuration into the admin area.
--
-- These two numbers were environment variables, which meant changing either one
-- took an SSH session and a container restart. That is the wrong shape for them:
-- they are not deployment settings, they are operational decisions somebody
-- makes while watching a lead import drain, in the same breath as the cadence
-- and the audience that already live in this table.
--
-- daily is the provider's limit, not a preference. It exists because an invited
-- lead is stamped before the send is attempted, so a message the relay refuses
-- costs a person who will never be written to again. Claiming the budget first
-- turns that into a lead that simply goes tomorrow.
--
-- transactional_reserve is the preference. Held back from the first message of
-- the day and spendable only by mail somebody is waiting for -- a confirmation
-- link, a review alert, the digest. Set it to zero and invitations may consume
-- the whole day, which is a legitimate choice and a quiet one: the cost is that
-- somebody signing up that day never receives their confirmation link, and
-- nothing anywhere reports it.
-- ============================================================================

alter table newsletter_settings
    add column mail_daily_budget integer not null default 300,
    add column mail_transactional_reserve integer not null default 0;

alter table newsletter_settings
    add constraint newsletter_settings_mail_budget_sane
    check (mail_daily_budget >= 0
           and mail_transactional_reserve >= 0
           and mail_transactional_reserve <= mail_daily_budget);

comment on column newsletter_settings.mail_daily_budget is
    'Messages of any kind that may be claimed per UTC day. Set to the mail provider plan''s real limit.';
comment on column newsletter_settings.mail_transactional_reserve is
    'The slice of that budget cold invitations may not touch, kept for confirmation links, review alerts and the digest.';
