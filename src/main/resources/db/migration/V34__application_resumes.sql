-- ============================================================================
-- A CV sent with one application is not the CV on the profile.
--
-- The first version had one document per person: applying used it, replacing it
-- replaced it everywhere. That is wrong in the ordinary case rather than the
-- edge case. People keep more than one CV and send the one that fits the role,
-- and somebody who tailors a résumé for a single application has not decided to
-- make it the one every future employer sees.
--
-- So a résumé now says what it is for. A PROFILE one is the single current
-- document on the account, replaced when a new one is uploaded there. An
-- APPLICATION one belongs to the application it was sent with, is never
-- promoted to the profile, and never displaces anything.
--
-- Existing rows are PROFILE by the column default, which is what they were.
-- ============================================================================

alter table candidate_resumes
    add column scope text not null default 'PROFILE'
        constraint candidate_resumes_scope_check check (scope in ('PROFILE', 'APPLICATION'));

-- One current PROFILE document per person. The old index covered every scope,
-- which would have made the second application upload collide with the first.
drop index if exists candidate_resumes_current;

create unique index candidate_resumes_current
    on candidate_resumes (candidate_id)
    where replaced_at is null and scope = 'PROFILE';

-- Application documents are found through the application that pins them, so
-- the lookup that matters is already covered by job_applications.resume_id.
create index candidate_resumes_by_scope
    on candidate_resumes (candidate_id, scope, uploaded_at desc);
