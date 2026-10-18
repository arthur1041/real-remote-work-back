-- ============================================================================
-- One reminder, and then nothing.
--
-- The first version sent a single invitation and stopped. A reminder to someone
-- who never responded is defensible -- mail gets missed -- but "resend every
-- month" with no ceiling is a drip campaign written slowly, and the person who
-- ignored it twice has answered twice.
--
-- So two columns rather than one flag:
--
--   invite_count         how many have gone out. Capped at 2 in code.
--   invite_requested_at  when a send was asked for, by import or by a resend.
--
-- The sender compares the two timestamps instead of reading a boolean, which
-- makes "due" a fact about the row rather than a state somebody has to
-- remember to clear. Import and resend are then the same operation: both set
-- invite_requested_at, and the sender does not care which did.
-- ============================================================================

alter table subscribers add column invite_count integer not null default 0;
alter table subscribers add column invite_requested_at timestamptz;

-- Existing invited rows have already had their first send asked for.
update subscribers
   set invite_requested_at = coalesce(invited_at, created_at),
       invite_count = case when invited_at is null then 0 else 1 end
 where status = 'INVITED';

drop index if exists subscribers_awaiting_invite_idx;

-- Leads with a send due: asked for, and not yet satisfied.
create index subscribers_invite_due_idx
    on subscribers (invite_requested_at)
    where status = 'INVITED';

comment on column subscribers.invite_count is
    'Invitations sent to this address. Two is the ceiling: the original and one reminder.';
comment on column subscribers.invite_requested_at is
    'When a send was last asked for. A send is due while this is later than invited_at.';
