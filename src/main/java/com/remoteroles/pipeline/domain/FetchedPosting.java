package com.remoteroles.pipeline.domain;

import java.time.Instant;

/**
 * One posting as it came off an ATS, with field names unified but values not yet
 * interpreted.
 *
 * <p>The important member is {@link #remoteHint()}. Ashby and Lever state
 * remoteness explicitly; Greenhouse does not, and leaves us only free text like
 * {@code "Remote "}. Carrying a tri-state hint rather than a boolean keeps
 * "the board told us it is not remote" distinct from "the board said nothing",
 * so the classifier knows when it is allowed to guess.
 *
 * @param remoteHint {@code TRUE}/{@code FALSE} when the ATS states it,
 *                   {@code null} when the ATS is silent.
 * @param rawPayload the original JSON for this posting, verbatim, so the raw
 *                   layer can store exactly what the server sent.
 */
public record FetchedPosting(
        String externalId,
        String title,
        String applyUrl,
        String locationRaw,
        String descriptionHtml,
        Instant postedAt,
        Boolean remoteHint,
        String department,
        String employmentType,
        String rawPayload,

        /**
         * Employer name, when the posting came from a feed rather than from a
         * company's own board. Null for ATS postings, where the company is already
         * known from the row being polled.
         */
        String employerName,

        /** Publisher-stated expiry, for sources that provide one. */
        Instant expiresAt,

        /**
         * Structured salary, when the source states it rather than burying it in
         * prose. Himalayas publishes it on a feed field; Ashby publishes it as
         * typed compensation components. Everything else stays null rather than
         * being guessed at from the description -- a misparsed salary is worse
         * than no salary.
         */
        java.math.BigDecimal salaryMin,
        java.math.BigDecimal salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String seniority
) {
    /** ATS postings: the company is the board being polled. */
    public static FetchedPosting ats(String externalId, String title, String applyUrl,
                                     String locationRaw, String descriptionHtml,
                                     Instant postedAt, Boolean remoteHint,
                                     String department, String employmentType,
                                     String rawPayload) {
        return new FetchedPosting(externalId, title, applyUrl, locationRaw, descriptionHtml,
                postedAt, remoteHint, department, employmentType, rawPayload, null, null,
                null, null, null, null, null);
    }

    /**
     * The same posting with a salary attached.
     *
     * <p>Separate from {@link #ats} because salary arrives from a different part of
     * the response than the rest of the posting, and only on the boards that choose
     * to publish it. Folding four more parameters into a factory that three fetchers
     * already call positionally would cost more than it saves.
     */
    public FetchedPosting withSalary(java.math.BigDecimal min, java.math.BigDecimal max,
                                     String currency, String period) {
        return new FetchedPosting(externalId, title, applyUrl, locationRaw, descriptionHtml,
                postedAt, remoteHint, department, employmentType, rawPayload, employerName,
                expiresAt, min, max, currency, period, seniority);
    }
}
