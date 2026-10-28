package com.remoteroles.pipeline.ingest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The clock for the CV retention sweep.
 *
 * <p>When a candidate deletes their account, every document no application
 * points at is destroyed in the bucket there and then, and the ones an employer
 * was actually sent go on a ninety-day clock instead — long enough to cover a
 * hiring process that was underway when somebody left, short enough to be a
 * retention period rather than "forever" spelled differently. This is what
 * makes the clock run out.
 *
 * <p>**Why it calls the web app instead of doing the work.** The bucket
 * credentials live in the web process and nowhere else. Holding them here too
 * would be the same secret on the same disk twice, and the alternative — a
 * hand-rolled S3 signer to avoid pulling the AWS SDK onto a 2GB box — would put
 * an untested signer on the one code path whose silent failure means documents
 * somebody asked to have destroyed quietly survive. Scheduling belongs in this
 * process because every other schedule does; the hands stay where the keys are.
 *
 * <p>Inert without a token, like every other integration here: no
 * `INTERNAL_TASK_TOKEN` means the call is never made, and the documents simply
 * wait. That is the right direction to fail in — a misconfiguration that
 * delays a deletion is recoverable, and one that performs a deletion nobody
 * asked for is not.
 *
 * <p>Runs at 03:20, before the ingest at 04:17 rather than after it. Nothing
 * about the two interacts, and the quiet hour is simply where there is room.
 */
@Service
public class ResumeRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ResumeRetentionJob.class);

    private final JdbcClient db;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String webBaseUrl;
    private final String token;

    public ResumeRetentionJob(
            JdbcClient db,
            // The web process on the compose network, not the public hostname:
            // this call has no business leaving the machine, and routing it
            // through Caddy would mean the token travelling over a path that is
            // also reachable from outside.
            @Value("${retention.web-url:http://web:3000}") String webBaseUrl,
            @Value("${retention.token:}") String token) {
        this.db = db;
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
        this.token = token == null ? "" : token.trim();
    }

    @Scheduled(cron = "${retention.cron:0 20 3 * * *}")
    public void run() {
        sweep();
    }

    /**
     * Asks the web app to destroy what is due, in batches until nothing is.
     *
     * <p>Bounded at ten rounds of fifty. A sweep that tries to clear an
     * unbounded backlog in one night is a sweep that holds a connection open
     * for an hour; anything left over is due again tomorrow, and the count
     * below says how much there was.
     */
    public int sweep() {
        // Read first, purely so the log says something true when the call is
        // skipped. The web app does the same query again -- deliberately, since
        // it is the one that acts on the answer and a count read here would be
        // stale by the time it mattered.
        int due = pendingCount();
        if (due == 0) {
            return 0;
        }

        if (token.isEmpty()) {
            log.warn("resume retention: {} document(s) are due for deletion but "
                    + "INTERNAL_TASK_TOKEN is unset, so nothing was swept", due);
            return 0;
        }

        int swept = 0;
        for (int round = 0; round < 10; round++) {
            Integer batch = callSweep();
            if (batch == null || batch == 0) {
                break;
            }
            swept += batch;
        }

        log.info("resume retention: {} of {} document(s) destroyed", swept, due);
        return swept;
    }

    private int pendingCount() {
        Integer n = db.sql("""
                        select count(*) from candidate_resumes
                         where delete_after is not null and delete_after <= now()
                        """)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /** One batch. Null on anything that was not a clean answer. */
    private Integer callSweep() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(webBaseUrl + "/api/internal/resume-sweep"))
                            // Como no PostingReviewer: o padrão HTTP_2 vira
                            // `Upgrade: h2c` em `http://`, e o servidor do Next fecha
                            // o socket sem responder. Aqui a falha era latente -- a
                            // varredura só chama o site quando há documento vencido, e
                            // em 72h de log de produção não houve nenhum -- mas é a
                            // mesma falha.
                            .version(HttpClient.Version.HTTP_1_1)
                            .header("X-Internal-Token", token)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(120))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("resume retention: sweep returned HTTP {} -- {}",
                        response.statusCode(), brief(response.body()));
                return null;
            }

            // Deliberately not a JSON parse. The one number needed is the count,
            // the response is written by code in this same repository, and a
            // Jackson dependency on a two-field answer is more moving parts than
            // the thing it reads.
            return readSwept(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("resume retention: interrupted");
            return null;
        } catch (Exception e) {
            log.error("resume retention: sweep could not be reached -- {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Pulls `"swept": <n>` out of the answer. Null when it is not there. */
    static Integer readSwept(String body) {
        if (body == null) {
            return null;
        }
        var matcher = java.util.regex.Pattern
                .compile("\"swept\"\\s*:\\s*(\\d+)")
                .matcher(body);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** The first 200 characters, on one line. See ScopeReviewer.brief. */
    static String brief(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200);
    }
}
