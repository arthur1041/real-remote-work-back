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

    /** What the admin area last saved. Read fresh on every wake-up. */
    record Settings(boolean enabled, int intervalHours, String audience, int minJobs, int maxJobs) {}

    private Settings settings() {
        return db.sql("""
                        select enabled, interval_hours, audience, min_jobs, max_jobs
                        from newsletter_settings where id = 1
                        """)
                .query((rs, n) -> new Settings(
                        rs.getBoolean("enabled"),
                        rs.getInt("interval_hours"),
                        rs.getString("audience"),
                        rs.getInt("min_jobs"),
                        rs.getInt("max_jobs")))
                .optional()
                .orElse(new Settings(false, 168, "CONFIRMED", minJobs, maxJobs));
    }

    /**
     * Wakes hourly and sends only if the stored interval has elapsed.
     *
     * <p>The cadence is a row rather than a cron expression so it can be changed
     * from the admin area without a deploy. Waking often and deciding late is
     * what makes that possible: a schedule compiled into the annotation would
     * have to be rebuilt to move.
     */
    @Scheduled(cron = "${newsletter.digest.cron:0 5 * * * *}")
    public void run() {
        sendIssue();
    }

    /** Sends now if anything other than the clock allows it. */
    public int sendIssueNow() {
        return send(true);
    }

    /** Returns how many recipients were mailed; zero means the issue was skipped. */
    public int sendIssue() {
        return send(false);
    }

    private int send(boolean ignoreInterval) {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        Settings config = settings();
        if (!config.enabled()) {
            return 0;
        }

        Instant since = lastIssueAt();
        if (!ignoreInterval
                && Instant.now().isBefore(since.plus(config.intervalHours(), ChronoUnit.HOURS))) {
            return 0;
        }

        List<Role> roles = newWorldwideRoles(since, config.maxJobs());

        // An empty or nearly empty issue is worse than no issue: it spends the
        // one habit that matters, which is that opening this is worth doing.
        if (roles.size() < config.minJobs()) {
            log.info("digest skipped: {} new worldwide roles since {}, minimum is {}",
                    roles.size(), since, config.minJobs());
            return 0;
        }

        List<SubscriberRepository.Recipient> audience =
                subscribers.digestAudience(config.audience(), 5_000);
        if (audience.isEmpty()) {
            log.info("digest skipped: no recipients for audience {}", config.audience());
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
            mailer.send(person.email(), subject(roles.size()),
                    body(roles, person), html(roles, person));
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

    private List<Role> newWorldwideRoles(Instant since, int limit) {
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
                .param("limit", limit)
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

    /** The laid-out half: one row per role, the row itself being the link. */
    private String html(List<Role> roles, SubscriberRepository.Recipient person) {
        String unsubscribe = "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(person.unsubscribeToken(), StandardCharsets.UTF_8));

        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < roles.size(); i++) {
            Role role = roles.get(i);
            rows.append(EmailLayout.roleRow(
                    role.title(),
                    role.company(),
                    "%s/jobs/%d".formatted(siteUrl, role.id()),
                    i == roles.size() - 1));
        }

        String card = EmailLayout.eyebrow("This week")
                + EmailLayout.heading("%d new roles open worldwide".formatted(roles.size()))
                + EmailLayout.paragraph(
                        "No country restriction at all &mdash; open to anyone, anywhere, as "
                                + "stated by the employer rather than guessed at from the word "
                                + "&ldquo;remote&rdquo;.",
                        true)
                + EmailLayout.roleList(rows.toString())
                + EmailLayout.button(siteUrl, "See everything open worldwide");

        return EmailLayout.document(
                "%d roles went up this week, open to anyone anywhere.".formatted(roles.size()),
                card,
                EmailLayout.footer(
                        siteUrl,
                        unsubscribe,
                        "You are getting this because you asked to hear about worldwide roles."));
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
        // A year back and capped, so a preview has roles in it whatever the week
        // happened to bring. The real issue uses the window since the last one.
        List<Role> roles = newWorldwideRoles(Instant.now().minus(365, ChronoUnit.DAYS), 8);
        SubscriberRepository.Recipient person =
                new SubscriberRepository.Recipient(0, to, "preview-token");
        mailer.send(to, "[preview] " + subject(roles.size()),
                body(roles, person), html(roles, person));
    }
}
