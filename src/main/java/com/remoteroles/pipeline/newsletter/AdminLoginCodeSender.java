package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the code that lets the operator past the admin gate.
 *
 * <p>The gate used to be one shared password. The password is the only thing
 * between the internet and every subscriber address and every CV on this board,
 * and the limiter that was meant to slow a guess down was keyed on a header the
 * client writes — so it counted to eight and never arrived. This is the half
 * that means knowing the password is no longer enough: on a browser the gate
 * has not seen in a month, a six-digit code goes to `ADMIN_EMAIL` first.
 *
 * <p>**The highest-priority message this system sends**, and the one place a
 * budget refusal is close to unacceptable: an operator who cannot get a code
 * cannot moderate the queue, cannot answer a submission, and cannot reach the
 * dashboard that would tell them why. It still claims from the transactional
 * reserve rather than bypassing the budget, because a mailer that can spend
 * past the provider's quota is a mailer that gets the domain blocked and then
 * delivers nothing at all — but it is first in the queue and it is the smallest
 * message here.
 *
 * <p>No link, deliberately, exactly as the password reset reasons: a link in an
 * email is a credential that travels through previews, proxies and forwarded
 * messages, while a number typed back into the page already open travels
 * nowhere. Here it matters more than anywhere else on the site.
 */
@Service
public class AdminLoginCodeSender {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginCodeSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public AdminLoginCodeSender(
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

    @Scheduled(cron = "${admin.login-code.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many codes were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select id, email, code_plain
                          from admin_login_codes
                         where sent_at is null and code_plain is not null
                           and expires_at > now()
                         order by created_at
                         limit 5
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"), rs.getString("email"), rs.getString("code_plain")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.error("admin sign-in code held: the day's whole mail budget is spent "
                        + "-- the admin area is unreachable from a new browser until tomorrow");
                break;
            }
            // Stamped and wiped before the send, as everywhere else here. After
            // this runs nothing anywhere can read the code: the table keeps only
            // the digest and the only copy left is in the operator's inbox.
            db.sql("""
                            update admin_login_codes
                               set sent_at = now(), code_plain = null
                             where id = :id
                            """)
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), SUBJECT, body(row.code()), html(row.code()));
            sent++;
        }
        log.info("admin sign-in codes sent: {}", sent);
        return sent;
    }

    private static final String SUBJECT = "Your admin sign-in code";

    private String body(String code) {
        return """
                Your code is %s

                Type it into the sign-in page to finish. It works for fifteen \
                minutes, and the browser you use it on will not be asked again \
                for a month.

                If you did not just try to sign in, somebody has the admin \
                password. Change it.

                — Actually Remote
                %s
                """.formatted(code, siteUrl);
    }

    private String html(String code) {
        String card = EmailLayout.heading("Your sign-in code")
                + EmailLayout.paragraph(
                        "Type this into the sign-in page to finish. It works for fifteen "
                                + "minutes, and this browser will not be asked again for a month.",
                        true)
                + """
                        <p style="margin:0 0 20px;font-size:34px;font-weight:800;letter-spacing:0.18em;color:%s;">%s</p>
                        """.formatted(EmailLayout.BRAND_DEEP, EmailLayout.escape(code))
                + EmailLayout.rule()
                // Said plainly rather than softened. A code arriving unasked is
                // not a curiosity here: it means the password is known to
                // somebody else, because no code is issued until the address and
                // the password have both been given correctly.
                + EmailLayout.paragraph(
                        "<strong>If you did not just try to sign in, somebody has the admin "
                                + "password.</strong> No code is sent until the address and the "
                                + "password are both correct, so an unexpected one means the "
                                + "password needs changing.");

        return EmailLayout.document(
                "Your code works for fifteen minutes.",
                card,
                EmailLayout.footer(siteUrl, null, null));
    }

    /** Sends this template to one address, for looking at. */
    public void sendPreview(String to) {
        mailer.send(to, "[preview] " + SUBJECT, body("048213"), html("048213"));
    }
}
