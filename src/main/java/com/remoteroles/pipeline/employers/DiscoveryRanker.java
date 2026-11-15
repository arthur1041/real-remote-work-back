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
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Reads the shortlist the vectors produced, and scores it.
 *
 * <p><strong>The third step of C.2, and the only one that costs a model call
 * per person.</strong> The filter says who may; the vector says who looks
 * close; this says who actually answers the role, requirement by requirement.
 * The first two cut this one's bill: thirty summaries, not four hundred CVs.
 *
 * <p><strong>It reads the summary, not the PDF.</strong> The CV was read once,
 * when `SemanticIndexer` wrote the summary, and what the index knows about
 * somebody is that text. Sending the PDF again would cost thirty uploads to
 * produce an answer about the same facts, and would let the ranking claim
 * things the index cannot show the employer. The honest consequence is in the
 * copy: a verdict here quotes the summary, which is what this knows.
 *
 * <p><strong>The score is arithmetic, never the model's opinion.</strong> The
 * model answers MET / UNMET / UNKNOWN per requirement; the number is computed
 * here with the same weights `ApplicationScreener` uses, for the same reason
 * written there — a number a model invents is not comparable between two
 * profiles, nor between two readings of the same one, and cannot be opened to
 * see where it came from.
 */
@Service
@ConditionalOnProperty(name = "ranking.enabled", havingValue = "true")
public class DiscoveryRanker {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryRanker.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private static final String PROMPT = """
            Decide how well this person answers this role.

            For every requirement listed, answer MET, UNMET or UNKNOWN, and
            quote the words of the profile that justify it. UNKNOWN is the
            right answer when the profile simply does not say — it is not a
            polite UNMET, and guessing is worse than not knowing.

            Then write one sentence, at most 25 words, that an employer reads
            before opening anything: what this person brings to this role and
            what is missing. No praise, no recommendation, no "great fit".

            Answer as JSON only:
            {"verdicts":[{"requirement":1,"verdict":"MET","basis":"..."}],
             "summary":"..."}

            ROLE
            %s

            REQUIREMENTS
            %s

            PROFILE
            %s
            """;

    private final JdbcClient db;
    private final GoogleServiceAccount account;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String endpoint;
    private final String model;
    private final int batchSize;
    private final int maxAttempts;
    private final long paceMillis;
    private final int backoffSeconds;

    public DiscoveryRanker(
            JdbcClient db,
            @Value("${ranking.key-file:${scope-review.key-file:}}") String keyFile,
            @Value("${ranking.project:${scope-review.project:actually-remote}}") String project,
            @Value("${ranking.location:global}") String location,
            @Value("${ranking.model:gemini-3.1-flash-lite}") String model,
            @Value("${ranking.batch-size:10}") int batchSize,
            @Value("${ranking.max-attempts:3}") int maxAttempts,
            @Value("${ranking.pace-ms:5000}") long paceMillis,
            @Value("${ranking.backoff-seconds:30}") int backoffSeconds) throws Exception {
        this.db = db;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.model = model;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.paceMillis = paceMillis;
        this.backoffSeconds = backoffSeconds;
        String host = "global".equals(location) ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        this.endpoint =
                "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                        .formatted(host, project, location, model);
    }

    public static boolean keyAvailable(String keyFile) {
        return keyFile != null && !keyFile.isBlank() && Files.isReadable(Path.of(keyFile));
    }

    /**
     * Often, because somebody is waiting.
     *
     * <p>Unlike the index, this queue is filled by a person pressing a button
     * and watching a panel: the list is already on their screen, ordered by
     * the vectors, and these calls are what turn it into scores. A minute is
     * the outside edge of what that wait can be.
     */
    @Scheduled(fixedDelayString = "${ranking.interval-ms:20000}",
               initialDelayString = "${ranking.initial-delay-ms:30000}")
    public void run() {
        try {
            rankDue();
            finishRankings();
        } catch (Exception e) {
            log.warn("ranking pass failed: {}", e.toString());
        }
    }

    record Entry(long id, long rankingId, long postingId, int contentVersion,
                 long candidateId, String summary) {}

    record Requirement(long id, int position, String text, boolean essential) {}

    void rankDue() {
        List<Entry> due = db.sql("""
                select e.id, e.ranking_id, r.posting_id, r.content_version,
                       e.candidate_id, coalesce(d.summary, '') as summary
                  from discovery_ranking_entries e
                  join discovery_rankings r on r.id = e.ranking_id
                  left join candidate_digests d on d.candidate_id = e.candidate_id
                 where e.state = 'PENDING' and e.attempts < :max
                 order by e.ranking_id, e.seed_rank
                 limit :batch
                """)
                .param("max", maxAttempts)
                .param("batch", batchSize)
                .query((rs, n) -> new Entry(
                        rs.getLong("id"), rs.getLong("ranking_id"), rs.getLong("posting_id"),
                        rs.getInt("content_version"), rs.getLong("candidate_id"),
                        rs.getString("summary")))
                .list();

        int limited = 0;
        for (Entry entry : due) {
            try {
                rank(entry);
            } catch (RateLimited e) {
                if (++limited >= 3) return;
            }
            try {
                Thread.sleep(paceMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void rank(Entry entry) {
        /* Nothing to read: the person has no summary yet, because the indexer
           has not reached them. Skipped rather than failed, and skipped rather
           than guessed — they keep their place in the list from the vectors,
           and the panel shows them without a score instead of with a wrong
           one. */
        if (entry.summary().isBlank()) {
            db.sql("""
                    update discovery_ranking_entries
                       set state = 'SKIPPED', failure = 'no summary yet', evaluated_at = now()
                     where id = :id
                    """).param("id", entry.id()).update();
            return;
        }

        List<Requirement> requirements = db.sql("""
                select id, position, text, essential
                  from posting_requirements
                 where posting_id = :posting and content_version = :version
                 order by position
                """)
                .param("posting", entry.postingId())
                .param("version", entry.contentVersion())
                .query((rs, n) -> new Requirement(rs.getLong("id"), rs.getInt("position"),
                        rs.getString("text"), rs.getBoolean("essential")))
                .list();

        String role = db.sql("""
                select coalesce(
                  (select summary from posting_digests
                    where posting_id = :posting and content_version = :version),
                  (select title from employer_postings where id = :posting))
                """)
                .param("posting", entry.postingId())
                .param("version", entry.contentVersion())
                .query(String.class).optional().orElse("");

        /* No requirements extracted for this version of the role: the
           screener owns that step and runs on its own clock. The role summary
           alone is a weaker yardstick, so the entry waits rather than being
           scored against nothing — the list on screen is already usable. */
        if (requirements.isEmpty()) {
            db.sql("""
                    update discovery_ranking_entries
                       set attempts = attempts + 1, failure = 'no requirements yet'
                     where id = :id
                    """).param("id", entry.id()).update();
            return;
        }

        StringBuilder list = new StringBuilder();
        for (Requirement r : requirements) {
            list.append(r.position()).append(". ")
                .append(r.essential() ? "[essential] " : "")
                .append(r.text()).append('\n');
        }

        Answer answer = ask(PROMPT.formatted(role, list, entry.summary()));
        db.sql("update discovery_ranking_entries set attempts = attempts + 1 where id = :id")
                .param("id", entry.id()).update();
        if (answer == null) {
            db.sql("""
                    update discovery_ranking_entries
                       set state = case when attempts >= :max then 'FAILED' else 'PENDING' end,
                           failure = 'model gave nothing back'
                     where id = :id
                    """).param("max", maxAttempts).param("id", entry.id()).update();
            return;
        }

        try {
            JsonNode root = json.readTree(stripFence(answer.text()));
            double points = 0;
            double possible = 0;
            boolean essentialMissed = false;
            for (Requirement r : requirements) {
                String verdict = verdictFor(root, r.position());

                // Mesma regra do ApplicationScreener, agora literalmente a
                // mesma: ver `FitScore`.
                possible += FitScore.weight(r.essential());
                points += FitScore.credit(verdict, r.essential());
                if (FitScore.capsTheScore(verdict, r.essential())) essentialMissed = true;
            }

            double score = FitScore.outOfTen(points, possible, essentialMissed);

            String summary = root.path("summary").asText("").trim();
            db.sql("""
                    update discovery_ranking_entries
                       set score = :score, summary = :summary, state = 'OK', failure = null,
                           model = :model, tokens_in = :in, tokens_out = :out,
                           evaluated_at = now()
                     where id = :id
                    """)
                    .param("score", score)
                    .param("summary", summary.isBlank() ? null : summary)
                    .param("model", model)
                    .param("in", answer.tokensIn())
                    .param("out", answer.tokensOut())
                    .param("id", entry.id())
                    .update();
        } catch (Exception e) {
            log.warn("ranking: entry {} unreadable: {}", entry.id(), e.toString());
            db.sql("""
                    update discovery_ranking_entries
                       set state = case when attempts >= :max then 'FAILED' else 'PENDING' end,
                           failure = 'answer could not be read'
                     where id = :id
                    """).param("max", maxAttempts).param("id", entry.id()).update();
        }
    }

    /** A ranking is done when nothing in it is still waiting. */
    void finishRankings() {
        db.sql("""
                update discovery_rankings r
                   set state = 'OK', finished_at = now()
                 where r.state = 'PENDING'
                   and not exists (
                     select 1 from discovery_ranking_entries e
                      where e.ranking_id = r.id and e.state = 'PENDING')
                """).update();
    }

    record Answer(String text, int tokensIn, int tokensOut) {}

    static class RateLimited extends RuntimeException {}

    /** Models wrap JSON in a fence often enough that trimming it is not a hack. */
    /**
     * What the model said about one requirement, cleaned before it counts.
     *
     * <p>Separated from the scoring loop so it can be read on its own and
     * tested without a database or a model: everything it refuses is a way a
     * wrong answer could become a number.
     *
     * <p><strong>No quote, no claim.</strong> A verdict with nothing behind it
     * is a guess wearing the clothes of a finding, and the one that arrives
     * with no `basis` is almost always the one the model inferred. Same rule
     * the screening keeps.
     *
     * <p>A requirement the answer never mentions, or a verdict that is not one
     * of the three, both land on UNKNOWN — which `FitScore` is careful to
     * price at half rather than zero.
     */
    static String verdictFor(JsonNode root, int position) {
        String verdict = "UNKNOWN";
        for (JsonNode v : root.path("verdicts")) {
            if (v.path("requirement").asInt(-1) == position) {
                verdict = v.path("verdict").asText("UNKNOWN").toUpperCase();
                if (!"UNKNOWN".equals(verdict) && v.path("basis").asText("").isBlank()) {
                    verdict = "UNKNOWN";
                }
                break;
            }
        }
        if (!List.of("MET", "UNMET", "UNKNOWN").contains(verdict)) verdict = "UNKNOWN";
        return verdict;
    }

    static String stripFence(String text) {
        String out = text.trim();
        if (out.startsWith("```")) {
            int first = out.indexOf('\n');
            int last = out.lastIndexOf("```");
            if (first > 0 && last > first) out = out.substring(first + 1, last);
        }
        return out.trim();
    }

    Answer ask(String prompt) {
        try {
            ObjectNode part = json.createObjectNode();
            part.put("text", prompt);
            ArrayNode parts = json.createArrayNode();
            parts.add(part);
            ObjectNode content = json.createObjectNode();
            content.put("role", "user");
            content.set("parts", parts);
            ArrayNode contents = json.createArrayNode();
            contents.add(content);
            ObjectNode body = json.createObjectNode();
            body.set("contents", contents);
            ObjectNode config = json.createObjectNode();
            config.put("temperature", 0);
            config.put("maxOutputTokens", 900);
            config.put("responseMimeType", "application/json");
            body.set("generationConfig", config);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                log.info("ranking: rate limited, waiting {}s", backoffSeconds);
                try {
                    Thread.sleep(backoffSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new RateLimited();
            }
            if (response.statusCode() / 100 != 2) {
                log.warn("ranking: HTTP {} — {}", response.statusCode(),
                        response.body().length() > 300
                                ? response.body().substring(0, 300) : response.body());
                return null;
            }
            JsonNode root = json.readTree(response.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode p : root.path("candidates").path(0).path("content").path("parts")) {
                text.append(p.path("text").asText(""));
            }
            JsonNode usage = root.path("usageMetadata");
            return new Answer(text.toString().trim(),
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0));
        } catch (RateLimited e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("ranking: call failed: {}", e.toString());
            return null;
        }
    }
}
