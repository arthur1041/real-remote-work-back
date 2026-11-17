package com.remoteroles.pipeline.newsletter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells an employer which of their roles came off the board, and why.
 *
 * <p><strong>Without this the feature should not ship.</strong> {@code
 * SourceVerifier} reads an employer's own Apply page and pauses the role when
 * that page says it is closed. It will be right nearly always and wrong
 * sometimes — a page redesign, a link the employer repointed, a host that
 * started answering differently — and a listing that disappears with no message
 * is indistinguishable, from the outside, from a board that lost it. The
 * message is what turns an automatic takedown into something the owner can
 * overrule.
 *
 * <p>So it says three things and no more: which roles, what the page said, and
 * that one click puts them back. No apology, no accusation, and no guessing
 * about why their page changed.
 *
 * <p><strong>One message per employer, never one per role.</strong> The same
 * lesson as {@link PostingDecisionSender}: a single account here holds 327 live
 * roles, and a sweep that found forty of them closed would otherwise send forty
 * emails to one address in one morning — more than the day's whole allowance,
 * for one person's inbox. Grouping is not politeness, it is the difference
 * between a working mailbox and a spam complaint.
 *
 * <p>Runs a few minutes after the verification, on its own cron rather than
 * inside the sweep: the sweep's job is to be right about the database, and a
 * mail server being slow or a budget being spent is not a reason for it to have
 * failed. A group held today goes out tomorrow, as one message rather than two.
 */
@Service
public class PauseNoticeSender {

    private static final Logger log = LoggerFactory.getLogger(PauseNoticeSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;
    private final int maxListed;

    public PauseNoticeSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${employer.pause-notice.max-roles-listed:25}") int maxListed) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.maxListed = Math.max(1, maxListed);
    }

    /** One paused posting. */
    record Paused(long id, long employerId, String email, String contact, String title) { }

    @Scheduled(cron = "${employer.pause-notice.cron:0 20 5 * * *}")
    public void run() {
        send();
    }

    /** Returns how many messages went out. */
    public int send() {
        if (!mailer.isDeliveryEnabled()) return 0;

        List<Paused> waiting = db.sql("""
                select p.id, p.employer_id, e.email, e.contact_name, p.title
                  from employer_postings p
                  join employers e on e.id = p.employer_id
                 where p.pause_notified_at is null
                   and p.paused_at is not null
                   and p.pause_reason = 'SOURCE_CLOSED'
                   and e.status = 'ACTIVE'
                 order by p.employer_id, p.paused_at
                 limit 500
                """)
                .query((rs, n) -> new Paused(rs.getLong("id"), rs.getLong("employer_id"),
                        rs.getString("email"), rs.getString("contact_name"),
                        rs.getString("title")))
                .list();
        if (waiting.isEmpty()) return 0;

        Map<Long, List<Paused>> groups = group(waiting);

        int sent = 0;
        for (List<Paused> group : groups.values()) {
            // Transactional: this is about the employer's own listings and it is
            // what makes the takedown reversible. Held rather than dropped when
            // the budget is gone — the rows stay unstamped and go out tomorrow.
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("pause notices held: the day's whole mail budget is spent");
                break;
            }
            // Stamped before sending, as everywhere else here: stamping
            // afterwards mails the same batch every cycle until somebody
            // notices.
            /* Claimed rather than stamped, and the count is read: a second
               process that got there first leaves nothing to claim and this
               group is skipped. See `config/SchedulingConfig`.

               A *partial* claim — some of the group taken, some not — would
               still re-announce the taken ones, and that is left as it is: the
               window it needs was closed at the other end, and re-querying the
               group here would be a second answer to a question the select
               above already asked. */
            if (db.sql("""
                            update employer_postings set pause_notified_at = now()
                             where id in (:ids) and pause_notified_at is null
                            """)
                    .param("ids", group.stream().map(Paused::id).toList())
                    .update() == 0) {
                continue;
            }
            Paused first = group.get(0);
            mailer.send(first.email(), subject(group), body(group), html(group));
            sent++;
        }

        if (sent > 0) {
            log.info("pause notices sent: {} message(s) for {} role(s)", sent, waiting.size());
        }
        return sent;
    }

    /**
     * One message's worth of rows per entry: same employer.
     *
     * <p>Separate from the sweep and pure, because this is the half where being
     * wrong is expensive and invisible. A key that collapsed too much would send
     * one employer's listings to another employer's address; a key that
     * collapsed too little would go quietly back to one mail per role, which for
     * the account holding 327 of them is the whole day's allowance in one
     * inbox. Neither shows up in a log.
     */
    static Map<Long, List<Paused>> group(List<Paused> rows) {
        Map<Long, List<Paused>> groups = new LinkedHashMap<>();
        for (Paused row : rows) {
            groups.computeIfAbsent(row.employerId(), k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    String subject(List<Paused> group) {
        return group.size() == 1
                ? "A role of yours was paused: " + group.get(0).title()
                : group.size() + " of your roles were paused";
    }

    /**
     * Empty rather than "Hi ," on accounts made before the name was asked for —
     * the same call as the decision notice, and for the same reason.
     */
    private static String greeting(Paused row) {
        String name = row.contact();
        if (name == null || name.isBlank()) return "";
        return "Hi %s,%n%n".formatted(name.trim().split("\\s+")[0]);
    }

    String list(List<Paused> group) {
        StringBuilder out = new StringBuilder();
        group.stream().limit(maxListed).forEach(p -> out.append("  ").append(p.title()).append('\n'));
        if (group.size() > maxListed) {
            out.append("  ... and %d more, all of them in your dashboard.%n"
                    .formatted(group.size() - maxListed));
        }
        return out.toString();
    }

    String body(List<Paused> group) {
        String what = group.size() == 1
                ? "This role has been paused:"
                : "%d of your roles have been paused:".formatted(group.size());
        return greeting(group.get(0)) + """
                %s

                %s
                The page each one sends applicants to says it is no longer accepting \
                applications, so they have come off the board rather than send people \
                to a closed door. Nothing was deleted: they are still in your \
                dashboard, and they do not count against your plan while paused.

                If a role is still open, put it back in one click: open your \
                dashboard and activate it. It stays up after that, and this check \
                leaves it alone from then on.

                %s/employers

                — Actually Remote
                %s
                """.formatted(what, list(group), siteUrl, siteUrl);
    }

    String html(List<Paused> group) {
        String heading = group.size() == 1
                ? "A role was paused"
                : "%d roles were paused".formatted(group.size());
        StringBuilder items = new StringBuilder();
        group.stream().limit(maxListed)
                .forEach(p -> items.append(EmailLayout.paragraph(p.title())));
        if (group.size() > maxListed) {
            items.append(EmailLayout.paragraph("&hellip; and %d more, all of them in "
                    .formatted(group.size() - maxListed)
                    + EmailLayout.textLink(siteUrl + "/employers", "your dashboard") + "."));
        }
        return EmailLayout.eyebrow("Paused")
                + EmailLayout.heading(heading)
                + items
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "The page each one sends applicants to says it is no longer accepting "
                                + "applications, so they have come off the board rather than send "
                                + "people to a closed door. Nothing was deleted: they are still in "
                                + "your dashboard, and they do not count against your plan while "
                                + "paused.")
                + EmailLayout.paragraph(
                        "If a role is still open, activate it again and it stays up — this check "
                                + "leaves it alone from then on.")
                + EmailLayout.button(siteUrl + "/employers", "Open your dashboard");
    }
}
