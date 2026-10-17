package com.remoteroles.pipeline.domain;

/**
 * The applicant tracking systems we can read.
 *
 * <p>Each of these serves an unauthenticated JSON board endpoint per company,
 * which is the whole basis of the pipeline: we are reading published job boards
 * through their own public APIs, not scraping.
 */
public enum AtsType {
    GREENHOUSE,
    LEVER,
    ASHBY,

    /**
     * Himalayas' public job feed.
     *
     * <p>Not an ATS, but it occupies the same column: it is a source of postings.
     * It earns its place because it publishes {@code locationRestrictions} per
     * posting, and an empty list there is an explicit statement that a role has no
     * geographic gate -- the single most valuable signal available anywhere, and
     * the only reliable supply of the worldwide roles this site exists to list.
     */
    HIMALAYAS,

    /**
     * WeWorkRemotely's RSS feeds.
     *
     * <p>The most productive source by a wide margin: its {@code <region>} element
     * says "Anywhere in the World" outright, and about nine postings in ten carry
     * it. A handful of category feeds — one cheap request each, no pagination and no
     * bot challenge — yield a couple of hundred genuinely worldwide roles, where
     * Himalayas needs thousands of requests to find a comparable number.
     */
    WEWORKREMOTELY,

    /**
     * Posted by the employer themselves, through the site.
     *
     * <p>The odd one out: there is nothing to fetch and no schedule to run. It
     * occupies this column because a posting has to say where it came from, and
     * because the ingest must never treat these rows as stale and retire them --
     * nothing will ever "disappear from the board" to confirm they are still open,
     * since there is no board to read.
     *
     * <p>It is also the only source where eligibility is answered rather than
     * inferred. The employer is asked outright, and a human checks the answer
     * before the role is published.
     */
    DIRECT
}
