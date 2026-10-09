-- Where the employer is based, as an ISO-3166 alpha-2 code.
--
-- A code rather than the board's own words, because the words are not usable.
-- Taking the last segment of Greenhouse's office string yields "Remote",
-- "Worldwide", "Any Location", "08018 Barcelona", "NY" and both "USA" and
-- "United States" for the same country. Resolving through the pipeline's
-- existing country gazetteer collapses the aliases and -- more importantly --
-- rejects everything that is not a country, so a card never says "HQ Remote".
--
-- On companies rather than jobs: an office is a property of the employer, so one
-- posting that carries an address fills the gap for all of them.
--
-- Nullable, and expected to stay null for many employers. Himalayas publishes no
-- address, Lever's location field says only "Remote", and a Greenhouse board can
-- omit its offices. Null means the source did not say -- never "no office", and
-- nothing downstream may infer anything from its absence.
alter table companies add column if not exists hq_country text;

comment on column companies.hq_country is
  'ISO-3166 alpha-2 code for the employer''s primary office, resolved from board '
  'payloads through the pipeline gazetteer. Null means unknown, not none.';
