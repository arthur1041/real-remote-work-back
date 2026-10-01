package com.remoteroles.pipeline.domain;

import java.time.Instant;

/** A fetched posting plus its classification: the row the website reads. */
public record CanonicalJob(
        long companyId,
        AtsType atsType,
        String externalId,
        String contentHash,
        String title,
        String descriptionHtml,
        String applyUrl,
        String locationRaw,
        Classification classification,
        String employmentType,
        String department,
        Instant postedAt,
        String dedupeKey,
        RoleCategory category
) {}
