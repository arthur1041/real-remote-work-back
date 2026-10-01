package com.remoteroles.pipeline.ingest;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Lets the pipeline run as a one-shot job rather than a long-lived service:
 * {@code java -jar pipeline.jar --ingest.once=true}.
 *
 * <p>This is the shape a container scheduler or a plain system cron wants, and it
 * keeps the daily pass from depending on a process that has to stay up all night
 * to do one minute of work.
 */
@Component
@ConditionalOnProperty(name = "ingest.once", havingValue = "true")
public class IngestCommand implements ApplicationRunner {

    private final IngestionService ingestion;
    private final ConfigurableApplicationContext context;

    public IngestCommand(IngestionService ingestion, ConfigurableApplicationContext context) {
        this.ingestion = ingestion;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        IngestionService.RunSummary summary = ingestion.runOnce();
        // Non-zero exit when every board failed, so a scheduler can actually alert.
        int exitCode = (summary.total() > 0 && summary.ok() == 0) ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }
}
