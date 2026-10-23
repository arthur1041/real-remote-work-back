package com.remoteroles.pipeline.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remoteroles.pipeline.indexing.GoogleServiceAccount;
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
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^a-z0-9]+");

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
    private final String endpoint;
    private final String model;
    private final int batchSize;

    public ScopeReviewer(
            JdbcClient db,
            @Value("${scope-review.key-file}") String keyFile,
            @Value("${scope-review.project}") String project,
            @Value("${scope-review.location:global}") String location,
            @Value("${scope-review.model:gemini-3.1-flash-lite}") String model,
            @Value("${scope-review.batch-size:60}") int batchSize) throws Exception {
        this.db = db;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.model = model;
        this.batchSize = batchSize;
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
        List<Candidate> waiting = db.sql("""
                        select j.id, j.title, j.description_html, j.content_hash
                          from jobs j
                         where j.closed_at is null
                           and j.geo_scope = 'WORLDWIDE'
                           and j.scope_reviewed_hash is distinct from j.content_hash
                         order by j.first_seen_at desc
                         limit :limit
                        """)
                .param("limit", batchSize)
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
                                       classified_by = :model
                                 where id = :id and geo_scope = 'WORLDWIDE'
                                """)
                        .param("id", job.id())
                        .param("model", model)
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

        log.info("scope review: {} read, {} restricted, {} failed", waiting.size(), restricted, failures);
        return waiting.size();
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
        return SPACES.matcher(TAGS.matcher(cleaned).replaceAll(" ")).replaceAll(" ").trim();
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
                return Verdict.failed("http " + response.statusCode() + " "
                        + response.body().replaceAll("\\s+", " ").substring(0,
                        Math.min(200, response.body().length())));
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
        if (needle.length() < 12) {
            return false;
        }
        return NOT_ALNUM.matcher(text.toLowerCase()).replaceAll("").contains(needle);
    }
}
