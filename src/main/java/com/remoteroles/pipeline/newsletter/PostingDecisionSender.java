package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells the employer what was decided about their role.
 *
 * <p>The counterpart to {@link ReviewAlerter}, which tells the moderator a role
 * is waiting. That half was built first because nothing moves without it; this
 * half is the one the employer experiences. Until now the decision existed only
 * on a page they had to think to revisit, which makes a review that took an
 * hour feel like one that never happened.
 *
 * <p>A rejection matters more here than an approval. The note explaining what
 * was wrong is already written at review time and already stored on the row --
 * it was simply never delivered to the one person it was written for. An
 * approval they will eventually notice; a rejection they will not, and the
 * role sits rejected with the fix sitting unread beside it.
 *
 * <p>Runs on the same short cycle as the password-reset sender. Both are read by
 * somebody who is waiting, and the cost of checking an empty queue is one
 * indexed lookup.
 *
 * <p>Each decision is announced once, by the stamp on the row. An edit clears
 * that stamp along with the review stamp, so the decision on the edited version
 * is announced as its own decision rather than being swallowed as a repeat.
 */
@Service
public class PostingDecisionSender {

    private static final Logger log = LoggerFactory.getLogger(PostingDecisionSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public PostingDecisionSender(
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
     * One decided submission.
     *
     * @param jobId the published row, present on an approval and null otherwise
     * @param note  what the moderator wrote, which only a rejection carries
     */
    record Decided(long id, String email, String title, String company,
                   String contact, boolean approved, Long jobId, String note) {}

    /**
     * How the message opens, or nothing at all.
     *
     * <p>This email had no greeting: it began with the role's own title,
     * which reads as a system announcing an outcome. A board whose whole
     * claim is that a person read the posting can afford one line that sounds
     * like one.
     *
     * <p>Empty rather than "Hi ," on the accounts made before the name was
     * asked for. A greeting with a hole in it is worse than none, and the
     * paragraph below it stands on its own either way.
     */
    private static String greeting(Decided row) {
        String name = row.contact();
        if (name == null || name.isBlank()) return "";
        // First word only. Somebody who typed their full name is not expecting
        // to be greeted with all of it.
        String first = name.trim().split("\\s+")[0];
        return "Hi %s,%n%n".formatted(first);
    }

    @Scheduled(cron = "${employer.decision-notice.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many notices were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Decided> waiting = db.sql("""
                        select p.id, e.email, p.title, e.company_name, e.contact_name, p.status,
                               p.job_id, coalesce(p.review_note, '') as review_note
                          from employer_postings p
                          join employers e on e.id = p.employer_id
                         where p.decision_notified_at is null
                           and p.status in ('APPROVED', 'REJECTED')
                         order by p.reviewed_at
                         limit 20
                        """)
                .query((rs, n) -> new Decided(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("title"),
                        rs.getString("company_name"),
                        rs.getString("contact_name"),
                        "APPROVED".equals(rs.getString("status")),
                        rs.getObject("job_id") == null ? null : rs.getLong("job_id"),
                        rs.getString("review_note")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Decided row : waiting) {
            // Transactional: this answers something a person asked for, and it
            // is the tier the day's reserve exists to protect. Held rather than
            // dropped if the budget is gone -- the row stays unstamped and goes
            // out tomorrow.
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("posting decision notice held: the day's whole mail budget is spent");
                break;
            }
            // Stamped before sending, as everywhere else here. A crash between
            // the two costs one notice; stamping afterwards would mail the same
            // decision every fifteen seconds until somebody noticed.
            db.sql("update employer_postings set decision_notified_at = now() where id = :id")
                    .param("id", row.id())
                    .update();
            mailer.send(row.email(), subject(row), body(row), html(row));
            sent++;
        }

        if (sent > 0) {
            log.info("posting decision notices sent: {}", sent);
        }
        return sent;
    }

    private String subject(Decided row) {
        return row.approved()
                ? "%s is live on Actually Remote".formatted(row.title())
                : "%s was not published".formatted(row.title());
    }

    private String roleUrl(Decided row) {
        return row.jobId() == null ? siteUrl : siteUrl + "/jobs/" + row.jobId();
    }

    /**
     * Plain text.
     *
     * <p>The approval leads with the address of the thing, because that is the
     * entire content of the message and anything before it is in the way. The
     * rejection leads with the reason, for the same reason inverted: the outcome
     * is already in the subject line, and what the reader needs is the sentence
     * that tells them what to change.
     */
    private String body(Decided row) {
        if (row.approved()) {
            return greeting(row) + """
                    %s is published and anyone can find it now:

                    %s

                    It is in the featured section while it is new, and it appears in \
                    the searches that match it. Edit or take it down any time from \
                    your dashboard:

                    %s/employers

                    — Actually Remote
                    %s
                    """.formatted(row.title(), roleUrl(row), siteUrl, siteUrl);
        }

        return greeting(row) + """
                %s was reviewed and not published.

                  %s

                Nothing was lost. The submission is still in your dashboard, and \
                editing it sends it back for review — there is no need to fill the \
                form in again:

                %s/employers

                — Actually Remote
                %s
                """.formatted(row.title(), note(row), siteUrl, siteUrl);
    }

    /** The laid-out half. */
    private String html(Decided row) {
        String card = row.approved()
                ? EmailLayout.eyebrow("Published")
                        + EmailLayout.heading(row.title())
                        + EmailLayout.paragraph(
                                "It is on the board and anyone can find it now.", true)
                        + EmailLayout.button(roleUrl(row), "See it on the board")
                        + EmailLayout.rule()
                        + EmailLayout.paragraph(
                                "It sits in the featured section while it is new, and it "
                                        + "appears in the searches that match it. Edit it or "
                                        + "take it down any time from "
                                        + EmailLayout.textLink(siteUrl + "/employers",
                                                "your dashboard")
                                        + ".")
                : EmailLayout.eyebrow("Not published")
                        + EmailLayout.heading(row.title())
                        + EmailLayout.paragraph("It was reviewed and turned down.", true)
                        + EmailLayout.field("Why", EmailLayout.escape(note(row)))
                        + EmailLayout.rule()
                        + EmailLayout.paragraph(
                                "Nothing was lost. The submission is still in your dashboard, "
                                        + "and editing it sends it back for review &mdash; "
                                        + "there is no need to fill the form in again.")
                        + EmailLayout.button(siteUrl + "/employers", "Open the submission");

        return EmailLayout.document(
                row.approved()
                        ? "%s is live.".formatted(row.title())
                        : "%s was not published.".formatted(row.title()),
                card,
                // No unsubscribe. This is the answer to something they submitted,
                // not a list they joined, and an opt-out on it would offer to
                // stop telling them the outcome of their own requests.
                EmailLayout.footer(siteUrl, null, null));
    }

    /** The moderator's note, or a usable sentence when the field came through empty. */
    private String note(Decided row) {
        String note = row.note() == null ? "" : row.note().trim();
        return note.isBlank()
                ? "It did not meet what this board lists. Reply to this message for the detail."
                : note;
    }

    /**
     * Sends both halves to one address, for looking at.
     *
     * <p>Separately addressable because they are not variants of one message.
     * An approval is a receipt and a rejection is a refusal carrying a reason,
     * and a change to one says nothing about the other -- so reviewing a change
     * to either should not put the other in the inbox beside it.
     */
    public void sendPreview(String to) {
        sendApprovedPreview(to);
        sendRejectedPreview(to);
    }

    public void sendApprovedPreview(String to) {
        /* The two previews carry a contact name and no contact name on purpose.
           Nothing else exercises this class -- there is no test for it -- so the
           pair is what shows that the greeting appears when the account has a
           name and that the message still opens properly when it does not,
           which is every account made before the name was asked for. */
        Decided row = new Decided(
                0, to, "Senior Platform Engineer", "Northwind Systems", "Dana Whitfield",
                true, 849298L, "");
        mailer.send(to, "[preview] " + subject(row), body(row), html(row));
    }

    public void sendRejectedPreview(String to) {
        Decided row = new Decided(
                0, to, "Senior Platform Engineer", "Northwind Systems", null, false, null,
                "The advert says candidates must be eligible to work in the EU, which does "
                        + "not match the worldwide eligibility declared on the form.");
        mailer.send(to, "[preview] " + subject(row), body(row), html(row));
    }
}
