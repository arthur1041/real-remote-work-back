package com.remoteroles.pipeline.ingest;

import com.remoteroles.pipeline.ats.FeedFetcher;
import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.CanonicalJob;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.normalize.Normalizer;
import com.remoteroles.pipeline.repo.CompanyRepository;
import com.remoteroles.pipeline.repo.JobRepository;
import com.remoteroles.pipeline.repo.RawPostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests worldwide postings from aggregator feeds.
 *
 * <p>Separate from {@link IngestionService} because the two have opposite expiry
 * semantics, and conflating them is how a job board silently empties itself. An ATS
 * board is read in full every run, so a missing posting has closed. A feed is
 * paginated and crawled partially, so a missing posting means only that the crawl
 * did not reach it — those postings retire on the publisher's own expiry date,
 * swept separately by {@link JobRepository#closeExpired()}.
 */
@Service
public class FeedIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FeedIngestionService.class);

    private final List<FeedFetcher> feeds;
    private final CompanyRepository companies;
    private final RawPostingRepository rawPostings;
    private final JobRepository jobs;
    private final Normalizer normalizer;
    private final TransactionTemplate tx;

    public FeedIngestionService(List<FeedFetcher> feeds,
                                CompanyRepository companies,
                                RawPostingRepository rawPostings,
                                JobRepository jobs,
                                Normalizer normalizer,
                                TransactionTemplate tx) {
        this.feeds = feeds;
        this.companies = companies;
        this.rawPostings = rawPostings;
        this.jobs = jobs;
        this.normalizer = normalizer;
        this.tx = tx;
    }

    public record FeedSummary(int created, int updated, int expired, int failed) {}

    public FeedSummary runOnce() {
        int created = 0;
        int updated = 0;
        int failed = 0;

        for (FeedFetcher feed : feeds) {
            try {
                List<FetchedPosting> postings = feed.fetch();
                log.info("{}: {} worldwide postings", feed.source(), postings.size());

                // Employer ids are cached for the run: a feed of a thousand postings
                // resolves to a few hundred employers, and each resolution is a write.
                Map<String, Long> employerIds = new HashMap<>();

                for (FetchedPosting posting : postings) {
                    try {
                        if (persist(feed.source(), posting, employerIds) == JobRepository.UpsertOutcome.CREATED) {
                            created++;
                        } else {
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.warn("{}: failed to persist posting {}",
                                feed.source(), posting.externalId(), e);
                    }
                }
            } catch (Exception e) {
                failed++;
                log.error("{}: feed crawl failed", feed.source(), e);
            }
        }

        int expired = tx.execute(status -> jobs.closeExpired());
        if (expired > 0) {
            log.info("closed {} postings past their stated expiry", expired);
        }

        log.info("feed ingest done: +{} new, ~{} updated, -{} expired, {} failed",
                created, updated, expired, failed);
        return new FeedSummary(created, updated, expired, failed);
    }

    private JobRepository.UpsertOutcome persist(AtsType source, FetchedPosting posting,
                                                Map<String, Long> employerIds) {
        String employer = posting.employerName();
        long companyId = employerIds.computeIfAbsent(employer,
                name -> tx.execute(s -> companies.resolveFeedEmployer(name, source)));

        // A minimal Company so the normalizer sees the same shape it does for ATS
        // postings. Domain is unknown from the feed, so dedupe falls back to name.
        Company company = new Company(companyId, employer, null, source, employer,
                "ACTIVE", 0, null, null, "FEED");

        return tx.execute(status -> {
            CanonicalJob job = normalizer.normalize(company, posting);
            rawPostings.saveIfNew(companyId, source, posting.externalId(),
                    job.contentHash(), posting.rawPayload());
            return jobs.upsert(job);
        });
    }
}
