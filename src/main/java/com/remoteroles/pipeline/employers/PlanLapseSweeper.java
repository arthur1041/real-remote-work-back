package com.remoteroles.pipeline.employers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes roles published under a paid plan off the board when the subscription
 * behind them ends, and puts them back when it returns.
 *
 * <p><strong>Why this exists.</strong> Starter is sold as a subscription and
 * was delivered as a permanent placement: nothing ever closed a published role,
 * so one paid month bought a shop window for ever. On 12 Nov 2026 that stopped
 * being theoretical — a Stripe subscriber had 128 submissions and 127 roles
 * live, every one of them published inside the subscription window and none on
 * Free, which was 64% of the 197 employer roles open on the whole board.
 * Cancelling the next day would have left all 127 up indefinitely.
 *
 * <p><strong>What it does not do is apply a ceiling.</strong> Starter has no
 * limit on active roles and is not being given one here; while it is the only
 * paid plan, more employer roles is the point. This reaches only roles whose
 * subscription has <em>ended</em>, and it leaves anything published on Free
 * alone — those were never paid for, so cancelling cannot take them.
 *
 * <p><strong>The trigger is the entitlement, not the cancel button.</strong>
 * The predicate below is the same one `planFor` uses in the web app: a row with
 * status ACTIVE or CANCELLED whose `expires_at` is still in the future. That is
 * deliberate and it is the whole defence — a card that quietly stops working, a
 * provider that goes silent and an explicit cancellation all arrive at the same
 * place, so "just let the card die" is not a way around it.
 *
 * <p><strong>Both directions, one sweep, idempotent.</strong> Restoring could
 * have hung off the Stripe webhook, and then a subscription released by hand
 * (provider MANUAL, which is how the first one was) would never have restored
 * anything. Asking the question from the state instead means it does not matter
 * how the entitlement came back, or whether the process was running at the
 * moment it did.
 */
@Service
public class PlanLapseSweeper {

    private static final Logger log = LoggerFactory.getLogger(PlanLapseSweeper.class);

    /**
     * The roles to take down, and the one that survives.
     *
     * <p>The rule, in the product owner's words: the role published on Free
     * holds its place, because cancelling a paid plan has no business taking
     * down something that plan never carried. Only when nothing free-era is
     * open does one paid-era role stay, so that an employer who subscribed
     * straight away is left on the same footing as a free account rather than
     * with nothing.
     *
     * <p><strong>"Paid" is written as "not FREE" rather than as a list of plan
     * names.</strong> A predicate naming STARTER would quietly stop reaching
     * roles the moment a second paid plan existed — and stop silently, because
     * a sweep that pauses nothing logs nothing.
     *
     * <p>Most recent rather than oldest, for a reason the data made concrete:
     * the 128 roles of the account that prompted this were all submitted within
     * six hours of one another, so "the first one" is a coin flip dressed as a
     * rule. The most recent one is at least the likeliest to still be open. The
     * employer can swap it afterwards — that is what the pause button on the
     * dashboard is for — and whichever they choose then is recorded as theirs,
     * not as this sweep's.
     */
    static final String TO_PAUSE = """
            with open_paid as (
                select p.id, p.employer_id, p.job_id, p.geo_scope, p.geo_detail,
                       row_number() over (partition by p.employer_id
                                          order by p.created_at desc, p.id desc) as recency
                  from employer_postings p
                  join jobs j on j.id = p.job_id
                 where p.published_plan is not null
                   and p.published_plan <> 'FREE'
                   and p.status = 'APPROVED'
                   and p.paused_at is null
                   and j.closed_at is null
                   and not exists (
                         select 1 from employer_subscriptions s
                          where s.employer_id = p.employer_id
                            and s.status in ('ACTIVE', 'CANCELLED')
                            and s.expires_at > now())
            ),
            has_free_open as (
                select distinct p.employer_id
                  from employer_postings p
                  join jobs j on j.id = p.job_id
                 where p.published_plan = 'FREE'
                   and p.status = 'APPROVED'
                   and p.paused_at is null
                   and j.closed_at is null
            )
            select o.id, o.job_id, o.employer_id, o.geo_scope, o.geo_detail
              from open_paid o
             where o.recency > 1
                or o.employer_id in (select employer_id from has_free_open)
             order by o.employer_id, o.id
            """;

    /**
     * What comes back when the entitlement does.
     *
     * <p>Only PLAN_LAPSE. A role the employer paused themselves stays paused:
     * they swapped which role was using their free slot, and resubscribing is
     * not an instruction to undo that. The status check carries the rest — a
     * role an administrator removed is REMOVED and a role the employer withdrew
     * is WITHDRAWN, and neither is ours to reopen.
     */
    static final String TO_RESTORE = """
            select p.id, p.job_id, p.employer_id, p.geo_scope, p.geo_detail
              from employer_postings p
             where p.paused_at is not null
               and p.pause_reason = 'PLAN_LAPSE'
               and p.status = 'APPROVED'
               and exists (
                     select 1 from employer_subscriptions s
                      where s.employer_id = p.employer_id
                        and s.status in ('ACTIVE', 'CANCELLED')
                        and s.expires_at > now())
             order by p.employer_id, p.id
            """;

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String webBaseUrl;
    private final String token;

    public PlanLapseSweeper(
            JdbcClient db,
            /* **A TransactionTemplate and not @Transactional, and the
               difference is not style.** `sweep()` calls these two methods on
               `this`, and a call on `this` does not go through the Spring
               proxy that @Transactional is implemented by -- so the annotation
               was there, looked right, and did nothing at all. Each statement
               would have committed on its own, and a failure between the two
               updates would leave a role flagged as paused while its listing
               stayed open, which `TO_PAUSE` can never return again because the
               flag is what it filters on. A permanent inconsistency produced
               by the code that exists to prevent one. Found by reading the
               call, not by running it: nothing about it fails in a test. */
            TransactionTemplate tx,
            // The web process on the compose network, not the public hostname.
            // This call has no business leaving the machine, and routing it
            // through Caddy would send the token over a path reachable from
            // outside. Same address the CV retention sweep uses, and for the
            // same reason.
            @Value("${employer.plan-lapse.web-url:http://web:3000}") String webBaseUrl,
            @Value("${employer.plan-lapse.token:}") String token) {
        this.db = db;
        this.tx = tx;
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
        this.token = token == null ? "" : token.trim();
    }

    /** One row of the sweep: the submission, the listing, and where it showed. */
    record Affected(long id, Long jobId, long employerId, String geoScope, String geoDetail) { }

    /**
     * Hourly, at five past.
     *
     * <p>Nothing here needs to be prompt to the minute: a subscription that
     * ended at 03:00 and a board that notices at 04:05 differ by an hour on a
     * thing measured in months. What it does need is to run often enough that
     * somebody who resubscribes does not sit looking at an empty dashboard
     * wondering whether it worked, which is the direction that costs money.
     */
    @Scheduled(cron = "${employer.plan-lapse.cron:0 5 * * * *}")
    public void sweep() {
        List<Affected> paused = pauseLapsed();
        List<Affected> restored = restoreResubscribed();
        if (paused.isEmpty() && restored.isEmpty()) return;

        log.info("plan lapse sweep: paused {}, restored {}", paused.size(), restored.size());

        /* **And then the site is told, because writing the row is only half of
           it.** Every page a role appears on is prerendered hourly, so a role
           paused in the database goes on being served complete -- description,
           Apply button and JobPosting structured data -- from the page built
           before the pause. Measured while building this: the apply route
           refuses the click, because it is dynamic and reads the row, so
           nobody can actually apply; but the role is still advertised and
           still offered to Google Jobs for as long as that page lasts.

           Both lists, in one call: a restored role's page is just as stale as
           a paused one's, and it is the one somebody is watching for. */
        List<Affected> all = new ArrayList<>(paused);
        all.addAll(restored);
        tellTheSite(all);
    }

    /** Roles whose subscription has ended come off the board. Returns how many. */
    public List<Affected> pauseLapsed() {
        return tx.execute(status -> {
        List<Affected> rows = db.sql(TO_PAUSE).query(PlanLapseSweeper::map).list();
        if (rows.isEmpty()) return List.of();

        db.sql("""
                update employer_postings
                   set paused_at = now(), pause_reason = 'PLAN_LAPSE'
                 where id in (:ids)
                """).param("ids", rows.stream().map(Affected::id).toList()).update();

        // **`featured_until` is left alone**, which is the same call the admin's
        // own removal makes and for the same reason: closing already hides the
        // role everywhere, because every Featured query reads `closed_at is
        // null` first, so clearing the window buys nothing and costs the one
        // thing a reversible takedown has to be able to give back. What is left
        // of a window survives the round trip; if it ran out while the role was
        // down, it ran out.
        List<Long> jobIds = rows.stream().map(Affected::jobId).filter(j -> j != null).toList();
        if (!jobIds.isEmpty()) {
            db.sql("update jobs set closed_at = now() where id in (:ids) and closed_at is null")
                    .param("ids", jobIds).update();
        }

        log.info("paused {} role(s) for {} employer(s) whose plan ended",
                rows.size(), rows.stream().map(Affected::employerId).distinct().count());
        return rows;
        });
    }

    /** Roles paused by a lapse go back up when the entitlement returns. */
    public List<Affected> restoreResubscribed() {
        return tx.execute(status -> {
        List<Affected> rows = db.sql(TO_RESTORE).query(PlanLapseSweeper::map).list();
        if (rows.isEmpty()) return List.of();

        db.sql("""
                update employer_postings
                   set paused_at = null, pause_reason = null
                 where id in (:ids)
                """).param("ids", rows.stream().map(Affected::id).toList()).update();

        // The closing date the employer set is still the employer's. A role
        // that was paused in October and whose own date passed in November does
        // not come back in December because somebody resubscribed -- it would
        // be reopening a listing its owner had already said was over, which is
        // the one thing a board about real listings cannot do. closeExpired()
        // would catch it on its next pass anyway; not reopening it is cheaper
        // and does not put a dead role on the board for an hour.
        List<Long> jobIds = rows.stream().map(Affected::jobId).filter(j -> j != null).toList();
        if (!jobIds.isEmpty()) {
            db.sql("""
                    update jobs set closed_at = null
                     where id in (:ids)
                       and (expires_at is null or expires_at > now())
                    """).param("ids", jobIds).update();
        }

        log.info("restored {} role(s) for {} employer(s) who are paying again",
                rows.size(), rows.stream().map(Affected::employerId).distinct().count());
        return rows;
        });
    }

    /**
     * Asks the site to drop the prerendered pages these listings were on.
     *
     * <p>Best effort on purpose, and it is the last thing the sweep does. The
     * database is already correct by the time this runs: a call that fails
     * leaves pages stale until the next hourly prerender, which is where they
     * would have been with no call at all. Throwing here would turn a cache
     * problem into a failed sweep and leave the next run to find the same
     * roles already paused and nothing to tell anybody about.
     *
     * <p>Inert without a token, like every other internal call here.
     */
    void tellTheSite(List<Affected> listings) {
        if (token.isEmpty() || listings.isEmpty()) return;

        StringBuilder json = new StringBuilder("{\"listings\":[");
        for (int i = 0; i < listings.size(); i++) {
            Affected a = listings.get(i);
            if (i > 0) json.append(',');
            json.append("{\"jobId\":").append(a.jobId() == null ? "null" : a.jobId())
                .append(",\"geoScope\":").append(quote(a.geoScope()))
                .append(",\"geoDetail\":").append(quote(a.geoDetail()))
                .append('}');
        }
        json.append("]}");

        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(webBaseUrl + "/api/internal/revalidate-jobs"))
                            // HTTP/1.1, explicitamente, como as outras duas chamadas
                            // internas deste processo. O HttpClient do Java negocia
                            // HTTP/2 por padrão e o servidor do Next fecha a conexão:
                            // `IOException: HTTP/1.1 header parser received no bytes`.
                            // Reproduzido de novo aqui, de ponta a ponta, por esta
                            // linha ter faltado -- a varredura pausou as cinco vagas
                            // e nenhuma página mudou, com o aviso engolido no log.
                            // É o mesmo defeito que custou dois vereditos do modelo
                            // em `PostingReviewer`, e a terceira vez que ele aparece.
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("content-type", "application/json")
                            .header("x-internal-token", token)
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("the site refused the revalidation: {}", response.statusCode());
            }
        } catch (InterruptedException e) {
            // The only exception that must put the flag back, because catching
            // it cleared it. Doing this for every failure -- which is what this
            // block used to do -- would leave the scheduler's thread marked as
            // interrupted after an ordinary connection refusal, and the next
            // blocking call on it would fail for a reason that has nothing to
            // do with what went wrong.
            Thread.currentThread().interrupt();
            log.warn("interrupted while asking the site to revalidate; the pages"
                    + " will catch up on their own within the hour");
        } catch (Exception e) {
            log.warn("could not ask the site to revalidate ({}); the pages will catch up"
                    + " on their own within the hour", e.toString());
        }
    }

    /** A JSON string, or null. Titles are not sent, so this only ever sees
        geography: scope names from a fixed list and country labels. */
    private static String quote(String value) {
        if (value == null) return "null";
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static Affected map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        long jobId = rs.getLong("job_id");
        Long job = rs.wasNull() ? null : jobId;
        return new Affected(rs.getLong("id"), job, rs.getLong("employer_id"),
                rs.getString("geo_scope"), rs.getString("geo_detail"));
    }
}
