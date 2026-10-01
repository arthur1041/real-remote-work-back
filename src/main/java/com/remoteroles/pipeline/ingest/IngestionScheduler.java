package com.remoteroles.pipeline.ingest;

import com.remoteroles.pipeline.config.IngestProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires the daily pass.
 *
 * <p>Guarded by a flag rather than overlapping: a run that is still going when the
 * next one is due means something is wrong, and starting a second pass over the
 * same boards would double the load we put on other people's servers at exactly
 * the wrong moment.
 */
@Component
@ConditionalOnProperty(name = "ingest.schedule.enabled", havingValue = "true")
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final IngestionService ingestion;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public IngestionScheduler(IngestionService ingestion, IngestProperties props) {
        this.ingestion = ingestion;
        log.info("ingest schedule enabled: {}", props.schedule().cron());
    }

    @Scheduled(cron = "${ingest.schedule.cron}", zone = "UTC")
    public void scheduled() {
        if (!running.compareAndSet(false, true)) {
            log.warn("previous ingest run still in progress; skipping this tick");
            return;
        }
        try {
            ingestion.runOnce();
        } catch (Exception e) {
            log.error("scheduled ingest run failed", e);
        } finally {
            running.set(false);
        }
    }
}
