package com.remoteroles.pipeline.ingest;

import com.remoteroles.pipeline.ats.AtsHttp;
import com.remoteroles.pipeline.ats.FetcherRegistry;
import com.remoteroles.pipeline.config.IngestProperties;
import com.remoteroles.pipeline.domain.CanonicalJob;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchResult;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.normalize.Normalizer;
import com.remoteroles.pipeline.repo.CompanyRepository;
import com.remoteroles.pipeline.repo.IngestRunRepository;
import com.remoteroles.pipeline.repo.JobRepository;
import com.remoteroles.pipeline.repo.RawPostingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls every active board, normalizes what comes back, and retires what vanished.
 *
 * <p>Concurrency shape: one virtual thread per company, with a semaphore as the
 * politeness budget. These are other people's servers, so the limit exists to be
 * kind rather than because threads are scarce -- the whole point of virtual threads
 * here is that the blocking HTTP call in each task costs essentially nothing while
 * it waits.
 *
 * <p>Each company fetches <em>and</em> persists inside its own task and its own
 * transaction. That keeps peak memory at roughly {@code concurrency × board size}
 * instead of the entire corpus (one large employer alone returns a 13 MB payload),
 * and stops one company's bad data from rolling back everybody else's.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /**
     * A board that returns zero postings when we are holding at least this many open
     * listings for it is treated as suspicious rather than empty. See
     * {@link #sweepExpired}.
     */
    private static final int EMPTY_RESULT_SUSPICION_THRESHOLD = 5;

    private final CompanyRepository companies;
    private final RawPostingRepository rawPostings;
    private final JobRepository jobs;
    private final IngestRunRepository runs;
    private final FetcherRegistry fetchers;
    private final Normalizer normalizer;
    private final TransactionTemplate tx;
    private final IngestProperties props;
    private final FeedIngestionService feeds;

    public IngestionService(CompanyRepository companies,
                            RawPostingRepository rawPostings,
                            JobRepository jobs,
                            IngestRunRepository runs,
                            FetcherRegistry fetchers,
                            Normalizer normalizer,
                            TransactionTemplate tx,
                            IngestProperties props,
                            FeedIngestionService feeds) {
        this.companies = companies;
        this.rawPostings = rawPostings;
        this.jobs = jobs;
        this.runs = runs;
        this.fetchers = fetchers;
        this.normalizer = normalizer;
        this.tx = tx;
        this.props = props;
        this.feeds = feeds;
    }

    /** Result counters for one full pass over every active board. */
    public record RunSummary(
            long runId, int total, int ok, int failed,
            int created, int updated, int closed, Duration elapsed
    ) {}

    public RunSummary runOnce() {
        Instant startedAt = Instant.now();
        long runId = runs.start();
        List<Company> active = companies.findActive();

        log.info("ingest run {} starting: {} active boards, concurrency {}",
                runId, active.size(), props.concurrency());

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger updated = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();

        Semaphore politeness = new Semaphore(props.concurrency());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Company company : active) {
                pool.submit(() -> {
                    try {
                        politeness.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        FetchResult result = fetch(company);
                        // Sealed interface: the compiler will not let a new result
                        // kind be added without a decision being made here.
                        switch (result) {
                            case FetchResult.Success success -> {
                                Counts counts = persist(success);
                                ok.incrementAndGet();
                                created.addAndGet(counts.created());
                                updated.addAndGet(counts.updated());
                                closed.addAndGet(counts.closed());
                            }
                            case FetchResult.Failure failure -> {
                                failed.incrementAndGet();
                                handleFailure(failure);
                            }
                        }
                    } catch (Exception e) {
                        // Never let one board take the run down.
                        failed.incrementAndGet();
                        log.error("unhandled error for {}/{}",
                                company.atsType(), company.atsToken(), e);
                    } finally {
                        politeness.release();
                    }
                });
            }
        } // close() awaits every task

        // Aggregator feeds run after the ATS boards. They are the only source of
        // genuinely worldwide roles, which is what this site is for -- the ATS pass
        // supplies the country-gated majority and the company pages.
        FeedIngestionService.FeedSummary feedSummary = feeds.runOnce();
        created.addAndGet(feedSummary.created());
        updated.addAndGet(feedSummary.updated());
        closed.addAndGet(feedSummary.expired());

        Duration elapsed = Duration.between(startedAt, Instant.now());
        String notes = "elapsed=" + elapsed.toSeconds() + "s, feedFailures=" + feedSummary.failed();
        runs.finish(runId, active.size(), ok.get(), failed.get(),
                created.get(), updated.get(), closed.get(), notes);

        RunSummary summary = new RunSummary(runId, active.size(), ok.get(), failed.get(),
                created.get(), updated.get(), closed.get(), elapsed);
        log.info("ingest run {} done in {}s: {} ok, {} failed, +{} new, ~{} updated, -{} closed",
                runId, elapsed.toSeconds(), ok.get(), failed.get(),
                created.get(), updated.get(), closed.get());
        return summary;
    }

    private FetchResult fetch(Company company) {
        try {
            List<FetchedPosting> postings = fetchers.require(company.atsType()).fetch(company);
            return new FetchResult.Success(company, postings);
        } catch (AtsHttp.BoardGoneException e) {
            return new FetchResult.Failure(company, "BOARD_GONE: " + e.getMessage(), e);
        } catch (Exception e) {
            return new FetchResult.Failure(company,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private record Counts(int created, int updated, int closed) {}

    private Counts persist(FetchResult.Success success) {
        Company company = success.company();
        List<FetchedPosting> postings = success.postings();

        // Captured before any write, so "not seen in this run" is well defined.
        Instant seenFrom = Instant.now();

        return tx.execute(status -> {
            int created = 0;
            int updated = 0;

            for (FetchedPosting posting : postings) {
                CanonicalJob job = normalizer.normalize(company, posting);

                rawPostings.saveIfNew(company.id(), company.atsType(),
                        posting.externalId(), job.contentHash(), posting.rawPayload());

                switch (jobs.upsert(job)) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                }
            }

            int closed = sweepExpired(company, postings.size(), seenFrom);
            companies.markSuccess(company.id());
            return new Counts(created, updated, closed);
        });
    }

    /**
     * Closes listings that were absent from this run.
     *
     * <p>Reached only from the {@link FetchResult.Success} branch. A failed fetch
     * yields no postings, so sweeping on failure would close a company's entire
     * catalogue the first time their ATS returned a 503 -- silently, and looking
     * exactly like a company that stopped hiring.
     *
     * <p>The empty-result guard covers the subtler version of the same trap: a
     * {@code 200 OK} carrying {@code {"jobs": []}}. That is legitimate for a small
     * company that filled its last role, so the sweep still runs for boards we hold
     * few listings for. But an established board going from hundreds of postings to
     * zero in one run is far more likely to be an ATS fault than a mass closure, so
     * we decline to act and leave it for a human.
     *
     * <p>TODO: confirm on a second consecutive empty result and then sweep, so a
     * genuine wind-down drains instead of needing manual review.
     */
    private int sweepExpired(Company company, int postingsSeen, Instant seenFrom) {
        if (postingsSeen == 0) {
            int openNow = jobs.countOpen(company.id());
            if (openNow >= EMPTY_RESULT_SUSPICION_THRESHOLD) {
                log.warn("{}/{} returned 0 postings while holding {} open listings; "
                                + "skipping expiry sweep pending review",
                        company.atsType(), company.atsToken(), openNow);
                return 0;
            }
        }
        return jobs.closeStale(company.id(), seenFrom);
    }

    private void handleFailure(FetchResult.Failure failure) {
        Company company = failure.company();
        if (failure.reason().startsWith("BOARD_GONE")) {
            // A 404 will not fix itself; no point burning the retry budget.
            log.warn("retiring {}/{}: {}",
                    company.atsType(), company.atsToken(), failure.reason());
            tx.executeWithoutResult(s -> companies.markDead(company.id(), failure.reason()));
            return;
        }
        log.warn("fetch failed for {}/{}: {}",
                company.atsType(), company.atsToken(), failure.reason());
        tx.executeWithoutResult(s ->
                companies.markFailure(company.id(), failure.reason(), props.maxConsecutiveFailures()));
    }
}
