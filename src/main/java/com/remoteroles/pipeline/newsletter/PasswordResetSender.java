package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the code that lets an employer back into their account.
 *
 * <p>Written by the website, sent from here, for the same reason every other
 * message is: capture happens in the web app and delivery lives in this one.
 * One SMTP configuration and one place for it to go wrong.
 *
 * <p>Runs every fifteen seconds rather than every minute. A confirmation for a
 * newsletter can wait; somebody staring at a sign-in form cannot, and the
 * difference between "it arrived" and "it never came" is mostly how long they
 * were willing to sit there.
 *
 * <p>The plaintext code is cleared in the same statement that stamps the send.
 * After this runs, nothing anywhere can read it -- the table keeps only the
 * digest, and the only copy left is in the reader's inbox.
 */
@Service
public class PasswordResetSender {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public PasswordResetSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    record Pending(long id, String email, String code) {}

    @Scheduled(cron = "${employer.password-reset.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many codes were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select r.id, e.email, r.code_plain
                          from employer_password_resets r
                          join employers e on e.id = r.employer_id
                         where r.sent_at is null and r.code_plain is not null
                           and r.expires_at > now()
                         order by r.created_at
                         limit 10
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"), rs.getString("email"), rs.getString("code_plain")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            // Transactional: somebody is waiting at a form for this, which is
            // the tier the reserve exists to protect.
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("password reset held: the day's whole mail budget is spent");
                break;
            }
            // Stamped and wiped first, as everywhere else here. A crash between
            // the two costs one unsent code, which the reader can ask for
            // again; stamping afterwards would re-send the same code every
            // fifteen seconds until somebody noticed.
            db.sql("""
                            update employer_password_resets
                               set sent_at = now(), code_plain = null
                             where id = :id
                            """)
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), SUBJECT, body(row.code()), html(row.code()));
            sent++;
        }
        log.info("password reset codes sent: {}", sent);
        return sent;
    }

    private static final String SUBJECT = "Your code to reset your password";

    /**
     * The text half.
     *
     * <p>No link. A reset link in an email is a credential that travels through
     * previews, proxies and forwarded messages; a code typed back into a page
     * the reader already has open travels nowhere. It also means a code that
     * arrives at the wrong inbox is useless without the form in front of it.
     */
    private String body(String code) {
        return """
                Your code is %s

                Type it into the page you came from to choose a new password. \
                It works for fifteen minutes.

                If you did not ask for this, nothing has happened to the \
                account and you can ignore this message.

                — Actually Remote
                %s
                """.formatted(code, siteUrl);
    }

    /** The laid-out half. One number, large enough to read off a phone. */
    private String html(String code) {
        String card = EmailLayout.heading("Your code")
                + EmailLayout.paragraph(
                        "Type this into the page you came from to choose a new password. "
                                + "It works for fifteen minutes.",
                        true)
                + """
                        <p style="margin:0 0 20px;font-size:34px;font-weight:800;letter-spacing:0.18em;color:%s;">%s</p>
                        """.formatted(EmailLayout.BRAND_DEEP, EmailLayout.escape(code))
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "If you did not ask for this, nothing has happened to the account "
                                + "and you can ignore this message.");

        return EmailLayout.document(
                "Your code works for fifteen minutes.",
                card,
                // No unsubscribe: this is not a list, and somebody locked out of
                // their account is not being marketed to.
                EmailLayout.footer(siteUrl, null, null));
    }

    /** Sends this template to one address, for looking at. */
    public void sendPreview(String to) {
        mailer.send(to, "[preview] " + SUBJECT, body("048213"), html("048213"));
    }
}
