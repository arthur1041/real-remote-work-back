package com.remoteroles.pipeline.newsletter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final MailBudget budget;
    private final String siteUrl;
    private final int minJobs;
    private final int maxJobs;

    public DigestSender(
            JdbcClient db,
            SubscriberRepository subscribers,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${newsletter.digest.min-jobs:5}") int minJobs,
            @Value("${newsletter.digest.max-jobs:15}") int maxJobs) {
        this.db = db;
        this.subscribers = subscribers;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.minJobs = minJobs;
        this.maxJobs = maxJobs;
    }

    /**
     * One role in the email. `employer` is the paying account behind a role in
     * the featured block, which is what {@link #sliceFor} shares the block out
     * by; it is 0 on the roles chosen the ordinary way, where nobody is paying.
     */
    record Role(long id, String title, String company, String location, long employer) {
        Role(long id, String title, String company, String location) {
            this(id, title, company, location, 0);
        }
    }

    /**
     * The slice of the paid list that one reader is sent.
     *
     * <p>**This is what makes the block scale with customers instead of
     * rationing them.** The first design gave the issue five sold places and
     * rotated roles through them, so with a hundred paying roles the one at
     * the back of the queue waited twenty weeks — a feature that is sold today
     * and delivered next season. Here the five places are *per reader*: with
     * 790 subscribers the issue has about 3,950 of them, so every paid role is
     * in every issue, and what differs between two readers is which five they
     * get.
     *
     * <p>That capacity is readers times places, which is why the pricing page
     * stops short of promising it in words: a paid list longer than 3,950
     * would start missing readers, and an issue is skipped altogether on a
     * week too quiet to fill the editorial list. What is sold is the place in
     * the block, not a count of issues.
     *
     * <p>Arithmetic rather than randomness, so the same issue sent twice is
     * the same issue, and so this can be tested. The ring is walked from an
     * offset made of the reader and the issue: the reader spreads the roles
     * across the audience, and the issue moves everybody along next week so
     * the same reader is not sent the same five for ever.
     *
     * <p><strong>Shared out by account, not by role.</strong> The ring used to
     * be every paid role in one line, so an account's share of the block was
     * its share of the roles. Measured on 24 Nov 2026: two accounts had
     * eligible roles, one with 50 and one with 1, so the one with a single
     * role was in 5 of every 51 readers' blocks -- about one reader in ten --
     * and every other reader got five roles from the same account. The owner
     * asked for the same thing the site's Featured sections already do (see
     * `capAdvertisers` in web/lib/rotation.ts): no single employer may take
     * the whole block while another one is paying.
     *
     * <p>So the block is dealt like cards. Each reader's accounts are put in
     * an order that starts at a different account for each reader, and one
     * role is taken from each in turn until the places are full. An account
     * with one role is therefore in every reader's block; one with fifty fills
     * whatever the others leave, and which of its fifty those are moves with
     * the reader and the issue exactly as the single ring did, so all fifty
     * still go out across the audience. Nothing is held empty: when only one
     * account has roles, it has the whole block, because a slot nobody else
     * can use is not taken away from the one who can.
     *
     * <p>When there are fewer paid roles than places, everybody gets all of
     * them -- still dealt in turn, so which account comes first moves between
     * readers rather than always being the same one.
     */
    static List<Role> sliceFor(List<Role> paid, int slots, long recipientId, long issueId) {
        if (paid.isEmpty() || slots <= 0) return List.of();

        Map<Long, List<Role>> byEmployer = new LinkedHashMap<>();
        for (Role role : paid) {
            byEmployer.computeIfAbsent(role.employer(), k -> new ArrayList<>()).add(role);
        }
        List<Long> employers = new ArrayList<>(byEmployer.keySet());
        employers.sort(Long::compare);

        // floorMod, not %: an id is never negative today, and a negative
        // offset here would throw on the first one that was.
        long offset = recipientId * 31 + issueId;
        int first = (int) Math.floorMod(offset, employers.size());

        // Each account's own roles entered at a point of their own. The
        // employer id is mixed in so two accounts with the same number of
        // roles do not advance in lockstep.
        Map<Long, Integer> cursor = new LinkedHashMap<>();
        for (long employer : employers) {
            int size = byEmployer.get(employer).size();
            cursor.put(employer, (int) Math.floorMod(offset + employer * 7_919, size));
        }
        Map<Long, Integer> taken = new LinkedHashMap<>();

        int want = Math.min(slots, paid.size());
        List<Role> out = new ArrayList<>(want);
        while (out.size() < want) {
            for (int i = 0; i < employers.size() && out.size() < want; i++) {
                long employer = employers.get((first + i) % employers.size());
                List<Role> own = byEmployer.get(employer);
                int n = taken.getOrDefault(employer, 0);
                if (n >= own.size()) continue;
                out.add(own.get((cursor.get(employer) + n) % own.size()));
                taken.put(employer, n + 1);
            }
        }
        return out;
    }

    /**
     * How many sold places one reader's email can carry, as a share of the
     * editorial list rather than a number of its own.
     *
     * <p>A third. The ratio is the point: a reader has to be able to see at a
     * glance that the paid block is the smaller part of what they were sent,
     * or the labelling is technically true and practically a pretence. Writing
     * it as a fraction means the two stay in proportion if the issue ever
     * carries more roles, instead of a constant that silently becomes half the
     * email the day `max-jobs` is raised.
     */
    static int paidSlots(int editorialSize) {
        return Math.max(1, editorialSize / 3);
    }

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
        // Capped well below the organic list on purpose. A sold block that can
        // grow to the size of the editorial one stops being a block inside the
        // email and becomes the email.
        List<Role> paid = paidRoles(roles);

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
        Long issueId = db.sql("""
                        insert into newsletter_issues (covers_from, job_count, recipients)
                        values (:since, :jobs, :recipients)
                        returning id
                        """)
                .param("since", Timestamp.from(since))
                .param("jobs", roles.size())
                .param("recipients", audience.size())
                .query(Long.class)
                .single();

        int slots = paidSlots(roles.size());
        int sent = 0;
        for (SubscriberRepository.Recipient person : audience) {
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("digest truncated: the day's whole mail budget is spent after {} of {}",
                        sent, audience.size());
                break;
            }
            subscribers.markDigestSent(person.id());
            // Each reader's own slice of the paid list. See sliceFor.
            List<Role> theirs = sliceFor(paid, slots, person.id(), issueId);
            mailer.send(person.email(), subject(roles.size()),
                    body(roles, theirs, person), html(roles, theirs, person),
                    oneClickUnsubscribe(person.unsubscribeToken()));
            sent++;
        }

        // The row was written with the intended size, because it has to exist
        // before the first send. If the budget cut the issue short it is
        // corrected here: the number in this table is read as "how many people
        // got it", and an issue that reports more readers than it had is a
        // quiet lie in the one place the list's health is judged from.
        if (sent != audience.size()) {
            db.sql("update newsletter_issues set recipients = :sent where id = :id")
                    .param("sent", sent)
                    .param("id", issueId)
                    .update();
        }

        // Recorded once the issue has gone out, for every paid role in it --
        // which under slicing is all of them. It is what the employer's
        // dashboard reads to say when their role last went out.
        markFeatured(paid);

        log.info("digest sent: {} roles to {} recipients; {} paid roles, {} of them per reader, "
                + "about {} readers each",
                roles.size(), sent, paid.size(), Math.min(slots, paid.size()),
                paid.isEmpty() ? 0 : (long) sent * Math.min(slots, paid.size()) / paid.size());
        return sent;
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

    /**
     * Roles from employers on a paid plan, for the block headed "Featured".
     *
     * <p>The digest carries the 15 newest worldwide roles out of roughly 160
     * that arrive in a week, so a place in it is scarce and is otherwise
     * decided by nothing but arrival time. The block is separate, headed, and
     * capped at a third of the issue rather than mixed into the list.
     *
     * <p>Nothing else about these roles is different: same worldwide-only
     * rule, same confidence floor, same person or model reading them before
     * they were published, and the same place in the feed on the site. The
     * only thing bought is this block.
     *
     * <p>`expires_at` rather than `status` alone, which is the rule
     * `employer_subscriptions` states in its own comment: a row marked ACTIVE
     * whose date has passed grants nothing.
     *
     * <p>Excludes anything already in the list above, because paying for a
     * place is not paying to appear twice.
     *
     * <p>**Every eligible role, not a page of them.** What each reader is sent
     * is a slice of this list; see {@link #sliceFor}.
     *
     * <p><strong>No arrival window, and that was a defect.</strong> This read
     * `j.first_seen_at > :since` alongside the editorial list, which is right
     * for that list -- it is "what went up since the last issue" -- and wrong
     * here. It meant a paid role rode exactly one issue, the one after it was
     * published, and was then gone while the subscription went on being paid
     * for. What is bought is a place in the block while the subscription is
     * live, so the only dates that may decide eligibility are the role's own
     * (closed or not) and the subscription's.
     *
     * <p><strong>The subscriber is the account that published the role, not
     * the company the role is at.</strong> This joined
     * `companies -> employers`, which answers "is this role's company some
     * account's own company?" -- the same question until V49 let a posting
     * carry its own hiring company. An agency publishing for a client files
     * the role under the client, no account points at that `companies` row,
     * and the join finds nobody: a paying recruiter would have been left out
     * of the block on precisely the roles they pay for. The site's Featured
     * sections ask through `employer_postings.job_id` and so does this now.
     *
     * <p>A lateral `limit 1` rather than a plain join, because
     * `employer_postings.job_id` carries no unique index: two rows pointing at
     * one job would multiply the role in this list. (Production holds no such
     * pair today; the shape is chosen so that it would not matter.) It was an
     * `exists` until 24 Nov 2026, when the block started being shared out by
     * account and so needed to know which account.
     */
    private List<Role> paidRoles(List<Role> already) {
        List<Long> seen = already.stream().map(Role::id).toList();
        return db.sql("""
                        select j.id, j.title, c.name as company,
                               coalesce(j.location_raw, 'Anywhere in the World') as location,
                               payer.employer_id
                        from jobs j
                        join companies c on c.id = j.company_id
                        -- Lateral with limit 1, so it still behaves as the
                        -- semi-join it replaced: two postings pointing at one
                        -- job cannot put the role in the ring twice.
                        join lateral (
                              select ep.employer_id
                                from employer_postings ep
                                join employer_subscriptions s
                                  on s.employer_id = ep.employer_id
                               where ep.job_id = j.id
                                 -- CANCELLED counts until the date: whoever
                                 -- paid for the month has the month, which is
                                 -- what V41 says and what the account page
                                 -- already showed.
                                 and s.status in ('ACTIVE', 'CANCELLED')
                                 and s.expires_at > now()
                               order by ep.employer_id
                               limit 1
                        ) payer on true
                        where j.closed_at is null
                          and j.is_remote is true
                          and j.geo_scope = 'WORLDWIDE'
                          and j.classification_confidence >= 0.5
                          and (:none or not (j.id = any(:seen)))
                        -- A stable order, not a priority one. Every eligible
                        -- role goes out in this issue; what this decides is
                        -- only where each sits in the ring that the slices are
                        -- cut from, and it has to be the same for every
                        -- recipient of the same issue or the slices overlap
                        -- unevenly.
                        order by j.id
                        """)
                // An empty array parameter is not portable through every driver
                // path here, so the "nothing to exclude" case is a flag rather
                // than an array that happens to be empty.
                .param("none", seen.isEmpty())
                .param("seen", seen.toArray(new Long[0]))
                .query((rs, n) -> new Role(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("company"),
                        rs.getString("location"),
                        rs.getLong("employer_id")))
                .list();
    }

    /**
     * Records that this issue carried these paid roles.
     *
     * <p>**After the sending, not before**, so an issue that was never sent
     * does not claim to have carried anything. It is read by the employer's
     * dashboard to answer "did my role go out", which is the only question
     * this date exists to settle -- under slicing every paid role is in every
     * issue, so it is a record of delivery rather than of a turn.
     */
    private void markFeatured(List<Role> featured) {
        if (featured.isEmpty()) return;
        db.sql("update jobs set digest_featured_at = now() where id = any(:ids)")
                .param("ids", featured.stream().map(Role::id).toArray(Long[]::new))
                .update();
    }

    /**
     * Where the List-Unsubscribe header points.
     *
     * <p>The body already carries an opt-out link; the provider cannot see it.
     * This is the one the client itself uses to draw a native unsubscribe
     * control, and a reader who finds that control does not reach for "report
     * spam" -- which is the same outcome for them and a far worse one for the
     * domain.
     */
    private String oneClickUnsubscribe(String token) {
        return "%s/api/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(token, StandardCharsets.UTF_8));
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
    private String body(List<Role> roles, List<Role> paid, SubscriberRepository.Recipient person) {
        StringBuilder list = new StringBuilder();
        for (Role role : roles) {
            list.append("  %s — %s\n     %s/jobs/%d\n\n"
                    .formatted(role.title(), role.company(), siteUrl, role.id()));
        }

        String unsubscribe = "%s/unsubscribe?token=%s"
                .formatted(siteUrl, URLEncoder.encode(person.unsubscribeToken(), StandardCharsets.UTF_8));

        // Featured first, then the week's list. See html() for why.
        StringBuilder sold = new StringBuilder();
        if (!paid.isEmpty()) {
            sold.append("FEATURED\n\n");
            for (Role role : paid) {
                sold.append("  %s — %s\n     %s/jobs/%d\n\n"
                        .formatted(role.title(), role.company(), siteUrl, role.id()));
            }
            sold.append("NEW THIS WEEK\n\n");
        }

        return """
                %d roles went up this week with no country restriction at all — \
                open to anyone, anywhere, as stated by the employer rather than \
                guessed at from the word "remote".

                %s%s
                See everything open worldwide:

                %s

                You are getting this because you asked to hear about worldwide \
                roles. Leave at any time, in one click:

                %s
                """.formatted(roles.size(), sold.toString(),
                        list.toString().stripTrailing() + "\n", siteUrl, unsubscribe);
    }

    /** The laid-out half: one row per role, the row itself being the link. */
    private String html(List<Role> roles, List<Role> paid, SubscriberRepository.Recipient person) {
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

        /* The featured block, above the week's list and headed "Featured".
           Until 24 Nov 2026 it sat under the list, headed "Paid placement",
           with a sentence saying these employers paid and what paying did not
           buy. The owner asked for it at the top and for the roles to read
           like the others, with no explanation of payment in the email: the
           heading is the label, the same word the site's promoted sections
           use, and /pricing and /terms say what it is. */
        StringBuilder sold = new StringBuilder();
        if (!paid.isEmpty()) {
            StringBuilder paidRows = new StringBuilder();
            for (int i = 0; i < paid.size(); i++) {
                Role role = paid.get(i);
                paidRows.append(EmailLayout.roleRow(
                        role.title(),
                        role.company(),
                        "%s/jobs/%d".formatted(siteUrl, role.id()),
                        i == paid.size() - 1));
            }
            sold.append(EmailLayout.eyebrow("Featured"))
                .append(EmailLayout.roleList(paidRows.toString()))
                // Without a heading of its own the week's list would read as
                // more of the featured block.
                .append(EmailLayout.eyebrow("New this week"));
        }

        String card = EmailLayout.eyebrow("This week")
                + EmailLayout.heading("%d new roles open worldwide".formatted(roles.size()))
                + EmailLayout.paragraph(
                        "No country restriction at all &mdash; open to anyone, anywhere, as "
                                + "stated by the employer rather than guessed at from the word "
                                + "&ldquo;remote&rdquo;.",
                        true)
                + sold
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
        // The sold block exactly as a real issue builds it -- it has no window
        // of its own to widen -- so a preview shows the email as a subscriber
        // would actually receive it, including the part that is paid for, which
        // is the part most worth looking at before it goes out.
        List<Role> paid = sliceFor(paidRoles(roles), paidSlots(roles.size()), 0, 0);
        SubscriberRepository.Recipient person =
                new SubscriberRepository.Recipient(0, to, "preview-token");
        mailer.send(to, "[preview] " + subject(roles.size()),
                body(roles, paid, person), html(roles, paid, person));
    }
}
