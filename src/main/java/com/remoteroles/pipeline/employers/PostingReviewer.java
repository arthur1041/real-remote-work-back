package com.remoteroles.pipeline.employers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.remoteroles.pipeline.indexing.GoogleServiceAccount;

/**
 * Reads a paying employer's posting the minute it arrives, so the ones with
 * nothing wrong with them do not wait for a person.
 *
 * <p>**What this actually buys, measured rather than assumed.** Of the 32
 * employer postings reviewed by hand so far the median decision took 1.6
 * hours and the mean 10.1 -- but the slowest took **95.6 hours**, four days,
 * because a queue read by one person is a queue that stops at night. The free
 * promise is "within 24 hours" and it is nearly always kept. What Starter
 * sells is the end of the case where it is not.
 *
 * <p>**It only ever approves.** The verdict is APPROVED or REFERRED, and
 * REFERRED means the posting goes to the human queue exactly as it does today,
 * with the model's reason attached for whoever opens it. There is no automatic
 * refusal and the column cannot store one: refusing closes the door on a
 * paying customer, which is a decision a person takes. Paying buys a faster
 * read, never a gentler one -- the rule is decision 4 of
 * `docs/plano-starter.md` and the pricing page says it in those words.
 *
 * <p>**Everything uncertain refers.** A malformed answer, an HTTP failure, a
 * missing description, an empty verdict: all of them land in the human queue.
 * The failure mode of this class is "the posting waits", which is what would
 * have happened anyway, and never "the posting is published unread".
 *
 * <p>**Why it does not publish the posting itself.** Approving is not a status
 * update: it resolves the company, renders the description, builds a content
 * hash and either inserts a job or edits the live one without restarting its
 * promoted window. That logic is in the web app, is what the admin button
 * calls, and a second copy of it here would drift from the first one the day
 * either changed. This process holds the model credentials and the clock; the
 * web app holds what approval means. Same split, and the same shared secret,
 * as the CV retention sweep.
 */
@Service
@ConditionalOnProperty(name = "posting-review.enabled", havingValue = "true")
public class PostingReviewer {

    private static final Logger log = LoggerFactory.getLogger(PostingReviewer.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /**
     * What the model is asked.
     *
     * <p>Two questions, and deliberately not the one {@link
     * com.remoteroles.pipeline.normalize.ScopeReviewer} asks. That one reads a
     * scraped listing and decides whether it is really open worldwide, because
     * nobody said. Here the employer has already said, in a structured form
     * with no free text in it, so the question is whether the description they
     * pasted agrees with the box they ticked -- and separately whether this is
     * a job at all.
     *
     * <p>The list of things that are not restrictions is lifted from
     * ScopeReviewer's prompt, where every entry was earned by a wrong answer
     * in testing and the costliest -- working hours -- came out of an audit
     * that found 69 of 478 demoted listings had been demoted for nothing but a
     * schedule. A posting that says "Mon-Fri 07.00-16.00 CST" is a posting
     * about the clock, and referring it to a person for that would make
     * priority review slower than no review at all.
     */
    private static final String PROMPT = """
            You are checking a job posting that an employer submitted to a job board.

            The employer selected who may apply from a fixed list of options. They did
            not type it. What they selected is:

            %s

            Answer two questions about the posting text below.

            1. CONSISTENT -- does the posting's own text agree with what the employer
               selected above? It disagrees only where the text says the person must
               live in, reside in, be located in, be based in, or be legally able to
               work in some place that the selection does not allow.

               These do NOT disagree with anything:
               - a company headquarters, office address, or where colleagues sit
               - the territory, market or customers the job covers ("EMEA Account
                 Executive", "scale our LATAM region")
               - salary quoted in one currency, or a pay range labelled with a country
               - legal boilerplate about work authorisation "in the country where you
                 apply"
               - benefits available only to employees in one country
               - an equal-opportunity or privacy notice naming a jurisdiction
               - a preference rather than a requirement: "preferred", "ideally", "a
                 plus", "nice to have", "we'd love"
               - WORKING HOURS. A shift, a schedule, core hours, an overlap
                 requirement or availability in somebody's time zone says WHEN the
                 person works, never WHERE they live. "Mon-Fri 07.00-16.00 CST", "at
                 least 4 hours of overlap with US Eastern", "European timezone
                 availability" -- somebody in Manila can keep any of them. This holds
                 however hard the sentence sounds and however many hours are demanded.

               A time zone disagrees only where the posting puts the PERSON inside it:
               "you must be based in the EU timezone", "your location must be between
               UTC-4 and UTC+4".

            2. GENUINE -- is this a real job opening at a real employer? It is NOT if
               it is a training course, a franchise or investment opportunity, a
               recruitment pitch for a multi-level marketing scheme, an advertisement
               for a service, an empty template with nothing filled in, or a posting
               asking the applicant for money.

            If you are unsure of either answer, say false. A person will read it.

            Answer with JSON only, no other text:
            {"consistent": true|false, "genuine": true|false,
             "concern": "<one sentence saying what is wrong, or null>",
             "quote": "<the exact sentence from the posting that decides it, or null>"}

            POSTING TITLE: %s

            POSTING TEXT:
            %s
            """;

    private final JdbcClient db;
    private final GoogleServiceAccount account;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    // Static so the answer parser can be tested without a service-account key:
    // what the model concludes is judged against real postings, but the code that
    // reads its answer is where a mistake publishes something unread.
    private static final ObjectMapper json = new ObjectMapper();
    private final String endpoint;
    private final String webBaseUrl;
    private final String token;
    private final int batchSize;

    public PostingReviewer(
            JdbcClient db,
            @Value("${posting-review.key-file:${scope-review.key-file:}}") String keyFile,
            @Value("${posting-review.project:${scope-review.project:actually-remote}}") String project,
            @Value("${posting-review.location:global}") String location,
            @Value("${posting-review.model:gemini-3.1-flash-lite}") String model,
            // The web process on the compose network, not the public hostname.
            // This call has no business leaving the machine.
            @Value("${posting-review.web-url:http://web:3000}") String webBaseUrl,
            @Value("${posting-review.token:${INTERNAL_TASK_TOKEN:}}") String token,
            // A paying employer posting more than a handful of roles in one
            // minute is not a situation that exists; the cap is here so a bad
            // day cannot turn into an unbounded run of model calls.
            @Value("${posting-review.batch-size:10}") int batchSize) throws Exception {
        this.db = db;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
        this.token = token == null ? "" : token.trim();
        this.batchSize = batchSize;
        String host = "global".equals(location) ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        this.endpoint = "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(host, project, location, model);
    }

    public static boolean keyAvailable(String keyFile) {
        return keyFile != null && !keyFile.isBlank() && Files.isReadable(Path.of(keyFile));
    }

    /**
     * Every minute, because "minutes" is the promise on the pricing page.
     *
     * <p>A fixed delay rather than a cron: the interval is what matters, and a
     * run that takes longer than the gap must not have the next one land on
     * top of it.
     */
    @Scheduled(fixedDelayString = "${posting-review.interval-ms:60000}",
               initialDelayString = "${posting-review.initial-delay-ms:30000}")
    public void run() {
        reviewDue();
    }

    record Pending(long id, String title, String description, String eligibility) {}

    /**
     * Postings waiting on a paid plan, oldest first.
     *
     * <p>`expires_at` and not `status` alone, which is the rule the
     * subscriptions table states in its own comment: a subscription marked
     * ACTIVE whose date has passed grants nothing, so a job that failed to run
     * cannot hand anybody a free month of priority review.
     */
    List<Pending> due() {
        return db.sql("""
                select p.id, p.title, p.description_source,
                       p.geo_scope, p.geo_detail,
                       coalesce(array_to_string(p.geo_excluded, ', '), '') as excluded
                  from employer_postings p
                  join employer_subscriptions s on s.employer_id = p.employer_id
                 where p.status = 'PENDING'
                   and p.auto_reviewed_at is null
                   and s.status = 'ACTIVE'
                   and s.expires_at > now()
                 order by p.created_at
                 limit :batch
                """)
                .param("batch", batchSize)
                .query((rs, n) -> new Pending(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("description_source"),
                        eligibility(rs.getString("geo_scope"),
                                    rs.getString("geo_detail"),
                                    rs.getString("excluded"))))
                .list();
    }

    /** What the employer ticked, in the words the model is asked to judge against. */
    static String eligibility(String scope, String detail, String excluded) {
        String place = detail == null || detail.isBlank() ? "" : detail.trim();
        String base = switch (scope == null ? "" : scope) {
            case "WORLDWIDE" -> "Anyone, anywhere. No country requirement at all.";
            case "WORLDWIDE_EXCEPT" -> "Anyone, anywhere, except people in: " + excluded + ".";
            case "REGION" -> "Only people in these regions: " + place + ".";
            case "COUNTRY" -> "Only people in: " + place + ".";
            default -> "Not stated.";
        };
        if ("REGION".equals(scope) && excluded != null && !excluded.isBlank()) {
            return base + " Excluding: " + excluded + ".";
        }
        return base;
    }

    void reviewDue() {
        if (token.isEmpty()) {
            // Inert without the shared secret, like every other integration
            // here. The postings simply wait for a person, which is what they
            // did before this class existed.
            return;
        }
        List<Pending> pending = due();
        if (pending.isEmpty()) return;

        int published = 0;
        int referred = 0;
        for (Pending posting : pending) {
            Verdict verdict = judge(posting);
            boolean ok = send(posting.id(), verdict);
            if (!ok) {
                // The web app could not be told. Nothing is stamped, so this
                // posting is picked up again on the next pass rather than
                // being lost between the two processes.
                log.warn("posting review: could not record verdict for posting {}", posting.id());
                continue;
            }
            if (verdict.approved()) published++; else referred++;
        }
        log.info("posting review: {} published, {} referred to a person", published, referred);
    }

    record Verdict(boolean approved, String note) {
        static Verdict refer(String why) { return new Verdict(false, why); }
    }

    Verdict judge(Pending posting) {
        String text = posting.description() == null ? "" : posting.description();
        if (text.isBlank()) {
            return Verdict.refer("There is no description to read.");
        }
        String body = text.length() > 12_000 ? text.substring(0, 12_000) : text;

        String payload;
        try {
            ObjectNode part = json.createObjectNode()
                    .put("text", PROMPT.formatted(posting.eligibility(), posting.title(), body));
            ObjectNode turn = json.createObjectNode().put("role", "user");
            turn.set("parts", json.createArrayNode().add(part));

            ObjectNode config = json.createObjectNode()
                    .put("temperature", 0)
                    .put("maxOutputTokens", 300)
                    .put("responseMimeType", "application/json");

            ObjectNode request = json.createObjectNode();
            request.set("contents", json.createArrayNode().add(turn));
            request.set("generationConfig", config);
            payload = json.writeValueAsString(request);
        } catch (Exception e) {
            return Verdict.refer("It could not be asked: " + e.getMessage() + ".");
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
                return Verdict.refer("It could not run (HTTP " + response.statusCode() + ").");
            }
            return parse(response.body());
        } catch (Exception e) {
            return Verdict.refer("It could not run: " + e.getMessage() + ".");
        }
    }

    /**
     * Reads the model's answer, and refers on anything it cannot read.
     *
     * <p>Both booleans have to be present and true. A missing field is not
     * taken as a yes -- an answer that forgot to mention whether the posting
     * is a real job is exactly the answer a person should see.
     */
    static Verdict parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode textNode = root.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");
            if (textNode.isMissingNode()) {
                return Verdict.refer("It returned nothing to read.");
            }
            JsonNode answer = json.readTree(textNode.asText());

            JsonNode consistent = answer.get("consistent");
            JsonNode genuine = answer.get("genuine");
            if (consistent == null || !consistent.isBoolean()
                    || genuine == null || !genuine.isBoolean()) {
                return Verdict.refer("It gave an answer that could not be read.");
            }
            if (consistent.asBoolean() && genuine.asBoolean()) {
                return new Verdict(true, null);
            }

            String concern = answer.path("concern").asText("").trim();
            String quote = answer.path("quote").asText("").trim();
            StringBuilder note = new StringBuilder();
            // The note is read under a heading that already says where it came
            // from, so it starts at the finding. "Referred by the automated
            // check. The automated check thinks..." was what the queue showed
            // first, and a reviewer reading forty of those stops reading them.
            note.append(consistent.asBoolean()
                    ? "It doubts this is a genuine job opening."
                    : "The text appears to disagree with the eligibility the employer chose.");
            if (!concern.isEmpty() && !"null".equals(concern)) {
                note.append(' ').append(concern);
            }
            if (!quote.isEmpty() && !"null".equals(quote)) {
                note.append(" It quoted: “").append(quote).append("”");
            }
            return Verdict.refer(note.toString());
        } catch (Exception e) {
            return Verdict.refer("It gave an answer that could not be read.");
        }
    }

    /** Hands the verdict to the web app, which is where approval means something. */
    boolean send(long postingId, Verdict verdict) {
        try {
            ObjectNode payload = json.createObjectNode()
                    .put("postingId", postingId)
                    .put("verdict", verdict.approved() ? "APPROVED" : "REFERRED");
            if (verdict.note() != null) payload.put("note", verdict.note());

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(webBaseUrl + "/api/internal/review-posting"))
                            .header("x-internal-token", token)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("posting review: call to the web app failed: {}", e.toString());
            return false;
        }
    }
}
