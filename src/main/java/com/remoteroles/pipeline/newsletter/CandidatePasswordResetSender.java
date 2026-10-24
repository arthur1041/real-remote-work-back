package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the code that lets a candidate back into their account.
 *
 * <p>The counterpart to {@link PasswordResetSender}, which has done this for
 * employers since 21 October. The candidate side shipped without it, and the
 * absence was worse on this side than it would have been on that one: the
 * account holds a CV, being discoverable is what the account is *for*, and
 * because leaving means deleting and deleting needs a sign-in, somebody who
 * forgot their password had no way in and no way out. Their profile stayed in
 * front of employers and nobody could do anything about it.
 *
 * <p>Separate class rather than a shared one with a column name passed in. The
 * two tables are genuinely separate, the wording differs because the reader
 * differs, and a mailer that takes the table to read from as an argument is one
 * SQL string away from mailing a candidate's code to an employer.
 *
 * <p>Fifteen seconds, like the employer's: somebody is sitting at a form.
 */
@Service
public class CandidatePasswordResetSender {

    private static final Logger log = LoggerFactory.getLogger(CandidatePasswordResetSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public CandidatePasswordResetSender(
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

    @Scheduled(cron = "${candidate.password-reset.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many codes were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select r.id, c.email, c.full_name, r.code_plain
                          from candidate_password_resets r
                          join candidates c on c.id = r.candidate_id
                         where r.sent_at is null and r.code_plain is not null
                           and r.expires_at > now()
                           -- A deleted account must not be recoverable. Its
                           -- address has been freed for registration and its row
                           -- holds nobody, so a code sent to it would be a code
                           -- sent to whoever has that mailbox now.
                           and c.status = 'ACTIVE'
                         order by r.created_at
                         limit 10
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getString("code_plain")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("candidate password reset held: the day's whole mail budget is spent");
                break;
            }
            db.sql("""
                            update candidate_password_resets
                               set sent_at = now(), code_plain = null
                             where id = :id
                            """)
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), SUBJECT, body(row), html(row));
            sent++;
        }
        log.info("candidate password reset codes sent: {}", sent);
        return sent;
    }

    private static final String SUBJECT = "Your code to reset your password";

    /** The first name, or nothing. A greeting with a blank in it is worse than none. */
    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }
        return " " + fullName.trim().split("\\s+")[0];
    }

    private String body(Pending row) {
        return """
                Hello%s,

                Your code is %s

                Type it into the page you came from to choose a new password. \
                It works for fifteen minutes.

                If you did not ask for this, nothing has happened to the \
                account and you can ignore this message.

                — Actually Remote
                %s
                """.formatted(firstName(row.name()), row.code(), siteUrl);
    }

    private String html(Pending row) {
        String card = EmailLayout.heading("Your code")
                + EmailLayout.paragraph(
                        "Type this into the page you came from to choose a new password. "
                                + "It works for fifteen minutes.",
                        true)
                + """
                        <p style="margin:0 0 20px;font-size:34px;font-weight:800;letter-spacing:0.18em;color:%s;">%s</p>
                        """.formatted(EmailLayout.BRAND_DEEP, EmailLayout.escape(row.code()))
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
        mailer.send(to, "[preview] " + SUBJECT,
                body(new Pending(0, to, "Sam Taylor", "048213")),
                html(new Pending(0, to, "Sam Taylor", "048213")));
    }
}
