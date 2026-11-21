package com.remoteroles.pipeline.employers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p><strong>Os critérios básicos são aritmética outra vez, desde 18 Nov
 * 2026.</strong> Em 17 Nov a nota passou a ser do modelo, para que um sênior
 * de Go não perdesse para um júnior que escreveu "Python" — e com isso nada
 * mais segurava o que não é habilidade. O modelo lia só o resumo do CV, nunca
 * o país, os anos, os idiomas nem o fuso que a pessoa declarou, e a única
 * lista real tinha um perfil MID do Uzbequistão com 5,0 numa vaga sênior. O
 * dono pediu o óbvio: quem quer LATAM com 15 anos não pode receber Oriente
 * Médio com pouca experiência, e um pouco abaixo do pedido desce na lista em
 * vez de sumir.
 *
 * <p>Então são três camadas. <em>Onde a pessoa pode trabalhar</em> é código:
 * a mesma regra do filtro da web, com as regiões expandidas por
 * `region_countries`, e quem está fora vai a zero. <em>Anos de
 * experiência</em> são código quando a pessoa declarou os seus e o requisito
 * diz um número: ninguém tem dez anos de Go com seis de carreira. O resto
 * — idioma, fuso, senioridade, diploma, e um "só LATAM" escrito na descrição
 * de uma vaga marcada como mundial — o modelo lê, agora com os fatos dos dois
 * lados na frente dele, e diz se é CONSTRAINT ou SKILL. Habilidade continua
 * julgamento do modelo; critério não atendido desconta por `adjust`, que é
 * onde os números estão e o que os testes leem.
 */
@Service
@ConditionalOnProperty(name = "ranking.enabled", havingValue = "true")
public class DiscoveryRanker {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryRanker.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** What is asked of the model lives in `FitJudge`, shared with the screening. */

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

    /**
     * Pontua uma lista inteira agora, em vez de dez a cada vinte segundos.
     *
     * <p><strong>Porque a lista tem que nascer pronta.</strong> A passada
     * agendada e sequencial e espera cinco segundos entre perfis -- bom para o
     * trabalho de fundo, que nao tem ninguem olhando. Quando alguem acaba de
     * gastar uma das cinco geracoes do mes, isso virava catorze perfis
     * aparecendo sem nota e se preenchendo na frente dele ao longo de mais de
     * um minuto. Medido em producao em 17 Nov 2026, na primeira lista vetorial
     * real: catorze entradas, setenta segundos so de pausa entre chamadas.
     *
     * <p>Aqui as leituras vao juntas, porque sao threads virtuais e o que elas
     * fazem e esperar rede. {@code AT_ONCE} e o freio: o limite do modelo e por
     * minuto, e disparar quarenta de uma vez troca a espera por 429 em todas
     * elas. Seis cabe, e catorze perfis saem em tres rodadas.
     *
     * <p>Nao substitui a passada agendada, que continua existindo para o que
     * esta rota nao alcanca: perfil que ainda nao tinha resumo quando a lista
     * foi montada e ganha um depois.
     */
    public Map<String, Object> rankNow(long rankingId) {
        List<Entry> due = dueFor(rankingId);
        if (due.isEmpty()) {
            finishRankings();
            return Map.of("scored", 0, "left", 0);
        }

        int scored = 0;
        for (int from = 0; from < due.size(); from += AT_ONCE) {
            List<Entry> slice = due.subList(from, Math.min(from + AT_ONCE, due.size()));
            List<Thread> threads = new ArrayList<>();
            for (Entry entry : slice) {
                Thread thread = Thread.ofVirtual().unstarted(() -> {
                    try {
                        rank(entry);
                    } catch (RateLimited e) {
                        /* O 429 ja esperou o backoff dentro de `ask`. Deixar
                           para a passada agendada e melhor do que insistir
                           aqui com alguem na frente da tela. */
                    } catch (Exception e) {
                        log.warn("ranking: entry {} failed now: {}", entry.id(), e.toString());
                    }
                });
                threads.add(thread);
                thread.start();
            }
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            scored += slice.size();
        }

        finishRankings();
        int left = db.sql("""
                select count(*) from discovery_ranking_entries
                 where ranking_id = :id and state = 'PENDING'
                """).param("id", rankingId).query(Integer.class).single();
        return Map.of("scored", scored, "left", left);
    }

    /** As entradas devidas de uma lista so, sem o teto da passada agendada. */
    private List<Entry> dueFor(long rankingId) {
        return db.sql("""
                select e.id, e.ranking_id, r.posting_id, r.content_version,
                       e.candidate_id, coalesce(d.summary, '') as summary
                  from discovery_ranking_entries e
                  join discovery_rankings r on r.id = e.ranking_id
                  left join candidate_digests d on d.candidate_id = e.candidate_id
                 where e.ranking_id = :id
                   and e.attempts < :max
                   and (
                     e.state = 'PENDING'
                     or (e.state = 'SKIPPED' and d.state = 'OK' and d.summary <> '')
                   )
                 order by e.seed_rank
                 limit :cap
                """)
                .param("id", rankingId)
                .param("max", maxAttempts)
                .param("cap", NOW_CAP)
                .query((rs, n) -> new Entry(
                        rs.getLong("id"), rs.getLong("ranking_id"), rs.getLong("posting_id"),
                        rs.getInt("content_version"), rs.getLong("candidate_id"),
                        rs.getString("summary")))
                .list();
    }

    /** Quantas leituras vao juntas. Ver `rankNow`. */
    private static final int AT_ONCE = 6;

    /** Teto de uma chamada sob demanda, para que ela termine. */
    private static final int NOW_CAP = 40;

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

        List<FitJudge.Requirement> requirements =
                FitJudge.requirementsFor(db, entry.postingId(), entry.contentVersion());
        String role = FitJudge.roleFor(db, entry.postingId(), entry.contentVersion());

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

        FitJudge.Facts facts = FitJudge.factsFor(db, entry.postingId(), entry.candidateId());
        List<FitJudge.Requirement> judged = FitJudge.judged(requirements, facts);

        /* Nobody here has applied, so there is no application to show -- and
           the section says "none" in the same words a reading of an
           application with nothing written in it says, so that the two
           questions are the same text. See `FitJudge.PROMPT`. */
        Answer answer = ask(FitJudge.prompt(role, facts, judged, entry.summary(),
                FitJudge.NO_APPLICATION));
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
            JsonNode root = json.readTree(FitJudge.stripFence(answer.text()));
            FitJudge.Judgement judgement = FitJudge.read(root, requirements, judged, facts);
            if (judgement == null) {
                db.sql("""
                        update discovery_ranking_entries
                           set attempts = attempts + 1, failure = 'no fit in the answer'
                         where id = :id
                        """).param("id", entry.id()).update();
                return;
            }
            if (judgement.ungrounded()) {
                /* Said in the log, because it is either a profile with nothing
                   to say or an answer this could not read, and only the text
                   tells the two apart. */
                log.info("ranking: entry {} grounded nothing; answer began {}", entry.id(),
                        answer.text().length() > 500 ? answer.text().substring(0, 500) : answer.text());
            }
            String summary = judgement.summary();
            db.sql("""
                    update discovery_ranking_entries
                       set score = :score, summary = :summary, state = 'OK', failure = null,
                           model = :model, tokens_in = :in, tokens_out = :out,
                           checks = cast(:checks as jsonb),
                           evaluated_at = now()
                     where id = :id
                    """)
                    .param("checks", judgement.trail())
                    .param("score", judgement.score())
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


    /** The lowest score the employer's list shows; the web reads the same 1. */
    static final double SHOWN_FLOOR = 1.0;

    /**
     * A ranking is done when nothing in it is still waiting.
     *
     * <p>And a ranking that is done with nobody at the floor or above is given
     * back: every entry was read, and the model found nothing on the board
     * that relates to the role. See V79. Entries that failed have no score and
     * do not count as "read" -- a list that failed to score is not a list that
     * found nobody, and it shows in the order of the words.
     */
    void finishRankings() {
        db.sql("""
                update discovery_rankings r
                   set state = 'OK', finished_at = now(),
                       refunded_at = case
                         when exists (select 1 from discovery_ranking_entries e
                                       where e.ranking_id = r.id and e.score is not null)
                          and not exists (select 1 from discovery_ranking_entries e
                                           where e.ranking_id = r.id and e.score >= :floor)
                         then now() end
                 where r.state = 'PENDING'
                   and not exists (
                     select 1 from discovery_ranking_entries e
                      where e.ranking_id = r.id and e.state = 'PENDING')
                """).param("floor", SHOWN_FLOOR).update();
    }

    record Answer(String text, int tokensIn, int tokensOut) {}

    static class RateLimited extends RuntimeException {}


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
            config.put("maxOutputTokens", 1400);
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
