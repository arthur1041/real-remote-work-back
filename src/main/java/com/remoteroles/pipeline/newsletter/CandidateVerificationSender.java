package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the code that proves a candidate's address exists.
 *
 * <p>The sibling of {@link EmailVerificationSender}, which does the same job
 * for employers and exists because the first real submission on this board came
 * from an account registered at {@code ...@gamil.com}. The stakes here are the
 * mirror image of that one: an employer who never hears back about their role
 * is a customer lost, and a candidate who never hears back about an application
 * is a person who thinks they were ignored.
 *
 * <p>Separate class rather than a parameter on that one. The two share a shape
 * and nothing else — different table, different audience, and the words have to
 * be written for somebody looking for work rather than somebody hiring. Folding
 * them together would mean a template with an if-statement in it, which is how
 * both halves end up sounding like neither.
 *
 * <p>Same cycle and the same discipline: fifteen seconds, because somebody is
 * sitting at a form waiting for it, and the plaintext code is wiped in the
 * statement that stamps the send so a message that went out cannot be read back
 * out of the database.
 */
@Service
public class CandidateVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(CandidateVerificationSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public CandidateVerificationSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    record Pending(long id, String email, String name, String code) {}

    @Scheduled(cron = "${candidate.email-verification.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many codes were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select v.id, v.email, c.full_name, v.code_plain
                          from candidate_email_verifications v
                          join candidates c on c.id = v.candidate_id
                         where v.sent_at is null and v.code_plain is not null
                           and v.expires_at > now()
                         order by v.created_at
                         limit 10
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("code_plain")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("candidate verification held: the day's whole mail budget is spent");
                break;
            }
            db.sql("""
                            update candidate_email_verifications
                               set sent_at = now(), code_plain = null
                             where id = :id
                            """)
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), SUBJECT, body(row), html(row));
            sent++;
        }

        if (sent > 0) {
            log.info("candidate email verification codes sent: {}", sent);
        }
        return sent;
    }

    private static final String SUBJECT = "Confirm your email address";

    /** Just the first name: the greeting should read like one, not like a record. */
    private static String firstName(String full) {
        if (full == null || full.isBlank()) {
            return "there";
        }
        String first = full.trim().split("\\s+")[0];
        return first.length() > 40 ? first.substring(0, 40) : first;
    }

    /**
     * The text half.
     *
     * <p>Says what confirming is for rather than demanding it, and the reason is
     * the true one: this address is how an employer's answer reaches them. It
     * promises nothing about what else the account may do later.
     */
    private String body(Pending row) {
        return """
                Hi %s,

                Your code is %s

                Type it into the page you came from to confirm this address.
                It works for fifteen minutes.

                It is how an employer's reply to an application reaches you.

                If you did not create an account, nothing further happens and you \
                can ignore this message.

                — Actually Remote
                %s
                """.formatted(firstName(row.name()), row.code(), siteUrl);
    }

    /** The laid-out half. One number, large enough to read off a phone. */
    private String html(Pending row) {
        String card = EmailLayout.eyebrow("Confirm your address")
                + EmailLayout.heading("Your code")
                + EmailLayout.paragraph(
                        "Hi " + EmailLayout.escape(firstName(row.name()))
                                + " — type this into the page you came from to confirm this "
                                + "address. It works for fifteen minutes.",
                        true)
                + """
                        <p style="margin:0 0 20px;font-size:34px;font-weight:800;letter-spacing:0.18em;color:%s;">%s</p>
                        """.formatted(EmailLayout.BRAND_DEEP, EmailLayout.escape(row.code()))
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "Confirming the address is how an employer's reply to an application "
                                + "reaches you.")
                + EmailLayout.paragraph(
                        "If you did not create an account, nothing further happens and you "
                                + "can ignore this message.");

        return EmailLayout.document(
                "Your code works for fifteen minutes.",
                card,
                // No unsubscribe: this answers a form somebody just submitted,
                // it is not a list they joined.
                EmailLayout.footer(siteUrl, null, null));
    }

    /** Sends this template to one address, for looking at. */
    public void sendPreview(String to) {
        Pending row = new Pending(0, to, "Ada Lovelace", "048213");
        mailer.send(to, "[preview] " + SUBJECT, body(row), html(row));
    }
}
