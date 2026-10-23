-- A model reads the description and says whether it contradicts the worldwide
-- tag. This records what it said, every time it is asked.
--
-- The verdict is stored rather than only applied because the result has to be
-- accountable: the admin report answers "how many were taken off the homepage
-- and why", and a verdict that only mutated geo_scope leaves nothing to answer
-- it with. It also cannot be reconstructed later -- descriptions are rewritten
-- in place on every fetch and roles close.
--
-- One row per decision, not per job. A job re-judged after its description
-- changes gets a second row, so the history of what was decided and on what
-- text stays intact.

create table job_scope_review (
    id             bigserial primary key,
    job_id         bigint      not null references jobs(id) on delete cascade,
    decided_at     timestamptz not null default now(),

    -- Which model, kept per row rather than in config, so a change of model is
    -- visible in the data instead of only in a deploy.
    model          text        not null,

    -- What it concluded. `restricted` false is a real answer and is stored:
    -- knowing the model looked and found nothing is what makes the cost and
    -- the hit rate measurable.
    restricted     boolean     not null,

    -- The place it named, as it wrote it. Deliberately free text and not a
    -- place code: it is evidence for a person reading the report, never a
    -- value anything filters on. Resolving prose to a code is the unreliable
    -- half of this job -- see RuleBasedLocationClassifier, which answers
    -- UNKNOWN rather than guessing a replacement.
    place          text,

    -- The sentence the model pointed at, checked against the description
    -- before this row is written. A restriction nobody can point at is one the
    -- model invented.
    quote          text,

    -- Whether this verdict actually changed the listing. False when the job
    -- had already moved on, so the report never claims credit it is not owed.
    applied        boolean     not null default false,

    prompt_tokens  integer     not null default 0,
    output_tokens  integer     not null default 0,

    -- Set when the call failed. The row is still written: a day where every
    -- call errored must not look like a day where nothing was restricted.
    error          text
);

-- The pass picks up worldwide jobs with no verdict for their current text.
create index job_scope_review_job_idx on job_scope_review (job_id, decided_at desc);

-- The admin report reads by day.
create index job_scope_review_day_idx on job_scope_review (decided_at desc);

-- What the reviewer has already seen. Compared against jobs.content_hash so a
-- rewritten description is judged again and an untouched one is never paid for
-- twice.
alter table jobs add column scope_reviewed_hash text;

-- Only worldwide jobs are ever candidates: a role already gated cannot
-- contradict a promise it does not make. Partial, so the queue lookup stays
-- small as the table grows.
create index jobs_scope_review_pending_idx
    on jobs (id)
    where closed_at is null
      and geo_scope = 'WORLDWIDE'
      and scope_reviewed_hash is distinct from content_hash;
