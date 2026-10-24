package com.remoteroles.pipeline.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remoteroles.pipeline.indexing.GoogleServiceAccount;
import com.remoteroles.pipeline.newsletter.Mailer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a model whether a posting's own text contradicts its worldwide tag.
 *
 * <p>The rule-based veto in {@link RuleBasedLocationClassifier} catches a
 * restriction that is <em>written</em> in a sentence it recognises. It cannot
 * catch one that is implied: five live roles state theirs only as a salary
 * table covering US metros, which no regular expression reaches. This is the
 * second reader, and it only ever runs where the first one concluded
 * WORLDWIDE, because a role already gated cannot contradict a promise it does
 * not make.
 *
 * <p>It takes the badge away and never removes a listing. The role keeps its
 * page and stays findable; it stops occupying the homepage and stops asserting
 * what its own description denies. That is the same thing the rule veto does,
 * deliberately: two different meanings of "removed" in one board would be two
 * things to explain, and a wrong verdict here has to be cheap to undo.
 *
 * <p>Measured against 26 roles whose answers were already known by hand --
 * six naming a country outright, five restricted only by a US pay table, five
 * gated to a timezone, and ten controls that are genuinely open. Three runs,
 * 26/26 each time, no genuinely open role taken off the homepage. The cheapest
 * model available was also the most accurate on this task, which is why it is
 * the default.
 */
@Service
@ConditionalOnProperty(name = "scope-review.enabled", havingValue = "true")
public class ScopeReviewer {

    private static final Logger log = LoggerFactory.getLogger(ScopeReviewer.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /**
     * The aggregator's own paragraph, cut before anything reads the posting.
     *
     * <p>WeWorkRemotely prepends {@code <p><strong>Headquarters:</strong> Remote
     * - United States</p>} to every description. It is not a sentence the
     * employer wrote and a headquarters restricts nobody, but it reads exactly
     * like a location requirement: four of the first five wrong answers in
     * testing quoted that line and nothing else. Cut at the element, where the
     * boundary is unambiguous — an earlier attempt to cut it from flattened
     * text matched nothing at all and the mistake was invisible until the token
     * count failed to move.
     */
    private static final Pattern PREAMBLE = Pattern.compile(
            "\\s*<p>\\s*<strong>\\s*(?:headquarters|url)\\s*:?\\s*</strong>.*?</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    /**
     * The handful of entities these descriptions actually carry.
     *
     * <p>Stripping tags leaves `&nbsp;` behind as the letters "nbsp", which the
     * model's quote never contains -- so the quote check rejected genuine
     * restrictions, including "This role will be remote, and based in the
     * United States". Five listings were put back on the homepage by a guard
     * meant to protect it.
     */
    private static final Pattern ENTITY = Pattern.compile(
            "&(nbsp|amp|quot|apos|lt|gt|#160|#38|#39|#34|rsquo|lsquo|ldquo|rdquo|mdash|ndash|hellip);",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /**
     * Everything that is not a letter or a digit, in any script.
     *
     * <p>`[^a-z0-9]` deleted Japanese and Chinese outright, so a quote in
     * either normalised to an empty string and was refused as too short. One
     * of them read "この職務は日本を拠点とし" -- this role is based in Japan.
     */
    private static final Pattern NOT_ALNUM =
            Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * What the model is asked.
     *
     * <p>Most of it is the list of things that look like a gate and are not,
     * and every entry was earned by a wrong answer in testing. The last
     * paragraph is the one that fixed the hardest class: "Head of West Coast
     * Sales" is the territory the job covers, not where the worker must live,
     * which is the same distinction {@link RuleBasedLocationClassifier} already
     * documents for titles.
     */
    private static final String PROMPT = """
            You check job postings for one specific thing.

            The posting below has been tagged as open worldwide, with no country requirement.
            Decide whether the posting's own text contradicts that tag.

            It CONTRADICTS if the text says the person must live in, reside in, be located
            in, be based in, or be legally able to work in a particular country, region,
            state, province or city -- or must be within particular time zones -- in order
            to hold the job.

            It does NOT contradict for any of these:
            - a company headquarters or office address
            - where colleagues or clients happen to be
            - salary quoted in one currency
            - legal boilerplate about work authorisation "in the country where you apply"
            - benefits available only to employees in one country
            - an equal-opportunity or privacy notice naming a jurisdiction
            - a preference, a "nice to have", or overlap that is merely encouraged
            - the territory, market or customers the job covers. "Head of West Coast
              Sales", "EMEA Account Executive", "scale our LATAM region" describe the work,
              not where the worker must live. Only a sentence about the PERSON counts.
            - a pay range labelled with a country, such as "the US hourly range for this
              position is ...". Where somebody is paid from is not where they must be.
            - "work from anywhere within your country", "remotely from any location within
              their country of employment", or similar. That is permission to move around
              inside wherever you already are. It names no country and restricts nobody.
            - where the team, the agency or the offices are. "Teams across the U.S.",
              "offices in Berlin and NYC", "a remote-first company with people in twelve
              countries" says where colleagues sit.
            - what employees in ONE named place do, when others plainly exist. "NYC
              employees are expected in the office on Tuesdays" is a rule for the people
              already in NYC, not a requirement to be one.

            Ask yourself: does this sentence say where the PERSON has to be? If it says
            where the work points, the money comes from, or the company sits, it does not
            count.

            "place" must be a NAMED country, region, state, province, city or time zone --
            "Germany", "EMEA", "Ontario", "CET". If you cannot name one, the answer is false:
            "their country", "country of employment", "wherever you live" name nothing and
            restrict nobody.

            Answer with JSON only, no other text:
            {"restricted": true|false, "place": "<the place, or null>", "quote": "<the exact sentence from the posting that decides it, or null>"}

            POSTING TITLE: %s

            POSTING TEXT:
            %s
            """;

    private final JdbcClient db;
    private final GoogleServiceAccount account;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final Mailer mailer;
    private final String endpoint;
    private final String model;
    private final int batchSize;
    private final int maxAgeDays;
    private final String corpusFloor;
    private final String alertTo;

    public ScopeReviewer(
            JdbcClient db,
            Mailer mailer,
            @Value("${scope-review.key-file}") String keyFile,
            @Value("${scope-review.project}") String project,
            @Value("${scope-review.location:global}") String location,
            @Value("${scope-review.model:gemini-3.1-flash-lite}") String model,
            @Value("${scope-review.batch-size:120}") int batchSize,
            @Value("${scope-review.max-age-days:3}") int maxAgeDays,
            // The date this pass went live. Everything first seen after it is in
            // scope forever; everything before it is the historical corpus,
            // which was deliberately left out and whose review is a separate
            // decision with its own bill.
            @Value("${scope-review.corpus-floor:2026-10-23}") String corpusFloor,
            // Where a pass that is failing says so. Shares the moderation
            // queue's address: one operator, one inbox, and an alert nobody
            // configured separately is an alert nobody gets.
            @Value("${newsletter.review-alert-to:}") String alertTo) throws Exception {
        this.db = db;
        this.mailer = mailer;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.model = model;
        // Raised from 60. With the order fixed, the batch size is no longer the
        // thing that decides whether a listing is ever read -- it only decides
        // how fast a backlog drains. Measured volume is ~27 worldwide arrivals a
        // day against a bill of cents a month, so doubling the ceiling costs
        // approximately nothing and halves the time to clear a bad week.
        this.batchSize = batchSize;
        this.maxAgeDays = maxAgeDays;
        this.corpusFloor = corpusFloor;
        this.alertTo = alertTo == null ? "" : alertTo.trim();
        // The global endpoint has no region prefix, and for this project it is
        // the only one that serves any Gemini model at all -- us-central1
        // returns NOT_FOUND for every one of them.
        String host = "global".equals(location) ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        this.endpoint = "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(host, project, location, model);
    }

    public static boolean keyAvailable(String keyFile) {
        return keyFile != null && !keyFile.isBlank() && Files.isReadable(Path.of(keyFile));
    }

    /** One posting waiting to be read. */
    record Candidate(long id, String title, String descriptionHtml, String contentHash) {}

    /** What the model said, after the quote has been checked against the text. */
    record Verdict(boolean restricted, String place, String quote,
                   int promptTokens, int outputTokens, String error) {

        static Verdict failed(String message) {
            return new Verdict(false, null, null, 0, 0, message);
        }
    }

    @Scheduled(cron = "${scope-review.cron:0 40 5 * * *}")
    public void run() {
        reviewPending();
    }

    /**
     * Reads every worldwide listing whose text has not been judged yet.
     *
     * <p>Keyed on `content_hash` rather than a timestamp, so a description
     * rewritten in place is read again and an untouched one is never paid for
     * twice. Runs after the ingest rather than inside it: a model that is slow
     * or down must not be able to hold up a fetch, and a pass that can simply
     * be run again is worth more than one that has to be rolled back.
     *
     * @return how many listings were judged
     */
    public int reviewPending() {
        return reviewPending(maxAgeDays);
    }

    /**
     * The same pass over a wider window, for catching up on purpose.
     *
     * <p>The nightly run only looks at what has just arrived, so the corpus
     * already stored is never touched by accident. This is how it gets touched
     * on purpose -- after a classifier rule is removed, say, when listings it
     * had been holding are about to return to the homepage unread.
     */
    public int reviewPending(int ageDays) {
        // How much is owed, before anything is taken off it. Counted separately
        // because the backlog is the health signal: a number that does not come
        // down from one night to the next means the pass is losing ground, and
        // nothing was measuring that.
        int backlog = pendingCount(ageDays);

        List<Candidate> waiting = db.sql("""
                        select j.id, j.title, j.description_html, j.content_hash
                          from jobs j
                         where j.closed_at is null
                           and j.geo_scope = 'WORLDWIDE'
                           and j.scope_reviewed_hash is distinct from j.content_hash
                           -- How far back to look. `max-age-days` is 3650 in
                           -- production -- effectively no limit, since the
                           -- original corpus has been read on purpose in one
                           -- catch-up -- and the mechanism stays because a bulk
                           -- import could recreate a backlog overnight, when it
                           -- is the difference between a surprise bill and a
                           -- deliberate one.
                           --
                           -- `corpus-floor` is the anchored alternative to it,
                           -- and `least` means whichever reaches further back
                           -- wins: with the age limit wide open the age limit
                           -- governs, and setting a short one does not re-open
                           -- the hole described below, because the floor then
                           -- holds the line.
                           and j.first_seen_at > least(
                                 :floor::timestamptz,
                                 now() - (:maxAge || ' days')::interval)
                         -- **Oldest first. This was `desc`, and that was a hole
                         -- in the only guard the homepage's claim has.**
                         --
                         -- The batch is capped per night. Newest-first on a
                         -- capped batch serves the front of the queue and leaves
                         -- the back of it unserved -- and because the window
                         -- reaches back ten years, the back of the queue does
                         -- not age out and get forgotten, it stays there being
                         -- skipped every single night, forever. Any listing that
                         -- was ever behind one night's worth of arrivals was
                         -- never going to be read, while sitting on the homepage
                         -- carrying a WORLDWIDE badge nothing had checked.
                         --
                         -- Oldest-first makes the queue a queue: the backlog
                         -- drains and the arrival that has waited longest is
                         -- next. In the steady state -- a backlog smaller than
                         -- one batch, which is the normal case at ~27 worldwide
                         -- arrivals a day against a batch of 120 -- the two
                         -- orders select exactly the same rows.
                         order by j.first_seen_at
                         limit :limit
                        """)
                .param("limit", batchSize)
                .param("maxAge", String.valueOf(ageDays))
                .param("floor", corpusFloor)
                .query((rs, n) -> new Candidate(rs.getLong("id"), rs.getString("title"),
                        rs.getString("description_html"), rs.getString("content_hash")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        int restricted = 0;
        int failures = 0;
        for (Candidate job : waiting) {
            String text = plainText(job.descriptionHtml());
            Verdict verdict = text.isBlank()
                    ? Verdict.failed("empty description")
                    : ask(job.title(), text);

            boolean applied = false;
            if (verdict.restricted()) {
                // Conditional on the scope not having moved while this ran. The
                // report must never claim a demotion that something else did.
                applied = db.sql("""
                                update jobs
                                   set geo_scope = 'UNKNOWN', geo_detail = null,
                                       classification_confidence = 0.6,
                                       -- A classifier version, like RULES_V1, not
                                       -- the model name: the upsert keys on this to
                                       -- know the verdict is the model's and must
                                       -- not be overwritten, and swapping models
                                       -- must not quietly turn that off. Which
                                       -- model answered is on the verdict row.
                                       classified_by = 'MODEL_V1'
                                 where id = :id and geo_scope = 'WORLDWIDE'
                                """)
                        .param("id", job.id())
                        .update() > 0;
                restricted++;
            }
            if (verdict.error() != null) {
                failures++;
            }

            db.sql("""
                            insert into job_scope_review
                              (job_id, model, restricted, place, quote, applied,
                               prompt_tokens, output_tokens, error)
                            values (:jobId, :model, :restricted, :place, :quote, :applied,
                                    :promptTokens, :outputTokens, :error)
                            """)
                    .param("jobId", job.id())
                    .param("model", model)
                    .param("restricted", verdict.restricted())
                    .param("place", verdict.place())
                    .param("quote", verdict.quote())
                    .param("applied", applied)
                    .param("promptTokens", verdict.promptTokens())
                    .param("outputTokens", verdict.outputTokens())
                    .param("error", verdict.error())
                    .update();

            // Marked as read whenever the model actually answered, which is
            // what promptTokens being non-zero means. An answer this code then
            // refused -- an unverifiable quote, a place that names nowhere --
            // was still paid for, and asking the same question about the same
            // text tomorrow would buy the same refusal at the same price,
            // every night, for as long as the listing is open. Only a call
            // that never completed is worth retrying.
            if (verdict.promptTokens() > 0) {
                db.sql("update jobs set scope_reviewed_hash = :hash where id = :id")
                        .param("hash", job.contentHash())
                        .param("id", job.id())
                        .update();
            }
        }

        int remaining = pendingCount(ageDays);
        log.info("scope review: {} read, {} restricted, {} failed, {} still waiting (was {})",
                waiting.size(), restricted, failures, remaining, backlog);

        raiseAlarmIfFailing(waiting.size(), failures, remaining);
        return waiting.size();
    }

    /**
     * How many open worldwide listings are still carrying an unchecked badge.
     *
     * <p>The same predicate as the selection above, minus the batch limit. This
     * is the number that matters and nothing was computing it: the log said how
     * many were read, which looks like health right up to the point where the
     * answer is "sixty, every night, forever".
     */
    public int pendingCount(int ageDays) {
        Integer n = db.sql("""
                        select count(*)
                          from jobs j
                         where j.closed_at is null
                           and j.geo_scope = 'WORLDWIDE'
                           and j.scope_reviewed_hash is distinct from j.content_hash
                           and j.first_seen_at > least(
                                 :floor::timestamptz,
                                 now() - (:maxAge || ' days')::interval)
                        """)
                .param("maxAge", String.valueOf(ageDays))
                .param("floor", corpusFloor)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /**
     * Says out loud that the second reader has stopped working.
     *
     * <p>docs/PROJECT.md names this as the open risk in so many words: the only
     * enforcement of the board's one claim depends on an external API, *"with no
     * floor beneath it and no alarm if it stops"*. This is the alarm. It was the
     * cheapest missing piece of the three and the one that decides whether the
     * other two matter — a bug in the error reporting and a window that starves
     * listings are both survivable if somebody is told; neither is if nobody is.
     *
     * <p>Fires on a pass that mostly failed, not on a single failure. One
     * refused call in sixty is a transient and mailing about it trains the
     * operator to ignore the alert, which is the failure mode that makes alarms
     * worse than nothing. Half or more means the key, the quota or the endpoint,
     * and all three need a person.
     *
     * <p>Also fires when the backlog is larger than a batch after a clean run,
     * which is the slow version of the same problem: the pass is working and
     * losing ground, and the listings it has not reached are on the homepage
     * meanwhile.
     *
     * <p>Runs once a night by construction, so there is no rate limit here and
     * no state to keep: the schedule is the rate limit.
     */
    private void raiseAlarmIfFailing(int read, int failures, int remaining) {
        boolean mostlyFailed = read > 0 && failures * 2 >= read;
        boolean losingGround = failures == 0 && remaining > batchSize;
        if (!mostlyFailed && !losingGround) {
            return;
        }

        String summary = mostlyFailed
                ? "%d of %d scope reviews failed".formatted(failures, read)
                : "%d worldwide listings are waiting for a scope review".formatted(remaining);

        // Logged at ERROR whether or not mail is configured, so the alarm exists
        // in the journal even on a box with no SMTP.
        log.error("scope review alarm: {} ({} still waiting)", summary, remaining);

        if (alertTo.isEmpty() || !mailer.isDeliveryEnabled()) {
            return;
        }

        String lastError = db.sql("""
                        select error from job_scope_review
                         where error is not null
                         order by decided_at desc
                         limit 1
                        """)
                .query(String.class)
                .optional()
                .orElse("none recorded");

        String text = """
                %s.

                The scope review is the second reader on every listing tagged as open \
                worldwide: it asks a model whether the posting's own text contradicts \
                the tag, and it is the only thing enforcing that claim. While it is \
                not running, new worldwide listings reach the homepage with nothing \
                having checked them.

                Listings still waiting: %d
                Most recent recorded error: %s

                /admin/scope-review shows every verdict and its quote.
                """.formatted(summary, remaining, lastError);

        mailer.send(alertTo, "Scope review needs attention", text);
    }

    /** The description with the aggregator's preamble cut and the tags removed. */
    static String plainText(String html) {
        if (html == null) {
            return "";
        }
        String cleaned = html;
        for (int i = 0; i < 3; i++) {
            Matcher m = PREAMBLE.matcher(cleaned);
            if (!m.lookingAt()) {
                break;
            }
            cleaned = cleaned.substring(m.end());
        }
        String noTags = TAGS.matcher(cleaned).replaceAll(" ");
        String decoded = ENTITY.matcher(noTags).replaceAll(" ");
        return SPACES.matcher(decoded).replaceAll(" ").trim();
    }

    private Verdict ask(String title, String text) {
        // Twelve thousand characters covers the ninetieth percentile with room
        // to spare; the median description is under six thousand. Truncating
        // costs a restriction stated only in a very long posting's tail, and
        // paying for every token of every outlier costs more.
        String body = text.length() > 12_000 ? text.substring(0, 12_000) : text;
        String payload;
        try {
            ObjectNode part = json.createObjectNode().put("text", PROMPT.formatted(title, body));
            ObjectNode turn = json.createObjectNode().put("role", "user");
            turn.set("parts", json.createArrayNode().add(part));

            ObjectNode config = json.createObjectNode()
                    .put("temperature", 0)
                    .put("maxOutputTokens", 300)
                    // JSON out, so the answer is parsed rather than scraped out
                    // of prose that varies between calls.
                    .put("responseMimeType", "application/json");

            ObjectNode request = json.createObjectNode();
            request.set("contents", json.createArrayNode().add(turn));
            request.set("generationConfig", config);
            payload = json.writeValueAsString(request);
        } catch (Exception e) {
            return Verdict.failed("payload: " + e.getMessage());
        }

        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .header("Authorization", "Bearer " + account.accessToken())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Verdict.failed("http " + response.statusCode() + " " + brief(response.body()));
            }
            return parse(response.body(), text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.failed("interrupted");
        } catch (Exception e) {
            return Verdict.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * The first 200 characters of a response body, on one line.
     *
     * <p>Written out because the obvious one-liner is wrong in a way that hides
     * exactly the information it exists to capture. It was:
     *
     * <pre>body.replaceAll("\\s+", " ").substring(0, Math.min(200, body.length()))</pre>
     *
     * <p>— and the bound comes from the length *before* the whitespace was
     * collapsed. Google's API errors are pretty-printed JSON, so a 319-character
     * body collapses to 58 and {@code substring(0, 200)} throws
     * StringIndexOutOfBoundsException. The throw is caught by the handler around
     * the call, which means every non-200 response — an expired key, a 403, a
     * quota refusal, a model that no longer exists — was recorded in
     * `job_scope_review.error` as "StringIndexOutOfBoundsException" with no
     * status code and no message. The one guard standing behind the homepage's
     * only claim could stop working for any of those reasons and they all left
     * the same useless footprint.
     */
    static String brief(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = SPACES.matcher(body).replaceAll(" ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200);
    }

    /**
     * Reads the answer, and refuses one it cannot stand behind.
     *
     * <p>A restriction has to come with the sentence that proves it, and that
     * sentence has to be in the posting. One run in testing returned
     * {@code restricted: true} with no place and no quote at all. Compared on
     * letters and digits only, because whitespace and HTML entities differ
     * between what was sent and what is stored.
     */
    Verdict parse(String responseBody, String text) {
        try {
            JsonNode root = json.readTree(responseBody);
            JsonNode usage = root.path("usageMetadata");
            int in = usage.path("promptTokenCount").asInt();
            int out = usage.path("candidatesTokenCount").asInt();

            String answer = root.path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asText("");
            JsonNode verdict = json.readTree(answer);

            boolean restricted = verdict.path("restricted").asBoolean(false);
            String place = verdict.path("place").isNull() ? null : verdict.path("place").asText(null);
            String quote = verdict.path("quote").isNull() ? null : verdict.path("quote").asText(null);

            if (restricted && !named(place)) {
                return new Verdict(false, null, quote, in, out, "no place named");
            }
            if (restricted && !quoted(quote, text)) {
                return new Verdict(false, null, quote, in, out, "unverifiable quote");
            }
            return new Verdict(restricted, place, quote, in, out, null);
        } catch (Exception e) {
            return Verdict.failed("unparsable: " + e.getMessage());
        }
    }

    /**
     * Whether the answer names somewhere, rather than a category.
     *
     * <p>On flexibility boilerplate -- "remotely from any location within their
     * country of employment" -- the model reaches for "country of employment"
     * and "their country". Those name nowhere and restrict nobody: the sentence
     * is permission to move around inside wherever you already are. Same
     * principle as the quote check. A restriction to a place has a place in it.
     */
    static boolean named(String place) {
        if (place == null || place.isBlank()) {
            return false;
        }
        String p = place.toLowerCase();
        return !p.contains("country of employment")
                && !p.contains("wherever")
                && !GENERIC_PLACE.matcher(p).find();
    }

    private static final Pattern GENERIC_PLACE = Pattern.compile(
            "\\b(their|your|his|her|the)\\s+(own\\s+)?(country|location|region|area|state)\\b");

    static boolean quoted(String quote, String text) {
        if (quote == null) {
            return false;
        }
        String needle = NOT_ALNUM.matcher(quote.toLowerCase()).replaceAll("");
        // Eight, not twelve. The floor exists to reject a fragment that would
        // match half the corpus, and in Japanese or Chinese eight characters
        // is a whole clause rather than two words.
        if (needle.length() < 8) {
            return false;
        }
        return NOT_ALNUM.matcher(text.toLowerCase()).replaceAll("").contains(needle);
    }
}
