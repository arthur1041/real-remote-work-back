-- The site's own visit counter (owner, 27 Nov 2026).
--
-- Why: Clarity and GA4 disagreed by a factor of two and a half (20,080 against
-- 7,952 sessions over the same thirty days), GA4 only counts visitors who
-- accept its cookies, and neither could say whether the late-November fall was
-- real. These two tables are written by the site's own beacon (and by the apply
-- redirect, which ChatGPT links to directly and which never loads a page), so
-- the trend no longer depends on a third party's sampling or on consent.
--
-- No cookie and no address is stored. A visitor is a hash of the address and
-- the browser keyed with a secret and the day, so the same person is one
-- visitor within a day and cannot be followed from one day to the next.
-- Written by the web app, like job_daily_stats.

create table site_daily_hits (
    day        date    not null,
    path_group text    not null,
    source     text    not null,
    country    text    not null default '',
    device     text    not null default '',
    views      integer not null default 0,
    primary key (day, path_group, source, country, device)
);

create table site_daily_visitors (
    day      date    not null,
    visitor  text    not null,
    -- Where the visitor's first page of the day came from, and which page it was.
    source   text    not null,
    landing  text    not null,
    country  text    not null default '',
    device   text    not null default '',
    pages    integer not null default 1,
    primary key (day, visitor)
);

create index site_daily_visitors_day on site_daily_visitors (day, source);
