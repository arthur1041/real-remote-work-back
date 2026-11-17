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

    /**
     * O que se pergunta ao modelo na descoberta, que não é o que se pergunta
     * na triagem.
     *
     * <p><strong>A diferença entre as duas coisas é a razão desta tela
     * existir.</strong> A triagem lê quem se candidatou e responde se a pessoa
     * atende o que o empregador escreveu; ali um requisito essencial não
     * atendido é motivo para descer, e a nota sai de aritmética sobre os
     * veredictos — ver `FitScore`, que a triagem continua usando.
     *
     * <p>A descoberta é o contrário: a pessoa não se candidatou, e o
     * empregador está procurando exatamente quem ele não acharia sozinho. Com
     * a aritmética da triagem, um sênior de dez anos de Go numa vaga de Python
     * levava `UNMET` no requisito essencial e tinha a nota <strong>travada em
     * 4,0 por regra</strong>, enquanto um júnior que escreveu "Python" no
     * perfil levava `MET` e subia acima dele. Isso é o oposto do que a
     * ferramenta promete, e foi o dono quem apontou, em 17 Nov 2026.
     *
     * <p>Então aqui a nota é do modelo, não da conta. Os veredictos continuam
     * sendo pedidos porque forçam a leitura a se apoiar no que está escrito —
     * um número sem base é palpite com roupa de medida —, mas quem decide o
     * lugar na lista é o julgamento sobre o perfil inteiro: senioridade, anos,
     * profundidade, trajetória, e o quanto do que a pessoa fez atravessa para
     * o que a vaga pede.
     */
    private static final String PROMPT = """
            Decide how well this person fits this role, as somebody who has
            not applied to it and may not know it exists.

            First, for every requirement listed, answer MET, UNMET or UNKNOWN
            and quote the words of the profile that justify it. UNKNOWN is the
            right answer when the profile simply does not say — it is not a
            polite UNMET, and guessing is worse than not knowing.

            Then give the fit as a number from 0 to 10, judging the whole
            profile and not the count of requirements met. What this means:

            - Seniority, years and the depth of what the person has actually
              built count. A strong senior whose experience is adjacent can be
              worth more to this role than a junior who names the exact tool
              and has a year of it.
            - Experience transfers. Somebody who has run production systems in
              one language carries most of that into another; a missing tool is
              a gap to learn, not a wall, and the summary should say which.
            - All else equal, direct experience with what the role names wins.
              Adjacency is not a free pass: it raises somebody who is strong,
              it does not rescue somebody who is thin.
            - A requirement the profile does not mention is unknown, not
              absent. Do not punish silence as though it were a denial.

            The scale, so that two people read on different days are
            comparable:
            10-9 would be interviewed today, little to teach.
            8-7  clearly able to do this role, with something to pick up.
            6-4  plausible, with a real gap named in the summary.
            3-1  a stretch, and the summary says why anyone would still look.
            0    nothing in this profile relates to this role.

            Then write one sentence, at most 25 words, that an employer reads
            before opening anything: what this person brings to this role and
            what is missing. No praise, no recommendation, no "great fit".

            Answer as JSON only:
            {"verdicts":[{"requirement":1,"verdict":"MET","basis":"..."}],
             "fit": 7.5,
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
                 where e.attempts < :max
                   and (
                     e.state = 'PENDING'
                     /*
                      * Or skipped for want of a summary that now exists.
                      *
                      * The two queues run on their own clocks: the indexer
                      * writes summaries every few minutes and this runs every
                      * twenty seconds, so a list built in the minutes after
                      * somebody signs up is a list of people with nothing to
                      * read yet. Skipping them is right -- a score invented
                      * from no material is worse than no score -- but leaving
                      * them skipped for ever is not, and that is what happened
                      * the first time the two were run together: every entry
                      * of every list came back `no summary yet` and nothing
                      * ever looked at them again. They keep their place from
                      * the vectors either way; this is only about whether the
                      * score arrives.
                      */
                     or (e.state = 'SKIPPED' and d.state = 'OK' and d.summary <> '')
                   )
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

        /* Coming back after a skip: the reason is no longer true, and a row
           that scores while still carrying "no summary yet" is a row nobody
           can read. */
        db.sql("""
                update discovery_ranking_entries
                   set state = 'PENDING', failure = null
                 where id = :id and state = 'SKIPPED'
                """).param("id", entry.id()).update();

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

            /* **A nota e do modelo, e os veredictos sao o que a sustenta.**
               
               Eram eles que produziam o numero, pela aritmetica do `FitScore`
               -- que a triagem continua usando, e com razao. Aqui isso punha
               um senior de dez anos atras de um junior que tivesse a palavra
               certa no perfil, porque um essencial UNMET travava a nota em 4,0
               sem olhar mais nada.
               
               Continuam sendo pedidos, e continuam sendo o que impede o numero
               de ser palpite: resposta que nao tocou em requisito nenhum nao
               ordena lista. */
            int grounded = 0;
            for (Requirement r : requirements) {
                if (!"UNKNOWN".equals(verdictFor(root, r.position()))) grounded++;
            }

            double score = fitFrom(root);
            if (score < 0) {
                db.sql("""
                        update discovery_ranking_entries
                           set attempts = attempts + 1, failure = 'no fit in the answer'
                         where id = :id
                        """).param("id", entry.id()).update();
                return;
            }
            if (grounded == 0 && requirements.size() > 1) {
                score = Math.min(score, 3.0);
            }

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
    /**
     * A nota que o modelo deu, ou -1 quando ele nao deu nenhuma.
     *
     * <p>Fora de 0..10 e resposta malformada e nao um extremo: um modelo que
     * responde 85 esta numa escala que ninguem pediu, e aceitar isso ordenaria
     * a lista por um engano.
     */
    static double fitFrom(JsonNode root) {
        JsonNode node = root.path("fit");
        double fit = node.isNumber() ? node.asDouble(-1)
                : node.isTextual() ? parseFit(node.asText()) : -1;
        if (fit < 0 || fit > 10) return -1;
        return Math.round(fit * 10) / 10.0;
    }

    private static double parseFit(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

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
