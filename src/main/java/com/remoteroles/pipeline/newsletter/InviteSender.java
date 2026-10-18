package com.remoteroles.pipeline.newsletter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * <p>Sent in small batches. A burst of mail from a domain with no sending
 * history is the pattern filters are built to catch, and this domain also
 * carries confirmation mail and the employer review alerts — so a reputation
 * spent here is taken from the product's own operations.
 */
@Service
@ConditionalOnProperty(name = "newsletter.invite.enabled", havingValue = "true", matchIfMissing = true)
public class InviteSender {

    private static final Logger log = LoggerFactory.getLogger(InviteSender.class);
    private static final int SAMPLE_SIZE = 4;

    private final JdbcClient db;
    private final SubscriberRepository subscribers;
    private final Mailer mailer;
    private final String siteUrl;
    private final int batchSize;

    public InviteSender(
            JdbcClient db,
            SubscriberRepository subscribers,
            Mailer mailer,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.invite.batch-size:25}") int batchSize) {
        this.db = db;
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

        // Read once for the whole batch rather than per recipient. The board does
        // not move between two sends a second apart, and twenty-five copies of the
        // same two aggregates is work done for nothing.
        Board board = board();
        List<Role> sample = sample();

        for (SubscriberRepository.Lead lead : batch) {
            subscribers.markInvited(lead.id());
            mailer.send(lead.email(), subject(board), body(lead, board, sample));
        }

        log.info("invitations sent: {}", batch.size());
        return batch.size();
    }

    /** What the board currently holds, as the invitation quotes it. */
    record Board(int roles, int employers) {
        boolean isPresentable() {
            return roles > 0 && employers > 0;
        }
    }

    record Role(String title, String company) {}

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
                        select ranked.title, c.name as company
                          from ranked
                          join companies c on c.id = ranked.company_id
                         where ranked.rn = 1
                         order by ranked.posted_at desc nulls last, ranked.id desc
                         limit :limit
                        """)
                .param("limit", SAMPLE_SIZE)
                .query((rs, n) -> new Role(rs.getString("title"), rs.getString("company")))
                .list();
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

                Prefer not to receive these? Opt out permanently: %s
                """.formatted(siteUrl, confirm, siteUrl, unsubscribe));

        return out.toString();
    }
}
