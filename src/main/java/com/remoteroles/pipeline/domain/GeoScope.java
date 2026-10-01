package com.remoteroles.pipeline.domain;

/**
 * How geographically open a remote role actually is.
 *
 * <p>This is the distinction the whole site rests on. "Remote" in a job posting
 * means anything from "work from any beach on earth" to "remote, but you must
 * be within commuting distance of Austin". Collapsing those into one boolean is
 * what makes most job boards useless.
 */
public enum GeoScope {
    /** Truly location-independent. No country or region gate stated. */
    WORLDWIDE,
    /** Open across a multi-country region: EMEA, LATAM, APAC, EU. */
    REGION,
    /** Gated to a single country. */
    COUNTRY,
    /** Remote, but the posting does not say how open. */
    UNKNOWN
}
