package com.remoteroles.pipeline.domain;

import java.time.Instant;

/** A fetched posting plus its classification: the row the website reads. */
public record CanonicalJob(
        long companyId,
        /** Denormalised so it can sit in the job's search vector; see V12. */
        String companyName,
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
        Instant expiresAt,
        String dedupeKey,
        RoleCategory category,
        java.util.Set<Benefit> benefits,
        EmploymentKind employmentKind,
        java.math.BigDecimal salaryMin,
        java.math.BigDecimal salaryMax,
        String salaryCurrency,
        String salaryPeriod,
        String seniority
) {}
