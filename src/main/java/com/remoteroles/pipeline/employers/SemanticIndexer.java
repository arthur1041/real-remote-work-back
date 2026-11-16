package com.remoteroles.pipeline.employers;

import com.remoteroles.pipeline.normalize.Text;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

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
 * Keeps the semantic index of candidates and roles up to date.
 *
 * <p><strong>What this is for.</strong> Discovery used to order profiles by
 * words in common with the role's title. That cannot tell `Go` from `Golang`,
 * has never read a CV, and ranks a Portuguese profile against an English role
 * at zero. This builds the index that replaces it: one short summary per
 * candidate, written by the model, and one vector per summary.
 *
 * <p><strong>Why a summary and not the CV itself.</strong> The embedder takes
 * 2048 tokens of text and a CV does not fit; a PDF also carries headers,
 * footers, phone numbers and OCR debris, which land in the vector as if they
 * were competence. The summary is written once per version of what it
 * describes, normalises synonyms and language — half this board's candidates
 * write in something other than English — and is reused by the ranking step,
 * which then never resends a PDF.
 *
 * <p><strong>Daily is the budget, not the cadence.</strong> Arthur asked for a
 * routine that runs daily; the sweep runs every few minutes instead, and the
 * daily figure is the cap. The difference is somebody who signs up at ten in
 * the morning: on a daily run they are invisible to discovery until tomorrow,
 * and the queue is empty the rest of the time anyway, so the frequent pass
 * costs one indexed query and buys a board that is current.
 *
 * <p><strong>The trigger is the fingerprint, not the calendar.</strong>
 * `candidate_digest_source` hashes everything a summary is made of; a row
 * whose hash differs from the stored one is stale by definition, and a row
 * whose hash matches is never paid for twice. A re-digest also waits for the
 * edit to settle, so somebody adjusting their headline five times in a minute
 * is summarised once.
 */
@Service
@ConditionalOnProperty(name = "semantic.enabled", havingValue = "true")
public class SemanticIndexer {

    private static final Logger log = LoggerFactory.getLogger(SemanticIndexer.class);
    private static final ObjectMapper json = new ObjectMapper();
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** The most of any one text sent to be summarised, as the screener uses. */
    private static final int MAX_CHARS = 12_000;

    /**
     * What the summary is asked to be.
     *
     * <p>Written for a reader that is a vector, not a person — though a person
     * can read it, and the table keeps it in text so that a bad ranking can be
     * argued with. Three rules carry their reasons: English, because the index
     * has to put a Portuguese CV next to an English role; no contact details,
     * because an address inside an embedding is an address that leaks through
     * similarity; and no invention, because a summary that fills gaps with
     * plausible experience makes the whole index confidently wrong.
     */
    private static final String CANDIDATE_PROMPT = """
            Summarise this person for a job-matching index.

            Write 120-200 words of plain English prose, no headings, no bullet
            points, no markdown. Cover, in this order and only where the source
            supports it: current and recent role titles; years of experience;
            the technologies, tools and methods they have actually worked with,
            naming each by its common name (write Go for Golang, Kubernetes for
            K8s, JavaScript for JS); the domains and industries they worked in;
            team size and seniority if stated; spoken languages; where they can
            work from and under which contract.

            Rules:
            - English only, whatever language the source is in.
            - Never include a name, email address, phone number, or any link.
            - Never invent. If the source does not say it, leave it out. Say
              "no CV provided" once if there is none, and summarise the profile.
            - No praise, no judgement, no fit assessment. Facts only.
            - You are indexing, not conversing. Never ask for anything, never
              address the reader, never explain what you would do. If there is
              genuinely nothing to summarise, answer with exactly the word
              NOTHING and no other text.

            Structured profile:
            %s
            """;

    /**
     * The role, reduced to what it needs rather than what it says.
     *
     * <p>Deliberately not the description verbatim: half a job description is
     * the company's self-portrait and its benefits, which match everybody and
     * therefore nobody. What is wanted in the same space as a candidate
     * summary is the work itself.
     */
    private static final String POSTING_PROMPT = """
            Summarise what this role needs, for a job-matching index.

            Write 100-160 words of plain English prose, no headings, no bullet
            points, no markdown. Cover only what the role asks of a person: the
            work itself; the technologies, tools and methods required, each by
            its common name; the level of experience; the domain; languages;
            and the contract and location constraints.

            Rules:
            - Leave out the company's description of itself, its benefits, its
              culture, its mission and its hiring process.
            - Never invent a requirement the text does not state.
            - No selling, no adjectives about the company. Facts only.

            Role:
            %s
            """;

    private final JdbcClient db;
    private final GoogleServiceAccount account;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String generateEndpoint;
    private final String embedEndpoint;
    private final String digestModel;
    private final String embedModel;
    private final String webBaseUrl;
    private final String token;
    private final int dimensions;
    private final int batchSize;
    private final int dailyCap;
    private final int maxAttempts;
    private final int settleMinutes;
    private final int retryAfterMinutes;
    private final long paceMillis;
    private final int backoffSeconds;

    public SemanticIndexer(
            JdbcClient db,
            @Value("${semantic.key-file:${scope-review.key-file:}}") String keyFile,
            @Value("${semantic.project:${scope-review.project:actually-remote}}") String project,
            @Value("${semantic.location:global}") String location,
            @Value("${semantic.model:gemini-3.1-flash-lite}") String digestModel,
            /**
             * The embedding model and where it answers.
             *
             * <p>Separate from the summariser's location on purpose: this
             * project's generative models answer on `global` and nowhere else
             * — measured, `us-central1` returns NOT_FOUND for every model name
             * here — and that is a fact about generateContent, not about the
             * embeddings endpoint. Two settings, so finding out costs a
             * configuration change rather than a rebuild.
             */
            @Value("${semantic.embed-model:gemini-embedding-001}") String embedModel,
            @Value("${semantic.embed-location:us-central1}") String embedLocation,
            /**
             * How many numbers a vector carries.
             *
             * <p>768, from a model whose default is 3072. The matryoshka
             * training is what makes the truncation honest rather than a
             * mutilation: the first 768 are meant to stand alone. Three
             * thousand would be 12KB a candidate and an index several times
             * larger, for a gain a base of hundreds cannot measure. It is in
             * the schema as `vector(768)`, so changing it here alone would
             * make every write fail — deliberately: the two must move
             * together.
             */
            @Value("${semantic.dimensions:768}") int dimensions,
            @Value("${semantic.web-url:${screening.web-url:http://web:3000}}") String webBaseUrl,
            @Value("${semantic.token:${INTERNAL_TASK_TOKEN:}}") String token,
            @Value("${semantic.batch-size:10}") int batchSize,
            /**
             * Summaries written across the whole site in a day.
             *
             * <p>The circuit breaker rather than the budget, like the
             * screener's. Four hundred candidates exist and each is summarised
             * once per change; this is set where a loop nobody modelled still
             * costs less than a few dollars before it trips.
             */
            @Value("${semantic.daily-cap:500}") int dailyCap,
            @Value("${semantic.max-attempts:3}") int maxAttempts,
            /**
             * Minutes a change must sit still before it is summarised.
             *
             * <p>Somebody filling in their profile saves it four times in two
             * minutes. Without this, that is four summaries and four
             * embeddings of the same person, three of them superseded before
             * they were written.
             */
            @Value("${semantic.settle-minutes:10}") int settleMinutes,
            @Value("${semantic.retry-after-minutes:20}") int retryAfterMinutes,
            /** Milliseconds between model calls. See `pace`. */
            @Value("${semantic.pace-ms:5000}") long paceMillis,
            /** Seconds to wait when Vertex says it is busy. See `ask`. */
            @Value("${semantic.backoff-seconds:30}") int backoffSeconds)
            throws Exception {
        this.db = db;
        this.account = new GoogleServiceAccount(Path.of(keyFile), SCOPE);
        this.digestModel = digestModel;
        this.embedModel = embedModel;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.dailyCap = dailyCap;
        this.maxAttempts = maxAttempts;
        this.settleMinutes = settleMinutes;
        this.retryAfterMinutes = retryAfterMinutes;
        this.paceMillis = paceMillis;
        this.backoffSeconds = backoffSeconds;
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
        this.token = token == null ? "" : token.trim();

        String host = "global".equals(location) ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        this.generateEndpoint =
                "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                        .formatted(host, project, location, digestModel);
        String embedHost = "global".equals(embedLocation) ? "aiplatform.googleapis.com"
                : embedLocation + "-aiplatform.googleapis.com";
        this.embedEndpoint =
                "https://%s/v1/projects/%s/locations/%s/publishers/google/models/%s:predict"
                        .formatted(embedHost, project, embedLocation, embedModel);
    }

    public static boolean keyAvailable(String keyFile) {
        return keyFile != null && !keyFile.isBlank() && Files.isReadable(Path.of(keyFile));
    }

    /**
     * Every few minutes, and almost always with nothing to do.
     *
     * <p>Three passes in a fixed order: claim what is stale, write the
     * summaries, then embed what has a summary and no vector. Separate passes
     * because the two calls fail for different reasons and at different
     * prices — an embedding that fails must not cost another summary.
     */
    @Scheduled(fixedDelayString = "${semantic.interval-ms:300000}",
               initialDelayString = "${semantic.initial-delay-ms:60000}")
    public void run() {
        /* Three steps, three failures, and they must not share one.
           Measured: a pass that gave up on a rate limit from the *summariser*
           never reached the embedder, so thirteen summaries sat written and
           unindexed while the queue looked like it was moving. The embedding
           endpoint has its own quota and was never the thing refusing. */
        try {
            claimStale();
        } catch (Exception e) {
            log.warn("semantic: claiming failed: {}", e.toString());
        }
        try {
            digestDue();
        } catch (RateLimited e) {
            log.info("semantic: summaries cut short by a rate limit; the queue keeps its place");
        } catch (Exception e) {
            log.warn("semantic: summaries failed: {}", e.toString());
        }
        try {
            embedDue();
        } catch (Exception e) {
            log.warn("semantic: embedding failed: {}", e.toString());
        }
    }

    /* ------------------------------------------------------------- claiming */

    /**
     * Marks as due everybody whose summary is missing or out of date.
     *
     * <p>The whole definition of "out of date" is the fingerprint in
     * `candidate_digest_source`; this statement adds only the two guards that
     * keep it from thrashing: the change must have settled, and a row that has
     * failed its way to the attempt limit is left alone until somebody looks.
     */
    void claimStale() {
        int rows = db.sql("""
                insert into candidate_digests (candidate_id, resume_id, fingerprint, summary, state)
                select s.candidate_id, s.resume_id, s.fingerprint, '', 'PENDING'
                  from candidate_digest_source s
                  left join candidate_digests d on d.candidate_id = s.candidate_id
                 where d.candidate_id is null
                    or (d.fingerprint <> s.fingerprint
                        and d.requested_at < now() - make_interval(mins => :settle))
                on conflict (candidate_id) do update
                   set resume_id    = excluded.resume_id,
                       fingerprint  = excluded.fingerprint,
                       state        = 'PENDING',
                       failure      = null,
                       attempts     = 0,
                       requested_at = now()
                 where candidate_digests.fingerprint <> excluded.fingerprint
                """)
                .param("settle", settleMinutes)
                .update();
        if (rows > 0) log.info("semantic: {} profiles due a summary", rows);

        /* Roles, on the same terms. A posting is summarised once per version
           of its text, and only while it is live: a closed role is a question
           nobody is asking.
           
           **Copiado antes de ser enfileirado.** Uma versão nova cujo texto é o
           mesmo -- salário acrescentado, data de encerramento posta, pergunta
           nova no formulário -- tem o mesmo resumo e o mesmo vetor, e pedi-los
           outra vez custa duas coisas: uma chamada ao modelo, gasta de um teto
           diário que estas duas filas dividem, e a janela até ela voltar, em
           que a descoberta daquela vaga cai no caminho das palavras. A V76
           carrega o que decide o resumo; igual quer dizer que não há o que
           resumir. Mesma forma da V74 para os requisitos e da V75 para a
           lista. */
        int carregados = db.sql("""
                insert into posting_digests
                      (posting_id, content_version, summary, embedding, digest_model,
                       embed_model, tokens_in, tokens_out, state, digested_at,
                       embedded_at, source_hash)
                select p.id, p.content_version, d.summary, d.embedding, d.digest_model,
                       d.embed_model, 0, 0, 'OK', d.digested_at, d.embedded_at, d.source_hash
                  from employer_postings p
                  left join jobs j on j.id = p.job_id
                  join lateral (
                    select d.* from posting_digests d
                     where d.posting_id = p.id
                       and d.content_version < p.content_version
                       and d.state = 'OK'
                       and d.source_hash is not null
                       and d.source_hash = %s
                     order by d.content_version desc
                     limit 1
                  ) d on true
                 where p.status = 'APPROVED'
                   and (p.job_id is null or j.closed_at is null)
                on conflict (posting_id, content_version) do nothing
                """.formatted(DIGEST_SOURCE_HASH))
                .update();
        if (carregados > 0) {
            log.info("semantic: {} roles ask the same as before, summary reused", carregados);
        }

        int postings = db.sql("""
                insert into posting_digests (posting_id, content_version, summary, state, source_hash)
                select p.id, p.content_version, '', 'PENDING', %s
                  from employer_postings p
                  left join jobs j on j.id = p.job_id
                 where p.status = 'APPROVED'
                   and (p.job_id is null or j.closed_at is null)
                on conflict (posting_id, content_version) do nothing
                """.formatted(DIGEST_SOURCE_HASH))
                .update();
        if (postings > 0) log.info("semantic: {} roles due a summary", postings);
    }

    /**
     * O que alimenta o resumo de uma vaga, como expressão SQL.
     *
     * <p>Uma definição só, usada por quem copia e por quem enfileira, para que
     * as duas não possam divergir. As colunas são as que entram no texto
     * mandado ao modelo em {@link #digestPosting}: título, categoria, regime,
     * elegibilidade e descrição. Os requisitos também entram no texto, mas não
     * aqui -- eles são derivados da descrição e já viajam com ela pela V74, e
     * incluí-los faria o resumo esperar a extração para poder ser copiado.
     *
     * <p>chr(1) pela razão da V74: `text` no Postgres recusa o caractere nulo.
     */
    private static final String DIGEST_SOURCE_HASH = """
            encode(sha256(convert_to(
              coalesce(p.title,'') || chr(1) ||
              coalesce(p.category,'') || chr(1) ||
              coalesce(p.employment_kind,'') || chr(1) ||
              coalesce(p.geo_scope,'') || chr(1) ||
              coalesce(p.geo_detail,'') || chr(1) ||
              coalesce(p.description_source,''), 'UTF8')), 'hex')""";

    /* ------------------------------------------------------------ summaries */

    record Pending(long id, Long resumeId, int attempts) {}

    int writtenToday() {
        return db.sql("""
                select coalesce(
                  (select count(*) from candidate_digests where digested_at >= current_date), 0)
                + coalesce(
                  (select count(*) from posting_digests where digested_at >= current_date), 0)
                """).query(Integer.class).single();
    }

    void digestDue() {
        int budget = dailyCap - writtenToday();
        if (budget <= 0) {
            log.warn("semantic: daily cap of {} reached", dailyCap);
            return;
        }

        List<Pending> people = db.sql("""
                select candidate_id, resume_id, attempts
                  from candidate_digests
                 where state = 'PENDING'
                   and attempts < :max
                   and (attempts = 0
                        or requested_at < now() - make_interval(mins => :retry * attempts))
                 order by requested_at
                 limit :batch
                """)
                .param("max", maxAttempts)
                .param("retry", retryAfterMinutes)
                .param("batch", Math.min(batchSize, budget))
                .query((rs, n) -> new Pending(
                        rs.getLong("candidate_id"),
                        rs.getObject("resume_id") == null ? null : rs.getLong("resume_id"),
                        rs.getInt("attempts")))
                .list();

        int limited = 0;
        for (Pending who : people) {
            try {
                digestCandidate(who);
            } catch (RateLimited e) {
                /* The wait already happened inside the call. Carrying on is
                   the point: the next item is a fresh minute's quota. Too
                   many in one pass and the pass stops, so a service that is
                   genuinely down is not hammered for an hour. */
                if (++limited >= 3) throw e;
            }
            pace();
        }

        List<long[]> roles = db.sql("""
                select posting_id, content_version
                  from posting_digests
                 where state = 'PENDING' and attempts < :max
                 order by requested_at
                 limit :batch
                """)
                .param("max", maxAttempts)
                .param("batch", Math.max(0, Math.min(batchSize, budget - people.size())))
                .query((rs, n) -> new long[] { rs.getLong(1), rs.getLong(2) })
                .list();

        for (long[] role : roles) {
            try {
                digestPosting(role[0], (int) role[1]);
            } catch (RateLimited e) {
                if (++limited >= 3) throw e;
            }
            pace();
        }
    }

    /**
     * A breath between model calls.
     *
     * <p>Measured on the first real backfill, 8 Nov 2026: ten summaries fired
     * back to back drew `429 Resource exhausted` from Vertex on the sixth, and
     * a 429 costs an attempt — three of those and a perfectly good profile is
     * `FAILED` for a reason that had nothing to do with it. The queue has no
     * deadline: a backfill that takes an hour instead of a minute costs
     * nothing, and spacing the calls is the cheapest way to never meet the
     * limit.
     */
    void pace() {
        try {
            Thread.sleep(paceMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void digestCandidate(Pending who) {

        String profile = db.sql("""
                select concat_ws(E'\\n',
                    'Headline: '   || coalesce(headline, '(none)'),
                    'Seniority: '  || coalesce(seniority, '(not stated)'),
                    'Years: '      || coalesce(years_experience::text, '(not stated)'),
                    'Skills: '     || coalesce(array_to_string(skills, ', '), '(none listed)'),
                    'Languages: '  || coalesce(array_to_string(languages, ', '), '(not stated)'),
                    'Timezone: '   || coalesce(timezone, '(not stated)'),
                    'Country: '    || coalesce(country, '(not stated)'),
                    'Open to: '    || coalesce(array_to_string(employment_kinds, ', '), '(not stated)'))
                  from candidates where id = :id
                """).param("id", who.id()).query(String.class).optional().orElse("");

        byte[] cv = who.resumeId() == null ? null : fetchCv(who.resumeId());
        // Counted only once the call has actually been made: a pass that
        // stops on a rate limit must leave the queue exactly as it found it.
        Answer answer = ask(CANDIDATE_PROMPT.formatted(cut(profile)), cv);
        db.sql("update candidate_digests set attempts = attempts + 1 where candidate_id = :id")
                .param("id", who.id()).update();
        if (answer == null || answer.text().isBlank()) {
            fail("candidate_digests", "candidate_id", who.id(), "model gave nothing back");
            return;
        }
        /* A summary that says there is nothing to summarise must not be
           embedded. Measured on the first real run: an empty profile with no
           CV produced 120 words of "no information is available", and texts
           like that sit near each other in the vector space -- an employer
           searching for anything would find a pocket of people the index
           knows nothing about, ranked by how little it knows. The queue now
           filters these out before the call; this is the second line of
           defence, for the profile that is technically non-empty and says
           nothing. */
        /* Only the word the prompt asks for. A length test stood here and
           was wrong: a thin profile -- "Senior QA engineer, Go and SQL,
           Brazil" -- produces a thin summary, and a thin summary is still a
           perfectly good vector. Measured locally: 6 of the first 11 were
           dropped by a `< 80 characters` rule, which took matchable people
           out of the index for being briefly described. */
        if (answer.text().strip().equalsIgnoreCase("NOTHING")) {
            db.sql("""
                    update candidate_digests
                       set summary = '', state = 'OK', failure = 'nothing to summarise',
                           digest_model = :model, digested_at = now(),
                           embedding = null, embedded_at = null
                     where candidate_id = :id
                    """)
                    .param("model", digestModel)
                    .param("id", who.id())
                    .update();
            return;
        }

        db.sql("""
                update candidate_digests
                   set summary = :summary, state = 'OK', failure = null,
                       digest_model = :model, tokens_in = :in, tokens_out = :out,
                       digested_at = now(),
                       embedding = null, embedded_at = null
                 where candidate_id = :id
                """)
                .param("summary", answer.text())
                .param("model", digestModel)
                .param("in", answer.tokensIn())
                .param("out", answer.tokensOut())
                .param("id", who.id())
                .update();
    }

    void digestPosting(long postingId, int version) {

        String role = db.sql("""
                select concat_ws(E'\\n',
                    'Title: '     || p.title,
                    'Category: '  || coalesce(p.category, '(none)'),
                    'Contract: '  || coalesce(p.employment_kind, '(not stated)'),
                    'Open to: '   || p.geo_scope || coalesce(' ' || p.geo_detail, ''),
                    'Requirements: ' || coalesce(
                        (select string_agg(r.text, '; ' order by r.position)
                           from posting_requirements r
                          where r.posting_id = p.id and r.content_version = p.content_version),
                        '(not extracted)'),
                    'Description:', p.description_source)
                  from employer_postings p where p.id = :id
                """).param("id", postingId).query(String.class).optional().orElse("");

        Answer answer = ask(POSTING_PROMPT.formatted(cut(role)), null);
        db.sql("""
                update posting_digests set attempts = attempts + 1
                 where posting_id = :id and content_version = :v
                """).param("id", postingId).param("v", version).update();
        if (answer == null || answer.text().isBlank()) {
            log.warn("semantic: posting {} v{} gave nothing back", postingId, version);
            db.sql("""
                    update posting_digests set state = 'FAILED', failure = 'model gave nothing back'
                     where posting_id = :id and content_version = :v and attempts >= :max
                    """).param("id", postingId).param("v", version).param("max", maxAttempts).update();
            return;
        }

        db.sql("""
                update posting_digests
                   set summary = :summary, state = 'OK', failure = null,
                       digest_model = :model, tokens_in = :in, tokens_out = :out,
                       digested_at = now(), embedding = null, embedded_at = null
                 where posting_id = :id and content_version = :v
                """)
                .param("summary", answer.text())
                .param("model", digestModel)
                .param("in", answer.tokensIn())
                .param("out", answer.tokensOut())
                .param("id", postingId)
                .param("v", version)
                .update();
    }

    /* ----------------------------------------------------------- embeddings */

    /**
     * Turns the summaries that have no vector into vectors.
     *
     * <p>Two task types, and they are not interchangeable: a candidate summary
     * is a **document** being indexed and a role is the **query** being asked
     * of it. Embedding both as documents is the mistake that makes a vector
     * search feel subtly wrong without ever failing — the model is trained to
     * put a question near its answers, not near other questions.
     */
    void embedDue() {
        List<Object[]> people = db.sql("""
                select candidate_id, summary from candidate_digests
                 where state = 'OK' and embedding is null and summary <> ''
                 order by digested_at limit :batch
                """)
                .param("batch", batchSize)
                .query((rs, n) -> new Object[] { rs.getLong(1), rs.getString(2) })
                .list();

        for (Object[] row : people) {
            float[] vector = embed((String) row[1], "RETRIEVAL_DOCUMENT");
            if (vector == null) continue;
            db.sql("""
                    update candidate_digests
                       set embedding = cast(:vec as vector), embed_model = :model,
                           embedded_at = now()
                     where candidate_id = :id
                    """)
                    .param("vec", literal(vector))
                    .param("model", embedModel)
                    .param("id", (Long) row[0])
                    .update();
        }

        List<Object[]> roles = db.sql("""
                select posting_id, content_version, summary from posting_digests
                 where state = 'OK' and embedding is null and summary <> ''
                 order by digested_at limit :batch
                """)
                .param("batch", batchSize)
                .query((rs, n) -> new Object[] { rs.getLong(1), rs.getInt(2), rs.getString(3) })
                .list();

        for (Object[] row : roles) {
            float[] vector = embed((String) row[2], "RETRIEVAL_QUERY");
            if (vector == null) continue;
            db.sql("""
                    update posting_digests
                       set embedding = cast(:vec as vector), embed_model = :model,
                           embedded_at = now()
                     where posting_id = :id and content_version = :v
                    """)
                    .param("vec", literal(vector))
                    .param("model", embedModel)
                    .param("id", (Long) row[0])
                    .param("v", (Integer) row[1])
                    .update();
        }
    }

    /** pgvector reads a vector from `[1,2,3]`, which is cheaper than a binding. */
    static String literal(float[] vector) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (float value : vector) out.add(Float.toString(value));
        return out.toString();
    }

    float[] embed(String text, String taskType) {
        try {
            ObjectNode instance = json.createObjectNode();
            instance.put("task_type", taskType);
            instance.put("content", cut(text, 8_000));
            ArrayNode instances = json.createArrayNode();
            instances.add(instance);
            ObjectNode body = json.createObjectNode();
            body.set("instances", instances);
            ObjectNode parameters = json.createObjectNode();
            parameters.put("outputDimensionality", dimensions);
            body.set("parameters", parameters);

            HttpRequest request = HttpRequest.newBuilder(URI.create(embedEndpoint))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("semantic: embedding HTTP {} — {}", response.statusCode(),
                        response.body().length() > 300
                                ? response.body().substring(0, 300) : response.body());
                return null;
            }
            JsonNode values = json.readTree(response.body())
                    .path("predictions").path(0).path("embeddings").path("values");
            if (!values.isArray() || values.size() != dimensions) {
                log.warn("semantic: embedding came back with {} values, expected {}",
                        values.size(), dimensions);
                return null;
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) vector[i] = (float) values.get(i).asDouble();
            return vector;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("semantic: embedding failed: {}", e.toString());
            return null;
        }
    }

    /* ---------------------------------------------------------------- model */

    record Answer(String text, int tokensIn, int tokensOut) {}

    /** Thrown so a pass stops rather than spending its queue on a closed door. */
    static class RateLimited extends RuntimeException {}

    Answer ask(String prompt, byte[] pdf) {
        try {
            ObjectNode part = json.createObjectNode();
            part.put("text", prompt);
            ArrayNode parts = json.createArrayNode();
            parts.add(part);
            if (pdf != null && pdf.length > 0) {
                ObjectNode inline = json.createObjectNode();
                inline.put("mimeType", "application/pdf");
                inline.put("data", java.util.Base64.getEncoder().encodeToString(pdf));
                ObjectNode wrapper = json.createObjectNode();
                wrapper.set("inlineData", inline);
                parts.add(wrapper);
            }
            ObjectNode content = json.createObjectNode();
            // Vertex refuses a content without one: "Please use a valid role:
            // user, model." Measured, on the first real call this ever made.
            content.put("role", "user");
            content.set("parts", parts);
            ArrayNode contents = json.createArrayNode();
            contents.add(content);
            ObjectNode body = json.createObjectNode();
            body.set("contents", contents);
            ObjectNode config = json.createObjectNode();
            config.put("temperature", 0);
            config.put("maxOutputTokens", 700);
            body.set("generationConfig", config);

            HttpRequest request = HttpRequest.newBuilder(URI.create(generateEndpoint))
                    .header("Authorization", "Bearer " + account.accessToken())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                /* "Later", not "no". Two things follow, and the second was
                   learned by watching: it must not count as an attempt --
                   three busy minutes at Vertex would otherwise turn good
                   profiles into FAILED rows -- and it must not end the pass.
                   Measured on the first backfill: this project's quota
                   refuses after a handful of calls a minute, so a pass that
                   gave up on the first 429 indexed four people in eight
                   minutes. Waiting is what the queue is for. */
                log.info("semantic: rate limited, waiting {}s", backoffSeconds);
                try {
                    Thread.sleep(backoffSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                throw new RateLimited();
            }
            if (response.statusCode() / 100 != 2) {
                log.warn("semantic: summary HTTP {} — {}", response.statusCode(),
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
            log.warn("semantic: summary failed: {}", e.toString());
            return null;
        }
    }

    /** The CV bytes, from the one route that is allowed to hand them over. */
    byte[] fetchCv(long resumeId) {
        try {
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create(webBaseUrl + "/api/internal/resume/" + resumeId))
                    // The header the route actually reads. `Authorization:
                    // Bearer` got a 403 and, from the JDK client, an empty
                    // reply that reads as a network fault rather than a
                    // refusal -- measured on the first backfill.
                    .header("x-internal-token", token)
                    /* HTTP/1.1 explicitly. The JDK client negotiates HTTP/2
                       by default and the Next dev server answers that attempt
                       by closing the connection, which arrives here as "header
                       parser received no bytes" -- a network fault to read,
                       a protocol mismatch in fact. Measured against the local
                       server on 8 Nov 2026. */
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("semantic: CV {} came back HTTP {}", resumeId, response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("semantic: CV {} could not be fetched: {}", resumeId, e.toString());
            return null;
        }
    }

    void fail(String table, String key, long id, String why) {
        db.sql("""
                update %s set state = case when attempts >= :max then 'FAILED' else 'PENDING' end,
                              failure = :why
                 where %s = :id
                """.formatted(table, key))
                .param("max", maxAttempts)
                .param("why", why)
                .param("id", id)
                .update();
    }

    static String cut(String text) {
        return cut(text, MAX_CHARS);
    }

    /** Delegates, so the surrogate rule lives in one place. See {@link Text}. */
    static String cut(String text, int max) {
        return Text.cut(text, max);
    }
}
