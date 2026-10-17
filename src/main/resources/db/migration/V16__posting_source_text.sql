-- ============================================================================
-- What the employer actually typed.
--
-- The column held HTML because the first version of the form took HTML. It now
-- takes plain text with a small amount of Markdown, and the HTML a listing
-- renders is derived from it when the posting is approved.
--
-- Renaming rather than adding a second column: there is one authored artefact
-- here and it is the text. Keeping both would mean deciding, on every edit,
-- which of the two is the truth -- and the answer would quietly become "the one
-- that was written last", which is how a draft and its published form drift
-- apart.
--
-- Existing rows are plain text already: the form fed a textarea straight into
-- this column, so no conversion is needed.
-- ============================================================================

alter table employer_postings rename column description_html to description_source;

comment on column employer_postings.description_source is
    'What the employer typed: plain text, with a small Markdown subset. The HTML on the published jobs row is derived from this.';
