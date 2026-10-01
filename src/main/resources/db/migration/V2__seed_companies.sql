-- ============================================================================
-- Seed companies.
--
-- Every token below was verified live against its ATS endpoint before being
-- committed (63 boards, ~7,871 open postings at seed time). These were
-- found by slug-guessing company names against all three ATS and keeping the
-- 200s -- 63 hits from 77 candidate names, which is why that approach is
-- worth automating rather than curating by hand.
--
-- Caveat: a slug is only unique WITHIN an ATS. "ghost" and "neon" resolve on
-- two different ATS and may be two different companies; dedupe keys on domain,
-- not slug, for exactly this reason.
-- ============================================================================

insert into companies (name, domain, ats_type, ats_token) values
    ('Airbyte', 'airbyte.com', 'ASHBY', 'airbyte'),
    ('Buffer', 'buffer.com', 'ASHBY', 'buffer'),
    ('Clerk', 'clerk.com', 'ASHBY', 'clerk'),
    ('ClickUp', 'clickup.com', 'ASHBY', 'clickup'),
    ('Confluent', 'confluent.io', 'ASHBY', 'confluent'),
    ('Ghost', 'ghost.org', 'ASHBY', 'ghost'),
    ('GitBook', 'gitbook.com', 'ASHBY', 'gitbook'),
    ('LangChain', 'langchain.com', 'ASHBY', 'langchain'),
    ('Linear', 'linear.app', 'ASHBY', 'linear'),
    ('LlamaIndex', 'llamaindex.ai', 'ASHBY', 'llamaindex'),
    ('Miro', 'miro.com', 'ASHBY', 'miro'),
    ('Modal', 'modal.com', 'ASHBY', 'modal'),
    ('Mux', 'mux.com', 'ASHBY', 'mux'),
    ('Neon', 'neon.tech', 'ASHBY', 'neon'),
    ('Notion', 'notion.so', 'ASHBY', 'notion'),
    ('OpenAI', 'openai.com', 'ASHBY', 'openai'),
    ('Oyster', 'oysterhr.com', 'ASHBY', 'oyster'),
    ('Pinecone', 'pinecone.io', 'ASHBY', 'pinecone'),
    ('PostHog', 'posthog.com', 'ASHBY', 'posthog'),
    ('Prefect', 'prefect.io', 'ASHBY', 'prefect'),
    ('Ramp', 'ramp.com', 'ASHBY', 'ramp'),
    ('Render', 'render.com', 'ASHBY', 'render'),
    ('Replit', 'replit.com', 'ASHBY', 'replit'),
    ('Sanity', 'sanity.io', 'ASHBY', 'sanity'),
    ('Sentry', 'sentry.io', 'ASHBY', 'sentry'),
    ('Supabase', 'supabase.com', 'ASHBY', 'supabase'),
    ('Temporal', 'temporal.io', 'ASHBY', 'temporal'),
    ('Zapier', 'zapier.com', 'ASHBY', 'zapier'),
    ('Airbnb', 'airbnb.com', 'GREENHOUSE', 'airbnb'),
    ('Airtable', 'airtable.com', 'GREENHOUSE', 'airtable'),
    ('Anthropic', 'anthropic.com', 'GREENHOUSE', 'anthropic'),
    ('Calendly', 'calendly.com', 'GREENHOUSE', 'calendly'),
    ('Cloudflare', 'cloudflare.com', 'GREENHOUSE', 'cloudflare'),
    ('Cockroach Labs', 'cockroachlabs.com', 'GREENHOUSE', 'cockroachlabs'),
    ('Coinbase', 'coinbase.com', 'GREENHOUSE', 'coinbase'),
    ('Contentful', 'contentful.com', 'GREENHOUSE', 'contentful'),
    ('Databricks', 'databricks.com', 'GREENHOUSE', 'databricks'),
    ('Datadog', 'datadoghq.com', 'GREENHOUSE', 'datadog'),
    ('Discord', 'discord.com', 'GREENHOUSE', 'discord'),
    ('Doximity', 'doximity.com', 'GREENHOUSE', 'doximity'),
    ('Dropbox', 'dropbox.com', 'GREENHOUSE', 'dropbox'),
    ('Duolingo', 'duolingo.com', 'GREENHOUSE', 'duolingo'),
    ('Elastic', 'elastic.co', 'GREENHOUSE', 'elastic'),
    ('Figma', 'figma.com', 'GREENHOUSE', 'figma'),
    ('Fivetran', 'fivetran.com', 'GREENHOUSE', 'fivetran'),
    ('Ghost', 'ghost.org', 'GREENHOUSE', 'ghost'),
    ('GitLab', 'gitlab.com', 'GREENHOUSE', 'gitlab'),
    ('Grafana Labs', 'grafana.com', 'GREENHOUSE', 'grafanalabs'),
    ('MongoDB', 'mongodb.com', 'GREENHOUSE', 'mongodb'),
    ('Netlify', 'netlify.com', 'GREENHOUSE', 'netlify'),
    ('Pinterest', 'pinterest.com', 'GREENHOUSE', 'pinterest'),
    ('PlanetScale', 'planetscale.com', 'GREENHOUSE', 'planetscale'),
    ('Reddit', 'reddit.com', 'GREENHOUSE', 'reddit'),
    ('Remote', 'remote.com', 'GREENHOUSE', 'remote'),
    ('Robinhood', 'robinhood.com', 'GREENHOUSE', 'robinhood'),
    ('Scale AI', 'scale.com', 'GREENHOUSE', 'scaleai'),
    ('Storyblok', 'storyblok.com', 'GREENHOUSE', 'storyblok'),
    ('Stripe', 'stripe.com', 'GREENHOUSE', 'stripe'),
    ('Twilio', 'twilio.com', 'GREENHOUSE', 'twilio'),
    ('Vercel', 'vercel.com', 'GREENHOUSE', 'vercel'),
    ('Webflow', 'webflow.com', 'GREENHOUSE', 'webflow'),
    ('Fly.io', 'fly.io', 'LEVER', 'fly'),
    ('Neon', 'neon.tech', 'LEVER', 'neon')
on conflict (ats_type, ats_token) do nothing;
