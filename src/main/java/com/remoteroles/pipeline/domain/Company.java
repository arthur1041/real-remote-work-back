package com.remoteroles.pipeline.domain;

import java.time.Instant;

/** A board we poll. */
public record Company(
        long id,
        String name,
        String domain,
        AtsType atsType,
        String atsToken,
        String status,
        int consecutiveFailures,
        Instant lastFetchedAt,
        Instant lastSuccessAt
) {}
