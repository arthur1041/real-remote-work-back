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
 * The one message sent to somebody who never asked to hear from us.
 *
 * <p>Deliberately separate from {@link ConfirmationSender}, which exists to
 * verify an address its owner typed into a form. That message opens with "you
 * asked to hear about new worldwide remote roles", and saying that to a lead
 * would be a lie in the first sentence — which is both dishonest and the
 * quickest way to be reported.
 *
 * <p>Two at most: the original and one reminder, and the reminder only after a
 * month and only to somebody who never responded. Mail gets missed, so one
 * nudge is fair; a third would be a campaign, and the person who ignored two
 * has answered twice.
 *
 * <p>Sent in small batches. A burst of cold mail from a domain with no sending
 * history is the pattern filters are built to catch, and this domain also
 * carries confirmation mail and the employer review alerts — so a reputation
 * spent here is taken from the product's own operations.
 */
@Service
@ConditionalOnProperty(name = "newsletter.invite.enabled", havingValue = "true", matchIfMissing = true)
public class InviteSender {

    private static final Logger log = LoggerFactory.getLogger(InviteSender.class);
    private static final String SUBJECT = "Worldwide remote roles, once a week — if you want them";

    private final SubscriberRepository subscribers;
    private final Mailer mailer;
    private final String siteUrl;
    private final int batchSize;

    public InviteSender(
            SubscriberRepository subscribers,
            Mailer mailer,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.invite.batch-size:25}") int batchSize) {
        this.subscribers = subscribers;
        this.mailer = mailer;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${newsletter.invite.cron:0 10 * * * *}")
    public void run() {
        sendPending();
    }

    /** Sends whatever is due right now, for the admin to call after an import. */
    public int sendDueNow() {
        return sendPending();
    }

    /** Returns how many invitations were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<SubscriberRepository.Lead> batch = subscribers.awaitingInvite(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        for (SubscriberRepository.Lead lead : batch) {
            subscribers.markInvited(lead.id());
            mailer.send(lead.email(), SUBJECT, body(lead));
        }

        log.info("invitations sent: {}", batch.size());
        return batch.size();
    }

    /**
     * Says in the first line that this was not asked for.
     *
     * <p>Leading with why the message arrived is the honest version and also the
     * one that performs: a stranger deciding whether to report an email is
     * deciding whether they were deceived. Nothing happens unless the link is
     * clicked — the address is not on any list until then, and the message says
     * so rather than implying it.
     */
    private String body(SubscriberRepository.Lead lead) {
        String confirm = "%s/confirm?token=%s"
                .formatted(siteUrl, URLEncoder.encode(lead.confirmToken(), StandardCharsets.UTF_8));
        String unsubscribe = "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(lead.unsubscribeToken(), StandardCharsets.UTF_8));

        return """
                You did not ask for this email, and it is the only one you will get \
                unless you say otherwise.

                I run Actually Remote, a job board that lists only roles open \
                worldwide — no country requirement at all, taken from what the \
                employer actually published rather than guessed at from the word \
                "remote". Of the remote jobs I track, fewer than one in five turn \
                out to be genuinely open to anyone, and the rest quietly require \
                you to already live somewhere.

                If that is useful, I send the new ones once a week:

                %s

                Nothing is sent unless you click that. You are not on a list, and \
                ignoring this email is a complete answer — I will not write again.

                If you would rather I never contact this address, here is a \
                permanent opt-out:

                %s

                — Actually Remote
                %s
                """.formatted(confirm, unsubscribe, siteUrl);
    }
}
