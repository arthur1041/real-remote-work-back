package com.remoteroles.pipeline.newsletter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells a referrer what their link did, at the three moments that matter.
 *
 * <ol>
 *   <li><strong>An employer created an account through it.</strong> Nothing is
 *   earned yet, and the message says so; it exists because a link nobody hears
 *   back from is a link nobody shares a second time.</li>
 *   <li><strong>That employer subscribed to Growth from the start</strong>,
 *   which is what earns -- with the amount and the date it confirms.</li>
 *   <li><strong>The earning confirmed</strong>: sixty days passed with the
 *   plan still running, and the money is owed. If the candidate has not given
 *   a PayPal address yet, this is the message that asks for it.</li>
 * </ol>
 *
 * <p><strong>No employer is named</strong>, in any of the three. The
 * candidate's page does not name them either (`referralSummary` in the site):
 * which companies hire through this board is the employers' information, and
 * the referrer knows whom they sent the link to anyway.
 *
 * <p>Each row is claimed before it is sent -- the same conditional update the
 * other senders use, see {@code PauseNoticeSender} -- so two processes never
 * announce the same thing twice. A message the day's budget cannot afford is
 * left unclaimed and goes out on the next pass. The rules for what earns live
 * in the site (`lib/referral-rules.ts`); this only reads what it wrote.
 */
@Service
public class ReferralNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReferralNotifier.class);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public ReferralNotifier(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    /** One message to send: who, and the facts it states. */
    record Notice(long id, String email, String name, int amountCents, String cycle,
                  Instant confirmsAt, String paypal) { }

    @Scheduled(cron = "${referrals.notify.cron:0 */15 * * * *}")
    public void run() {
        send();
    }

    /** Sends whatever is due, in the order the events happened. Returns how many went. */
    public int send() {
        if (!mailer.isDeliveryEnabled()) return 0;
        return signups() + subscriptions() + confirmations();
    }

    int signups() {
        List<Notice> due = db.sql("""
                select e.id, c.email, c.full_name
                  from employers e
                  join candidates c on c.id = e.referred_by_candidate_id
                 where e.referred_at is not null
                   and e.referral_notified_at is null
                   and c.status = 'ACTIVE'
                 order by e.referred_at
                 limit 200
                """)
                .query((rs, n) -> new Notice(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), 0, null, null, null))
                .list();
        int sent = 0;
        for (Notice n : due) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) break;
            if (db.sql("update employers set referral_notified_at = now() "
                            + "where id = :id and referral_notified_at is null")
                    .param("id", n.id()).update() == 0) continue;
            mailer.send(n.email(), signupSubject(), signupText(n), signupHtml(n));
            sent++;
        }
        if (sent > 0) log.info("referral notices: {} sign-up(s) announced", sent);
        return sent;
    }

    int subscriptions() {
        List<Notice> due = db.sql("""
                select r.id, c.email, c.full_name, r.amount_cents, r.cycle, r.confirms_at,
                       c.payout_paypal_email
                  from referral_earnings r
                  join candidates c on c.id = r.candidate_id
                 where r.subscribed_notified_at is null
                   and r.status = 'PENDING'
                   and c.status = 'ACTIVE'
                 order by r.created_at
                 limit 200
                """)
                .query((rs, n) -> new Notice(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getInt("amount_cents"), rs.getString("cycle"),
                        rs.getTimestamp("confirms_at").toInstant(), rs.getString("payout_paypal_email")))
                .list();
        int sent = 0;
        for (Notice n : due) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) break;
            if (db.sql("update referral_earnings set subscribed_notified_at = now() "
                            + "where id = :id and subscribed_notified_at is null")
                    .param("id", n.id()).update() == 0) continue;
            mailer.send(n.email(), subscribedSubject(n), subscribedText(n), subscribedHtml(n));
            sent++;
        }
        if (sent > 0) log.info("referral notices: {} subscription(s) announced", sent);
        return sent;
    }

    /**
     * Earnings past their sixty days whose plan was still running then. The
     * {@code exists} is the site's {@code HELD} test, word for word: a
     * confirmation must never be announced for an earning the admin screen
     * would not let anybody pay.
     */
    int confirmations() {
        List<Notice> due = db.sql("""
                select r.id, c.email, c.full_name, r.amount_cents, r.cycle, r.confirms_at,
                       c.payout_paypal_email
                  from referral_earnings r
                  join candidates c on c.id = r.candidate_id
                 where r.confirmed_notified_at is null
                   and r.status = 'PENDING'
                   and r.confirms_at <= now()
                   and c.status = 'ACTIVE'
                   and exists (select 1 from employer_subscriptions s
                                where s.employer_id = r.employer_id
                                  and s.plan = r.plan
                                  and s.expires_at >= r.confirms_at)
                 order by r.confirms_at
                 limit 200
                """)
                .query((rs, n) -> new Notice(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getInt("amount_cents"), rs.getString("cycle"),
                        rs.getTimestamp("confirms_at").toInstant(), rs.getString("payout_paypal_email")))
                .list();
        int sent = 0;
        for (Notice n : due) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) break;
            if (db.sql("update referral_earnings set confirmed_notified_at = now() "
                            + "where id = :id and confirmed_notified_at is null")
                    .param("id", n.id()).update() == 0) continue;
            mailer.send(n.email(), confirmedSubject(n), confirmedText(n), confirmedHtml(n));
            sent++;
        }
        if (sent > 0) log.info("referral notices: {} confirmation(s) announced", sent);
        return sent;
    }

    /* ------------------------------------------------------------- the words */

    static String money(int cents) {
        return cents % 100 == 0 ? "$" + (cents / 100) : String.format(Locale.ROOT, "$%.2f", cents / 100.0);
    }

    private static String hi(Notice n) {
        String name = n.name();
        if (name == null || name.isBlank()) return "Hi,";
        return "Hi " + name.trim().split("\\s+")[0] + ",";
    }

    private String page() {
        return siteUrl + "/account/referrals";
    }

    private static String plan(Notice n) {
        return "ANNUAL".equals(n.cycle()) ? "an annual Growth plan" : "a monthly Growth plan";
    }

    String signupSubject() {
        return "Someone signed up through your referral link";
    }

    String signupText(Notice n) {
        return """
                %s

                An employer just created an account on Actually Remote through your \
                referral link.

                Nothing is earned yet: it counts once they subscribe to Growth. We will \
                write again if they do.

                %s

                — Actually Remote
                """.formatted(hi(n), page());
    }

    String signupHtml(Notice n) {
        String card = EmailLayout.eyebrow("Referrals")
                + EmailLayout.heading("Someone signed up through your link")
                + EmailLayout.paragraph(EmailLayout.escape(hi(n)))
                + EmailLayout.paragraph("An employer just created an account on Actually Remote "
                        + "through your referral link.")
                + EmailLayout.paragraph("Nothing is earned yet: it counts once they subscribe to "
                        + "Growth. We will write again if they do.")
                + EmailLayout.button(page(), "See your referrals");
        return EmailLayout.document("An employer created an account through your link.", card,
                EmailLayout.footer(siteUrl, null,
                        "You get this because you shared your referral link."));
    }

    String subscribedSubject(Notice n) {
        return "You earned " + money(n.amountCents()) + " — confirms on " + DAY.format(n.confirmsAt());
    }

    String subscribedText(Notice n) {
        return """
                %s

                An employer you referred subscribed to %s. That earns you %s.

                It confirms on %s, if they are still subscribed then, and is paid by \
                PayPal after that.%s

                %s

                — Actually Remote
                """.formatted(hi(n), plan(n), money(n.amountCents()), DAY.format(n.confirmsAt()),
                n.paypal() == null ? " Add your PayPal email on your referrals page so it can be sent." : "",
                page());
    }

    String subscribedHtml(Notice n) {
        String card = EmailLayout.eyebrow("Referrals")
                + EmailLayout.heading("You earned " + money(n.amountCents()))
                + EmailLayout.paragraph(EmailLayout.escape(hi(n)))
                + EmailLayout.paragraph("An employer you referred subscribed to " + plan(n)
                        + ". That earns you <strong>" + money(n.amountCents()) + "</strong>.")
                + EmailLayout.paragraph("It confirms on " + DAY.format(n.confirmsAt())
                        + ", if they are still subscribed then, and is paid by PayPal after that."
                        + (n.paypal() == null
                            ? " Add your PayPal email on your referrals page so it can be sent."
                            : ""))
                + EmailLayout.button(page(), "See your referrals");
        return EmailLayout.document("An employer you referred subscribed to Growth.", card,
                EmailLayout.footer(siteUrl, null,
                        "You get this because you shared your referral link."));
    }

    String confirmedSubject(Notice n) {
        return "Your " + money(n.amountCents()) + " referral earning is confirmed";
    }

    String confirmedText(Notice n) {
        return """
                %s

                The %s you earned for referring an employer is confirmed.

                %s

                %s

                — Actually Remote
                """.formatted(hi(n), money(n.amountCents()),
                n.paypal() == null
                        ? "To receive it, add the email address of your PayPal account on your "
                                + "referrals page."
                        : "It will be sent by PayPal to " + n.paypal() + ".",
                page());
    }

    String confirmedHtml(Notice n) {
        String card = EmailLayout.eyebrow("Referrals")
                + EmailLayout.heading("Your " + money(n.amountCents()) + " is confirmed")
                + EmailLayout.paragraph(EmailLayout.escape(hi(n)))
                + EmailLayout.paragraph("The " + money(n.amountCents())
                        + " you earned for referring an employer is confirmed.")
                + EmailLayout.paragraph(n.paypal() == null
                        ? "To receive it, add the email address of your PayPal account on your "
                                + "referrals page."
                        : "It will be sent by PayPal to " + EmailLayout.escape(n.paypal()) + ".")
                + EmailLayout.button(page(), n.paypal() == null ? "Add your PayPal email" : "See your referrals");
        return EmailLayout.document("Your referral earning is confirmed.", card,
                EmailLayout.footer(siteUrl, null,
                        "You get this because you shared your referral link."));
    }
}
