package com.remoteroles.pipeline.employers;

import com.remoteroles.pipeline.employers.ApplySource.State;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asks each employer's own Apply page whether the role is still open, and pauses
 * the ones it says are not.
 *
 * <p><strong>Age decides when to ask, never whether the role stays.</strong>
 * That distinction is the whole design. An expiry closes the live role and the
 * dead one by the same rule, which is why the thirty-day window was taken out of
 * the Featured sections on 13 Nov 2026; bringing it back one layer down would be
 * the same mistake wearing a different hat. What closes a role here is the
 * employer's own source saying so, in as many words.
 *
 * <p><strong>Three answers, and only one of them does anything.</strong> OPEN
 * resets the clock. CLOSED pauses. UNKNOWN -- a timeout, a bot wall, a page
 * whose shape changed, a host nobody wrote a rule for -- does nothing but count
 * itself, because a question that could not be asked is not evidence. A run that
 * cannot reach the internet at all therefore pauses nothing, which is the
 * behaviour you want from the thing that is allowed to take listings down.
 *
 * <p><strong>Pause, not close.</strong> The row stays, the employer sees it in
 * their dashboard, the free plan's slot goes back to them, and
 * {@code activatePosting} brings it back in one click -- see V59. A machine that
 * takes a paying customer's advert down has to be a machine the customer can
 * overrule, and overruling it is recorded, after which this never pauses that
 * role again.
 *
 * <p><strong>What it costs the box.</strong> One run a day, eighty roles at
 * most, one request at a time, each body read to at most 256 KB and dropped.
 * That is a few megabytes transient in a process capped at 576 MB and sitting at
 * 175, and roughly eighty seconds of mostly-idle waiting. The weekly re-check
 * means those eighty cover 560 a week against the 384 roles that exist, so the
 * queue drains rather than growing.
 *
 * <p>The eighty seconds of sleeping block nothing, and that is worth being
 * explicit about on a process that already carries twenty scheduled jobs:
 * {@code spring.threads.virtual.enabled} is on, so each execution gets its own
 * virtual thread and the mail senders that run every fifteen seconds keep their
 * cadence while this one waits. A fixed-size scheduler pool is where this design
 * would have been a mistake.
 */
@Component
public class SourceVerifier {

    private static final Logger log = LoggerFactory.getLogger(SourceVerifier.class);

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final PlanLapseSweeper sweeper;

    private final int batch;
    private final int minAgeDays;
    private final int recheckDays;
    private final int politenessMs;

    /**
     * Redirects are followed here, unlike in {@code AtsHttp}, and on purpose:
     * Mercor publishes short links (t.mercor.com/fgIIU) that hop to the real job
     * page, and refusing to follow them would make every one of those 47 roles
     * permanently UNKNOWN. There is no allowlist to protect either -- the URL
     * comes from the employer's own posting, which is already the thing being
     * asked about.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SourceVerifier(
            JdbcClient db,
            TransactionTemplate tx,
            PlanLapseSweeper sweeper,
            @Value("${source-check.batch:80}") int batch,
            @Value("${source-check.min-age-days:30}") int minAgeDays,
            @Value("${source-check.recheck-days:7}") int recheckDays,
            @Value("${source-check.politeness-ms:1000}") int politenessMs) {
        this.db = db;
        this.tx = tx;
        this.sweeper = sweeper;
        this.batch = batch;
        this.minAgeDays = minAgeDays;
        this.recheckDays = recheckDays;
        this.politenessMs = politenessMs;
    }

    /** One posting to ask about. */
    record Due(long id, Long jobId, long employerId, String applyUrl,
               String geoScope, String geoDetail, boolean overridden) { }

    /**
     * Daily, at ten to five in the morning UTC.
     *
     * <p>Nothing here is urgent to the hour: a role that closed on Tuesday and
     * comes down on Wednesday morning is a board that is right to within a day
     * about something nobody was told. The slot is chosen to sit away from the
     * hourly ingest at the top of the hour, the scope review at 05:40 and the CV
     * retention pass at 03:20, so three jobs are not fetching at once on a box
     * with two gigabytes.
     */
    @Scheduled(cron = "${source-check.cron:0 50 4 * * *}")
    public void verify() {
        List<Due> due = due();
        if (due.isEmpty()) return;

        int open = 0, closed = 0, unknown = 0;
        List<PlanLapseSweeper.Affected> paused = new ArrayList<>();

        for (Due posting : due) {
            State state = ask(posting.applyUrl());
            switch (state) {
                case OPEN -> open++;
                case CLOSED -> closed++;
                case UNKNOWN -> unknown++;
            }
            if (state == State.CLOSED && !posting.overridden()) {
                PlanLapseSweeper.Affected affected = pause(posting);
                if (affected != null) paused.add(affected);
            } else {
                record(posting, state);
            }
            pause();
        }

        log.info("source check: asked {} ({} open, {} closed, {} unknown), paused {}",
                due.size(), open, closed, unknown, paused.size());

        // Same reason as the plan-lapse sweep: every page a role appears on is
        // prerendered hourly, so a paused role goes on being advertised -- Apply
        // button and JobPosting structured data included -- until the page is
        // rebuilt. Best effort, and last, so a cache failure cannot undo a
        // correct database.
        sweeper.tellTheSite(paused);
    }

    /**
     * The roles worth asking about: approved, live, old enough, and either never
     * asked or last asked a week ago.
     *
     * <p>Under the age floor nothing is asked at all. A role approved last week
     * was read by a person (or by the model) days ago, and spending a request to
     * confirm what the review already established is the kind of busywork that
     * makes a scheduled job expensive for nothing.
     *
     * <p>Ordered oldest-check-first so the queue is fair and a new role never
     * jumps ahead of one that has been waiting.
     */
    List<Due> due() {
        return db.sql("""
                select p.id, p.job_id, p.employer_id, j.apply_url,
                       j.geo_scope, j.geo_detail,
                       (p.source_override_at is not null) as overridden
                  from employer_postings p
                  join jobs j on j.id = p.job_id
                 where p.status = 'APPROVED'
                   and p.paused_at is null
                   and j.closed_at is null
                   and j.apply_url is not null
                   and j.posted_at < now() - cast(:age as int) * interval '1 day'
                   and (p.source_checked_at is null
                        or p.source_checked_at < now() - cast(:recheck as int) * interval '1 day')
                 order by p.source_checked_at nulls first, p.id
                 limit :batch
                """)
                .param("age", minAgeDays)
                .param("recheck", recheckDays)
                .param("batch", batch)
                .query((rs, n) -> {
                    long jobId = rs.getLong("job_id");
                    return new Due(rs.getLong("id"),
                            rs.wasNull() ? null : jobId,
                            rs.getLong("employer_id"),
                            rs.getString("apply_url"),
                            rs.getString("geo_scope"),
                            rs.getString("geo_detail"),
                            rs.getBoolean("overridden"));
                })
                .list();
    }

    /**
     * Fetches the page and reads the answer out of it.
     *
     * <p>Every failure is UNKNOWN, including the ones that look like an answer.
     * A 403 is a bot wall, not a closed role; a redirect to a careers index
     * means the role is gone <em>or</em> that the site was reorganised, and
     * those are not the same claim.
     *
     * <p>The body is read through a stream with a hard ceiling rather than with
     * {@code BodyHandlers.ofString}, which would hold whatever the other end
     * chose to send. The markers are in the first few kilobytes; 256 KB is room
     * for the pages to grow several times over, and it is also the promise that
     * one hostile response cannot take the pipeline's heap with it.
     */
    State ask(String applyUrl) {
        if (!ApplySource.readable(applyUrl)) return State.UNKNOWN;

        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(applyUrl))
                            .header("user-agent", USER_AGENT)
                            .header("accept", "text/html,application/xhtml+xml")
                            .timeout(Duration.ofSeconds(15))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            String body;
            try (InputStream in = response.body()) {
                body = new String(in.readNBytes(ApplySource.MAX_BODY_BYTES), StandardCharsets.UTF_8);
            }
            return ApplySource.read(applyUrl, response.statusCode(), body);
        } catch (InterruptedException e) {
            // Put the flag back; see the same catch in PlanLapseSweeper for what
            // swallowing it costs the next blocking call on this thread.
            Thread.currentThread().interrupt();
            return State.UNKNOWN;
        } catch (IOException | IllegalArgumentException e) {
            log.debug("could not reach {}: {}", applyUrl, e.toString());
            return State.UNKNOWN;
        }
    }

    /**
     * Identifies the fetcher honestly, because it is asking a question on an
     * employer's behalf about that employer's own page.
     */
    private static final String USER_AGENT =
            "actuallyremote/1.0 (+https://actuallyremote.work/bot; listing check)";

    /** Writes down what the source said, and nothing else. */
    private void record(Due posting, State state) {
        db.sql("""
                update employer_postings
                   set source_checked_at = now(),
                       source_state = :state,
                       source_unknown_streak =
                           case when :state = 'UNKNOWN' then source_unknown_streak + 1 else 0 end
                 where id = :id
                """)
                .param("state", state.name())
                .param("id", posting.id())
                .update();
    }

    /**
     * Takes the role off the board, reversibly, and says why in the row.
     *
     * <p>One transaction for the two writes: a posting marked paused whose job
     * is still open is a listing that the dashboard calls paused and the board
     * goes on showing, which is the worst of the three possible states.
     */
    private PlanLapseSweeper.Affected pause(Due posting) {
        return tx.execute(status -> {
            int rows = db.sql("""
                    update employer_postings
                       set paused_at = now(), pause_reason = 'SOURCE_CLOSED',
                           source_checked_at = now(), source_state = 'CLOSED',
                           source_unknown_streak = 0
                     where id = :id and paused_at is null and status = 'APPROVED'
                    """).param("id", posting.id()).update();
            if (rows == 0) return null;

            if (posting.jobId() != null) {
                db.sql("update jobs set closed_at = now() where id = :id and closed_at is null")
                        .param("id", posting.jobId()).update();
            }
            log.info("paused posting {} of employer {}: the source says the role is closed",
                    posting.id(), posting.employerId());
            return new PlanLapseSweeper.Affected(posting.id(), posting.jobId(),
                    posting.employerId(), posting.geoScope(), posting.geoDetail());
        });
    }

    /**
     * A second between requests, which is manners rather than throttling.
     *
     * <p>Eighty requests spread over eighty seconds against two hosts is less
     * than one of their own users browsing, and far less than what this site was
     * receiving from Meta's crawler -- a request every ten seconds, all day --
     * when this was written. Doing it the other way round would be hard to
     * complain about and easy to be blocked for.
     */
    private void pause() {
        try {
            Thread.sleep(politenessMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
