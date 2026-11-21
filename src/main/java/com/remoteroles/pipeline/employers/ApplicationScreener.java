package com.remoteroles.pipeline.employers;

import com.remoteroles.pipeline.normalize.Text;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

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
 * Reads each application against what the role actually asks for.
 *
 * <p>What the Growth plan sells. The inbox itself is free and stays free —
 * reading an application somebody chose to send costs nothing on any plan, and
 * so does marking it by hand. What is paid for is this: the CV, the profile
 * and the answers read against the requirements of that role, with a verdict
 * per requirement and the line it was taken from.
 *
 * <p><strong>Two readings, and the first one is per role rather than per
 * candidate.</strong> The requirements of a posting are extracted once from the
 * description its employer wrote, and every application to that posting is then
 * read against that list. A hundred applicants to one role cost one extraction
 * and a hundred readings, not two hundred.
 *
 * <p><strong>It decides nothing.</strong> No application is rejected here, no
 * candidate is written to, and nothing is hidden from the employer. The output
 * is an ordering and an explanation. That is principle 1 of
 * `docs/plano-growth.md`, and it is stronger here than in the posting review
 * because the person on the other end is a candidate.
 *
 * <p><strong>The direction of failure is chosen.</strong> Model down,
 * malformed answer, unreadable CV: the application arrives <em>without</em> a
 * reading, with the reason recorded, and the employer reads it the way they
 * read one before this existed. Never "did not match", never "discarded".
 *
 * <p><strong>What it costs, and what stops it costing more.</strong> One
 * reading is roughly 4,000 tokens in and 600 out — about two tenths of a cent
 * on `gemini-3.1-flash-lite`, the same model and the same credential the scope
 * and posting reviews already use. Four things bound it: the queue only sees
 * postings whose employer holds a live Growth subscription; each account has a
 * monthly ceiling, which the plan card states out loud; every input is
 * truncated; and a global daily cap sits above all of it. Every reading stores
 * the tokens it used, so the month's cost is a query rather than an estimate.
 * Since 20 Nov 2026 a reading is two calls, sometimes three: the verdicts, the
 * score from {@link FitJudge} (a short prompt over a summary, not the PDF),
 * and -- only when the index holds no summary of this very CV -- the summary
 * itself. All of it lands in the same two token columns.
 */
@Service
@ConditionalOnProperty(name = "screening.enabled", havingValue = "true")
public class ApplicationScreener {

    private static final Logger log = LoggerFactory.getLogger(ApplicationScreener.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /**
     * The most of any one text that is sent to be read.
     *
     * <p>Twelve thousand characters, which is what the posting review already
     * uses, and for the same reason: it is longer than any honest job
     * description and it bounds what a pathological one can cost. A CV that
     * runs past it is read down to here rather than refused — the top of a CV
     * is where the recent work is.
     */
    private static final int MAX_CHARS = 12_000;

    private final JdbcClient db;
    private final GoogleServiceAccount account;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String endpoint;
    private final String webBaseUrl;
    private final String token;
    private final String model;
    private final int batchSize;
    private final int dailyCap;
    private final int readsAMonth;
    private final int readsADay;
    private final int maxAttempts;
    private final int retryAfterMinutes;
    private final int staleAfterMinutes;

    public ApplicationScreener(
            JdbcClient db,
            @Value("${screening.key-file:${scope-review.key-file:}}") String keyFile,
            @Value("${screening.project:${scope-review.project:actually-remote}}") String project,
            @Value("${screening.location:global}") String location,
            @Value("${screening.model:gemini-3.1-flash-lite}") String model,
            @Value("${screening.web-url:${posting-review.web-url:http://web:3000}}") String webBaseUrl,
            @Value("${screening.token:${INTERNAL_TASK_TOKEN:}}") String token,
            /** Applications read in one pass. Small, because the pass repeats. */
            @Value("${screening.batch-size:20}") int batchSize,
            /**
             * Readings across every account in a day.
             *
             * <p>The circuit breaker, not the budget. Nothing on the board
             * comes close: five roles accept applications and the busiest has
             * two. It exists for the case nobody modelled — a script, a loop, a
             * posting that somehow re-enters the queue — and it is set where
             * the day's spend stays under five dollars even then.
             */
            @Value("${screening.daily-cap:2000}") int dailyCap,
            /**
             * Readings one account gets in a calendar month.
             *
             * <p>A thousand, and it is on the plan card in those words, because
             * an allowance nobody can see is a surprise waiting to happen. At
             * two tenths of a cent a reading that is about two dollars against
             * a subscription of $39.90. Kept here in step with
             * `GROWTH_READS_A_MONTH` in `web/lib/plans.ts`, which is what the
             * card reads.
             */
            @Value("${screening.reads-a-month:1000}") int readsAMonth,
            /**
             * Readings one account gets in any rolling day.
             *
             * <p>A quarter of the month, and it is the burst that the monthly
             * ceiling does not bound: a thousand readings fit inside an hour.
             * The case it is for is somebody pointing a script at a paying
             * employer's role — fake accounts, real applications — which costs
             * the attacker nothing and costs that employer their month. The
             * busiest role on the board has had two applicants, so no honest
             * day comes near this; what passes it is read the next day, and
             * until then the application is on screen like any other.
             */
            @Value("${screening.reads-a-day:250}") int readsADay,
            /**
             * How many times one application may be read before giving up.
             *
             * <p>Three, and the number is a compromise between the two ways of
             * being wrong here. A failed reading used to be permanent — the
             * claim was `on conflict do nothing`, so a `FAILED` row made the
             * queue treat that person as already done, and a network blip cost
             * them their score for good. Measured on 1 Nov 2026 locally: 2 of
             * 11 readings failed, both the same candidate, both returning in
             * half a second against the ~2.3s a real reading takes — the call
             * erroring, not the model judging. Retrying for ever is the other
             * way: a CV that cannot be parsed would then cost a model call
             * every minute until somebody noticed the bill.
             */
            @Value("${screening.max-attempts:3}") int maxAttempts,
            /**
             * Minutes before a failed reading is tried again, multiplied by the
             * attempts already made — so 10, then 20, then stop.
             *
             * <p>Growing rather than fixed because the failures worth retrying
             * are the ones that clear on their own: a quota refusing for a
             * minute, a deploy restarting the model endpoint. Hammering those
             * at a fixed minute is how a transient failure becomes three.
             */
            @Value("${screening.retry-after-minutes:10}") int retryAfterMinutes,
            /**
             * Minutes after which a reading still `PENDING` is assumed dead.
             *
             * <p>The other half of the same defect, and the worse half: if the
             * process dies between claiming the row and writing the answer, the
             * row stays `PENDING` and the employer's screen says "Being read
             * against this role now" for ever, about a reading nobody is doing.
             * A `FAILED` row at least says something true. Fifteen minutes is
             * well past the 90-second call timeout and well past a full pass, so
             * nothing still running is ever mistaken for abandoned.
             */
            @Value("${screening.stale-after-minutes:15}") int staleAfterMinutes)
            throws Exception {
        this.db = db;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
        this.token = token == null ? "" : token.trim();
        this.model = model;
        this.batchSize = batchSize;
        this.dailyCap = dailyCap;
        this.readsAMonth = readsAMonth;
        this.readsADay = readsADay;
        this.maxAttempts = maxAttempts;
        this.retryAfterMinutes = retryAfterMinutes;
        this.staleAfterMinutes = staleAfterMinutes;
        String host = "global".equals(location) ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        this.endpoint = "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(host, project, location, model);
    }

    public static boolean keyAvailable(String keyFile) {
        return keyFile != null && !keyFile.isBlank() && Files.isReadable(Path.of(keyFile));
    }

    /**
     * Every minute, like the posting review beside it.
     *
     * <p>A fixed delay rather than a cron, so a pass that runs long cannot have
     * the next one land on top of it.
     */
    @Scheduled(fixedDelayString = "${screening.interval-ms:60000}",
               initialDelayString = "${screening.initial-delay-ms:45000}")
    public void run() {
        try {
            extractDue();
            screenDue();
            rejudgeDue();
        } catch (Exception e) {
            // A pass that throws must not kill the schedule: the next minute is
            // a free retry, and the alternative is a reader that stops silently.
            log.warn("screening pass failed: {}", e.toString());
        }
    }

    /* ------------------------------------------------------- requirements */

    record Role(long postingId, int contentVersion, String title, String description,
                String employmentKind) {}

    /**
     * Roles whose requirements have not been read at their current text.
     *
     * <p>Gated on the subscription in the query itself, by date and not by
     * status alone — the rule the subscriptions table states in its own
     * comment, and the reason a cancelled account stops costing anything the
     * day its period ends rather than whenever somebody notices.
     */
    List<Role> rolesDue() {
        return db.sql("""
                select p.id, p.content_version, p.title, p.description_source,
                       coalesce(p.employment_kind, '') as employment_kind
                  from employer_postings p
                  join employer_subscriptions s on s.employer_id = p.employer_id
                 where p.status = 'APPROVED'
                   /* Toda vaga do assinante, e não só as que recebem
                      candidatura aqui.
                      
                      Esta linha dizia `and p.apply_via_platform is true`,
                      pela razão de que uma vaga que manda a pessoa para o
                      site do employer não tem o que ler. Isso deixou de ser
                      verdade: antes de mandar embora, o site pede uma
                      confirmação curta e guarda uma candidatura REFERRED.
                      Mantê-la excluía da funcionalidade metade das vagas de
                      quem paga por ela -- e os requisitos da vaga são lidos
                      uma vez só, então o custo de extrair os de uma vaga que
                      nunca receber ninguém é uma chamada, não uma por
                      candidatura. */
                   and s.plan = 'GROWTH'
                   and s.status in ('ACTIVE', 'CANCELLED')
                   and s.expires_at > now()
                   /* Ainda não perguntada nesta versão -- ou perguntada sem
                      resposta.
                      
                      A pergunta é sobre a vaga e não sobre as linhas que
                      sobraram, porque "nenhum requisito" é uma resposta
                      legítima e não deixa linha nenhuma: o prompt manda
                      devolver lista vazia quando a vaga não exige nada, e o
                      requisito de campo só existe se o employer escolheu um
                      tipo de contratação, que é opcional. Enquanto esta
                      condição olhava as linhas, uma vaga assim ficava devida
                      para sempre e o modelo era perguntado uma vez por minuto
                      -- 1.440 vezes por dia, sem teto que segurasse, porque o
                      disjuntor conta leituras de candidatura e não extrações.
                      Reproduzido em 16 Nov 2026.
                      
                      `requirements_version` só é escrita quando houve
                      resposta, então uma extração que falhou continua devida e
                      é tentada na passada seguinte -- um minuto de
                      indisponibilidade deixa de custar uma vaga lida contra
                      requisito nenhum até alguém editá-la. */
                   and p.requirements_version is distinct from p.content_version
                 order by p.created_at
                 limit :batch
                """)
                .param("batch", batchSize)
                .query((rs, n) -> new Role(
                        rs.getLong("id"),
                        rs.getInt("content_version"),
                        rs.getString("title"),
                        rs.getString("description_source"),
                        rs.getString("employment_kind")))
                .list();
    }

    static final String REQUIREMENTS_PROMPT = """
            You are reading a job posting so that applications to it can be \
            checked against what it asks for.

            List the requirements it states. Each one must be a single short \
            phrase, in the posting's own terms, that a reader could check \
            against a CV: "5+ years in backend engineering", "fluent English", \
            "overlap with 09:00-13:00 CET", "has shipped a payment integration".

            Rules:
            - Only what the posting actually says. Never infer a requirement \
            from the job title, from the industry, or from what roles like this \
            usually want.
            - Mark a requirement essential only where the posting itself treats \
            it as mandatory. "Nice to have", "a plus", "bonus" are not essential.
            - Never list anything about age, sex, race, religion, nationality, \
            marital or family status, disability, or a photograph. If the \
            posting asks for one of those, leave it out.
            - At most 12. If it states more, keep the ones it leads with.
            - If it states none, return an empty list.

            Answer as JSON: {"requirements":[{"text":"...","essential":true}]}

            TITLE: %s

            POSTING:
            %s
            """;

    void extractDue() {
        for (Role role : rolesDue()) {
            try {
                extract(role);
                /* And, if the edit did not change what the role asks for, the
                   readings that already answered that question. See
                   `carryForward`: this is the difference between a typo
                   costing one model call and costing one per applicant. */
                int carried = carryForward(role);
                if (carried > 0) {
                    log.info("screening: posting {} v{} reused {} readings from the previous version",
                            role.postingId(), role.contentVersion(), carried);
                }
            } catch (Exception e) {
                log.warn("requirements for posting {} failed: {}", role.postingId(), e.toString());
            }
        }
    }

    /**
     * The readings of the previous version, when the new version asks the same.
     *
     * <p><strong>Why this exists.</strong> The queue is "an application with no
     * reading <em>at this version of the text</em>", and `content_version`
     * rises on every save of the posting — including a save that changed
     * nothing, which is deliberate, because otherwise a referral could be
     * walked past by pressing save again. What was not deliberate: on 15 Nov
     * 2026, end to end, one edited line in a description sent every
     * application to that posting back to the model. On a role with two
     * applicants that is two readings; on a role with two hundred it is two
     * hundred, about 38 cents and a fifth of the subscriber's monthly
     * allowance, spent without anything on screen having offered the trade.
     *
     * <p><strong>What it compares.</strong> The requirement set itself — text,
     * the essential flag, where it came from and which field answers it, in
     * order. Not the description: an employer who rewrites three paragraphs
     * and still asks for exactly the same five things has not changed the
     * question, and the stored verdicts, each with the line they were taken
     * from, still answer it. A changed requirement set re-reads as before,
     * because then the question really is different.
     *
     * <p><strong>What a copy is worth.</strong> Tokens zero, because none were
     * spent, and `carried_from` set, because the month's bill is the sum of
     * the rows that did spend. The subscriber's monthly ceiling counts only
     * rows with no `carried_from` for the same reason: a copy cost nothing
     * and must not take one of their thousand.
     *
     * @return how many readings were reused.
     */
    int carryForward(Role role) {
        Integer previous = db.sql("""
                select max(content_version) from posting_requirements
                 where posting_id = :posting and content_version < :version
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (previous == null) return 0;

        boolean same = Boolean.TRUE.equals(db.sql("""
                select coalesce(
                    (select array_agg(position || '|' || text || '|' || essential
                                      || '|' || coalesce(source, '') || '|' || coalesce(field_code, '')
                                      order by position)
                       from posting_requirements
                      where posting_id = :posting and content_version = :version)
                    is not distinct from
                    (select array_agg(position || '|' || text || '|' || essential
                                      || '|' || coalesce(source, '') || '|' || coalesce(field_code, '')
                                      order by position)
                       from posting_requirements
                      where posting_id = :posting and content_version = :previous)
                , false)
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .param("previous", previous)
                .query(Boolean.class)
                .single());
        if (!same) return 0;

        /* The readings first. Only the ones that finished: a FAILED or a
           PENDING row from the old version is not an answer, and copying it
           forward would hide the fact that this application was never read. */
        int copied = db.sql("""
                insert into application_screenings
                      (application_id, content_version, state, score, summary, model,
                       attempts, tokens_in, tokens_out, requested_at, evaluated_at, carried_from,
                       fit_checks)
                select g.application_id, :version, 'OK', g.score, g.summary, g.model,
                       0, 0, 0, now(), now(), g.id, g.fit_checks
                  from application_screenings g
                  join job_applications a on a.id = g.application_id
                 where a.posting_id = :posting
                   and g.content_version = :previous
                   and g.state = 'OK'
                   and not exists (
                       select 1 from application_screenings x
                        where x.application_id = g.application_id
                          and x.content_version = :version
                   )
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .param("previous", previous)
                .update();
        if (copied == 0) return 0;

        /* Then the verdicts, matched requirement to requirement by position --
           which is safe precisely because the sets were just proved identical.
           `disputed_at` travels too: an employer who marked a verdict wrong
           said so about a judgement that has not changed. */
        db.sql("""
                insert into application_requirement_verdicts
                      (screening_id, requirement_id, verdict, basis, basis_source, disputed_at)
                select n.id, rn.id, v.verdict, v.basis, v.basis_source, v.disputed_at
                  from application_screenings n
                  join application_screenings o on o.id = n.carried_from
                  join application_requirement_verdicts v on v.screening_id = o.id
                  join posting_requirements ro on ro.id = v.requirement_id
                  join posting_requirements rn
                    on rn.posting_id = ro.posting_id
                   and rn.content_version = :version
                   and rn.position = ro.position
                  join job_applications a on a.id = n.application_id
                 where a.posting_id = :posting
                   and n.content_version = :version
                   and n.carried_from is not null
                   and not exists (
                       select 1 from application_requirement_verdicts w
                        where w.screening_id = n.id and w.requirement_id = rn.id
                   )
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .update();
        return copied;
    }

    /**
     * O que esta vaga exige, extraído uma vez por versão -- e não por versão.
     *
     * <p><strong>Antes de perguntar, verifica se já perguntou isto.</strong>
     * Medido em 16 Nov 2026: uma vaga teve o título editado, a descrição
     * intacta, e as três candidaturas foram relidas a preço cheio. O modelo
     * havia reescrito um requisito de nove -- "Professional English" virou
     * "Professional English, written and spoken" -- e o {@link #carryForward},
     * que compara os conjuntos como texto, viu dois conjuntos diferentes.
     *
     * <p>A raiz não era a comparação, era extrair de novo. Um modelo não é
     * determinístico; pedir duas vezes a mesma coisa devolve duas redações, e
     * a segunda invalida todas as leituras guardadas. Se a descrição e o tipo
     * de contratação não mudaram, a pergunta não mudou: os requisitos da
     * versão anterior são copiados e nada é perguntado. Isso economiza a
     * chamada da extração e, por ser idêntico byte a byte, é o que faz o
     * carry-forward disparar -- antes disto ele dependia de o modelo repetir-se
     * por acaso.
     *
     * <p>O título fica fora do hash. Ele entra no prompt como contexto, mas
     * quem decide o que se exige é a descrição, e deixá-lo dentro faria um
     * erro de digitação no título custar uma leitura por candidatura.
     */
    void extract(Role role) {
        if (copyRequirements(role)) return;

        List<String[]> found = new ArrayList<>();

        /**
         * Se o que vai ser gravado é uma resposta ou a falta de uma.
         *
         * <p><strong>O que ele impede.</strong> Sem esta distinção, uma falha
         * passageira virava permanente: o modelo fora do ar deixava `found`
         * vazio, o requisito de FIELD era gravado assim mesmo -- carimbado com
         * o hash da descrição -- e toda versão seguinte do mesmo texto copiava
         * aquele conjunto de um requisito só e nunca mais perguntava. A vaga
         * passava a ser lida contra "trabalha em tempo integral" e mais nada,
         * com notas plausíveis e erradas, até alguém editar a descrição.
         *
         * <p>Antes da V74 a versão seguinte tentava de novo, então isto é um
         * buraco que a V74 abriu e que fecha aqui: só carimba o hash quando há
         * resposta de verdade. Sem carimbo, nada copia, e a próxima versão
         * pergunta outra vez -- que é o comportamento certo para uma falha de
         * rede.
         *
         * <p>Uma lista legitimamente vazia conta como resposta: o prompt manda
         * devolver vazio quando a vaga não exige nada, e isso é um fato sobre
         * a vaga, não uma falha.
         */
        boolean answered = true;

        String text = role.description() == null ? "" : role.description();
        if (!text.isBlank()) {
            String body = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
            Answer answer = ask(REQUIREMENTS_PROMPT.formatted(
                    role.title() == null ? "" : role.title(), body), null, 900);
            if (answer == null) {
                log.warn("requirements for posting {} not read: the model did not answer",
                        role.postingId());
                answered = false;
            } else {
                try {
                    JsonNode list = json.readTree(answer.text()).path("requirements");
                    for (JsonNode item : list) {
                        String line = item.path("text").asText("").trim();
                        if (line.isBlank() || line.length() > 200) continue;
                        found.add(new String[]{line, String.valueOf(item.path("essential").asBoolean(false))});
                        if (found.size() == 12) break;
                    }
                } catch (Exception e) {
                    log.warn("requirements for posting {} unreadable: {}", role.postingId(), e.toString());
                    answered = false;
                }
            }
        }

        /* Null quando não houve resposta, e é o null que faz tentar de novo:
           `rolesDue` só considera lida a versão que tem hash, e
           `copyRequirements` casa por igualdade, e em SQL nada é igual a
           null. */
        String hash = answered ? sourceHash(role) : null;

        /* O que uma tentativa sem resposta deixou para trás.
           
           Ela gravou o requisito de campo na posição 0, porque não havia
           nenhum do modelo antes dele. A retentativa põe os do modelo a partir
           da posição 0 e o de campo por último, de modo que sem esta limpeza o
           `on conflict do nothing` guardaria a linha velha na posição 0 e
           descartaria o primeiro requisito de verdade -- um conjunto
           embaralhado, que é pior do que o conjunto incompleto que ele
           substitui. Só linhas sem hash são apagadas: um conjunto bom nunca é
           tocado por isto. */
        db.sql("""
                delete from posting_requirements
                 where posting_id = :posting
                   and content_version = :version
                   and source_hash is null
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .update();

        int position = 0;
        for (String[] item : found) {
            insertRequirement(role, position++, item[0], Boolean.parseBoolean(item[1]), "MODEL", null, hash);
        }

        /* The one requirement that needs no model, and that the model must not
           be allowed to answer instead. Both sides of it are structured — the
           posting's `employment_kind` and the candidate's `employment_kinds`,
           from the same fixed list — so the answer is a set test rather than a
           judgement, and it is free, reproducible and checkable by query.
           Added last so it reads after what the description itself said. */
        if (role.employmentKind() != null && !role.employmentKind().isBlank()) {
            insertRequirement(role, position,
                    requirementForKind(role.employmentKind()),
                    true, "FIELD", "EMPLOYMENT_KIND", hash);
        }

        /* Por último, e só se houve resposta: é isto que tira a vaga da fila.
           Escrito mesmo quando não sobrou requisito nenhum, porque "nenhum" é
           uma resposta -- ver a condição em `rolesDue`. */
        if (answered) {
            db.sql("""
                    update employer_postings
                       set requirements_version = :version
                     where id = :posting
                    """)
                    .param("version", role.contentVersion())
                    .param("posting", role.postingId())
                    .update();
        }
    }

    /**
     * O que gerou um conjunto de requisitos, para a próxima versão reconhecer.
     *
     * <p>Descrição e tipo de contratação, separados por um byte que nenhum dos
     * dois contém -- sem ele, mover uma palavra da ponta de um para o começo do
     * outro daria o mesmo hash. Ver {@link #extract} para por que o título não
     * está aqui.
     *
     * <p><strong>0x01 e não 0x00.</strong> O separador óbvio seria o byte
     * nulo, e ele torna este hash impossível de reproduzir em SQL: o tipo
     * `text` do Postgres recusa o caractere nulo, e `chr(0)` é um erro. Isso
     * importa porque a V74 precisa carimbar as linhas que já existem com o
     * hash do texto de que saíram -- sem esse backfill, no primeiro minuto
     * depois do deploy toda vaga de assinante pareceria nunca ter sido lida e
     * seria extraída de novo, com as releituras que vêm junto.
     */
    static String sourceHash(Role role) {
        String body = (role.description() == null ? "" : role.description())
                + '\u0001'
                + (role.employmentKind() == null ? "" : role.employmentKind());
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sum);
        } catch (NoSuchAlgorithmException e) {
            /* SHA-256 é obrigatório em toda JVM. Se faltasse, o certo é
               extrair de novo e não travar a fila. */
            throw new IllegalStateException(e);
        }
    }

    /**
     * Copia os requisitos da última versão que saiu do mesmo texto.
     *
     * @return true quando copiou, e então nada precisa ser perguntado.
     */
    private boolean copyRequirements(Role role) {
        String hash = sourceHash(role);
        Integer previous = db.sql("""
                select max(content_version) from posting_requirements
                 where posting_id = :posting
                   and content_version < :version
                   and source_hash = :hash
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .param("hash", hash)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (previous == null) return false;

        int copied = db.sql("""
                insert into posting_requirements
                  (posting_id, content_version, position, text, essential, source,
                   field_code, source_hash)
                select posting_id, :version, position, text, essential, source,
                       field_code, source_hash
                  from posting_requirements
                 where posting_id = :posting and content_version = :previous
                on conflict (posting_id, content_version, position) do nothing
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .param("previous", previous)
                .update();
        if (copied > 0) {
            log.info("screening: posting {} v{} asks the same as v{}, {} requirements reused",
                    role.postingId(), role.contentVersion(), previous, copied);
        }
        return copied > 0;
    }

    private void insertRequirement(Role role, int position, String text, boolean essential,
                                   String source, String fieldCode, String hash) {
        db.sql("""
                insert into posting_requirements
                  (posting_id, content_version, position, text, essential, source, field_code,
                   source_hash)
                values (:posting, :version, :position, :text, :essential, :source, :field, :hash)
                on conflict (posting_id, content_version, position) do nothing
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .param("position", position)
                .param("text", text)
                .param("essential", essential)
                .param("source", source)
                .param("field", fieldCode)
                .param("hash", hash)
                .update();
    }

    /** One requirement's answer, after the rules that are not the model's. */
    record Call(String verdict, String quote, String source) {}

    /**
     * What the reading actually records for one requirement.
     *
     * <p>Lifted out of the loop so it can be tested without a database, a
     * model or an application: everything here is a way a wrong answer could
     * otherwise become a verdict in front of an employer.
     *
     * <p><strong>A field answers for itself, and the model does not get a vote
     * on it.</strong> The candidate declared which regimes they work under and
     * the role declared the one it needs; comparing two declared facts is free,
     * reproducible, and stops the reading contradicting something both sides
     * stated.
     *
     * <p><strong>This application's answer beats the profile.</strong> The
     * profile says what somebody accepts in general; the form asks again, for
     * this role, prefilled. Somebody who ticked "full-time" while job hunting
     * may well take this one on contract, and the reverse happens just as
     * often. Reading the profile when there is an answer turns a general
     * preference into a statement about one role — and marks as unmet somebody
     * who would have said yes. The profile still answers for applications sent
     * before the question existed.
     *
     * <p><strong>No quote, no claim.</strong> A verdict with nothing behind it
     * is a guess wearing the clothes of a finding.
     */
    static Call decide(Requirement r, String verdict, String quote, String source,
                       String answerKind, String profileKinds) {
        if ("FIELD".equals(r.source()) && "EMPLOYMENT_KIND".equals(r.fieldCode())) {
            boolean fromAnswer = answerKind != null && !answerKind.isBlank();
            String declared = fromAnswer ? answerKind : (profileKinds == null ? "" : profileKinds);
            if (declared.isBlank()) {
                verdict = "UNKNOWN"; quote = ""; source = null;
            } else {
                boolean matches = false;
                for (String kind : declared.split(",")) {
                    if (r.text().contains(humanKind(kind.trim()))) { matches = true; break; }
                }
                verdict = matches ? "MET" : "UNMET";
                /* The quote says where it came from, because the two sentences
                   mean different things to whoever reads them: one is about
                   this role, the other is about a sign-up form. */
                quote = fromAnswer
                        ? "Asked on this application, they answered: " + humanKind(declared.trim())
                        : "Their profile says they work on: " + declared.replace(",", ", ");
                source = fromAnswer ? "ANSWER" : "PROFILE";
            }
        }

        if (!"UNKNOWN".equals(verdict) && quote.isBlank()) verdict = "UNKNOWN";
        if (!List.of("MET", "UNMET", "UNKNOWN").contains(verdict)) verdict = "UNKNOWN";
        if (source != null && !List.of("CV", "PROFILE", "ANSWER", "FIELD").contains(source)) {
            source = null;
        }
        return new Call(verdict, quote, source);
    }

    /**
     * O requisito de regime, escrito em inglês que se possa ler.
     *
     * <p>O artigo sai do som e não de uma constante: dos seis regimes, um
     * começa por vogal, e a frase era montada com "a" fixo. Uma vaga de
     * estágio publicada pelo formulário -- que oferece "Internship" -- gerava
     * "Works on a internship basis", e essa frase não fica escondida: ela é um
     * dos requisitos listados na tela do empregador, aparece na quebra
     * requisito a requisito de cada candidatura e vai no CSV exportado.
     *
     * <p>O teste de regime continua funcionando porque ele procura a palavra
     * dentro da frase, e a palavra não mudou -- ver o laço que compara os
     * regimes que o candidato aceita.
     */
    static String requirementForKind(String code) {
        String kind = humanKind(code);
        String article = "aeiou".indexOf(Character.toLowerCase(kind.charAt(0))) >= 0 ? "an" : "a";
        return "Works on " + article + " " + kind + " basis";
    }

    static String humanKind(String code) {
        return switch (code) {
            case "FULL_TIME" -> "full-time";
            case "PART_TIME" -> "part-time";
            case "CONTRACT" -> "contract";
            case "TEMPORARY" -> "temporary";
            case "INTERNSHIP" -> "internship";
            case "VOLUNTEER" -> "volunteer";
            default -> code.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    /* ----------------------------------------------------------- readings */

    record Candidate(long applicationId, long postingId, int contentVersion, long employerId,
                     Long resumeId, String profile, String answers, String kinds,
                     String answerKind) {}

    /* ------------------------------------------------------------ the score */

    /**
     * The score of one application, from the judge the discovery uses.
     *
     * <p><strong>Since 20 Nov 2026 the number is not arithmetic over this
     * reader's verdicts.</strong> It was, through `FitScore`, while the
     * discovery had moved to the model's judgement on 17 Nov; the end-to-end
     * test of 20 Nov found the same developer, with the same CV, at 4.0 on
     * his application and 1.5 in the discovery panel of the same role. The
     * owner's rule: the same material gives the same score. The verdicts this
     * reader asks for stay exactly as they were -- quoted, per requirement,
     * and what the inbox shows and the funnel groups by. Only the number
     * moved, to {@link FitJudge}.
     *
     * <p>Null when the model gave nothing usable; the caller decides what
     * that means for its row.
     */
    record Scored(double score, String trail, int tokensIn, int tokensOut) {}

    Scored judge(long applicationId, Supplier<byte[]> cv) throws Exception {
        record Inputs(long candidateId, long postingId, int contentVersion, String coverNote,
                      String answers, String custom, String account) {}
        Inputs in = db.sql("""
                select a.candidate_id, a.posting_id, p.content_version,
                       coalesce(a.cover_note, '') as cover_note,
                       coalesce(a.answers, '{}'::jsonb)::text as answers,
                       coalesce(a.custom_answers, '[]'::jsonb)::text as custom,
                       -- What the account already says, in the shape the form
                       -- prefills it in, so an answer left as it came is
                       -- recognised as the account speaking and not the
                       -- application. See `applicationText`.
                       jsonb_build_object(
                           'COUNTRY', c.country,
                           'TIMEZONE', c.timezone,
                           'SENIORITY', c.seniority,
                           'YEARS_EXPERIENCE', c.years_experience::text,
                           'SKILLS', array_to_string(c.skills, ', '),
                           'LANGUAGES', array_to_string(c.languages, ', '),
                           'EMPLOYMENT_KIND', array_to_string(c.employment_kinds, ','),
                           'SALARY_EXPECTATION', concat_ws(' ', c.salary_currency, c.salary_expectation::text),
                           'AVAILABLE_FROM', c.available_from::text
                       )::text as account
                  from job_applications a
                  join employer_postings p on p.id = a.posting_id
                  join candidates c on c.id = a.candidate_id
                 where a.id = :id
                """)
                .param("id", applicationId)
                .query((rs, n) -> new Inputs(rs.getLong("candidate_id"), rs.getLong("posting_id"),
                        rs.getInt("content_version"), rs.getString("cover_note"),
                        rs.getString("answers"), rs.getString("custom"), rs.getString("account")))
                .optional().orElse(null);
        if (in == null) return null;

        List<FitJudge.Requirement> requirements =
                FitJudge.requirementsFor(db, in.postingId(), in.contentVersion());
        if (requirements.isEmpty()) return null;
        String role = FitJudge.roleFor(db, in.postingId(), in.contentVersion());
        FitJudge.Facts facts = FitJudge.factsFor(db, in.postingId(), in.candidateId());
        List<FitJudge.Requirement> judged = FitJudge.judged(requirements, facts);

        Material material = materialFor(applicationId, in.candidateId(), cv);
        if (material == null) return null;
        String application = applicationText(in.coverNote(), json.readTree(in.answers()),
                json.readTree(in.custom()), json.readTree(in.account()));

        Answer answer = ask(FitJudge.prompt(role, facts, judged, material.text(), application),
                null, 1400, true);
        if (answer == null) return null;
        FitJudge.Judgement judgement = FitJudge.read(
                json.readTree(FitJudge.stripFence(answer.text())), requirements, judged, facts);
        if (judgement == null) return null;

        /* Which material the number came from, kept with the trail: when an
           application and a discovery entry disagree, this is the first thing
           anybody asks, and it is not recoverable afterwards. */
        ObjectNode trail = (ObjectNode) json.readTree(judgement.trail());
        trail.put("material", material.kind());
        trail.put("application", !application.isBlank());
        return new Scored(judgement.score(), json.writeValueAsString(trail),
                answer.tokensIn() + material.tokensIn(), answer.tokensOut() + material.tokensOut());
    }

    /** What the judge reads as the person, and where it came from. */
    record Material(String text, String kind, int tokensIn, int tokensOut) {}

    /**
     * The person as the judge reads them: the index's own summary when it
     * describes this very CV, a summary written now by the same words when it
     * does not.
     *
     * <p><strong>The reuse is the whole point.</strong> The discovery judges
     * {@code candidate_digests.summary}; an application sent with the same CV
     * must be judged from the same text, or "same material, same score" is
     * only true by luck. Same means the same file (by its hash, since an
     * application may carry a copy) and a summary that is current for the
     * profile (its fingerprint is the one the index would write today).
     *
     * <p>Anything else -- a different CV attached to this application, a
     * summary not yet written, somebody not in the index at all -- gets a
     * summary now, from {@link SemanticIndexer#candidateDigestPrompt}, the
     * same question the index asks. It is not stored as the person's digest:
     * it describes a CV that may not be their profile's.
     */
    Material materialFor(long applicationId, long candidateId, Supplier<byte[]> cv) {
        String indexed = db.sql("""
                select d.summary
                  from job_applications a
                  join candidate_digests d on d.candidate_id = a.candidate_id
                  left join candidate_resumes ar on ar.id = a.resume_id
                  left join candidate_resumes dr on dr.id = d.resume_id
                 where a.id = :id
                   and d.state = 'OK' and d.summary <> ''
                   and ar.sha256 is not distinct from dr.sha256
                   and exists (select 1 from candidate_digest_source s
                                where s.candidate_id = d.candidate_id
                                  and s.fingerprint = d.fingerprint)
                """)
                .param("id", applicationId)
                .query(String.class).optional().orElse(null);
        if (indexed != null) return new Material(indexed, "INDEX_SUMMARY", 0, 0);

        Answer written = ask(SemanticIndexer.candidateDigestPrompt(db, candidateId), cv.get(), 700, false);
        if (written == null && cv.get() != null) {
            // The same fallback the verdicts have: a PDF the model refuses
            // must not cost the whole reading.
            written = ask(SemanticIndexer.candidateDigestPrompt(db, candidateId), null, 700, false);
        }
        if (written == null) return null;
        if (SemanticIndexer.saysNothing(written.text())) {
            return new Material("No CV provided, and the profile says nothing to summarise.",
                    "NOTHING", written.tokensIn(), written.tokensOut());
        }
        return new Material(written.text().strip(), "WRITTEN_NOW", written.tokensIn(), written.tokensOut());
    }

    /** Fields the judge is never shown: who the person is, and links it cannot open. */
    static final Set<String> NOT_MATERIAL =
            Set.of("FULL_NAME", "EMAIL", "LINKEDIN", "PORTFOLIO", "COVER_NOTE");

    /**
     * What this application says that the account does not.
     *
     * <p><strong>Only what is new.</strong> The form prefills country,
     * seniority, years, skills, pay and the rest from the account, and most
     * people send them as they came. Shown to the judge anyway, they would
     * make every application's question differ from the discovery's by a
     * block of text that adds nothing -- and the score with it. So an answer
     * equal to what the account already says is left out (the account is in
     * CANDIDATE FACTS and in the summary already); an answer that changed is
     * kept, because it is the person telling this employer something.
     *
     * <p>The covering note and the answers to the employer's own questions are
     * always new: nothing else holds them. Empty, the whole thing is "", and
     * the judge reads the same "none" the discovery sends.
     */
    static String applicationText(String coverNote, JsonNode answers, JsonNode custom, JsonNode account) {
        List<String> lines = new ArrayList<>();
        if (coverNote != null && !coverNote.isBlank()) {
            lines.add("Covering note: " + cut(coverNote.strip(), 2000));
        }
        List<String> codes = new ArrayList<>();
        answers.fieldNames().forEachRemaining(codes::add);
        codes.sort(null);
        for (String code : codes) {
            if (NOT_MATERIAL.contains(code)) continue;
            String value = answers.path(code).asText("").strip();
            if (value.isBlank()) continue;
            if (sameAsAccount(code, value, account.path(code).asText(""))) continue;
            lines.add(code.toLowerCase(Locale.ROOT).replace('_', ' ') + ": " + cut(value, 300));
        }
        for (JsonNode pair : custom) {
            String q = pair.path("q").asText("").strip();
            JsonNode a = pair.path("a");
            String answer;
            if (a.isArray()) {
                List<String> picks = new ArrayList<>();
                a.forEach(x -> picks.add(x.asText("")));
                answer = String.join(" | ", picks);
            } else {
                answer = a.asText("");
            }
            if (q.isBlank() || answer.isBlank()) continue;
            lines.add("Q: " + cut(q, 300) + "\nA: " + cut(answer.strip(), 1500));
        }
        return String.join("\n", lines);
    }

    /** "USD 50,000" is the account's 50000 USD; an accepted contract is one the account lists. */
    static boolean sameAsAccount(String code, String answer, String account) {
        if (account == null || account.isBlank()) return false;
        if ("EMPLOYMENT_KIND".equals(code)) {
            for (String kind : account.split(",")) {
                if (kind.strip().equalsIgnoreCase(answer.strip())) return true;
            }
            return false;
        }
        return plain(answer).equals(plain(account));
    }

    private static String plain(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Readings that finished before the score moved to the judge, scored by it.
     *
     * <p>Their verdicts and sentence stay; only the number is replaced, so an
     * inbox does not hold scores from two different rules side by side. It
     * runs on its own small batch, after the new readings, and it is not a
     * reading: it takes nothing from the employer's monthly allowance, which
     * counts rows, and it creates none. One that cannot be judged keeps its
     * old score and is marked, so a profile the model will not answer about
     * is not asked again every minute for ever.
     */
    /**
     * Passes in which a re-score could not fetch a CV that exists, by
     * screening. In memory on purpose: it only has to stop a missing file from
     * being asked for every minute, and a restart giving three more tries is
     * no harm.
     */
    private final java.util.Map<Long, Integer> cvMisses = new java.util.concurrent.ConcurrentHashMap<>();

    void rejudgeDue() {
        record Due(long id, long applicationId, Long resumeId) {}
        List<Due> due = db.sql("""
                select g.id, g.application_id, a.resume_id
                  from application_screenings g
                  join job_applications a on a.id = g.application_id
                  join employer_postings p on p.id = a.posting_id
                 where g.state = 'OK'
                   and g.fit_checks is null
                   and g.content_version = p.content_version
                 order by g.id
                 limit :batch
                """)
                .param("batch", batchSize)
                .query((rs, n) -> new Due(rs.getLong("id"), rs.getLong("application_id"),
                        rs.getObject("resume_id") == null ? null : rs.getLong("resume_id")))
                .list();
        for (Due d : due) {
            try {
                byte[][] cv = new byte[1][];
                boolean[] missed = {false};
                Scored scored = judge(d.applicationId(), () -> {
                    if (cv[0] == null && d.resumeId() != null && !missed[0]) {
                        cv[0] = fetchCv(d.resumeId());
                        missed[0] = cv[0] == null;
                    }
                    return cv[0];
                });
                /* A CV that exists and did not arrive is not "no CV". Scored
                   anyway, the person would be judged from the profile alone,
                   for good, because of a network blip -- measured locally on
                   20 Nov 2026, when four re-scores ran while the site that
                   serves the files was down and all four read "nothing to
                   summarise". The old score stays until the file comes. */
                if (missed[0] && cvMisses.merge(d.id(), 1, Integer::sum) < 3) {
                    continue;
                }
                cvMisses.remove(d.id());
                if (scored == null) {
                    log.warn("screening: could not re-score screening {}; it keeps its old score", d.id());
                    db.sql("""
                            update application_screenings
                               set fit_checks = '{"rescore":"failed"}'::jsonb
                             where id = :id
                            """).param("id", d.id()).update();
                    continue;
                }
                db.sql("""
                        update application_screenings
                           set score = :score, fit_checks = cast(:trail as jsonb),
                               tokens_in = coalesce(tokens_in, 0) + :tin,
                               tokens_out = coalesce(tokens_out, 0) + :tout
                         where id = :id
                        """)
                        .param("score", scored.score())
                        .param("trail", scored.trail())
                        .param("tin", scored.tokensIn())
                        .param("tout", scored.tokensOut())
                        .param("id", d.id())
                        .update();
            } catch (Exception e) {
                log.warn("screening: re-scoring screening {} failed: {}", d.id(), e.toString());
            }
        }
    }

    List<Candidate> readingsDue() {
        return db.sql("""
                select a.id, a.posting_id, p.content_version, p.employer_id, a.resume_id,
                       -- O perfil em texto, montado aqui para que o leitor não
                       -- precise saber a forma da tabela de candidatos.
                       concat_ws(E'\\n',
                           'Headline: '   || coalesce(c.headline, '(not given)'),
                           'Seniority: '  || coalesce(c.seniority, '(not given)'),
                           'Years: '      || coalesce(c.years_experience::text, '(not given)'),
                           'Skills: '     || coalesce(array_to_string(c.skills, ', '), '(not given)'),
                           'Languages: '  || coalesce(array_to_string(c.languages, ', '), '(not given)'),
                           'Country: '    || coalesce(c.country, '(not given)'),
                           'Timezone: '   || coalesce(c.timezone, '(not given)')
                       ) as profile,
                       -- As respostas desta candidatura, as três formas que
                       -- elas têm. `a.answers` ficou de fora da primeira
                       -- versão e era a falta mais cara: são as perguntas do
                       -- catálogo que o employer escolheu para esta vaga --
                       -- fuso, senioridade, anos, pretensão, autorização de
                       -- trabalho -- respondidas para esta vaga. Ler o perfil
                       -- e ignorá-las é julgar a pessoa pelo cadastro quando
                       -- ela respondeu a pergunta.
                       concat_ws(E'\\n',
                           coalesce(a.cover_note, ''),
                           coalesce(a.answers::text, '{}'),
                           coalesce(a.custom_answers::text, '[]')
                       ) as answers,
                       coalesce(array_to_string(c.employment_kinds, ','), '') as kinds,
                       -- O regime que ela aceitou para ESTA vaga, perguntado
                       -- no formulário. Separado do resto porque não é texto
                       -- para o modelo ler: é a resposta de um campo de lista
                       -- fixa, e ela decide o requisito sozinha.
                       coalesce(a.answers->>'EMPLOYMENT_KIND', '') as answer_kind
                  from job_applications a
                  join employer_postings p on p.id = a.posting_id
                  join candidates c on c.id = a.candidate_id
                  join employer_subscriptions s on s.employer_id = p.employer_id
                 where s.plan = 'GROWTH'
                   and s.status in ('ACTIVE', 'CANCELLED')
                   and s.expires_at > now()
                   -- Uma candidatura retirada não é trabalho pendente, e lê-la
                   -- é gastar com quem já foi embora.
                   and a.status <> 'WITHDRAWN'
                   -- Só depois que os requisitos daquela versão existem: sem
                   -- eles não há contra o que ler.
                   and exists (
                       select 1 from posting_requirements r
                        where r.posting_id = p.id and r.content_version = p.content_version
                   )
                   -- Uma linha de leitura existe, e por isso esta
                   -- candidatura não é mais trabalho pendente -- a não ser que
                   -- aquela linha esteja morta. Duas formas de morrer:
                   -- `FAILED`, que já respondeu e respondeu errado, e
                   -- `PENDING` abandonada, cujo processo não existe mais. As
                   -- duas voltam para cá até o teto de tentativas; `OK` e
                   -- `SKIPPED` são finais e nunca voltam.
                   and not exists (
                       select 1 from application_screenings g
                        where g.application_id = a.id
                          and g.content_version = p.content_version
                          and not (
                              g.attempts < :attempts
                              and (
                                  (g.state = 'FAILED'
                                   and g.evaluated_at < now() - make_interval(
                                       mins => :backoff * g.attempts))
                               or (g.state = 'PENDING'
                                   and g.requested_at < now() - make_interval(
                                       mins => :stale))
                              )
                          )
                   )
                   -- O teto do assinante, no mês corrente. Conta só as
                   -- leituras que gastaram modelo: uma linha copiada de uma
                   -- versão anterior (`carried_from`) não custou chamada
                   -- nenhuma e não pode tomar uma das mil.
                   and (
                       select count(*) from application_screenings g2
                         join job_applications a2 on a2.id = g2.application_id
                         join employer_postings p2 on p2.id = a2.posting_id
                        where p2.employer_id = p.employer_id
                          and g2.requested_at >= date_trunc('month', now())
                          and g2.carried_from is null
                   ) < :reads
                   /* **O teto do dia, por conta.**
                      
                      O mensal sozinho não segura uma rajada: mil leituras
                      cabem numa hora, e uma enxurrada de candidaturas falsas
                      contra uma vaga de quem paga consome o mês inteiro antes
                      de alguém notar. Um quarto do mês por dia é mais do que
                      qualquer dia honesto precisa -- a vaga mais movimentada
                      do quadro recebeu duas candidaturas -- e transforma
                      "seu mês acabou numa hora" em "houve um dia estranho".
                      As candidaturas que passarem do teto não somem: são lidas
                      no dia seguinte, e até lá o employer as vê na tela do
                      mesmo jeito que via antes disto existir. */
                   and (
                       select count(*) from application_screenings g3
                         join job_applications a3 on a3.id = g3.application_id
                         join employer_postings p3 on p3.id = a3.posting_id
                        where p3.employer_id = p.employer_id
                          and g3.requested_at >= now() - interval '24 hours'
                          and g3.carried_from is null
                   ) < :readsADay
                   /* **Um endereço que nunca respondeu a um código não é
                      lido.**
                      
                      A candidatura chega, aparece na tela do employer e pode
                      ser respondida à mão como sempre; o que espera é a
                      leitura paga. A razão é o único ataque que fecha a conta
                      para quem ataca: criar contas de candidato em massa e
                      disparar candidaturas contra a vaga de um assinante
                      queima a cota dele e o nosso Vertex, e cada conta dessas
                      custa ao atacante um endereço de e-mail que funcione.
                      A descoberta já exige endereço confirmado para mostrar
                      alguém; isto põe a leitura na mesma régua. */
                   and c.email_verified_at is not null
                 order by a.created_at
                 limit :batch
                """)
                .param("reads", readsAMonth)
                .param("readsADay", readsADay)
                .param("attempts", maxAttempts)
                .param("backoff", retryAfterMinutes)
                .param("stale", staleAfterMinutes)
                .param("batch", batchSize)
                .query((rs, n) -> new Candidate(
                        rs.getLong("id"),
                        rs.getLong("posting_id"),
                        rs.getInt("content_version"),
                        rs.getLong("employer_id"),
                        rs.getObject("resume_id") == null ? null : rs.getLong("resume_id"),
                        rs.getString("profile"),
                        rs.getString("answers"),
                        rs.getString("kinds"),
                        rs.getString("answer_kind")))
                .list();
    }

    /**
     * Quanto do disjuntor do dia já foi gasto, no quadro inteiro.
     *
     * <p><strong>Leitura copiada não conta.</strong> O disjuntor existe para
     * limitar <em>gasto</em> -- está escrito assim onde ele é configurado, em
     * dólares por dia -- e uma linha nascida de {@link #carryForward} não
     * chamou o modelo nem custou um centavo: é a leitura anterior, copiada
     * porque os requisitos da vaga não mudaram.
     *
     * <p>Sem este filtro um employer que salvasse a mesma vaga duas vezes com
     * duzentas candidaturas gerava quatrocentas linhas num dia e derrubava o
     * disjuntor de todo o quadro, com um aviso no log culpando um teto que
     * nada tinha gasto. Os dois tetos por conta -- o do mês e o do dia -- já
     * excluíam o carregado desde a V73; este, que é de outra consulta, tinha
     * ficado para trás.
     */
    int readToday() {
        Integer n = db.sql("""
                select count(*) from application_screenings
                 where requested_at >= date_trunc('day', now())
                   and carried_from is null
                """).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    void screenDue() {
        int budget = dailyCap - readToday();
        if (budget <= 0) {
            log.warn("screening: daily cap of {} reached, nothing read this pass", dailyCap);
            return;
        }
        for (Candidate who : readingsDue()) {
            if (budget-- <= 0) return;
            try {
                screen(who);
            } catch (Exception e) {
                log.warn("screening application {} failed: {}", who.applicationId(), e.toString());
            }
        }
    }

    /** A leitura reivindicada, e em que tentativa ela está. */
    record Claim(long id, int attempts) {}

    record Requirement(long id, String text, boolean essential, String source, String fieldCode) {}

    List<Requirement> requirementsOf(long postingId, int contentVersion) {
        return db.sql("""
                select id, text, essential, source, coalesce(field_code, '') as field_code
                  from posting_requirements
                 where posting_id = :posting and content_version = :version
                 order by position
                """)
                .param("posting", postingId)
                .param("version", contentVersion)
                .query((rs, n) -> new Requirement(
                        rs.getLong("id"),
                        rs.getString("text"),
                        rs.getBoolean("essential"),
                        rs.getString("source"),
                        rs.getString("field_code")))
                .list();
    }

    static final String SCREENING_PROMPT = """
            You are checking one application against the requirements of one \
            role, so that the employer can see where this person stands on each \
            of them. You are not deciding anything and nobody is told your \
            answer but the employer.

            For every requirement, answer MET, UNMET or UNKNOWN, and quote the \
            words you took the answer from.

            Rules:
            - UNKNOWN is the answer whenever the material does not say. A CV \
            that is silent about something does not prove the absence of it. \
            Never answer UNMET because you did not find evidence; answer UNMET \
            only where the material says something that contradicts the \
            requirement.
            - Every MET and every UNMET needs a quote, copied exactly from the \
            CV, the profile or the answers. No quote means UNKNOWN.
            - Say where the quote came from: CV, PROFILE or ANSWER.
            - Judge the work, nothing else. Ignore age, sex, race, religion, \
            nationality, marital or family status, disability and photographs, \
            even where the material mentions them.
            - Then write one sentence for the employer, at most 20 words, \
            saying where this person is strong and where the evidence is \
            missing. No recommendation, no score, no "should interview".

            Answer as JSON: {"verdicts":[{"n":1,"verdict":"MET","quote":"...",\
            "source":"CV"}],"summary":"..."}

            REQUIREMENTS:
            %s

            PROFILE:
            %s

            WHAT THEY ANSWERED:
            %s
            """;

    void screen(Candidate who) {
        List<Requirement> requirements = requirementsOf(who.postingId(), who.contentVersion());
        if (requirements.isEmpty()) return;

        /* Reivindicar a leitura, seja ela a primeira ou outra tentativa.

           Era `on conflict do nothing` seguido de `orElse(-1)` e um `return`:
           qualquer linha existente, inclusive uma `FAILED`, fazia o leitor
           desistir daquela candidatura para sempre. Agora o conflito atualiza a
           linha quando ela está morta, pelas mesmas condições que a consulta da
           fila usa -- as duas têm de concordar, senão a fila devolve uma
           candidatura que a reivindicação recusa e a passagem gira em falso.

           `requested_at` é remarcado de propósito: é ele que diz "esta
           tentativa começou agora", e sem isso uma linha `PENDING` abandonada
           seria reivindicada de novo a cada passagem, três chamadas ao modelo
           em três minutos. O teto mensal conta linhas e não tentativas, então
           remarcar não dá ao employer uma leitura a menos; o que ele muda é o
           mês em que uma leitura virada à meia-noite é contada, e esse é o mês
           em que o trabalho foi de fato feito. */
        Claim claim = db.sql("""
                insert into application_screenings
                    (application_id, content_version, state, model, attempts)
                values (:application, :version, 'PENDING', :model, 1)
                on conflict (application_id, content_version) do update
                   set state = 'PENDING',
                       attempts = application_screenings.attempts + 1,
                       failure = null,
                       score = null,
                       fit_checks = null,
                       summary = null,
                       tokens_in = null,
                       tokens_out = null,
                       evaluated_at = null,
                       requested_at = now(),
                       model = :model
                 where application_screenings.attempts < :attempts
                   and (
                       (application_screenings.state = 'FAILED'
                        and application_screenings.evaluated_at < now() - make_interval(
                            mins => :backoff * application_screenings.attempts))
                    or (application_screenings.state = 'PENDING'
                        and application_screenings.requested_at < now() - make_interval(
                            mins => :stale))
                   )
                returning id, attempts
                """)
                .param("application", who.applicationId())
                .param("version", who.contentVersion())
                .param("model", model)
                .param("attempts", maxAttempts)
                .param("backoff", retryAfterMinutes)
                .param("stale", staleAfterMinutes)
                .query((rs, n) -> new Claim(rs.getLong("id"), rs.getInt("attempts")))
                .optional()
                .orElse(null);
        if (claim == null) return;
        long screeningId = claim.id();

        if (claim.attempts() > 1) {
            /* Os vereditos da tentativa anterior saem antes da próxima
               começar. Eles são gravados num laço dentro do `try`, e o
               `state = 'OK'` só vem depois dele, então uma exceção no meio
               deixa uma linha `FAILED` com vereditos parciais. O insert deles é
               `on conflict do nothing`, o que seria o pior resultado possível
               numa segunda tentativa: os vereditos velhos ficariam, calados, e a
               nota seria calculada a partir dos novos -- o número na tela
               discordando das linhas que o sustentam. */
            db.sql("delete from application_requirement_verdicts where screening_id = :id")
                    .param("id", screeningId)
                    .update();
            log.info("screening: attempt {} of {} for application {}",
                    claim.attempts(), maxAttempts, who.applicationId());
        }

        byte[] cv = who.resumeId() == null ? null : fetchCv(who.resumeId());

        StringBuilder list = new StringBuilder();
        for (int i = 0; i < requirements.size(); i++) {
            list.append(i + 1).append(". ").append(requirements.get(i).text())
                .append(requirements.get(i).essential() ? "  [essential]" : "").append('\n');
        }

        String prompt = SCREENING_PROMPT.formatted(
                list.toString(), cut(who.profile()), cut(who.answers()));
        Answer answer = ask(prompt, cv, 1200);

        /* Um anexo que o modelo recusa não derruba a leitura inteira.

           Era o que acontecia: a chamada ia com o PDF, o Vertex respondia
           `INVALID_ARGUMENT`, e a candidatura ficava sem leitura nenhuma --
           sem nota, sem veredito, sem frase -- embora o perfil e as respostas
           ao formulário estivessem perfeitamente legíveis ao lado. O caminho
           sem CV já existia e já funcionava: é por ele que passa quem se
           candidatou sem anexar nada.

           Medido em 1 Nov 2026: um CV de 69 bytes no banco local (um PDF de
           esqueleto, que o bucket tinha no lugar do arquivo que a linha
           declarava) fez as duas candidaturas daquele candidato falharem por
           inteiro, enquanto a candidatura ao lado, cujo CV voltou 404 e por
           isso nem foi anexado, foi lida e pontuou 5,7. Falhar porque o anexo
           chegou é pior do que falhar porque ele não chegou, e era o que o
           código fazia.

           A segunda chamada custa uma leitura a mais no pior caso, e só
           acontece quando havia PDF e a primeira falhou. O que ela não faz é
           esconder a diferença: a frase para o employer diz que o CV não pôde
           ser lido, porque uma nota montada sem o documento principal tem de
           se apresentar como tal -- ela ainda é a melhor resposta disponível,
           mas não é a mesma resposta. */
        boolean cvUnread = false;
        if (answer == null && cv != null) {
            log.warn("screening: application {} retried without its CV", who.applicationId());
            answer = ask(prompt, null, 1200);
            cvUnread = answer != null;
        }

        if (answer == null) {
            /* The cause, and only the cause. The screen that shows this adds
               "Nothing about this application has been assumed" itself, and
               while this string carried that sentence too the employer read
               it twice in a row -- seen end to end on 15 Nov 2026. One
               sentence, one owner. */
            fail(screeningId, "The model did not answer.");
            return;
        }

        try {
            JsonNode root = json.readTree(answer.text());
            String summary = root.path("summary").asText("").trim();
            /* Dito na frase que o employer lê primeiro, e não num campo que
               só existe no banco: ele está a ponto de comparar esta pessoa com
               outra que foi lida com o CV, e a comparação é injusta se a
               diferença não estiver à vista. */
            if (cvUnread) {
                /* A frase do modelo é encurtada antes, e não o conjunto
                   depois: a coluna corta em 300 e o aviso vai no fim, então
                   cortar o conjunto seria perder exatamente o aviso -- a
                   leitura voltaria a parecer completa por ter ficado comprida
                   demais para dizer que não era. */
                String notice = "Their CV could not be read \u2014 this is from the profile "
                        + "and the answers only.";
                summary = summary.isBlank() ? notice
                        : cut(summary, 300 - notice.length() - 4) + " (" + notice + ")";
            }
            JsonNode verdicts = root.path("verdicts");

            for (int i = 0; i < requirements.size(); i++) {
                Requirement r = requirements.get(i);
                String verdict = "UNKNOWN", quote = "", source = null;

                for (JsonNode v : verdicts) {
                    if (v.path("n").asInt(-1) != i + 1) continue;
                    verdict = v.path("verdict").asText("UNKNOWN").trim().toUpperCase(Locale.ROOT);
                    quote = v.path("quote").asText("").trim();
                    source = v.path("source").asText("").trim().toUpperCase(Locale.ROOT);
                    break;
                }

                Call call = decide(r, verdict, quote, source, who.answerKind(), who.kinds());
                verdict = call.verdict();
                quote = call.quote();
                source = call.source();

                db.sql("""
                        insert into application_requirement_verdicts
                          (screening_id, requirement_id, verdict, basis, basis_source)
                        values (:screening, :requirement, :verdict, :basis, :source)
                        on conflict (screening_id, requirement_id) do nothing
                        """)
                        .param("screening", screeningId)
                        .param("requirement", r.id())
                        .param("verdict", verdict)
                        .param("basis", quote.isBlank() ? null : cut(quote, 600))
                        .param("source", source)
                        .update();
            }

            /* A nota, do mesmo juiz da descoberta. Ver `judge`. Sem ela a
               leitura não termina: uma linha OK sem número aparece na caixa
               como 0,0, que é a pior leitura possível dita sem motivo. Falha,
               e a próxima tentativa refaz tudo -- os vereditos desta saem no
               começo dela, como sempre. */
            final byte[] attached = cvUnread ? null : cv;
            Scored scored = judge(who.applicationId(), () -> attached);
            if (scored == null) {
                fail(screeningId, "The model did not give a score.");
                return;
            }

            db.sql("""
                    update application_screenings
                       set state = 'OK', score = :score, summary = :summary,
                           tokens_in = :tin, tokens_out = :tout,
                           fit_checks = cast(:trail as jsonb),
                           evaluated_at = now()
                     where id = :id
                    """)
                    .param("score", scored.score())
                    .param("trail", scored.trail())
                    .param("summary", summary.isBlank() ? null : cut(summary, 300))
                    // O que esta leitura custou. A extração de requisitos é
                    // por vaga e não por candidatura, então ela não entra
                    // aqui: a coluna responde "quanto custou ler esta
                    // candidatura", e somar a leitura da vaga em cada uma
                    // contaria a mesma chamada dez vezes.
                    .param("tin", answer.tokensIn() + scored.tokensIn())
                    .param("tout", answer.tokensOut() + scored.tokensOut())
                    .param("id", screeningId)
                    .update();
        } catch (Exception e) {
            /* The parser's own message never reaches the employer's screen.
               It is a Jackson error about a byte offset: it tells the person
               reading applications nothing, and it is the kind of string that
               carries a fragment of whatever came back. Logged in full, shown
               as what it means. */
            log.warn("screening: unreadable answer for screening {}", screeningId, e);
            fail(screeningId, "The answer came back in a shape this could not read.");
        }
    }

    private void fail(long screeningId, String why) {
        db.sql("""
                update application_screenings
                   set state = 'FAILED', failure = :why, evaluated_at = now()
                 where id = :id
                """)
                .param("why", cut(why, 300))
                .param("id", screeningId)
                .update();
    }

    /* -------------------------------------------------------------- model */

    /**
     * The answer, and what it cost to get.
     *
     * <p>The counts travel with the text because they are only available
     * here: Vertex reports them on the response and nothing downstream can
     * recover them. They were being thrown away, and `tokens_in` and
     * `tokens_out` -- the columns whose whole purpose is to turn the plan's
     * cost estimate into a measurement -- had been null on every row since
     * the feature was written.
     */
    record Answer(String text, int tokensIn, int tokensOut) {}

    /**
     * Asks the model, with an optional PDF alongside the prompt.
     *
     * <p>Returns the answer, or null on anything that is not a clean
     * answer — which the caller turns into "no reading", never into a verdict.
     */
    Answer ask(String prompt, byte[] pdf, int maxOutputTokens) {
        return ask(prompt, pdf, maxOutputTokens, true);
    }

    /** As above; {@code jsonOut} false for the one prose answer, the summary. */
    Answer ask(String prompt, byte[] pdf, int maxOutputTokens, boolean jsonOut) {
        try {
            ArrayNode parts = json.createArrayNode();
            parts.add(json.createObjectNode().put("text", prompt));
            if (pdf != null && pdf.length > 0) {
                ObjectNode inline = json.createObjectNode();
                inline.put("mimeType", "application/pdf");
                inline.put("data", Base64.getEncoder().encodeToString(pdf));
                parts.add(json.createObjectNode().set("inlineData", inline));
            }
            ObjectNode turn = json.createObjectNode().put("role", "user");
            turn.set("parts", parts);

            ObjectNode config = json.createObjectNode()
                    .put("temperature", 0)
                    .put("maxOutputTokens", maxOutputTokens);
            if (jsonOut) config.put("responseMimeType", "application/json");

            ObjectNode request = json.createObjectNode();
            request.set("contents", json.createArrayNode().add(turn));
            request.set("generationConfig", config);

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .header("Authorization", "Bearer " + account.accessToken())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(90))
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                /* O corpo, e não só o código. A diferença entre "a cota
                   recusou por um minuto" e "este PDF não é um PDF" está só
                   nele, e ela decide se tentar de novo tem chance de dar outro
                   resultado. Medido em 1 Nov 2026: duas leituras falharam com
                   um "HTTP 400" pelado no log, o retry gastou as tentativas
                   para aprender nada, e descobrir a causa exigiu mudar o
                   código -- que é esta linha. */
                log.warn("screening model returned HTTP {}: {}",
                        response.statusCode(), cut(response.body(), 400));
                return null;
            }
            JsonNode root = json.readTree(response.body());
            String text = root.path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asText("");
            if (text.isBlank()) return null;
            /* Vertex's own count, not an estimate from the string length: the
               bill is written against these numbers and a character count is
               not the same unit. Absent on a malformed response, and zero is
               then the honest answer -- it says "not reported", and a sum of
               zeros is visibly not a cost. */
            JsonNode usage = root.path("usageMetadata");
            return new Answer(text,
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0));
        } catch (Exception e) {
            log.warn("screening model call failed: {}", e.toString());
            return null;
        }
    }

    /**
     * The CV, from the one process that can open the bucket.
     *
     * <p>The storage keys live in the web app and nowhere else, so the reader
     * asks for the bytes over the same internal token the posting review
     * already uses rather than being handed a second copy of them.
     */
    byte[] fetchCv(long resumeId) {
        if (token.isBlank()) return null;
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(webBaseUrl + "/api/internal/resume/" + resumeId))
                            .header("x-internal-token", token)
                            /* HTTP/1.1 explicitly. The client defaults to
                               HTTP/2, which over plain http means an h2c
                               upgrade request -- and the dev server answers
                               that by closing the connection, so every CV came
                               back as "header parser received no bytes" and
                               every application was read without the one
                               document it most needed. Measured on 1 Nov 2026:
                               ten of ten failed, and the reading carried on
                               regardless, which is the failure direction this
                               wants but not the outcome. */
                            .version(HttpClient.Version.HTTP_1_1)
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("CV {} came back HTTP {}", resumeId, response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("CV {} could not be fetched: {}", resumeId, e.toString());
            return null;
        }
    }

    static String cut(String text) {
        return cut(text, MAX_CHARS);
    }

    /** Delegates, so the surrogate rule lives in one place. See {@link Text}. */
    static String cut(String text, int max) {
        return Text.cut(text, max);
    }
}
