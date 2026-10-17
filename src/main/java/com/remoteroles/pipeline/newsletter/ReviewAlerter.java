package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells the moderator that a submission is waiting.
 *
 * <p>Nothing an employer posts reaches the board without a person reading it,
 * and the person has no reason to be looking at the queue. Without this, a role
 * submitted on a Friday sits untouched until someone happens to open the admin
 * area -- which, for an employer who was told "usually within a day", is the
 * whole promise broken by an absence rather than a decision.
 *
 * <p>Each posting is announced once. The alternative, re-sending until the
 * queue is empty, is how a person learns to ignore the mail.
 */
@Service
public class ReviewAlerter {

    private static final Logger log = LoggerFactory.getLogger(ReviewAlerter.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final String alertTo;
    private final String siteUrl;

    public ReviewAlerter(
            JdbcClient db,
            Mailer mailer,
            @Value("${newsletter.review-alert-to:}") String alertTo,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.alertTo = alertTo == null ? "" : alertTo.trim();
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    record Waiting(long id, String title, String company, String scope, String detail, String applyUrl) {}

    @Scheduled(cron = "${newsletter.review-alert-cron:0 * * * * *}")
    public void run() {
        alertPending();
    }

    /** Returns how many alerts were sent. */
    public int alertPending() {
        if (alertTo.isBlank() || !mailer.isDeliveryEnabled()) {
            return 0;
        }

        List<Waiting> waiting = db.sql("""
                        select p.id, p.title, e.company_name, p.geo_scope,
                               coalesce(p.geo_detail, '') as geo_detail, p.apply_url
                        from employer_postings p
                        join employers e on e.id = p.employer_id
                        where p.status = 'PENDING' and p.review_notified_at is null
                        order by p.created_at
                        limit 20
                        """)
                .query((rs, n) -> new Waiting(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("company_name"),
                        rs.getString("geo_scope"),
                        rs.getString("geo_detail"),
                        rs.getString("apply_url")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        for (Waiting row : waiting) {
            // Stamped first, as everywhere else here: one missed alert beats a
            // loop that mails the same submission every minute.
            db.sql("update employer_postings set review_notified_at = now() where id = :id")
                    .param("id", row.id())
                    .update();
            mailer.send(alertTo, subject(row), body(row));
        }

        log.info("review alerts sent: {}", waiting.size());
        return waiting.size();
    }

    private String subject(Waiting row) {
        return "Review needed: %s at %s".formatted(row.title(), row.company());
    }

    /**
     * Plain text, and it leads with the thing that has to be checked.
     *
     * <p>The eligibility the employer declared is the only claim on this board
     * that cannot be verified from the posting alone, so it goes at the top with
     * the link that lets it be checked against the advert, rather than being
     * buried under the role's details.
     */
    private String body(Waiting row) {
        String declared = "WORLDWIDE".equals(row.scope())
                ? "Anyone, anywhere — no country requirement"
                : "%s: %s".formatted(row.scope(), row.detail());

        return """
                %s submitted a role and it is waiting for review.

                  Role      %s
                  Declared  %s
                  Applies at %s

                The thing to check is whether the body of the advert agrees with the \
                eligibility they declared. Everything else on this board is read out \
                of a posting; this is the only place a person is taking someone's word \
                for it, and the WORLDWIDE badge is worth exactly as much as that check.

                Review it here:

                %s/admin/postings
                """.formatted(
                row.company(), row.title(), declared, row.applyUrl(), siteUrl);
    }
}
