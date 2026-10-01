package com.remoteroles.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(
        int concurrency,
        String userAgent,
        Duration requestTimeout,
        int maxConsecutiveFailures,
        Schedule schedule,
        Feed feed
) {
    public record Schedule(boolean enabled, String cron) {}

    /**
     * Aggregator crawl budget.
     *
     * <p>Himalayas caps at 20 postings per request with no server-side filter, so
     * reaching the worldwide roles means walking the feed. These numbers are a
     * politeness budget against someone else's service, not a limit of the machine:
     * the crawl could run an order of magnitude faster and should not.
     */
    public record Feed(int maxPages, int concurrency, long pauseMillis) {}
}
