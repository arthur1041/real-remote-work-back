package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.CanonicalJob;
import com.remoteroles.pipeline.domain.Classification;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchedPosting;
import org.springframework.stereotype.Component;

/** Turns a fetched posting plus its company into the row the website reads. */
@Component
public class Normalizer {

    private final LocationClassifier classifier;
    private final RoleCategorizer categorizer;

    public Normalizer(LocationClassifier classifier, RoleCategorizer categorizer) {
        this.classifier = classifier;
        this.categorizer = categorizer;
    }

    public CanonicalJob normalize(Company company, FetchedPosting posting) {
        Classification classification = classifier.classify(posting);

        String contentHash = Hashing.contentHash(
                posting.title(), posting.locationRaw(), posting.applyUrl(), posting.descriptionHtml());

        String dedupeKey = Hashing.dedupeKey(company.domain(), company.name(), posting.title());

        return new CanonicalJob(
                company.id(),
                company.atsType(),
                posting.externalId(),
                contentHash,
                posting.title(),
                posting.descriptionHtml(),
                posting.applyUrl(),
                posting.locationRaw(),
                classification,
                posting.employmentType(),
                posting.department(),
                posting.postedAt(),
                posting.expiresAt(),
                dedupeKey,
                categorizer.categorize(posting.title(), posting.department())
        );
    }
}
