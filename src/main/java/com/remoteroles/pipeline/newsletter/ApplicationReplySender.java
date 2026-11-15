package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Delivers what an employer wrote to the people who applied to their role.
 *
 * <p><strong>Every word of this is the employer's.</strong> B.2 of
 * {@code docs/plano-growth.md}, and principle 1 of the same document seen from
 * the sending end: no model writes to a candidate and nothing goes out on a
 * timer that a person did not press. The site wrote the row when somebody
 * pressed send; this class is the postman, and it has no opinion about the
 * letter.
 *
 * <p><strong>Why the text is in the row rather than composed here.</strong>
 * The subject and body arrive already rendered, one copy per person, with the
 * address resolved at the moment of the press. A template is a thing people
 * edit, and "what did this person actually receive?" has to stay answerable a
 * month later — composing at send time would answer with today's template and
 * today's profile address.
 *
 * <p><strong>TRANSACTIONAL, not COLD.</strong> This is a reply to somebody who
 * wrote first: they applied for a job and are waiting to hear. It is the same
 * tier as a password reset for the same reason — holding it for a day to
 * protect the budget would be protecting the budget from the mail the product
 * exists to send. Cold mail is the invitation to an address that asked for
 * nothing, and this is its opposite.
 *
 * <p><strong>No unsubscribe header, and that is deliberate.</strong> There is
 * no list to leave: one application produces at most the replies that
 * employer chooses to send about it, and the way out is the way in — the
 * candidate withdrew, or never applied. A `List-Unsubscribe` here would offer
 * to stop a correspondence rather than a campaign.
 */
@Service
public class ApplicationReplySender {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReplySender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final int batch;
    private final int maxAttempts;

    public ApplicationReplySender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${employer.reply.batch-size:25}") int batch,
            /* How many refusals before a letter is given up on. Three, like
               every other retry in this pipeline: enough to ride out a relay
               that is down for a minute, few enough that a wrong address does
               not get tried for ever. */
            @Value("${employer.reply.max-attempts:3}") int maxAttempts) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.batch = batch;
        this.maxAttempts = maxAttempts;
    }

    /**
     * One queued letter, with what the send has to check again beside it.
     *
     * @param withdrawn whether the application was taken back after the press
     * @param deleted whether the account is gone
     */
    record Pending(long id, String to, String subject, String body,
                   boolean withdrawn, boolean deleted) {}

    @Scheduled(cron = "${employer.reply.cron:*/20 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select r.id, r.to_address, r.subject, r.body,
                               (a.status = 'WITHDRAWN') as withdrawn,
                               (c.status = 'DELETED') as deleted
                          from application_replies r
                          join job_applications a on a.id = r.application_id
                          join candidates c on c.id = a.candidate_id
                         where r.sent_at is null and r.failed_at is null
                           and r.attempts < :max
                         order by r.created_at
                         limit :batch
                        """)
                .param("batch", batch)
                .param("max", maxAttempts)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        rs.getString("to_address"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getBoolean("withdrawn"),
                        rs.getBoolean("deleted")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            /* Re-checked here and not only where the row was written. Seconds
               pass between the two, and the one letter this must never
               deliver is one to somebody who withdrew in the meantime: they
               have said the conversation is over, and a reply arriving after
               that reads as if the withdrawal was ignored. */
            String stop = reasonToStop(row);
            if (stop != null) {
                fail(row.id(), stop);
                continue;
            }
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("application replies held: the day's mail budget is spent");
                break;
            }
            /* Stamped before the send, like every other sender here. A crash
               between the two costs one undelivered reply; the other order
               costs the same person the same letter on every pass. */
            db.sql("update application_replies set sent_at = now(), attempts = attempts + 1 where id = :id")
                    .param("id", row.id())
                    .update();

            /* And unstamped when the provider refuses it.
             *
             * `send` answers whether it was accepted, and most callers here
             * ignore that on purpose -- they are working through a batch and
             * one bad address must not stop the rest. This one cannot ignore
             * it. A row that says `sent_at` when nothing left the building
             * tells the employer "Replied 15 Nov" on their screen and tells
             * the candidate, through `stageForCandidate`, that they were not
             * taken forward -- a decision they can read about in an email
             * they never received. Measured on a laptop with a refusing
             * relay: the row said sent, the log said "Authentication failed",
             * and the two screens said the letter had gone.
             *
             * Clearing it puts the row back in the queue for the next pass,
             * which is right for a relay that was briefly down; after
             * `maxAttempts` it stops and says so, which is right for an
             * address or a credential that is simply wrong. */
            boolean accepted = mailer.send(row.to(), row.subject(), row.body(), html(row.body()));
            if (!accepted) {
                db.sql("""
                        update application_replies
                           set sent_at = null,
                               failed_at = case when attempts >= :max then now() else null end,
                               failure = case when attempts >= :max
                                              then 'the mail provider refused it'
                                              else null end
                         where id = :id
                        """)
                        .param("max", maxAttempts)
                        .param("id", row.id())
                        .update();
                continue;
            }
            sent++;
        }

        if (sent > 0) {
            log.info("application replies sent: {}", sent);
        }
        return sent;
    }

    /** Why this one must not go out, or null when it may. */
    static String reasonToStop(Pending row) {
        if (row.withdrawn()) {
            return "the application was withdrawn before this was sent";
        }
        if (row.deleted()) {
            return "the account was deleted before this was sent";
        }
        if (row.to() == null || !row.to().contains("@")) {
            return "no address to send to";
        }
        return null;
    }

    private void fail(long id, String reason) {
        db.sql("""
                        update application_replies
                           set failed_at = now(), failure = :reason
                         where id = :id
                        """)
                .param("reason", reason)
                .param("id", id)
                .update();
        log.info("application reply {} not sent: {}", id, reason);
    }

    /**
     * The HTML half, which is the employer's text and nothing else.
     *
     * <p>Escaped and turned into paragraphs, never interpreted: an employer
     * pasting `<b>` into the box is somebody who typed a character, not
     * somebody authoring markup, and a template that could carry a tag could
     * carry a link to anywhere with this product's name on the envelope.
     */
    static String html(String body) {
        StringBuilder out = new StringBuilder("<div style=\"font:15px/1.6 -apple-system,Segoe UI,Roboto,sans-serif\">");
        for (String paragraph : body.split("\n\\s*\n")) {
            if (paragraph.isBlank()) {
                continue;
            }
            out.append("<p>").append(escape(paragraph).replace("\n", "<br>")).append("</p>");
        }
        return out.append("</div>").toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
