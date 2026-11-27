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
 *   <li><strong>That employer paid for a plan, or moved up to a better
 *   one</strong> -- with the amount it earned and the date it confirms.</li>
 *   <li><strong>The earning confirmed</strong>: two months passed with the
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

    /**
     * One message to send: who, and the facts it states.
     *
     * @param referrerIsEmployer the referrer is an employer (owner, 25 Nov 2026), whose
     *     referral page is in the employer area; paid by PayPal like anybody
     */
    record Notice(long id, String email, String name, int amountCents, String plan, String cycle,
                  Instant confirmsAt, String paypal, boolean upgrade, boolean referrerIsEmployer) {
        Notice(long id, String email, String name, int amountCents, String plan, String cycle,
               Instant confirmsAt, String paypal, boolean upgrade) {
            this(id, email, name, amountCents, plan, cycle, confirmsAt, paypal, upgrade, false);
        }

        Notice(long id, String email, String name, int amountCents, String cycle,
               Instant confirmsAt, String paypal) {
            this(id, email, name, amountCents, "GROWTH", cycle, confirmsAt, paypal, false, false);
        }
    }

    /*
     * The referrer, whoever they are: a candidate (c) or, since 25 Nov 2026, an
     * employer (re). Exactly one of the two joins finds a row.
     */
    private static final String WHO = """
            coalesce(c.email, re.email) as email,
            coalesce(c.full_name, re.contact_name) as full_name,
            coalesce(c.payout_paypal_email, re.payout_paypal_email) as paypal,
            re.id is not null as referrer_is_employer""";

    @Scheduled(cron = "${referrals.notify.cron:0 */15 * * * *}")
    public void run() {
        send();
    }

    /** Sends whatever is due, in the order the events happened. Returns how many went. */
    public int send() {
        settle();
        if (!mailer.isDeliveryEnabled()) return 0;
        return signups() + subscriptions() + confirmations();
    }

    /**
     * Settles every claim whose day has come, once and for good, oldest day
     * first.
     *
     * <p>Each referral earning is a claim for one plan and billing period at
     * its full worth (26 Nov 2026). On its day it is CONFIRMED if a card-paid
     * subscription on that rung of the ladder or higher (Starter monthly &lt;
     * Starter annual &lt; Growth monthly &lt; Growth annual) ran to that day,
     * and LAPSED if not. A confirmed claim pays its worth less the best worth
     * already confirmed or paid for that employer, so the total is always the
     * best plan kept for two months -- never more, whatever order plans were
     * bought, dropped and bought again in. Storing only the difference at
     * purchase time, as this did before, lost or over-paid money in a new
     * sequence at every review.
     *
     * <p>One claim at a time, each in its own statements: a run cut short
     * leaves the rest PENDING for the next run, and every amount is worked out
     * from what is stored at that moment, so nothing is decided twice. Runs
     * before the mail check, so a server with mail switched off still settles.
     */
    int settle() {
        record Due(long id, long employerId) {}
        List<Due> due = db.sql("""
                select id, employer_id from referral_earnings
                 where status = 'PENDING' and confirms_at <= now()
                 order by confirms_at, id
                 limit 500
                """).query((rs, i) -> new Due(rs.getLong("id"), rs.getLong("employer_id"))).list();
        for (Due d : due) {
            db.sql("""
                    update referral_earnings r
                       set status = case when %s then 'CONFIRMED' else 'LAPSED' end,
                           amount_cents = case when %s
                                then greatest(0, r.worth_cents - coalesce((
                                       select max(x.worth_cents) from referral_earnings x
                                        where x.employer_id = r.employer_id
                                          and x.status in ('CONFIRMED', 'PAID')), 0))
                                else r.amount_cents end,
                           settled_at = now()
                     where r.id = :id and r.status = 'PENDING'
                    """.formatted(HELD, HELD)).param("id", d.id()).update();
        }
        for (long employer : due.stream().map(Due::employerId).distinct().toList()) {
            reestimate(employer);
        }
        if (!due.isEmpty()) log.info("referral claims settled: {}", due.size());
        return due.size();
    }

    /*
     * A card-paid subscription on the claim's rung or higher that ran to its
     * day. COVERS and HELD in web/lib/referrals.ts are the same test; change
     * them together.
     */
    private static final String HELD = """
            exists (select 1 from employer_subscriptions s
                     where s.employer_id = r.employer_id
                       and s.provider = 'STRIPE'
                       and (case when s.plan = 'GROWTH' and s.cycle = 'ANNUAL' then 4
                                 when s.plan = 'GROWTH' then 3
                                 when s.cycle = 'ANNUAL' then 2 else 1 end)
                        >= (case when r.plan = 'GROWTH' and r.cycle = 'ANNUAL' then 4
                                 when r.plan = 'GROWTH' then 3
                                 when r.cycle = 'ANNUAL' then 2 else 1 end)
                       -- An hour of slack: the claim is dated from the
                       -- webhook, moments after Stripe's billing anchor (see
                       -- HELD in web/lib/referrals.ts).
                       and s.expires_at >= r.confirms_at - interval '1 hour')""";

    /**
     * Re-estimates what an employer's still-pending claims will pay, now that
     * some settled: worth less the best worth confirmed, paid, or pending
     * before it. Only the estimate the pages and the "you earned" email show;
     * the amount that is paid is fixed by {@link #settle()} on the day.
     */
    void reestimate(long employerId) {
        db.sql("""
                update referral_earnings r
                   set amount_cents = greatest(0, r.worth_cents - coalesce((
                         select max(x.worth_cents) from referral_earnings x
                          where x.employer_id = r.employer_id and x.id <> r.id
                            and (x.status in ('CONFIRMED', 'PAID')
                                 or (x.status = 'PENDING' and x.id < r.id))), 0))
                 where r.employer_id = :e and r.status = 'PENDING'
                """).param("e", employerId).update();
    }

    int signups() {
        List<Notice> due = db.sql("""
                select e.id, %s
                  from employers e
             left join candidates c on c.id = e.referred_by_candidate_id
             left join employers re on re.id = e.referred_by_employer_id
                 where e.referred_at is not null
                   and e.referral_notified_at is null
                   and coalesce(c.status, re.status) = 'ACTIVE'
                 order by e.referred_at
                 limit 200
                """.formatted(WHO))
                .query((rs, n) -> new Notice(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), 0, null, null, null, null, false,
                        rs.getBoolean("referrer_is_employer")))
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
                select r.id, %s, r.amount_cents, r.plan, r.cycle, r.confirms_at,
                       -- A move up only if an earlier earning still stands: one
                       -- that lapsed or was cancelled earned nothing, and
                       -- "another $X" would be untrue.
                       exists (select 1 from referral_earnings p
                                where p.employer_id = r.employer_id
                                  and p.created_at < r.created_at
                                  and p.status not in ('LAPSED', 'VOID')) as upgrade
                  from referral_earnings r
             left join candidates c on c.id = r.candidate_id
             left join employers re on re.id = r.referrer_employer_id
                 where r.subscribed_notified_at is null
                   and r.status = 'PENDING'
                   and r.amount_cents > 0
                   and coalesce(c.status, re.status) = 'ACTIVE'
                 order by r.created_at
                 limit 200
                """.formatted(WHO))
                .query((rs, n) -> new Notice(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getInt("amount_cents"), rs.getString("plan"),
                        rs.getString("cycle"), rs.getTimestamp("confirms_at").toInstant(),
                        rs.getString("paypal"), rs.getBoolean("upgrade"),
                        rs.getBoolean("referrer_is_employer")))
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
     * Earnings past their two months whose plan was still running then. The
     * {@code exists} is the site's {@code HELD} test, word for word: a
     * confirmation must never be announced for an earning the admin screen
     * would not let anybody pay.
     */
    int confirmations() {
        List<Notice> due = db.sql("""
                select r.id, %s, r.amount_cents, r.plan, r.cycle, r.confirms_at
                  from referral_earnings r
             left join candidates c on c.id = r.candidate_id
             left join employers re on re.id = r.referrer_employer_id
                 where r.confirmed_notified_at is null
                   and r.confirms_at <= now()
                   and coalesce(c.status, re.status) = 'ACTIVE'
                   and r.status = 'CONFIRMED'
                   and r.amount_cents > 0
                 order by r.confirms_at
                 limit 200
                """.formatted(WHO))
                .query((rs, n) -> new Notice(rs.getLong("id"), rs.getString("email"),
                        rs.getString("full_name"), rs.getInt("amount_cents"), rs.getString("plan"),
                        rs.getString("cycle"), rs.getTimestamp("confirms_at").toInstant(),
                        rs.getString("paypal"), false, rs.getBoolean("referrer_is_employer")))
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

    private String page(Notice n) {
        return siteUrl + (n.referrerIsEmployer() ? "/employers/referrals" : "/account/referrals");
    }

    private static String plan(Notice n) {
        String name = "STARTER".equals(n.plan()) ? "Starter" : "Growth";
        return ("ANNUAL".equals(n.cycle()) ? "an annual " : "a monthly ") + name + " plan";
    }

    /** "subscribed to a monthly Starter plan", or "moved up to an annual Growth plan". */
    private static String what(Notice n) {
        return (n.upgrade() ? "moved up to " : "subscribed to ") + plan(n);
    }

    /** "That earns you $5", or, on a move up, "That earns you another $20". */
    private static String earns(Notice n, boolean html) {
        String amount = html ? "<strong>" + money(n.amountCents()) + "</strong>" : money(n.amountCents());
        return "That earns you " + (n.upgrade() ? "another " : "") + amount + ".";
    }

    String signupSubject() {
        return "Someone signed up with your referral";
    }

    String signupText(Notice n) {
        return """
                %s

                An employer just created an account on Actually Remote through your \
                referral link or code.

                Nothing is earned yet: it counts once they pay for a plan, now or later. \
                We will write again when they do.

                %s

                — Actually Remote
                """.formatted(hi(n), page(n));
    }

    String signupHtml(Notice n) {
        String card = EmailLayout.eyebrow("Referrals")
                + EmailLayout.heading("Someone signed up with your referral")
                + EmailLayout.paragraph(EmailLayout.escape(hi(n)))
                + EmailLayout.paragraph("An employer just created an account on Actually Remote "
                        + "through your referral link or code.")
                + EmailLayout.paragraph("Nothing is earned yet: it counts once they pay for a "
                        + "plan, now or later. We will write again when they do.")
                + EmailLayout.button(page(n), "See your referrals");
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

                An employer you referred %s. %s

                It confirms on %s, if they are still on that plan or a better one then, \
                and is paid by \
                PayPal after that.%s

                %s

                — Actually Remote
                """.formatted(hi(n), what(n), earns(n, false), DAY.format(n.confirmsAt()),
                n.paypal() == null
                        ? " Add your PayPal email on your referrals page so it can be sent." : "",
                page(n));
    }

    String subscribedHtml(Notice n) {
        String card = EmailLayout.eyebrow("Referrals")
                + EmailLayout.heading("You earned " + money(n.amountCents()))
                + EmailLayout.paragraph(EmailLayout.escape(hi(n)))
                + EmailLayout.paragraph("An employer you referred " + what(n) + ". " + earns(n, true))
                + EmailLayout.paragraph("It confirms on " + DAY.format(n.confirmsAt())
                        + ", if they are still on that plan or a better one then, and is paid by PayPal after that."
                        + (n.paypal() == null
                            ? " Add your PayPal email on your referrals page so it can be sent."
                            : ""))
                + EmailLayout.button(page(n), "See your referrals");
        return EmailLayout.document("An employer you referred " + what(n) + ".", card,
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
                page(n));
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
                + EmailLayout.button(page(n), n.paypal() == null ? "Add your PayPal email" : "See your referrals");
        return EmailLayout.document("Your referral earning is confirmed.", card,
                EmailLayout.footer(siteUrl, null,
                        "You get this because you shared your referral link."));
    }
}
