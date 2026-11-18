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

            The role has two kinds of requirement, and they are judged
            differently.

            CONSTRAINT: a condition the employer will not hire without, that
            no amount of skill makes up for. Where the person lives or may
            work, their timezone or working hours, the languages they speak,
            how many years of experience they have, the seniority level the
            role is for, a degree, licence or certification, the right to
            work somewhere, the contract or hours they are available for.

            SKILL: what the person knows how to do. Tools, languages of
            programming, domains, methods.

            Being marked [essential] does not make a skill a constraint:
            "[essential] experience with Kubernetes" is a SKILL. Years with a
            named tool or domain are part of that skill ("3+ years with Go"
            is a SKILL); years of experience overall, in the profession or at
            a level ("15+ years of experience", "10 years in sales") are a
            CONSTRAINT.

            First, for every requirement listed, give its kind, and answer
            MET, PARTIAL, UNMET or UNKNOWN, quoting the words of the profile
            or the line of CANDIDATE FACTS that justify it.
            - PARTIAL is close but short: 12 years against "15+", "upper
              intermediate" against "fluent", two hours outside the window.
            - UNKNOWN is the right answer when neither the profile nor the
              facts say. It is not a polite UNMET, and guessing is worse
              than not knowing.
            - CANDIDATE FACTS are what the person declared on their account.
              Use them: "country: EG" answers a requirement to live in Latin
              America (UNMET), "years: 6" answers "10+ years" (UNMET).

            Then give the fit as a number from 0 to 10.

            - Constraints come first. Somebody who misses an essential one is
              not a fit however strong they are, and the number must say so.
              Close-but-short on one is a real cost, not a footnote.
            - For skills, judge the whole profile, not the count. Seniority,
              years and the depth of what the person has actually built
              count. A strong senior whose experience is adjacent can be
              worth more than a junior who names the exact tool and has a
              year of it. A missing tool is a gap to learn, not a wall; the
              summary should say which.
            - All else equal, direct experience with what the role names wins.
              Adjacency raises somebody who is strong, it does not rescue
              somebody who is thin.
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
            what is missing, naming any constraint they miss. No praise, no
            recommendation, no "great fit".

            Answer as JSON only:
            {"verdicts":[{"requirement":1,"kind":"CONSTRAINT","verdict":"MET","basis":"..."}],
            where "requirement" is the number in front of it in the list, as a number.
             "fit": 7.5,
             "summary":"..."}

            ROLE
            %s

            ROLE FACTS
            %s

            REQUIREMENTS
            %s

            CANDIDATE FACTS
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

        Facts facts = factsFor(entry.postingId(), entry.candidateId());

        /* Os critérios que a vaga declara em campo e não em texto. O nível do
           título e o fuso pedido raramente viram requisito na extração, e são
           exatamente o que o empregador filtraria à mão; entram como
           requisitos essenciais a mais, julgados como os outros -- só quando
           nenhum requisito extraído já fala do mesmo assunto, senão o mesmo
           critério desconta duas vezes. */
        List<Requirement> judged = new ArrayList<>(requirements);
        int next = requirements.stream().mapToInt(Requirement::position).max().orElse(0) + 1;
        List<String> extracted = requirements.stream().map(Requirement::text).toList();
        String level = DiscoveryConstraints.seniorityRequirement(facts.title());
        if (level != null && !DiscoveryConstraints.coversSeniority(extracted)) {
            judged.add(new Requirement(-1, next++, level, true));
        }
        if (facts.timezone() != null && !facts.timezone().isBlank()
                && !DiscoveryConstraints.coversTimezone(extracted)) {
            judged.add(new Requirement(-1, next++, "Works in this timezone or hours: " + facts.timezone(), true));
        }

        StringBuilder list = new StringBuilder();
        for (Requirement r : judged) {
            list.append(r.position()).append(". ")
                .append(r.essential() ? "[essential] " : "")
                .append(r.text()).append('\n');
        }

        Answer answer = ask(PROMPT.formatted(role, facts.roleFacts(), list,
                facts.candidateFacts(), entry.summary()));
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
                /* Said in the log, because it is either a profile with nothing
                   to say or an answer this could not read, and only the text
                   tells the two apart. */
                log.info("ranking: entry {} grounded nothing; answer began {}", entry.id(),
                        answer.text().length() > 500 ? answer.text().substring(0, 500) : answer.text());
                score = Math.min(score, 3.0);
            }

            /* Os critérios, por cima do julgamento. Ver `DiscoveryConstraints`. */
            List<DiscoveryConstraints.Check> checks = new ArrayList<>();
            ArrayNode recorded = json.createArrayNode();
            for (Requirement r : judged) {
                String verdict = verdictFor(root, r.position());
                boolean constraint = r.id() < 0 || constraintFor(root, r.position());
                String source = "MODEL";
                /* Anos contra a carreira declarada. Quando a conta diz que não
                   dá -- seis anos de carreira não são dez de nada --, isso é
                   critério básico seja o requisito "10 anos de experiência" ou
                   "10 anos de Go". Quando a conta não diz nada, o tipo é o que
                   o modelo leu: anos de uma ferramenta são habilidade, e o
                   sênior sem Go numa vaga de Go volta a ser julgado inteiro,
                   que é a regra de 17 Nov 2026. */
                Integer years = DiscoveryConstraints.requiredYears(r.text());
                if (years != null) {
                    String code = DiscoveryConstraints.yearsVerdict(years, facts.years());
                    if ("PARTIAL".equals(code) || "UNMET".equals(code)) {
                        String combined = DiscoveryConstraints.combine(code, verdict);
                        if (!combined.equals(verdict)) source = "YEARS";
                        verdict = combined;
                        constraint = true;
                    }
                }
                checks.add(new DiscoveryConstraints.Check(
                        r.position(), r.text(), r.essential(), constraint, verdict, source));
                ObjectNode c = recorded.addObject();
                c.put("requirement", r.position());
                c.put("text", r.text());
                c.put("essential", r.essential());
                c.put("kind", constraint ? "CONSTRAINT" : "SKILL");
                c.put("verdict", verdict);
                c.put("source", source);
            }
            DiscoveryConstraints.Adjusted adjusted =
                    DiscoveryConstraints.adjust(score, facts.eligible(), checks);
            ObjectNode trail = json.createObjectNode();
            trail.put("modelFit", score);
            trail.put("eligible", facts.eligible());
            trail.set("checks", recorded);
            ArrayNode effects = trail.putArray("effects");
            adjusted.effects().forEach(effects::add);
            score = adjusted.score();

            String summary = root.path("summary").asText("").trim();
            if (!facts.eligible()) {
                summary = "Outside the role's location. " + summary;
            }
            db.sql("""
                    update discovery_ranking_entries
                       set score = :score, summary = :summary, state = 'OK', failure = null,
                           model = :model, tokens_in = :in, tokens_out = :out,
                           checks = cast(:checks as jsonb),
                           evaluated_at = now()
                     where id = :id
                    """)
                    .param("checks", json.writeValueAsString(trail))
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

    /**
     * What both sides declared in fields, as the model is shown it, and the
     * location check the code makes itself.
     *
     * <p>The eligibility is the web's own rule (`candidateFilter` in
     * `lib/discovery-fit.ts`) said again here, with regions expanded through
     * `region_countries`: a list built before that table existed, or by a
     * filter that one day drifts, still cannot put somebody outside the role's
     * geography above somebody inside it.
     */
    record Facts(String title, String timezone, Integer years, boolean eligible,
                 String roleFacts, String candidateFacts) {}

    Facts factsFor(long postingId, long candidateId) {
        return db.sql("""
                select p.title, p.geo_scope, p.geo_detail,
                       array_to_string(p.geo_excluded, ', ') as excluded,
                       p.timezone_requirement, p.employment_kind,
                       p.salary_min, p.salary_max, p.salary_currency, p.salary_period,
                       c.country, c.seniority, c.years_experience,
                       array_to_string(c.languages, ', ') as languages, c.timezone,
                       array_to_string(c.employment_kinds, ', ') as kinds,
                       c.salary_expectation, c.salary_currency as want_currency,
                       c.available_from,
                       (
                         (p.geo_scope in ('WORLDWIDE', 'WORLDWIDE_EXCEPT')
                          or (c.country is not null and exists (
                                select 1 from regexp_split_to_table(upper(coalesce(p.geo_detail, '')), '[^A-Z_]+') t
                                 where t <> ''
                                   and (t = upper(c.country)
                                        or exists (select 1 from region_countries rc
                                                    where rc.region = t and rc.country = upper(c.country))))))
                         and not (c.country is not null and exists (
                                select 1 from unnest(coalesce(p.geo_excluded, '{}'::text[])) x
                                 where upper(x) = upper(c.country)
                                    or exists (select 1 from region_countries rc
                                                where rc.region = upper(x) and rc.country = upper(c.country))))
                       ) as eligible
                  from employer_postings p, candidates c
                 where p.id = :posting and c.id = :candidate
                """)
                .param("posting", postingId)
                .param("candidate", candidateId)
                .query((rs, n) -> {
                    String scope = rs.getString("geo_scope");
                    String detail = rs.getString("geo_detail");
                    String excluded = rs.getString("excluded");
                    String where = "WORLDWIDE".equals(scope) ? "anywhere"
                            : "WORLDWIDE_EXCEPT".equals(scope) ? "anywhere except " + orNone(excluded)
                            : "only " + orNone(detail)
                              + (excluded == null || excluded.isBlank() ? "" : ", except " + excluded);
                    String salary = rs.getObject("salary_min") == null && rs.getObject("salary_max") == null
                            ? "not stated"
                            : "%s %s-%s per %s".formatted(orNone(rs.getString("salary_currency")),
                                    plain(rs.getObject("salary_min")), plain(rs.getObject("salary_max")),
                                    orNone(rs.getString("salary_period")).toLowerCase());
                    String role = """
                            title: %s
                            may be done from: %s
                            timezone or hours: %s
                            contract: %s
                            pay: %s
                            """.formatted(rs.getString("title"), where,
                            orNone(rs.getString("timezone_requirement")),
                            orNone(rs.getString("employment_kind")), salary);
                    Object years = rs.getObject("years_experience");
                    String candidate = """
                            country: %s
                            seniority: %s
                            years of experience: %s
                            languages: %s
                            timezone: %s
                            contract wanted: %s
                            pay expected: %s
                            available from: %s
                            """.formatted(orNone(rs.getString("country")),
                            orNone(rs.getString("seniority")),
                            years == null ? "not stated" : years.toString(),
                            orNone(rs.getString("languages")), orNone(rs.getString("timezone")),
                            orNone(rs.getString("kinds")),
                            rs.getObject("salary_expectation") == null ? "not stated"
                                    : rs.getObject("salary_expectation") + " "
                                      + orNone(rs.getString("want_currency")) + " (period not stated)",
                            rs.getObject("available_from") == null ? "not stated"
                                    : rs.getObject("available_from").toString());
                    return new Facts(rs.getString("title"), rs.getString("timezone_requirement"),
                            years == null ? null : ((Number) years).intValue(),
                            rs.getBoolean("eligible"), role, candidate);
                })
                .optional()
                .orElse(new Facts("", null, null, true, "not available", "not available"));
    }

    private static String orNone(String value) {
        return value == null || value.isBlank() ? "not stated" : value;
    }

    private static String plain(Object number) {
        if (number == null) return "?";
        return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
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

    /**
     * The requirement number a verdict is about: 3, "3", "3." or "#3 ...".
     * Since the answer gained a `kind` beside it, models have started writing
     * the number as text; a verdict that cannot be tied to a requirement is
     * one that counts for nothing, so this is lenient about the form and
     * strict about the number.
     */
    static int requirementOf(JsonNode v) {
        JsonNode n = v.path("requirement");
        if (n.isInt() || n.isLong()) return n.asInt();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*#?(\\d{1,3})\\b")
                .matcher(n.asText(""));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static String verdictFor(JsonNode root, int position) {
        String verdict = "UNKNOWN";
        for (JsonNode v : root.path("verdicts")) {
            if (requirementOf(v) == position) {
                verdict = v.path("verdict").asText("UNKNOWN").toUpperCase();
                if (!"UNKNOWN".equals(verdict) && v.path("basis").asText("").isBlank()) {
                    verdict = "UNKNOWN";
                }
                break;
            }
        }
        if (!List.of("MET", "PARTIAL", "UNMET", "UNKNOWN").contains(verdict)) verdict = "UNKNOWN";
        return verdict;
    }

    /**
     * Whether the model called a requirement a constraint. Anything it did not
     * label, or labelled with a word nobody asked for, is a skill: the
     * constraint is the half that costs points, and an unlabelled answer is
     * not evidence enough to charge them.
     */
    static boolean constraintFor(JsonNode root, int position) {
        for (JsonNode v : root.path("verdicts")) {
            if (requirementOf(v) == position) {
                return "CONSTRAINT".equalsIgnoreCase(v.path("kind").asText(""));
            }
        }
        return false;
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
