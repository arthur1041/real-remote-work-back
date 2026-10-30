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
 * Tells somebody, once a day at most, that messages are waiting for them.
 *
 * <p>The chat reaches a person whose browser is open, and web push reaches one
 * who granted permission and is on a device that supports it. Neither reaches
 * somebody who closed the tab two days ago, and that is most people. This is
 * the channel that does.
 *
 * <p><strong>The whole design is the anti-repeat rule.</strong> A daily job
 * that asks "does this person have unread messages?" mails them every single
 * day until they read, forever, which is how a helpful notice becomes the
 * reason somebody marks the domain as spam — and this domain also carries the
 * login codes. So the question asked is not "are there unread messages" but
 * "are there unread messages **this has never mentioned**". Each message is
 * stamped `notified_at` when it is included, and a stamped message can never
 * be counted again. Somebody who never reads anything gets exactly one email
 * per message, spread across days, and then silence.
 *
 * <p>Messages already read are stamped too, without being counted. They must
 * never be able to trigger a future email either, and leaving them unstamped
 * is a row that waits for the day the read mark moves.
 *
 * <p><strong>An hour of grace.</strong> Only messages older than that are
 * considered, so a reply that arrived while the two of them were talking does
 * not become an email about being behind. Younger ones stay unstamped and are
 * picked up by the next run.
 *
 * <p>Claimed against the COLD tier. Nobody is sitting at a form waiting for
 * this, and a nudge must never spend the slot a password reset needed — the
 * whole argument for the reserve in {@link MailBudget}. Being held for a day
 * costs nothing here, because nothing is stamped unless it is sent.
 */
@Service
public class UnreadMessageNotifier {

    private static final Logger log = LoggerFactory.getLogger(UnreadMessageNotifier.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public UnreadMessageNotifier(
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
     * One person with something waiting.
     *
     * @param side which half of the product they are, which decides the link
     * @param unread how many they have never been told about
     * @param optOutToken the candidate's one-click token; null for an employer
     */
    record Waiting(String side, long id, String email, String name, int unread, String optOutToken) {}

    @Scheduled(cron = "${messages.digest.cron:0 0 9 * * *}")
    public void run() {
        notifyWaiting();
    }

    /** Returns how many people were written to. */
    public int notifyWaiting() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Waiting> waiting = db.sql("""
                        with fresh as (
                          select m.id, m.conversation_id, m.sender, m.created_at,
                                 c.employer_id, c.candidate_id,
                                 c.employer_read_at, c.candidate_read_at
                            from conversation_messages m
                            join conversations c on c.id = m.conversation_id
                           where m.notified_at is null
                             and m.created_at < now() - interval '1 hour'
                        ),
                        for_candidate as (
                          select candidate_id as who, count(*) as n
                            from fresh
                           where sender = 'EMPLOYER'
                             and (candidate_read_at is null or created_at > candidate_read_at)
                           group by candidate_id
                        ),
                        for_employer as (
                          select employer_id as who, count(*) as n
                            from fresh
                           where sender = 'CANDIDATE'
                             and (employer_read_at is null or created_at > employer_read_at)
                           group by employer_id
                        )
                        select 'CANDIDATE' as side, c.id, c.email, c.full_name as name,
                               f.n as unread, c.mail_opt_out_token as token
                          from for_candidate f
                          join candidates c on c.id = f.who
                         where c.deleted_at is null
                           and c.status = 'ACTIVE'
                           and c.mail_opt_out_at is null
                        union all
                        select 'EMPLOYER' as side, e.id, e.email, e.company_name as name,
                               f.n as unread, null as token
                          from for_employer f
                          join employers e on e.id = f.who
                         where e.status = 'ACTIVE'
                        """)
                .query((rs, n) -> new Waiting(
                        rs.getString("side"),
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("name"),
                        rs.getInt("unread"),
                        rs.getString("token")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Waiting row : waiting) {
            if (!budget.claim(MailBudget.Tier.COLD)) {
                log.warn("unread notices held: the day's mail budget for non-transactional mail is spent");
                break;
            }
            // Stamped before the send, like every other sender here: a crash
            // between the two costs one unsent notice, where the other order
            // costs the same person the same notice every day until they read.
            stamp(row);
            boolean ok = row.optOutToken() == null
                    ? mailer.send(row.email(), subject(row), body(row), html(row))
                    : mailer.send(row.email(), subject(row), body(row), html(row),
                            oneClickOptOut(row.optOutToken()));
            if (ok) {
                sent++;
            }
        }

        if (sent > 0) {
            log.info("unread message notices sent: {}", sent);
        }
        return sent;
    }

    /**
     * Marks everything this person could have been told about, counted or not.
     *
     * <p>The predicate is deliberately wider than the one that counted them: a
     * message they had already read is stamped here too. It was never going to
     * be in an email, and leaving it unstamped would leave a row waiting for
     * the day somebody's read mark moves backwards — which nothing does today
     * and which is not worth depending on.
     */
    private void stamp(Waiting row) {
        boolean candidate = "CANDIDATE".equals(row.side());
        db.sql("""
                        update conversation_messages m
                           set notified_at = now()
                          from conversations c
                         where c.id = m.conversation_id
                           and m.notified_at is null
                           and m.created_at < now() - interval '1 hour'
                           and m.sender = :from
                           and c.%s = :who
                        """.formatted(candidate ? "candidate_id" : "employer_id"))
                .param("from", candidate ? "EMPLOYER" : "CANDIDATE")
                .param("who", row.id())
                .update();
    }

    /**
     * What the inbox sees first.
     *
     * <p>No number in the subject, and nothing urgent. A count there is what a
     * filter scores hardest and what makes a notice read like a campaign; the
     * number belongs in the first line of the message, where the person has
     * already decided to look.
     */
    static String subject(Waiting row) {
        return row.unread() == 1 ? "A message about your application" : "Messages waiting for you";
    }

    private String home(Waiting row) {
        return "CANDIDATE".equals(row.side())
                ? siteUrl + "/account/messages"
                : siteUrl + "/employers/messages";
    }

    /** Just the first name, so the greeting reads like one. */
    static String firstName(String full) {
        if (full == null || full.isBlank()) {
            return "there";
        }
        String first = full.trim().split("\\s+")[0];
        return first.length() > 40 ? first.substring(0, 40) : first;
    }

    static String count(int n) {
        return n == 1 ? "one message" : n + " messages";
    }

    private String optOutUrl(String token) {
        return "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    private String oneClickOptOut(String token) {
        return "%s/api/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    /**
     * The text half, which is also the half a spam filter reads most closely.
     *
     * <p><strong>It does not carry what was written.</strong> That is a
     * decision, not an omission: /privacy promises a message is deleted ninety
     * days after the last one in its conversation, and a copy sitting in
     * somebody's mailbox forever is outside anything this site can keep that
     * promise about. It also makes the link the only way to read it, which is
     * the point — the conversation is meant to happen here rather than leak
     * back into email, which is the leak the chat was built to close.
     */
    String body(Waiting row) {
        return """
                Hi %s,

                You have %s on Actually Remote that you have not read yet.

                %s

                This is sent at most once for each message. If you never open \
                them, it does not keep asking.
                %s
                — Actually Remote
                %s
                """.formatted(
                        firstName(row.name()),
                        count(row.unread()),
                        home(row),
                        row.optOutToken() == null
                                ? ""
                                : "\nIf you would rather not receive messages like this, "
                                        + "that is settled in one press here:\n"
                                        + optOutUrl(row.optOutToken()) + "\n",
                        siteUrl);
    }

    /** The laid-out half, saying the same thing in the same order. */
    String html(Waiting row) {
        return """
                <p>Hi %s,</p>
                <p>You have <strong>%s</strong> on Actually Remote that you have not read yet.</p>
                <p><a href="%s">Open your messages</a></p>
                <p style="color:#666;font-size:13px">This is sent at most once for each \
                message. If you never open them, it does not keep asking.</p>
                %s
                <p style="color:#666;font-size:13px">— Actually Remote<br><a href="%s">%s</a></p>
                """.formatted(
                        escape(firstName(row.name())),
                        count(row.unread()),
                        home(row),
                        row.optOutToken() == null
                                ? ""
                                : "<p style=\"color:#666;font-size:13px\">"
                                        + "<a href=\"" + optOutUrl(row.optOutToken()) + "\">"
                                        + "Stop receiving messages like this</a></p>",
                        siteUrl, siteUrl);
    }

    /** The name is the only thing here that somebody else typed. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
