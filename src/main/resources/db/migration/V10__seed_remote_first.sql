-- ============================================================================
-- Second seed: remote-first companies.
--
-- The first seed was well-known tech brands, and it was the wrong population.
-- Those companies gate remote hiring on the countries where they hold a legal
-- entity: across 63 boards and ~8,000 postings they yielded TWO worldwide roles.
--
-- These 29 boards are remote-first companies and companies that hire through
-- an employer of record. Measured before adding them: ~1,359 open postings, of
-- which 139 state an unrestricted location outright -- against 2 from the entire
-- original seed.
--
-- Every token below was verified live against its ATS before being committed.
-- ============================================================================

insert into companies (name, domain, ats_type, ats_token) values
    ('Alan', 'alan.com', 'ASHBY', 'alan'),
    ('Andela', 'andela.com', 'ASHBY', 'andela'),
    ('Camunda', 'camunda.com', 'ASHBY', 'camunda'),
    ('Close', 'close.com', 'ASHBY', 'close'),
    ('Coder', 'coder.com', 'ASHBY', 'coder'),
    ('Docker', 'docker.com', 'ASHBY', 'docker'),
    ('incident.io', 'incident.io', 'ASHBY', 'incident'),
    ('Kit', 'kit.com', 'ASHBY', 'kit'),
    ('n8n', 'n8n.io', 'ASHBY', 'n8n'),
    ('Pennylane', 'pennylane.com', 'ASHBY', 'pennylane'),
    ('Phrase', 'phrase.com', 'ASHBY', 'phrase'),
    ('Pleo', 'pleo.io', 'ASHBY', 'pleo'),
    ('Qonto', 'qonto.com', 'ASHBY', 'qonto'),
    ('Railway', 'railway.com', 'ASHBY', 'railway'),
    ('Canonical', 'canonical.com', 'GREENHOUSE', 'canonical'),
    ('Customer.io', 'customer.io', 'GREENHOUSE', 'customerio'),
    ('Fastly', 'fastly.com', 'GREENHOUSE', 'fastly'),
    ('Intercom', 'intercom.com', 'GREENHOUSE', 'intercom'),
    ('Lokalise', 'lokalise.com', 'GREENHOUSE', 'lokalise'),
    ('Mattermost', 'mattermost.com', 'GREENHOUSE', 'mattermost'),
    ('Mozilla', 'mozilla.org', 'GREENHOUSE', 'mozilla'),
    ('Rocket.Chat', 'rocket.chat', 'GREENHOUSE', 'rocketchat'),
    ('StackBlitz', 'stackblitz.com', 'GREENHOUSE', 'stackblitz'),
    ('Tailscale', 'tailscale.com', 'GREENHOUSE', 'tailscale'),
    ('Turing', 'turing.com', 'GREENHOUSE', 'turing'),
    ('Wikimedia Foundation', 'wikimedia.org', 'GREENHOUSE', 'wikimedia'),
    ('Wise', 'wise.com', 'GREENHOUSE', 'wise'),
    ('Qonto', 'qonto.com', 'LEVER', 'qonto'),
    ('Toptal', 'toptal.com', 'LEVER', 'toptal')
on conflict (ats_type, ats_token) do nothing;
