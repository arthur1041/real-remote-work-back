package com.remoteroles.pipeline.newsletter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Asks one candidate for the CV their account does not have.
 *
 * <p>Queued by hand from /admin/candidates, one person at a time, which is the
 * whole design: there is no "mail everybody with no CV" button and this class
 * would be the wrong place to build one. A single ask, chosen by somebody who
 * looked at the row, is a different kind of message from a campaign — it
 * behaves like one at the filter and it reads like one in the inbox.
 *
 * <p>The conditions are checked again here, not only where the row was written.
 * Fifteen seconds pass between the two, and the one thing this message must
 * never do is ask for a document that arrived while it sat in the queue. A row
 * that no longer qualifies is stamped `skipped_at` with the reason rather than
 * deleted, because "the ask was made and withdrawn" and "no ask was made" are
 * different facts and the admin page shows the difference.
 *
 * <p>Claimed against the COLD tier, which is the part of the day's budget that
 * stops before the transactional reserve does. Nobody is sitting at a form
 * waiting for this, and a nudge must never be the message that spends the last
 * slot a password reset needed. That is the entire argument for the reserve in
 * {@link MailBudget}.
 */
@Service
public class CvRequestSender {

    private static final Logger log = LoggerFactory.getLogger(CvRequestSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public CvRequestSender(
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
     * One queued ask, with everything the send has to re-check alongside it.
     *
     * @param hasResume whether a profile CV has appeared since the ask
     * @param optedOut whether this person has since asked for no more mail
     */
    record Pending(long id, String email, String name, String token,
                   boolean hasResume, boolean optedOut, String status) {}

    @Scheduled(cron = "${candidate.cv-request.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select q.id, q.email, c.full_name, c.mail_opt_out_token, c.status,
                               (c.mail_opt_out_at is not null) as opted_out,
                               exists (
                                 select 1 from candidate_resumes r
                                  where r.candidate_id = c.id
                                    and r.scope = 'PROFILE'
                                    and r.replaced_at is null
                               ) as has_resume
                          from candidate_cv_requests q
                          join candidates c on c.id = q.candidate_id
                         where q.sent_at is null and q.skipped_at is null
                         order by q.created_at
                         limit 10
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("mail_opt_out_token"),
                        rs.getBoolean("has_resume"),
                        rs.getBoolean("opted_out"),
                        rs.getString("status")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            String skip = reasonToSkip(row);
            if (skip != null) {
                skip(row.id(), skip);
                continue;
            }
            if (!budget.claim(MailBudget.Tier.COLD)) {
                log.warn("cv request held: the day's mail budget for non-transactional mail is spent");
                break;
            }
            // Stamped before the send, like every other sender here: a crash
            // between the two costs one unsent message, where the other order
            // costs an unbounded number of duplicates to the same person.
            db.sql("update candidate_cv_requests set sent_at = now() where id = :id")
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), SUBJECT, body(row), html(row), oneClickOptOut(row.token()));
            sent++;
        }

        if (sent > 0) {
            log.info("cv requests sent: {}", sent);
        }
        return sent;
    }

    /** Why this one must not go out, or null when it may. */
    static String reasonToSkip(Pending row) {
        if (row.hasResume()) {
            return "a CV arrived before this was sent";
        }
        if (row.optedOut()) {
            return "opted out of mail";
        }
        if (!"ACTIVE".equals(row.status())) {
            return "account is " + String.valueOf(row.status()).toLowerCase();
        }
        return null;
    }

    private void skip(long id, String reason) {
        db.sql("""
                        update candidate_cv_requests
                           set skipped_at = now(), skip_reason = :reason
                         where id = :id
                        """)
                .param("reason", reason)
                .param("id", id)
                .update();
        log.info("cv request {} not sent: {}", id, reason);
    }

    /**
     * What the inbox sees first.
     *
     * <p>Plain, specific, and about the reader's own account. No exclamation
     * mark, no capitals, no "don't miss out", no urgency and no number —
     * everything a filter scores as a campaign, in the one line it scores
     * hardest. It says what the message is about and stops.
     */
    private static final String SUBJECT = "Adding your CV to your profile";

    /** Just the first name: the greeting should read like one, not like a record. */
    static String firstName(String full) {
        if (full == null || full.isBlank()) {
            return "there";
        }
        String first = full.trim().split("\\s+")[0];
        return first.length() > 40 ? first.substring(0, 40) : first;
    }

    private String optOutUrl(String token) {
        return "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    /**
     * Where the List-Unsubscribe header points.
     *
     * <p>The same argument as {@code InviteSender}: the provider never reads
     * the link in the body, so the header is the only place a sender gets to
     * say there is a way out. Without it the nearest control to "stop this" is
     * the one that reports the domain, and that domain also carries the login
     * codes.
     */
    private String oneClickOptOut(String token) {
        return "%s/api/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    /**
     * The text half, which is also the half a spam filter reads most closely.
     *
     * <p>It says why this address is being written to in the first sentence,
     * gives one reason that is true — the document is the thing an employer
     * actually opens — and carries two links and no more: the account, and the
     * way out. The sentence about what a CV is for is the account page's own,
     * so the message and the page it leads to do not describe the same thing
     * two different ways.
     */
    String body(Pending row) {
        return """
                Hi %s,

                You created a profile on Actually Remote, and there is no CV on it yet.

                The CV is the part an employer opens. It goes with every application \
                you send, and it is what puts you in front of recruiters hiring for \
                what you do, so a profile without one is harder to act on than it \
                looks.

                You can add it from your account, in one step:
                %s/account

                A PDF, up to 10 MB. You can replace it whenever you like.

                If you would rather not receive messages like this, that is settled \
                in one press here:
                %s

                — Actually Remote
                %s
                """.formatted(
                        firstName(row.name()),
                        siteUrl,
                        optOutUrl(row.token()),
                        siteUrl);
    }

    /**
     * The laid-out half, saying the same thing in the same order.
     *
     * <p>Same words as the text part, deliberately. A filter compares the two,
     * and the pattern it is looking for is an HTML half selling something the
     * text half does not mention.
     */
    String html(Pending row) {
        String card = EmailLayout.eyebrow("Your profile")
                + EmailLayout.heading("Your CV is the missing part")
                + EmailLayout.paragraph(
                        "Hi " + EmailLayout.escape(firstName(row.name()))
                                + " — you created a profile on Actually Remote, and there is "
                                + "no CV on it yet.",
                        true)
                + EmailLayout.paragraph(
                        "The CV is the part an employer opens. It goes with every application "
                                + "you send, and it is what puts you in front of recruiters "
                                + "hiring for what you do, so a profile without one is harder "
                                + "to act on than it looks.")
                + EmailLayout.button(siteUrl + "/account", "Add your CV")
                + EmailLayout.paragraph(
                        "A PDF, up to 10 MB. You can replace it whenever you like.")
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "This went to the address on your Actually Remote account.");

        return EmailLayout.document(
                "Your profile has no CV on it yet.",
                card,
                EmailLayout.footer(
                        siteUrl,
                        optOutUrl(row.token()),
                        "Stop messages like this",
                        null));
    }

    /** Sends this template to one address, for looking at. */
    public void sendPreview(String to) {
        Pending row = new Pending(0, to, "Ada Lovelace", "preview", false, false, "ACTIVE");
        mailer.send(to, "[preview] " + SUBJECT, body(row), html(row), null);
    }
}
