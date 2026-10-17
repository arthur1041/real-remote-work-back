package com.remoteroles.pipeline.newsletter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The weekly digest of new worldwide roles.
 *
 * <p>Weekly rather than daily because the subscription form promises "one
 * message a week", and a list mailed more often than it was told is a list that
 * reports the sender. The cadence is configurable, but the default matches the
 * promise rather than the appetite.
 *
 * <p>Only roles with no country restriction. The whole reason someone gave their
 * address is that they did not want to read another list of jobs they cannot
 * apply for, and filling the digest out with gated roles to make it look fuller
 * would be the fastest way to make it worthless.
 *
 * <p>Sends to CONFIRMED addresses only. A PENDING row is someone who typed into
 * a box; nothing proves they asked for this.
 */
@Service
@ConditionalOnProperty(name = "newsletter.digest.enabled", havingValue = "true")
public class DigestSender {

    private static final Logger log = LoggerFactory.getLogger(DigestSender.class);

    private final JdbcClient db;
    private final SubscriberRepository subscribers;
    private final Mailer mailer;
    private final String siteUrl;
    private final int minJobs;
    private final int maxJobs;

    public DigestSender(
            JdbcClient db,
            SubscriberRepository subscribers,
            Mailer mailer,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.digest.min-jobs:5}") int minJobs,
            @Value("${newsletter.digest.max-jobs:15}") int maxJobs) {
        this.db = db;
        this.subscribers = subscribers;
        this.mailer = mailer;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.minJobs = minJobs;
        this.maxJobs = maxJobs;
    }

    record Role(long id, String title, String company, String location) {}

    @Scheduled(cron = "${newsletter.digest.cron:0 0 13 * * TUE}")
    public void run() {
        sendIssue();
    }

    /** Returns how many recipients were mailed; zero means the issue was skipped. */
    public int sendIssue() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        Instant since = lastIssueAt();
        List<Role> roles = newWorldwideRoles(since);

        // An empty or nearly empty issue is worse than no issue: it spends the
        // one habit that matters, which is that opening this is worth doing.
        if (roles.size() < minJobs) {
            log.info("digest skipped: {} new worldwide roles since {}, minimum is {}",
                    roles.size(), since, minJobs);
            return 0;
        }

        List<SubscriberRepository.Recipient> audience = subscribers.digestAudience(5_000);
        if (audience.isEmpty()) {
            log.info("digest skipped: no confirmed subscribers");
            return 0;
        }

        // Recorded before the first send. A crash halfway through costs the rest
        // of this issue; recording afterwards would re-send the whole thing to
        // everyone who already had it.
        db.sql("""
                        insert into newsletter_issues (covers_from, job_count, recipients)
                        values (:since, :jobs, :recipients)
                        """)
                .param("since", Timestamp.from(since))
                .param("jobs", roles.size())
                .param("recipients", audience.size())
                .update();

        for (SubscriberRepository.Recipient person : audience) {
            subscribers.markDigestSent(person.id());
            mailer.send(person.email(), subject(roles.size()), body(roles, person));
        }

        log.info("digest sent: {} roles to {} recipients", roles.size(), audience.size());
        return audience.size();
    }

    /** When the last issue went out, or a week ago if there has never been one. */
    private Instant lastIssueAt() {
        return db.sql("select max(sent_at) from newsletter_issues")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant)
                .orElse(Instant.now().minus(7, ChronoUnit.DAYS));
    }

    private List<Role> newWorldwideRoles(Instant since) {
        return db.sql("""
                        select j.id, j.title, c.name as company,
                               coalesce(j.location_raw, 'Anywhere in the World') as location
                        from jobs j
                        join companies c on c.id = j.company_id
                        where j.closed_at is null
                          and j.is_remote is true
                          and j.geo_scope = 'WORLDWIDE'
                          and j.classification_confidence >= 0.5
                          and j.first_seen_at > :since
                        order by j.posted_at desc nulls last, j.id desc
                        limit :limit
                        """)
                .param("since", Timestamp.from(since))
                .param("limit", maxJobs)
                .query((rs, n) -> new Role(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("company"),
                        rs.getString("location")))
                .list();
    }

    private String subject(int count) {
        return "%d new roles open worldwide".formatted(count);
    }

    /**
     * Plain text, one line per role.
     *
     * <p>No images, no tracked buttons, no layout. A digest is read in ten
     * seconds by someone deciding whether to click one thing, and the shape that
     * survives every mail client is the one that also survives spam filtering.
     */
    private String body(List<Role> roles, SubscriberRepository.Recipient person) {
        StringBuilder list = new StringBuilder();
        for (Role role : roles) {
            list.append("  %s — %s\n     %s/jobs/%d\n\n"
                    .formatted(role.title(), role.company(), siteUrl, role.id()));
        }

        String unsubscribe = "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(person.unsubscribeToken(), StandardCharsets.UTF_8));

        return """
                %d roles went up this week with no country restriction at all — \
                open to anyone, anywhere, as stated by the employer rather than \
                guessed at from the word "remote".

                %s
                See everything open worldwide:

                %s

                You are getting this because you asked to hear about worldwide \
                roles. Leave at any time, in one click:

                %s
                """.formatted(roles.size(), list.toString().stripTrailing() + "\n", siteUrl, unsubscribe);
    }
}
