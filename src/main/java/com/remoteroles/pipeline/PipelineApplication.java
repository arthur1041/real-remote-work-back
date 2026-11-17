package com.remoteroles.pipeline;

import com.remoteroles.pipeline.config.IngestProperties;
import com.remoteroles.pipeline.newsletter.NewsletterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Scheduling is turned on by {@link com.remoteroles.pipeline.config.SchedulingConfig}
 * and not here, so that a one-shot process has no timers. See that class.
 */
@SpringBootApplication
@EnableConfigurationProperties({IngestProperties.class, NewsletterProperties.class})
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
