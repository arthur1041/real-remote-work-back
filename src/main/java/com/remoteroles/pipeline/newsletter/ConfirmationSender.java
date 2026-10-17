package com.remoteroles.pipeline.newsletter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Asks each captured address whether it really wants this.
 *
 * <p>The double opt-in half of the newsletter. An address typed into a form is
 * only ever evidence that somebody typed it; the mail sent from here is what
 * turns it into consent, and nothing else in this codebase may promote a row to
 * CONFIRMED.
 *
 * <p>Runs on a schedule rather than at capture time because capture happens in
 * the web app and delivery lives in this one. A minute of latency on a
 * confirmation costs nothing; splitting mail across two processes would cost a
 * second SMTP configuration and a second place for it to go wrong.
 *
 * <p>Checks {@link Mailer#isDeliveryEnabled()} before doing anything. With
 * delivery off the only Mailer is {@link LoggingMailer}, and marking addresses
 * as asked when nothing was sent would strand them: never confirmed, and never
 * selected again.
 */
@Service
@ConditionalOnProperty(name = "newsletter.confirmation.enabled", havingValue = "true", matchIfMissing = true)
public class ConfirmationSender {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationSender.class);

    private final SubscriberRepository subscribers;
    private final Mailer mailer;
    private final String siteUrl;
    private final int batchSize;

    public ConfirmationSender(
            SubscriberRepository subscribers,
            Mailer mailer,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.confirmation.batch-size:20}") int batchSize) {
        this.subscribers = subscribers;
        this.mailer = mailer;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${newsletter.confirmation.cron:0 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many confirmations were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<SubscriberRepository.Awaiting> batch = subscribers.awaitingConfirmation(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (SubscriberRepository.Awaiting row : batch) {
            // Stamped first. See markConfirmationSent.
            subscribers.markConfirmationSent(row.id());
            mailer.send(row.email(), SUBJECT, body(row));
            sent++;
        }
        log.info("confirmation emails sent: {}", sent);
        return sent;
    }

    private static final String SUBJECT = "Confirm your email for Actually Remote";

    /**
     * Plain text, deliberately.
     *
     * <p>A first message from an unknown domain that is one tracked image and a
     * button is the shape spam filters are tuned against. It also has to make
     * sense to someone who has forgotten signing up, so it says what the site is
     * before it asks for anything.
     */
    private String body(SubscriberRepository.Awaiting row) {
        String confirm = "%s/confirm?token=%s".formatted(siteUrl, encode(row.confirmToken()));
        String unsubscribe = "%s/unsubscribe?token=%s".formatted(siteUrl, encode(row.unsubscribeToken()));

        return """
                You asked to hear about new worldwide remote roles on Actually Remote \
                — jobs with no country restriction, where the eligibility is read from \
                what the employer published rather than guessed at.

                Confirm your address and we will start sending:

                %s

                If that was not you, ignore this. We will not send anything unless \
                the link above is clicked, and the address is removed on its own \
                after a while.

                You can leave at any time:

                %s

                — Actually Remote
                %s
                """.formatted(confirm, unsubscribe, siteUrl);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
