package com.remoteroles.pipeline.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Timers, in the process that is supposed to have them.
 *
 * <p><strong>`@EnableScheduling` was on `PipelineApplication`</strong>, which
 * meant every process built from this jar had the whole clock running — the
 * one-shot ingest included. That container starts the same Spring context,
 * takes minutes to walk sixty boards, and for all of those minutes the
 * fifteen-second mail senders, the posting reviewer and the screener were
 * firing in it as well as in the long-lived pipeline beside it.
 *
 * <p><strong>What that cost, measured on 16 Nov 2026.</strong> The senders
 * claim their work by selecting rows whose stamp is null and then stamping
 * them, and the stamp was unconditional: two sessions both selected
 * application 7, both reported `UPDATE 1`, and both would have sent. So an
 * employer gets the same "New application" twice, a candidate gets the same
 * code twice, and the jobs that call a model pay twice. Thirteen of the
 * fourteen stamps in `newsletter` were written that way;
 * `UnreadMessageNotifier` was the one that already said `and notified_at is
 * null`, which is the proof the pattern was known.
 *
 * <p>Both ends are fixed: the claims are conditional now, and this is the
 * other end — a one-shot process has no timers at all, so the only thing it
 * does is the pass it was started for.
 *
 * <p><strong>Keyed on the property and not on the profile.</strong>
 * `IngestCommand` documents its own invocation as
 * {@code java -jar pipeline.jar --ingest.once=true}, with no profile in sight;
 * the `oneshot` profile sets the same property. Gating on the profile would
 * have left the documented command running every timer.
 */
@Configuration
@ConditionalOnProperty(name = "ingest.once", havingValue = "false", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
