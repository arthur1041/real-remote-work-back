-- ============================================================================
-- A day's sending, counted.
--
-- The relay has a daily quota. Until now nothing here knew the number, so the
-- only way the system learned it was by being refused -- and because a lead is
-- stamped as invited before the attempt, every refusal cost a person who would
-- never be written to again. Discovering a limit by hitting it is affordable
-- for one message and not for an import of two hundred.
--
-- One row per day, claimed atomically. A claim is the insert itself: the
-- counter advances and the caller learns whether it was allowed in a single
-- statement, so two schedulers waking at the same second cannot both be told
-- there is one message left.
--
-- `cold` is counted separately because the two kinds of mail are not worth the
-- same. A confirmation is somebody waiting at a form right now, and a review
-- alert is an employer who was promised a day; an invitation is a stranger who
-- has waited this long already and can wait until tomorrow. When the budget
-- tightens, the cold mail is what stops -- see MailBudget, which holds back a
-- reserve that only the transactional kinds may spend.
--
-- The day is the database's current_date, which is UTC here. That is also when
-- a relay quota resets, and the two agreeing matters more than either matching
-- anybody's local midnight.
-- ============================================================================

create table mail_budget (
    day         date    primary key,
    sent        integer not null default 0,
    cold        integer not null default 0,
    -- What the sender believed the limits were. Written with every claim, so
    -- the admin area can show "212 of 280" without the website holding a
    -- second copy of the pipeline's configuration and drifting from it.
    daily_limit integer not null,
    cold_limit  integer not null,
    constraint mail_budget_sent_nonneg check (sent >= 0),
    constraint mail_budget_cold_sane   check (cold >= 0 and cold <= sent)
);

comment on table mail_budget is
    'One row per UTC day: how many messages were claimed, and how many of those were cold invitations.';
comment on column mail_budget.sent is
    'Every message claimed today, of any kind. Compared against newsletter.budget.daily.';
comment on column mail_budget.daily_limit is
    'The configured daily cap at the time of the last claim, so the admin area reads limits rather than guessing them.';
comment on column mail_budget.cold is
    'The subset that was a cold invitation. These stop early, leaving a reserve for transactional mail.';
