package com.remoteroles.pipeline.newsletter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The invitation sent to an address that has not subscribed yet.
 *
 * <p>Deliberately separate from {@link ConfirmationSender}, which verifies an
 * address its owner typed into a form. That message opens with "you asked to
 * hear about new worldwide remote roles", and this one cannot say that.
 *
 * <p>What it says instead is what the board holds: the live count of roles open
 * worldwide, the number of employers behind them, and four of them by name. A
 * stranger deciding whether this is worth a click is deciding whether the thing
 * on offer is real, and a list of real roles answers that faster than any
 * sentence about the list could. Nothing here describes how the board works, how
 * new it is, or who runs it — none of that is the offer.
 *
 * <p>Two at most: the invitation and one reminder, the reminder only after a
 * month and only to somebody who never responded. Mail gets missed, so one
 * nudge is fair; a third would be a campaign, and the person who ignored two has
 * answered twice.
 *
 * <p>Sent in small batches, often. The pacing is not caution for its own sake:
 * a burst of mail from a domain with little sending history is the pattern
 * filters are built to catch, and this domain also carries confirmation mail
 * and the employer review alerts — so a reputation spent here is taken from the
 * product's own operations.
 *
 * <p>What matters is the shape, not only the rate. Twenty-five messages in one
 * second each hour and five every two minutes come to roughly the same volume,
 * but the first is a burst with a flat silence after it and the second is a
 * trickle. The trickle is both the safer pattern and the faster drain, which is
 * why the batch is small and the schedule is frequent rather than the reverse.
 */
@Service
@ConditionalOnProperty(name = "newsletter.invite.enabled", havingValue = "true", matchIfMissing = true)
public class InviteSender {

    private static final Logger log = LoggerFactory.getLogger(InviteSender.class);
    private static final int SAMPLE_SIZE = 4;

    /**
     * How long the queue stops after the relay refuses a message.
     *
     * <p>A synchronous refusal here is hardly ever about the address — the relay
     * accepts those and bounces them later. It is a daily quota, a revoked key,
     * an outage. Without a pause the scheduler would wake every couple of
     * minutes and feed another lead into the same wall, and because a lead is
     * stamped before the attempt, each one it burns is a person who will never
     * be written to again.
     */
    private static final Duration PAUSE_AFTER_FAILURE = Duration.ofMinutes(30);

    /** Set when a send is refused; nothing is attempted until it passes. */
    private volatile Instant pausedUntil = Instant.EPOCH;

    private final JdbcClient db;
    private final SubscriberRepository subscribers;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;
    private final int batchSize;

    public InviteSender(
            JdbcClient db,
            SubscriberRepository subscribers,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.invite.batch-size:25}") int batchSize) {
        this.db = db;
        this.subscribers = subscribers;
        this.mailer = mailer;
        this.budget = budget;
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
        if (!mailer.isDeliveryEnabled() || Instant.now().isBefore(pausedUntil)) {
            return 0;
        }

        List<SubscriberRepository.Lead> batch = subscribers.awaitingInvite(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        // Read once for the whole batch rather than per recipient. The board does
        // not move between two sends a second apart, and twenty-five copies of the
        // same two aggregates is work done for nothing.
        Board board = board();
        List<Role> sample = sample();

        int sent = 0;
        for (SubscriberRepository.Lead lead : batch) {
            // Before the stamp, so a refusal costs nothing. This is the whole
            // difference between knowing the daily limit and discovering it:
            // the lead stays queued and goes out tomorrow, rather than being
            // marked as written to by an attempt that never left.
            if (!budget.claim(MailBudget.Tier.COLD)) {
                log.info("invitations held: today's cold-mail budget is spent, {} sent this run", sent);
                break;
            }
            subscribers.markInvited(lead.id());
            boolean accepted = mailer.send(lead.email(), subject(board),
                    body(lead, board, sample), html(lead, board, sample),
                    oneClickUnsubscribe(lead.unsubscribeToken()));
            if (!accepted) {
                // Stop on the first refusal rather than working through the
                // rest. The stamp on this one lead stands -- recovering it
                // would mean retrying an address that may be the problem, in a
                // loop -- but it is the only one lost, instead of everything
                // still queued behind it.
                pausedUntil = Instant.now().plus(PAUSE_AFTER_FAILURE);
                log.warn("invitations paused for {} after a refused send; {} sent this run",
                        PAUSE_AFTER_FAILURE, sent);
                break;
            }
            sent++;
        }

        if (sent > 0) {
            log.info("invitations sent: {}", sent);
        }
        return sent;
    }

    /** What the board currently holds, as the invitation quotes it. */
    record Board(int roles, int employers) {
        boolean isPresentable() {
            return roles > 0 && employers > 0;
        }
    }

    record Role(long id, String title, String company) {}

    /**
     * The same definition of "open worldwide" the board itself serves.
     *
     * <p>If this drifted from the site's own filter the invitation would quote a
     * number the landing page then contradicts, which is the one way a concrete
     * figure is worse than no figure at all.
     */
    private Board board() {
        return db.sql("""
                        select count(*) as roles, count(distinct j.company_id) as employers
                        from jobs j
                        where j.closed_at is null
                          and j.is_remote is true
                          and j.geo_scope = 'WORLDWIDE'
                          and j.classification_confidence >= 0.5
                        """)
                .query((rs, n) -> new Board(rs.getInt("roles"), rs.getInt("employers")))
                .optional()
                .orElse(new Board(0, 0));
    }

    /**
     * The four roles the invitation names.
     *
     * <p>Not simply the four newest. The newest four, taken raw, were a posting
     * from a company with one role and a test name, and a title that opened with
     * a salary band and closed with "(Central or Eastern Time Zone)" — in a
     * message whose own second line promises no timezone band. An invitation is a
     * shop window, and the first four things in it decide whether the reader
     * believes the rest.
     *
     * <p>So: one role per company, only from employers with at least two roles
     * open worldwide, and only titles short enough to survive a plain-text line.
     * The repeat-hirer rule is the one doing the work — a company that has built
     * the machinery to employ anybody uses it again, so the filter selects for
     * employers a reader recognises without anything being hand-picked. Titles
     * over seventy characters are dropped because they wrap, and a wrapped title
     * in a list of four reads as a classified ad.
     *
     * <p>Recency still orders what survives. A stale example reads as a stale
     * board, and the reminder a month later is a different email for free.
     */
    private List<Role> sample() {
        return db.sql("""
                        with worldwide as (
                          select j.id, j.title, j.company_id, j.posted_at
                            from jobs j
                           where j.closed_at is null
                             and j.is_remote is true
                             and j.geo_scope = 'WORLDWIDE'
                             and j.classification_confidence >= 0.5
                        ),
                        repeat_hirers as (
                          select company_id from worldwide
                           group by company_id having count(*) >= 2
                        ),
                        ranked as (
                          select w.*,
                                 row_number() over (
                                     partition by w.company_id
                                     order by w.posted_at desc nulls last, w.id desc) as rn
                            from worldwide w
                            join repeat_hirers r on r.company_id = w.company_id
                           where length(w.title) <= 70
                        )
                        select ranked.id, ranked.title, c.name as company
                          from ranked
                          join companies c on c.id = ranked.company_id
                         where ranked.rn = 1
                         order by ranked.posted_at desc nulls last, ranked.id desc
                         limit :limit
                        """)
                .param("limit", SAMPLE_SIZE)
                .query((rs, n) -> new Role(
                        rs.getLong("id"), rs.getString("title"), rs.getString("company")))
                .list();
    }

    /**
     * Where the List-Unsubscribe header points.
     *
     * <p>This matters more on an invitation than anywhere else on the site. The
     * reader did not ask for this message, and the nearest control to "make
     * this stop" in most clients is the one that reports it as spam. A native
     * unsubscribe button, drawn by the client because this header is present,
     * is the only thing standing between that impulse and the domain's
     * reputation -- which the confirmation mail and the review alerts spend
     * too.
     */
    private String oneClickUnsubscribe(String token) {
        return "%s/api/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private static String count(int value) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(value);
    }

    private String subject(Board board) {
        return board.isPresentable()
                ? "%s remote jobs with no country requirement".formatted(count(board.roles()))
                : "Remote jobs with no country requirement";
    }

    /**
     * Opens on the offer, not on an explanation.
     *
     * <p>Plain text and no images, the same as the digest: an invitation that
     * renders as a wall of broken image placeholders in the two clients that
     * block them by default has spent its one impression. The links are bare so
     * that what they point at is visible before anything is clicked.
     */
    private String body(SubscriberRepository.Lead lead, Board board, List<Role> sample) {
        String confirm = "%s/confirm?token=%s"
                .formatted(siteUrl, URLEncoder.encode(lead.confirmToken(), StandardCharsets.UTF_8));
        String unsubscribe = "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(lead.unsubscribeToken(), StandardCharsets.UTF_8));

        StringBuilder out = new StringBuilder();
        out.append("""
                Most jobs advertised as "remote" still require you to live in a \
                particular country. Actually Remote lists only the ones that do not.
                """);

        if (board.isPresentable()) {
            out.append("""

                    %s roles from %s companies are open worldwide right now — no \
                    country requirement, no timezone band, no relocation.
                    """.formatted(count(board.roles()), count(board.employers())));
        } else {
            out.append("""

                    Every role on the board is open worldwide — no country \
                    requirement, no timezone band, no relocation.
                    """);
        }

        if (!sample.isEmpty()) {
            out.append("\nRecently added:\n\n");
            for (Role role : sample) {
                out.append("  • %s — %s\n".formatted(role.title(), role.company()));
            }
        }

        out.append("""

                Browse them all: %s

                New roles are added every day. To get the week's worldwide \
                openings by email, free:

                %s

                — Actually Remote
                %s

                Prefer not to hear from us? Opt out here and we will not \
                write again: %s
                """.formatted(siteUrl, confirm, siteUrl, unsubscribe));

        return out.toString();
    }

    /**
     * The same message, laid out.
     *
     * <p>Shares nothing with {@link #body} except the facts, deliberately. A
     * templating layer that produced both from one source would have to be
     * written for the lowest common denominator, and the two halves want
     * genuinely different things: the text part wants a URL on its own line, the
     * HTML part wants the row to be the tap target.
     */
    private String html(SubscriberRepository.Lead lead, Board board, List<Role> sample) {
        String confirm = "%s/confirm?token=%s"
                .formatted(siteUrl, URLEncoder.encode(lead.confirmToken(), StandardCharsets.UTF_8));
        String unsubscribe = "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(lead.unsubscribeToken(), StandardCharsets.UTF_8));

        StringBuilder card = new StringBuilder();
        card.append(EmailLayout.heading("Remote jobs that do not ask where you live"));
        card.append(EmailLayout.paragraph(
                "Most jobs advertised as &ldquo;remote&rdquo; still require you to live in a "
                        + "particular country. Actually Remote lists only the ones that do not.",
                true));

        if (board.isPresentable()) {
            card.append(EmailLayout.stats(List.of(
                    new String[] {count(board.roles()), "roles open worldwide"},
                    new String[] {count(board.employers()), "companies hiring"})));
            card.append(EmailLayout.paragraph(
                    "No country requirement, no timezone band, no relocation."));
        } else {
            card.append(EmailLayout.paragraph(
                    "Every role on the board is open worldwide &mdash; no country requirement, "
                            + "no timezone band, no relocation."));
        }

        if (!sample.isEmpty()) {
            card.append(EmailLayout.eyebrow("Recently added"));
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < sample.size(); i++) {
                Role role = sample.get(i);
                rows.append(EmailLayout.roleRow(
                        role.title(),
                        role.company(),
                        "%s/jobs/%d".formatted(siteUrl, role.id()),
                        i == sample.size() - 1));
            }
            card.append(EmailLayout.roleList(rows.toString()));
        }

        card.append(EmailLayout.textLink(
                siteUrl,
                board.isPresentable()
                        ? "Browse all %s roles \u2192".formatted(count(board.roles()))
                        : "Browse the board \u2192"));
        card.append(EmailLayout.rule());
        card.append(EmailLayout.paragraph(
                "New roles are added every day. Get the week&rsquo;s worldwide openings by "
                        + "email, free.",
                true));
        card.append(EmailLayout.button(confirm, "Get the weekly email"));

        return EmailLayout.document(
                board.isPresentable()
                        ? "%s roles from %s companies, open to anyone anywhere."
                                .formatted(count(board.roles()), count(board.employers()))
                        : "Remote roles with no country requirement.",
                card.toString(),
                // Not "Unsubscribe": this reader is on no list, and a footer
                // offering to end a subscription contradicts the invitation
                // above it. The link is the same one -- what it stops is the
                // reminder, not a newsletter nobody signed up for.
                EmailLayout.footer(siteUrl, unsubscribe, "Don\u2019t email me again", null));
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
        SubscriberRepository.Lead lead =
                new SubscriberRepository.Lead(0, to, "preview-token", "preview-token");
        Board board = board();
        List<Role> sample = sample();
        mailer.send(to, "[preview] " + subject(board),
                body(lead, board, sample), html(lead, board, sample),
                oneClickUnsubscribe(lead.unsubscribeToken()));
    }
}
