package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells an employer that somebody applied.
 *
 * <p>This did not exist, and nothing in this whole module read
 * `job_applications` at all. Every other event on the board announces itself —
 * registration, verification, a moderation decision, a password reset, the
 * review queue filling up — and the one that matters most to the person who
 * posted the role did not. A role taking applications here collected them in a
 * dashboard the employer had no reason to open, which means the best case was
 * an applicant waiting while their application sat unread, and the realistic
 * case was nobody ever looking.
 *
 * <p>**One message per application and never a second.** `notified_at` is
 * stamped in the statement before the send, exactly as the three code senders
 * do it: a crash between the two costs one notice, and the employer still has
 * the dashboard; stamping afterwards would re-send the same notice every fifteen
 * seconds all night.
 *
 * <p>**Transactional, not cold.** Somebody is waiting at the other end of this
 * — an applicant who pressed send — so it draws on the reserve the budget keeps
 * for mail nobody chose to receive but everybody is waiting for.
 *
 * <p>**The applicant's details are not in the message.** Only that there is one,
 * which role, and a link. Partly data minimisation — a CV summary in an email
 * is a copy of somebody's personal data in a mailbox we do not control and
 * cannot withdraw — and partly that the dashboard is where the CV, the answers
 * and the reply button are, so a message that reproduced them would be a worse
 * copy of a page the link reaches in one press.
 */
@Service
public class ApplicationSender {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public ApplicationSender(
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
     * One application waiting to be announced.
     *
     * @param waiting how many are unread on that posting, including this one
     */
    record Pending(long id, long postingId, String employerEmail, String companyName,
                   String roleTitle, boolean hasResume, int waiting) {}

    @Scheduled(cron = "${application.notice.cron:*/15 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many notices were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Pending> waiting = db.sql("""
                        select a.id, a.posting_id, e.email, e.company_name, p.title,
                               (a.resume_id is not null) as has_resume,
                               (select count(*) from job_applications u
                                 where u.posting_id = a.posting_id and u.read_at is null) as waiting
                          from job_applications a
                          join employer_postings p on p.id = a.posting_id
                          join employers e on e.id = p.employer_id
                         where a.notified_at is null
                           -- An address that has never answered a code is an
                           -- address a notice cannot be delivered to, and
                           -- spending a transactional message on it is spending
                           -- it on nothing. The dashboard still shows the
                           -- application; the notice is owed until the address
                           -- works, which is the same rule the posting decision
                           -- mailer applies.
                           and e.email_verified_at is not null
                           and e.status = 'ACTIVE'
                         order by a.created_at
                         limit 20
                        """)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        rs.getLong("posting_id"),
                        rs.getString("email"),
                        rs.getString("company_name"),
                        rs.getString("title"),
                        rs.getBoolean("has_resume"),
                        rs.getInt("waiting")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : waiting) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("application notice held: the day's whole mail budget is spent");
                break;
            }
            db.sql("update job_applications set notified_at = now() where id = :id")
                    .param("id", row.id())
                    .update();
            mailer.send(row.employerEmail(), subject(row), body(row), html(row));
            sent++;
        }
        log.info("application notices sent: {}", sent);
        return sent;
    }

    /**
     * The role in the subject line, because that is what the employer recognises.
     *
     * <p>Not "You have a new application" — an employer with three roles open
     * cannot act on that without opening it, and a subject line that has to be
     * opened to be understood is one that gets left for later.
     */
    private String subject(Pending row) {
        return "New application: " + row.roleTitle();
    }

    private String body(Pending row) {
        String others = row.waiting() > 1
                ? "\n\nThat makes %d waiting to be read on this role.".formatted(row.waiting())
                : "";
        return """
                Somebody has applied for %s.

                %sTheir answers and their contact details are on the role's \
                applications page:

                %s/employers/applications/%d%s

                — Actually Remote
                %s
                """.formatted(
                row.roleTitle(),
                row.hasResume() ? "Their CV came with it. " : "",
                siteUrl,
                row.postingId(),
                others,
                siteUrl);
    }

    private String html(Pending row) {
        String card = EmailLayout.eyebrow("New application")
                + EmailLayout.heading(row.roleTitle())
                + EmailLayout.paragraph(
                        row.hasResume()
                                ? "Somebody has applied, and their CV came with it."
                                : "Somebody has applied for this role.",
                        true)
                + EmailLayout.button(
                        "%s/employers/applications/%d".formatted(siteUrl, row.postingId()),
                        "Read the application")
                + (row.waiting() > 1
                        ? EmailLayout.paragraph(
                                "%d applications are waiting to be read on this role."
                                        .formatted(row.waiting()))
                        : "")
                + EmailLayout.rule()
                + EmailLayout.paragraph(
                        "Replying goes straight to the applicant from that page. "
                                + "Nothing is shared with anybody else.");

        return EmailLayout.document(
                "Somebody has applied for " + EmailLayout.escape(row.roleTitle()) + ".",
                card,
                // No unsubscribe: this is not a list. It is the employer's own
                // role telling them it did the thing they posted it to do, and
                // the way to stop it is to take the role down.
                EmailLayout.footer(siteUrl, null, null));
    }

    /** Sends this template to one address, for looking at. */
    public void sendPreview(String to) {
        Pending sample = new Pending(0, 0, to, "Example Co", "Senior Backend Engineer", true, 3);
        mailer.send(to, "[preview] " + subject(sample), body(sample), html(sample));
    }
}
