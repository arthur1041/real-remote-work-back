package com.remoteroles.pipeline;

import com.remoteroles.pipeline.config.IngestProperties;
import com.remoteroles.pipeline.newsletter.NewsletterProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({IngestProperties.class, NewsletterProperties.class})
public class PipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineApplication.class, args);
    }
}
