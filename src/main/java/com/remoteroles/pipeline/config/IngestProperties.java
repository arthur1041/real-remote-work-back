package com.remoteroles.pipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(
        int concurrency,
        String userAgent,
        Duration requestTimeout,
        int maxConsecutiveFailures,
        Schedule schedule
) {
    public record Schedule(boolean enabled, String cron) {}
}
