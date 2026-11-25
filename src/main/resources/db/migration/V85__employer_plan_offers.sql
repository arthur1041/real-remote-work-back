-- Offers of a paid plan, sent from the admin area to one employer at a time.
--
-- The site writes the row and the pipeline sends it, like every other mail
-- here. The words and prices are written by the site into `content`, not kept
-- in the pipeline: the site already holds the prices and the list of what each
-- plan adds (web/lib/plans.ts), and a second copy here would be the copy that
-- goes stale the next time a price moves. The pipeline only lays it out.
--
-- `plans` is what was offered, for the admin list to say so; `content` is the
-- email as it was written at the moment of sending, kept as the record of what
-- this account was told.
create table employer_plan_offers (
    id          bigserial   primary key,
    employer_id bigint      not null references employers (id) on delete cascade,
    email       text        not null,
    plans       text[]      not null,
    content     jsonb       not null,
    created_at  timestamptz not null default now(),
    sent_at     timestamptz,
    failed_at   timestamptz,
    failure     text
);

create index employer_plan_offers_unsent_idx
    on employer_plan_offers (created_at)
    where sent_at is null and failed_at is null;

create index employer_plan_offers_employer_idx
    on employer_plan_offers (employer_id, created_at desc);

comment on table employer_plan_offers is
    'A paid plan offered to one employer from the admin area. Written by the site, sent by PlanOfferSender.';
