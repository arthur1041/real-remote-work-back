-- ============================================================================
-- Durable rate limiting and an admin audit trail.
--
-- Both were previously in-process: counters in a Map that reset when the
-- container restarted and were invisible to any second instance. That is a
-- speed bump, not a control -- an attacker only has to wait for a deploy, and
-- behind two containers the effective limit doubles.
-- ============================================================================

-- Fixed-window counters. One row per (bucket, window), incremented on conflict,
-- so the check and the increment are a single atomic statement with no read.
create table rate_limits (
    bucket       text        not null,
    window_start timestamptz not null,
    count        int         not null default 0,
    primary key (bucket, window_start)
);

comment on table rate_limits is
    'Fixed-window counters shared across instances. Buckets hold a hash of the '
    'client address, never the address itself -- this is a record of who visited '
    'and should not be readable as one.';

-- Old windows are dead weight; this makes sweeping them cheap.
create index rate_limits_sweep_idx on rate_limits (window_start);


-- Who signed in to the admin area, when, and whether it worked.
create table admin_audit (
    id       bigserial   primary key,
    at       timestamptz not null default now(),
    event    text        not null,
    outcome  text        not null,
    ip_hash  text,
    detail   text
);

comment on column admin_audit.ip_hash is
    'Hash of the client address. Enough to recognise a burst from one source '
    'without the table becoming a log of who looked at what.';

create index admin_audit_recent_idx on admin_audit (at desc);
