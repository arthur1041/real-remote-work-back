-- Where a candidate's answer to an employer's letter goes, chosen per batch.
--
-- Since pipeline v24 (24 Nov 2026) a letter carries Reply-To: the employer's
-- confirmed sign-up address. The first employer to use the feature asked for
-- the next step on the same day: the address an applicant should answer is not
-- always the one the account signed up with -- a shared hiring inbox, a
-- colleague running the interviews. So the batch names it, and the address is
-- kept with each letter as the record of where answers were sent.
--
-- Null means "the sign-up address", resolved at send time and only when it is
-- confirmed (ApplicationReplySender). A value here is one the employer typed
-- into their own form, for their own letters.
alter table application_replies add column reply_to text;

-- The addresses an employer has used before, so the next batch is a pick from
-- a list rather than typing it again. The sign-up address is never stored here:
-- it is always offered, and it can change.
create table employer_reply_addresses (
    id           bigserial   primary key,
    employer_id  bigint      not null references employers (id) on delete cascade,
    address      text        not null,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz not null default now()
);
create unique index employer_reply_addresses_once
    on employer_reply_addresses (employer_id, lower(address));
