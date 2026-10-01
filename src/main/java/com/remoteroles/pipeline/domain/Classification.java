package com.remoteroles.pipeline.domain;

/**
 * The verdict on a posting's remoteness.
 *
 * @param confidence 0..1. Low-confidence rows are still stored but should be
 *                   held back from the "truly worldwide" feed, which is the
 *                   claim users actually trust us for.
 * @param classifiedBy identifier of the classifier that produced this, e.g.
 *                     {@code RULES_V1}. Recorded per row so that when a better
 *                     classifier ships we can re-run only the rows it beats.
 */
public record Classification(
        boolean isRemote,
        GeoScope geoScope,
        String geoDetail,
        String timezoneRequirement,
        double confidence,
        String classifiedBy
) {
    public static Classification notRemote(String classifiedBy) {
        return new Classification(false, null, null, null, 1.0, classifiedBy);
    }
}
