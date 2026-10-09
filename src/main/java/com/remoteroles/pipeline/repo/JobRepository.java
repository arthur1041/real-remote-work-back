package com.remoteroles.pipeline.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remoteroles.pipeline.ats.AshbyCompensation;
import com.remoteroles.pipeline.domain.CanonicalJob;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JobRepository {

    /** Whether an upsert created a new listing or refreshed an existing one. */
    public enum UpsertOutcome { CREATED, UPDATED }

    private final JdbcClient db;

    public JobRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Inserts or refreshes a listing.
     *
     * <p>{@code first_seen_at} is left out of the update clause so it keeps meaning
     * "when we first saw this role", which is what the feed sorts by for postings
     * whose board gives no publish date.
     *
     * <p>{@code closed_at} is reset to null: a posting that reappears has been
     * reopened, and leaving it closed would hide a live role forever after one
     * blip.
     *
     * <p>The {@code xmax = 0} test distinguishes insert from update. Postgres leaves
     * {@code xmax} at zero for a freshly inserted tuple, so this gets the answer in
     * the same round trip instead of a select-then-write race.
     */
    public UpsertOutcome upsert(CanonicalJob job) {
        Boolean created = db.sql("""
                        insert into jobs (
                            company_id, company_name, ats_type, external_id, content_hash,
                            title, description_html, apply_url, location_raw,
                            is_remote, geo_scope, geo_detail, timezone_requirement,
                            classification_confidence, classified_by,
                            employment_type, department, category, posted_at, expires_at, dedupe_key,
                            benefits, employment_kind, seniority,
                            salary_min, salary_max, salary_currency, salary_period,
                            first_seen_at, last_seen_at
                        ) values (
                            :companyId, :companyName, :atsType, :externalId, :contentHash,
                            :title, :descriptionHtml, :applyUrl, :locationRaw,
                            :isRemote, :geoScope, :geoDetail, :timezoneRequirement,
                            :confidence, :classifiedBy,
                            :employmentType, :department, :category, :postedAt, :expiresAt, :dedupeKey,
                            cast(:benefits as text[]), :employmentKind, :seniority,
                            :salaryMin, :salaryMax, :salaryCurrency, :salaryPeriod,
                            now(), now()
                        )
                        on conflict (company_id, external_id) do update set
                            content_hash              = excluded.content_hash,
                            company_name              = excluded.company_name,
                            title                     = excluded.title,
                            description_html          = excluded.description_html,
                            apply_url                 = excluded.apply_url,
                            location_raw              = excluded.location_raw,
                            is_remote                 = excluded.is_remote,
                            geo_scope                 = excluded.geo_scope,
                            geo_detail                = excluded.geo_detail,
                            timezone_requirement      = excluded.timezone_requirement,
                            classification_confidence = excluded.classification_confidence,
                            classified_by             = excluded.classified_by,
                            employment_type           = excluded.employment_type,
                            department                = excluded.department,
                            category                  = excluded.category,
                            benefits                  = excluded.benefits,
                            employment_kind           = excluded.employment_kind,
                            seniority                 = excluded.seniority,
                            salary_min                = excluded.salary_min,
                            salary_max                = excluded.salary_max,
                            salary_currency           = excluded.salary_currency,
                            salary_period             = excluded.salary_period,
                            posted_at                 = excluded.posted_at,
                            expires_at                = excluded.expires_at,
                            dedupe_key                = excluded.dedupe_key,
                            last_seen_at              = now(),
                            closed_at                 = null
                        returning (xmax = 0) as created
                        """)
                .param("companyId", job.companyId())
                .param("companyName", job.companyName())
                .param("atsType", job.atsType().name())
                .param("externalId", job.externalId())
                .param("contentHash", job.contentHash())
                .param("title", job.title())
                .param("descriptionHtml", job.descriptionHtml())
                .param("applyUrl", job.applyUrl())
                .param("locationRaw", job.locationRaw())
                .param("isRemote", job.classification().isRemote())
                .param("geoScope", job.classification().geoScope() == null
                        ? null : job.classification().geoScope().name())
                .param("geoDetail", job.classification().geoDetail())
                .param("timezoneRequirement", job.classification().timezoneRequirement())
                .param("confidence", job.classification().confidence())
                .param("classifiedBy", job.classification().classifiedBy())
                .param("employmentType", job.employmentType())
                .param("department", job.department())
                .param("category", job.category() == null ? null : job.category().name())
                // Postgres array literal: {A,B,C}. The values are enum names, so
                // there is nothing in them that needs quoting or escaping.
                .param("benefits", job.benefits() == null || job.benefits().isEmpty()
                        ? "{}"
                        : job.benefits().stream().map(Enum::name)
                                .collect(java.util.stream.Collectors.joining(",", "{", "}")))
                .param("employmentKind", job.employmentKind() == null ? null : job.employmentKind().name())
                .param("seniority", job.seniority())
                .param("salaryMin", job.salaryMin())
                .param("salaryMax", job.salaryMax())
                .param("salaryCurrency", job.salaryCurrency())
                .param("salaryPeriod", job.salaryPeriod())
                .param("postedAt", job.postedAt() == null ? null : Timestamp.from(job.postedAt()))
                .param("expiresAt", job.expiresAt() == null ? null : Timestamp.from(job.expiresAt()))
                .param("dedupeKey", job.dedupeKey())
                .query(Boolean.class)
                .single();

        return Boolean.TRUE.equals(created) ? UpsertOutcome.CREATED : UpsertOutcome.UPDATED;
    }

    /**
     * Closes this company's postings that were not present in the run starting at
     * {@code runStartedAt}.
     *
     * <p>Call this only after a confirmed successful fetch. On a failed fetch the
     * board returns nothing, and running this anyway would close every posting the
     * company has the first time their ATS throws a 503 -- the single most damaging
     * bug a job board can ship, because it is silent and looks like the site simply
     * has no jobs.
     */
    public int closeStale(long companyId, Instant runStartedAt) {
        return db.sql("""
                        update jobs
                           set closed_at = now()
                         where company_id = :companyId
                           and closed_at is null
                           and last_seen_at < :runStartedAt
                        """)
                .param("companyId", companyId)
                .param("runStartedAt", Timestamp.from(runStartedAt))
                .update();
    }

    /**
     * Closes postings whose publisher-stated expiry has passed.
     *
     * <p>This is how feed postings retire. They cannot use the absence rule the ATS
     * boards use: a paginated crawl sees only part of the feed, so absence from a run
     * carries no information at all, and sweeping on it would close almost everything
     * every night.
     */
    public int closeExpired() {
        return db.sql("""
                        update jobs set closed_at = now()
                         where closed_at is null
                           and expires_at is not null
                           and expires_at < now()
                        """)
                .update();
    }

    /** How many of this company's listings are currently open. Guards the expiry sweep. */
    public int countOpen(long companyId) {
        Integer n = db.sql("select count(*) from jobs where company_id = :id and closed_at is null")
                .param("id", companyId)
                .query(Integer.class)
                .single();
        return n == null ? 0 : n;
    }

    /**
     * Fills in salaries from Ashby payloads already on disk.
     *
     * <p>Ashby has been asked for compensation since the board was first polled, so
     * the numbers are sitting in {@code raw_postings} for thousands of listings that
     * were ingested before anything read them. Waiting for the natural re-fetch to
     * surface those would mean a board where pay shows on 0.7% of cards until every
     * company's turn comes round again.
     *
     * <p>Only null salaries are touched, so a figure that came from the live fetch is
     * never overwritten by an older payload, and the parse is
     * {@link AshbyCompensation} itself rather than a second copy of the rules in SQL.
     */
    public int refreshAshbySalaries() {
        record Candidate(long jobId, String payload) {}
        List<Candidate> candidates = db.sql("""
                with latest as (
                  select distinct on (r.company_id, r.external_id)
                         r.company_id, r.external_id, r.payload
                    from raw_postings r
                   where r.ats_type = 'ASHBY'
                   order by r.company_id, r.external_id, r.fetched_at desc
                )
                select j.id as job_id, l.payload::text as payload
                  from latest l
                  join jobs j
                    on j.company_id = l.company_id
                   and j.external_id = l.external_id
                 where j.salary_min is null
                   and j.salary_max is null
                """)
                .query((rs, n) -> new Candidate(rs.getLong("job_id"), rs.getString("payload")))
                .list();

        ObjectMapper mapper = new ObjectMapper();
        int updated = 0;
        for (Candidate c : candidates) {
            AshbyCompensation.Pay pay;
            try {
                JsonNode node = mapper.readTree(c.payload());
                pay = AshbyCompensation.read(node);
            } catch (Exception e) {
                continue; // an unparseable stored payload is not worth failing a run over
            }
            if (pay == null) {
                continue;
            }
            updated += db.sql("""
                            update jobs
                               set salary_min = :min, salary_max = :max,
                                   salary_currency = :cur, salary_period = :per
                             where id = :id
                               and salary_min is null and salary_max is null
                            """)
                    .param("min", pay.min())
                    .param("max", pay.max())
                    .param("cur", pay.currency())
                    .param("per", pay.period())
                    .param("id", c.jobId())
                    .update();
        }
        return updated;
    }
}
