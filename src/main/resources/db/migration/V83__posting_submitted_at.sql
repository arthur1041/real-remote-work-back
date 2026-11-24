-- When the version now in front of the reviewer was sent.
--
-- The review queue showed `created_at`, the day the posting was first written.
-- A role submitted on 18 Oct, decided, edited and sent back on the 23rd arrived
-- saying "waiting 5 days" -- five days of which nobody had owed it anything.
-- The site now stamps this every time a posting goes (back) to PENDING: on
-- creation (the default), on an edit, and on relisting a withdrawn role.
--
-- Backfilled only where it is known: a posting still on its first version was
-- submitted when it was created. A later version's time was never recorded,
-- and inventing one would be the same mistake in the other direction, so it
-- stays null and the queue says so.
alter table employer_postings add column submitted_at timestamptz;
update employer_postings set submitted_at = created_at where content_version = 1;
alter table employer_postings alter column submitted_at set default now();
