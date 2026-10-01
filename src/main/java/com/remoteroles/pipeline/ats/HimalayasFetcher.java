package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.remoteroles.pipeline.config.IngestProperties;
import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.repo.FeedCursorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Himalayas' public feed — the supply of genuinely worldwide roles.
 *
 * <p>Why this source carries the product: it publishes {@code locationRestrictions}
 * per posting, and an <em>empty</em> list is an explicit statement that the role
 * has no country gate. Everywhere else that fact has to be inferred from prose; here
 * the publisher asserts it. Measured over a 300-posting sample, 1.3% of the feed is
 * unrestricted, which against a catalogue of roughly 93,000 is on the order of a
 * thousand worldwide roles.
 *
 * <p>The cost is that the API caps at 20 postings per request and offers no
 * server-side filter, so finding those thousand means walking the whole feed. That
 * is thousands of requests against someone else's service, so the crawl is
 * explicitly budgeted and rate-limited rather than run flat out, and it stops early
 * once the feed reports it is exhausted.
 */
@Component
public class HimalayasFetcher implements FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(HimalayasFetcher.class);

    private static final String URL = "https://himalayas.app/jobs/api?limit=%d&offset=%d";
    private static final int PAGE_SIZE = 20;

    /**
     * How much of the 24-hour span a posting's timezone list must cover before we
     * treat "no location restriction" as genuinely meaning anywhere. A role open to
     * every country but only three timezones is not one you can take from anywhere.
     */
    private static final int TIMEZONE_SPAN_FOR_TRULY_ANYWHERE = 18;

    private final AtsHttp http;
    private final IngestProperties props;
    private final FeedCursorRepository cursors;

    public HimalayasFetcher(AtsHttp http, IngestProperties props, FeedCursorRepository cursors) {
        this.http = http;
        this.props = props;
        this.cursors = cursors;
    }

    @Override
    public AtsType source() {
        return AtsType.HIMALAYAS;
    }

    /**
     * Walks a budgeted slice of the feed, resuming where the last run stopped.
     *
     * <p>Sequential, and deliberately so. The host sits behind Cloudflare and starts
     * returning 429 challenge pages after roughly sixty requests; a concurrent crawl
     * reached that wall almost immediately and then failed 4,698 times in a row,
     * which is both useless and rude. One request at a time, with a pause, stops at
     * the first refusal and leaves the rest for the next run.
     *
     * <p>Covering ~93,000 postings at 20 per page therefore takes many runs. That is
     * the honest cost of this source, and the reason WeWorkRemotely is the primary
     * one: it yields more worldwide roles in a dozen requests than this does in
     * hundreds.
     */
    @Override
    public List<FetchedPosting> fetch() {
        IngestProperties.Feed feed = props.feed();
        List<FetchedPosting> out = new ArrayList<>();

        int offset = cursors.nextOffset(AtsType.HIMALAYAS);
        int startedAt = offset;
        int pages = 0;
        boolean wrapped = false;
        String status = "ok";

        for (int i = 0; i < feed.maxPages(); i++) {
            JsonNode root;
            try {
                root = http.getJson(URL.formatted(PAGE_SIZE, offset));
            } catch (AtsHttp.RateLimitedException e) {
                // Stop immediately. Retrying is what turns a polite crawl into abuse,
                // and the challenge page is not something to be worked around.
                status = "rate-limited";
                log.info("himalayas: rate limited at offset {}; stopping this pass", offset);
                break;
            } catch (Exception e) {
                status = "error: " + e.getClass().getSimpleName();
                log.warn("himalayas: failed at offset {}: {}", offset, e.toString());
                break;
            }

            JsonNode jobs = root.get("jobs");
            if (jobs == null || !jobs.isArray() || jobs.isEmpty()) {
                wrapped = true;
                status = "end-of-feed";
                break;
            }

            for (JsonNode job : jobs) {
                FetchedPosting posting = toWorldwidePosting(job);
                if (posting != null) {
                    out.add(posting);
                }
            }

            pages++;
            offset += PAGE_SIZE;

            int total = root.path("totalCount").asInt(0);
            if (total > 0 && offset >= total) {
                wrapped = true;
                status = "end-of-feed";
                break;
            }

            try {
                Thread.sleep(feed.pauseMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "interrupted";
                break;
            }
        }

        cursors.advance(AtsType.HIMALAYAS, offset, wrapped, status);
        log.info("himalayas: {} pages from offset {} ({}), {} worldwide postings kept",
                pages, startedAt, status, out.size());
        return List.copyOf(out);
    }

    /** Returns null for any posting that states a geographic restriction. */
    private static FetchedPosting toWorldwidePosting(JsonNode job) {
        JsonNode restrictions = job.get("locationRestrictions");
        boolean unrestricted = restrictions == null || !restrictions.isArray() || restrictions.isEmpty();
        if (!unrestricted) {
            return null;
        }

        String externalId = Json.text(job, "guid");
        String title = Json.text(job, "title");
        String applyUrl = Json.text(job, "applicationLink");
        String employer = Json.text(job, "companyName");
        if (externalId == null || title == null || applyUrl == null || employer == null) {
            return null;
        }

        String timezone = timezoneRequirement(job.get("timezoneRestrictions"));

        // "Anywhere" is the location text precisely because the publisher stated no
        // restriction. The classifier reads this and reaches WORLDWIDE through the
        // same path as any other posting, rather than this source getting a bypass.
        String locationRaw = timezone == null ? "Anywhere" : "Anywhere (" + timezone + ")";

        return new FetchedPosting(
                externalId,
                title,
                applyUrl,
                locationRaw,
                Json.text(job, "description"),
                epochSeconds(job, "pubDate"),
                Boolean.TRUE,
                firstCategory(job),
                Json.text(job, "employmentType"),
                job.toString(),
                employer,
                epochSeconds(job, "expiryDate"),
                decimal(job, "minSalary"),
                decimal(job, "maxSalary"),
                Json.text(job, "currency"),
                Json.text(job, "salaryPeriod"),
                firstOf(job, "seniority")
        );
    }

    /**
     * Describes a timezone band, or null when the posting spans enough of the globe
     * to count as unrestricted.
     */
    private static String timezoneRequirement(JsonNode zones) {
        if (zones == null || !zones.isArray() || zones.isEmpty()) {
            return null;
        }
        if (zones.size() >= TIMEZONE_SPAN_FOR_TRULY_ANYWHERE) {
            return null;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (JsonNode zone : zones) {
            if (zone.isNumber()) {
                min = Math.min(min, zone.asDouble());
                max = Math.max(max, zone.asDouble());
            }
        }
        if (min > max) {
            return null;
        }
        return "UTC%+.0f to UTC%+.0f".formatted(min, max);
    }

    private static String firstCategory(JsonNode job) {
        JsonNode categories = job.get("categories");
        if (categories != null && categories.isArray() && !categories.isEmpty()) {
            return categories.get(0).asText(null);
        }
        return null;
    }

    /** Salary fields arrive as numbers or as strings, and often as empty strings. */
    private static java.math.BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Seniority arrives as an array, e.g. ["Mid-level"]. */
    private static String firstOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isArray() && !value.isEmpty()) {
            return value.get(0).asText(null);
        }
        return Json.text(node, field);
    }

    private static Instant epochSeconds(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || !value.isNumber()) ? null : Instant.ofEpochSecond(value.asLong());
    }
}
