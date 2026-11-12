package com.remoteroles.pipeline.employers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

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
                   and not exists (
                       select 1 from posting_requirements r
                        where r.posting_id = p.id
                          and r.content_version = p.content_version
                   )
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
            } catch (Exception e) {
                log.warn("requirements for posting {} failed: {}", role.postingId(), e.toString());
            }
        }
    }

    void extract(Role role) {
        List<String[]> found = new ArrayList<>();

        String text = role.description() == null ? "" : role.description();
        if (!text.isBlank()) {
            String body = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
            Answer answer = ask(REQUIREMENTS_PROMPT.formatted(
                    role.title() == null ? "" : role.title(), body), null, 900);
            if (answer != null) {
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
                }
            }
        }

        int position = 0;
        for (String[] item : found) {
            insertRequirement(role, position++, item[0], Boolean.parseBoolean(item[1]), "MODEL", null);
        }

        /* The one requirement that needs no model, and that the model must not
           be allowed to answer instead. Both sides of it are structured — the
           posting's `employment_kind` and the candidate's `employment_kinds`,
           from the same fixed list — so the answer is a set test rather than a
           judgement, and it is free, reproducible and checkable by query.
           Added last so it reads after what the description itself said. */
        if (role.employmentKind() != null && !role.employmentKind().isBlank()) {
            insertRequirement(role, position,
                    "Works on a " + humanKind(role.employmentKind()) + " basis",
                    true, "FIELD", "EMPLOYMENT_KIND");
        }
    }

    private void insertRequirement(Role role, int position, String text, boolean essential,
                                   String source, String fieldCode) {
        db.sql("""
                insert into posting_requirements
                  (posting_id, content_version, position, text, essential, source, field_code)
                values (:posting, :version, :position, :text, :essential, :source, :field)
                on conflict (posting_id, content_version, position) do nothing
                """)
                .param("posting", role.postingId())
                .param("version", role.contentVersion())
                .param("position", position)
                .param("text", text)
                .param("essential", essential)
                .param("source", source)
                .param("field", fieldCode)
                .update();
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
                   -- O teto do assinante, no mês corrente.
                   and (
                       select count(*) from application_screenings g2
                         join job_applications a2 on a2.id = g2.application_id
                         join employer_postings p2 on p2.id = a2.posting_id
                        where p2.employer_id = p.employer_id
                          and g2.requested_at >= date_trunc('month', now())
                   ) < :reads
                 order by a.created_at
                 limit :batch
                """)
                .param("reads", readsAMonth)
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

    int readToday() {
        Integer n = db.sql("""
                select count(*) from application_screenings
                 where requested_at >= date_trunc('day', now())
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
            fail(screeningId, "The reading could not be completed. Nothing about this "
                    + "application has been assumed.");
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

            double points = 0, possible = 0;
            boolean essentialMissed = false;
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

                /* A field answers for itself, and the model does not get a vote
                   on it: the candidate declared these regimes and the role
                   declared the one it needs. Free, reproducible, and it stops
                   the reading contradicting a stated fact. */
                if ("FIELD".equals(r.source()) && "EMPLOYMENT_KIND".equals(r.fieldCode())) {
                    /* **A resposta desta candidatura vence o perfil.** O
                       perfil diz o que a pessoa aceita em geral; o formulário
                       pergunta de novo, já preenchido, o que ela aceita para
                       esta vaga. Quem marcou "full-time" procurando emprego
                       pode muito bem pegar esta por contrato, e o contrário
                       acontece igual. Ler o perfil quando existe resposta é
                       transformar uma preferência geral em afirmação sobre
                       uma vaga -- e marcar como não atendido quem teria dito
                       que sim. O perfil continua respondendo pelas
                       candidaturas enviadas antes de a pergunta existir. */
                    boolean fromAnswer = who.answerKind() != null && !who.answerKind().isBlank();
                    String declared = fromAnswer
                            ? who.answerKind()
                            : (who.kinds() == null ? "" : who.kinds());
                    if (declared.isBlank()) {
                        verdict = "UNKNOWN"; quote = ""; source = null;
                    } else {
                        boolean matches = false;
                        for (String kind : declared.split(",")) {
                            if (r.text().contains(humanKind(kind.trim()))) { matches = true; break; }
                        }
                        verdict = matches ? "MET" : "UNMET";
                        /* A citação diz de onde veio, porque as duas frases
                           significam coisas diferentes para quem lê: uma é
                           sobre esta vaga, a outra é sobre o cadastro. */
                        quote = fromAnswer
                                ? "Asked on this application, they answered: "
                                  + humanKind(declared.trim())
                                : "Their profile says they work on: "
                                  + declared.replace(",", ", ");
                        source = fromAnswer ? "ANSWER" : "PROFILE";
                    }
                }

                // No quote, no claim. This is what keeps the reading from being
                // a guess wearing the clothes of a finding.
                if (!"UNKNOWN".equals(verdict) && quote.isBlank()) verdict = "UNKNOWN";
                if (!List.of("MET", "UNMET", "UNKNOWN").contains(verdict)) verdict = "UNKNOWN";
                if (source != null && !List.of("CV", "PROFILE", "ANSWER", "FIELD").contains(source)) {
                    source = null;
                }

                double weight = r.essential() ? 2 : 1;
                possible += weight;
                if ("MET".equals(verdict)) points += weight;
                // "Não sei" vale metade, e não zero: punir o silêncio como se
                // fosse uma falha é a mesma coisa que tratar UNKNOWN como UNMET
                // por outro caminho -- devagar, na nota, onde ninguém vê.
                else if ("UNKNOWN".equals(verdict)) points += weight / 2;
                else if (r.essential()) essentialMissed = true;

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

            double score = possible == 0 ? 0 : (points / possible) * 10;
            /* An essential requirement the material contradicts caps the score,
               rather than merely costing its weight. Without this a candidate
               who cannot do the one thing the role requires can still outrank
               one who can, by being strong on everything else -- which is how a
               ranking stops meaning what the employer reads it to mean. */
            if (essentialMissed) score = Math.min(score, 4.0);

            db.sql("""
                    update application_screenings
                       set state = 'OK', score = :score, summary = :summary,
                           tokens_in = :tin, tokens_out = :tout,
                           evaluated_at = now()
                     where id = :id
                    """)
                    .param("score", Math.round(score * 10) / 10.0)
                    .param("summary", summary.isBlank() ? null : cut(summary, 300))
                    // O que esta leitura custou. A extração de requisitos é
                    // por vaga e não por candidatura, então ela não entra
                    // aqui: a coluna responde "quanto custou ler esta
                    // candidatura", e somar a leitura da vaga em cada uma
                    // contaria a mesma chamada dez vezes.
                    .param("tin", answer.tokensIn())
                    .param("tout", answer.tokensOut())
                    .param("id", screeningId)
                    .update();
        } catch (Exception e) {
            fail(screeningId, "The answer could not be read: " + e.getMessage() + ".");
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
                    .put("maxOutputTokens", maxOutputTokens)
                    .put("responseMimeType", "application/json");

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

    static String cut(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) : text;
    }
}
