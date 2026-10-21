package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the code that proves an employer's address exists.
 *
 * <p>The reason this exists is a single character. The first real submission
 * came from an account registered as {@code ...@gamil.com}, and every message
 * the system would ever send that company -- the decision on their role above
 * all -- was addressed to a mailbox that does not exist. Nothing would have
 * reported that; it would simply have looked, from their side, like a board
 * that took their posting and never answered.
 *
 * <p>Same cycle and same shape as {@link PasswordResetSender}: somebody is
 * sitting at a form waiting for this, so it runs every fifteen seconds, and the
 * plaintext code is wiped in the statement that stamps the send.
 */
@Service
public class EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public EmailVerificationSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    record Pending(long id, String email, String company, String code) {}

    @Scheduled(cron = "${employer.email-verification.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many codes were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select v.id, v.email, e.company_name, v.code_plain
                          from employer_email_verifications v
                          join employers e on e.id = v.employer_id
                         where v.sent_at is null and v.code_plain is not null
                           and v.expires_at > now()
                         order by v.created_at
                         limit 10
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("company_name"),
                        rs.getString("code_plain")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("email verification held: the day's whole mail budget is spent");
                break;
            }
            db.sql("""
                            update employer_email_verifications
                               set sent_at = now(), code_plain = null
                             where id = :id
                            """)
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), SUBJECT, body(row), html(row));
            sent++;
        }

        if (sent > 0) {
            log.info("employer email verification codes sent: {}", sent);
        }
        return sent;
    }

    private static final String SUBJECT = "Confirm your email address";

    /**
     * The text half.
     *
     * <p>Says what the code unlocks rather than demanding it. Somebody who has
     * just filled in a registration form is owed a reason, and "so that we can
     * tell you what happens to your role" is the honest one -- it is exactly
     * what the address is for.
     */
    private String body(Pending row) {
        return """
                Your code is %s

                Type it into the page you came from to confirm this address for %s.
                It works for fifteen minutes.

                Confirming it is what lets a role be submitted, and it is how you \
                get told when one is published or turned down.

                If you did not sign up, nothing further happens and you can ignore \
                this message.

                — Actually Remote
                %s
                """.formatted(row.code(), row.company(), siteUrl);
    }

    /** The laid-out half. One number, large enough to read off a phone. */
    private String html(Pending row) {
        String card = EmailLayout.eyebrow("Confirm your address")
                + EmailLayout.heading("Your code")
                + EmailLayout.paragraph(
                        "Type this into the page you came from to confirm this address for "
                                + EmailLayout.escape(row.company())
                                + ". It works for fifteen minutes.",
                        true)
                + """
                        <p style="margin:0 0 20px;font-size:34px;font-weight:800;letter-spacing:0.18em;color:%s;">%s</p>
                        """.formatted(EmailLayout.BRAND_DEEP, EmailLayout.escape(row.code()))
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "Confirming the address is what lets a role be submitted, and it is "
                                + "how you get told when one is published or turned down.")
                + EmailLayout.paragraph(
                        "If you did not sign up, nothing further happens and you can ignore "
                                + "this message.");

        return EmailLayout.document(
                "Your code works for fifteen minutes.",
                card,
                // No unsubscribe: this is the answer to a form somebody just
                // submitted, not a list they joined.
                EmailLayout.footer(siteUrl, null, null));
    }

    /** Sends this template to one address, for looking at. */
    public void sendPreview(String to) {
        Pending row = new Pending(0, to, "Northwind Systems", "048213");
        mailer.send(to, "[preview] " + SUBJECT, body(row), html(row));
    }
}
