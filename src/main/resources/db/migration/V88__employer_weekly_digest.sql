-- A weekly summary for employers, sent only when their week had something in it.
--
-- Agreed 25 Nov 2026 (docs/TODOs.md, "Weekly digest for employers"). The
-- commonest failure is not the product breaking but the employer not coming
-- back: on that day the one real Growth account had two screened applications
-- nobody had opened. The digest brings each employer back to what is waiting.
--
-- It is recurring and not the answer to anything they did, so unlike every
-- other mail an employer gets it carries a way out: one token, one press, the
-- same shape as `candidates.mail_opt_out_token` (V39).
alter table employers
    add column digest_opt_out_at    timestamptz,
    add column digest_opt_out_token text;

update employers
   set digest_opt_out_token = replace(gen_random_uuid()::text, '-', '')
 where digest_opt_out_token is null;

alter table employers
    alter column digest_opt_out_token set default replace(gen_random_uuid()::text, '-', ''),
    alter column digest_opt_out_token set not null;

create unique index employers_digest_opt_out_token on employers (digest_opt_out_token);

-- One row per digest sent: what it said, for the record, and the guard that
-- keeps a second run in the same week from sending it twice.
create table employer_digests (
    id          bigserial   primary key,
    employer_id bigint      not null references employers (id) on delete cascade,
    sent_at     timestamptz not null default now(),
    summary     jsonb       not null
);

create index employer_digests_employer_idx on employer_digests (employer_id, sent_at desc);

comment on table employer_digests is
    'Weekly employer digests sent, one row each. Read by EmployerDigestSender to send at most one a week.';
