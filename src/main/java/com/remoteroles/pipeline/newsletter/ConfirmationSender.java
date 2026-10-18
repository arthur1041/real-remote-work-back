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
    private final MailBudget budget;
    private final String siteUrl;
    private final int batchSize;

    public ConfirmationSender(
            SubscriberRepository subscribers,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.confirmation.batch-size:20}") int batchSize) {
        this.subscribers = subscribers;
        this.mailer = mailer;
        this.budget = budget;
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
            // Transactional, so this may spend the reserve that cold mail is
            // kept away from. Reaching this at all means the whole day's budget
            // is gone, which is worth a warning rather than a note: somebody is
            // at a form waiting for this link, and they are not getting it.
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("confirmation held: the day's whole mail budget is spent, {} sent this run", sent);
                break;
            }
            // Stamped first. See markConfirmationSent.
            subscribers.markConfirmationSent(row.id());
            mailer.send(row.email(), SUBJECT, body(row), html(row));
            sent++;
        }
        log.info("confirmation emails sent: {}", sent);
        return sent;
    }

    private static final String SUBJECT = "Confirm your email for Actually Remote";

    /**
     * The text half.
     *
     * <p>It has to make sense to someone who has forgotten signing up, so it
     * says what the site is before it asks for anything. No tracked images and
     * no redirect links in either half: a first message from a domain with no
     * sending history that is one image and one wrapped URL is the exact shape
     * spam filters are tuned against.
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

                If that was not you, ignore this. Nothing is sent unless the link \
                above is clicked.

                You can leave at any time:

                %s

                — Actually Remote
                %s
                """.formatted(confirm, unsubscribe, siteUrl);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** The laid-out half. One ask, one button, nothing competing with it. */
    private String html(SubscriberRepository.Awaiting row) {
        String confirm = "%s/confirm?token=%s".formatted(siteUrl, encode(row.confirmToken()));
        String unsubscribe = "%s/unsubscribe?token=%s".formatted(siteUrl, encode(row.unsubscribeToken()));

        String card = EmailLayout.heading("Confirm your email")
                + EmailLayout.paragraph(
                        "You asked to hear about new remote roles with no country requirement "
                                + "&mdash; eligibility read from what the employer published, "
                                + "never guessed at. One click and the weekly email starts.",
                        true)
                + EmailLayout.button(confirm, "Confirm my email")
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "If that was not you, ignore this. Nothing is sent unless the button "
                                + "above is clicked.");

        return EmailLayout.document(
                "One click to start the weekly email.",
                card,
                EmailLayout.footer(siteUrl, unsubscribe, null));
    }

    /**
     * Sends this template to one address, for looking at.
     *
     * <p>Lives beside the template rather than in a separate preview service so
     * that a change to the layout and the thing that renders it cannot drift
     * apart. Real data where there is real data to use; the tokens are
     * deliberately invalid, so a click on the button in a preview lands on an
     * error page instead of confirming or unsubscribing somebody for real.
     */
    public void sendPreview(String to) {
        SubscriberRepository.Awaiting row =
                new SubscriberRepository.Awaiting(0, to, "preview-token", "preview-token");
        mailer.send(to, "[preview] " + SUBJECT, body(row), html(row));
    }
}
