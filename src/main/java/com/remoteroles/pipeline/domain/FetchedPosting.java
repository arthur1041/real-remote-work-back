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
        String rawPayload
) {}
