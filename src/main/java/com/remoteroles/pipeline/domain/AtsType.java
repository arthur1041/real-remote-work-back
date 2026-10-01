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
    WEWORKREMOTELY
}
