-- ============================================================================
-- Make company names searchable.
--
-- The search vector covered title, department and location, so typing "vercel"
-- or "doximity" returned nothing -- which is one of the two things people type
-- into a job-board search box.
--
-- The company name lives on `companies`, and a generated column cannot read
-- another table, so it is denormalised onto `jobs`. That is a real duplication,
-- and the pipeline keeps it current: every upsert writes it alongside the rest
-- of the row, so a renamed company is corrected on the next run.
--
-- Weighted 'A', the same as the title: for a job board, "who is hiring" is as
-- strong a query as "what the job is".
-- ============================================================================

alter table jobs add column company_name text;

update jobs j set company_name = c.name from companies c where c.id = j.company_id;

-- A generated column cannot be altered in place; it has to be replaced. Dropping
-- it takes the GIN index with it, so that is recreated below.
alter table jobs drop column search_vector;

alter table jobs add column search_vector tsvector generated always as (
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(company_name, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(department, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(location_raw, '')), 'C')
) stored;

create index jobs_search_idx on jobs using gin (search_vector);
