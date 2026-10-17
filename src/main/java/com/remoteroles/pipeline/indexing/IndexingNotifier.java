package com.remoteroles.pipeline.indexing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells Google when a posting appears and when it goes away.
 *
 * <p>Google's Indexing API accepts one kind of page: a URL carrying JobPosting
 * structured data, which is exactly what this board publishes. Its own guidance
 * recommends it over a sitemap for these URLs, and the two calls it offers map
 * onto facts the pipeline already holds — a posting was created or changed, a
 * posting closed.
 *
 * <p>The deletion half may be the more valuable one. Nothing else tells Google
 * that a role is gone except a `noindex` it has to re-crawl to discover, which
 * spends crawl budget to learn the visit was wasted.
 *
 * <p>Strictly budgeted. The default quota is a couple of hundred calls a day
 * against a corpus of sixteen thousand, so this runs on a schedule, takes a
 * capped batch, and stamps each row as it goes. Only worldwide roles are
 * announced, because those are the only ones the sitemap nominates and the only
 * ones this site competes for.
 *
 * <p>Failures are logged and swallowed. A notification is an optimisation; an
 * ingest run must not fail because Google was briefly unavailable.
 */
@Service
@ConditionalOnProperty(name = "indexing.enabled", havingValue = "true")
public class IndexingNotifier {

    private static final Logger log = LoggerFactory.getLogger(IndexingNotifier.class);
    private static final String ENDPOINT = "https://indexing.googleapis.com/v3/urlNotifications:publish";
    private static final String SCOPE = "https://www.googleapis.com/auth/indexing";

    private final JdbcClient db;
    private final GoogleServiceAccount account;
    private final HttpClient http;
    private final String siteUrl;
    private final int dailyBudget;

    public IndexingNotifier(
            JdbcClient db,
            @Value("${indexing.key-file}") String keyFile,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${indexing.daily-budget:180}") int dailyBudget) throws Exception {
        this.db = db;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.dailyBudget = dailyBudget;
        log.info("indexing notifier ready as {}", account.clientEmail());
    }

    /** True when the key file is present and readable. */
    public static boolean keyAvailable(String keyFile) {
        return keyFile != null && !keyFile.isBlank() && Files.isReadable(Path.of(keyFile));
    }

    record Pending(long id, boolean closed) {}

    @Scheduled(cron = "${indexing.cron:0 25 * * * *}")
    public void run() {
        notifyPending();
    }

    /** Returns how many notifications were accepted. */
    public int notifyPending() {
        int spent = spentToday();
        int remaining = dailyBudget - spent;
        if (remaining <= 0) {
            return 0;
        }

        List<Pending> batch = selectPending(remaining);
        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (Pending row : batch) {
            // Stamped before the call, as everywhere else here: one missed
            // notification is recoverable, a loop that re-announces the same URL
            // every hour burns the day's quota on one page.
            db.sql("update jobs set indexing_notified_at = now() where id = :id")
                    .param("id", row.id())
                    .update();

            if (publish(row.id(), row.closed() ? "URL_DELETED" : "URL_UPDATED")) {
                sent++;
            }
        }

        log.info("indexing notifications: {} sent, {} of today's budget used", sent, spent + batch.size());
        return sent;
    }

    /**
     * What still needs announcing, closures first.
     *
     * <p>Closures come first because a listing that is indexed after the role is
     * gone is the worst thing a job board can have in search, and because the
     * fix cannot come from anywhere else — a new posting would be found by a
     * crawl eventually, a dead one would not.
     */
    private List<Pending> selectPending(int limit) {
        return db.sql("""
                        (
                          select id, true as closed from jobs
                           where closed_at is not null
                             and indexing_notified_at is not null
                             and indexing_notified_at < closed_at
                           order by closed_at desc
                           limit :limit
                        )
                        union all
                        (
                          select id, false as closed from jobs
                           where closed_at is null and is_remote is true
                             and geo_scope = 'WORLDWIDE'
                             and classification_confidence >= 0.5
                             and (indexing_notified_at is null
                                  or indexing_notified_at < content_changed_at)
                           order by content_changed_at desc
                           limit :limit
                        )
                        limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Pending(rs.getLong("id"), rs.getBoolean("closed")))
                .list();
    }

    private int spentToday() {
        Integer n = db.sql("""
                        select count(*) from jobs
                         where indexing_notified_at >= date_trunc('day', now())
                        """)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    private boolean publish(long jobId, String type) {
        String url = "%s/jobs/%d".formatted(siteUrl, jobId);
        String payload = "{\"url\":\"%s\",\"type\":\"%s\"}".formatted(url, type);
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(ENDPOINT))
                            .header("Authorization", "Bearer " + account.accessToken())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(20))
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return true;
            }
            // 429 is the quota, and it is not an error worth shouting about: the
            // next run picks up where this one stopped.
            log.warn("indexing notification refused for {}: {} {}",
                    url, response.statusCode(), response.body().replaceAll("\\s+", " "));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("indexing notification failed for {}: {}", url, e.getMessage());
            return false;
        }
    }
}
