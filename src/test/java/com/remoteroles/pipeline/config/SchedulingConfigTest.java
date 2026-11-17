package com.remoteroles.pipeline.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clock belongs to the long-lived process only.
 *
 * <p>A one-shot ingest starts the same Spring context and takes minutes. While
 * `@EnableScheduling` sat on `PipelineApplication`, those minutes had the
 * fifteen-second mail senders firing in it as well as in the pipeline beside
 * it — and the senders claimed their work with an unconditional stamp, so both
 * processes sent. Measured on 16 Nov 2026: two sessions both selected
 * application 7 and both reported `UPDATE 1`.
 *
 * <p>Asserted here rather than left to a comment, because the gate is a single
 * annotation and the cost of it silently inverting is every transactional
 * email going out twice.
 */
class SchedulingConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    @DisplayName("the long-lived process schedules")
    void scheduledByDefault() {
        runner.run((context) -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .as("the scheduling post-processor @EnableScheduling registers")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("ingest.once=false is still the long-lived process")
    void explicitFalseSchedules() {
        runner.withPropertyValues("ingest.once=false")
                .run((context) -> assertThat(context).hasSingleBean(SchedulingConfig.class));
    }

    @Test
    @DisplayName("a one-shot process has no timers at all")
    void oneShotDoesNotSchedule() {
        runner.withPropertyValues("ingest.once=true").run((context) -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .as("nothing may register a scheduled method in a one-shot")
                    .isFalse();
        });
    }
}
