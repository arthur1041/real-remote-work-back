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
    ASHBY
}
