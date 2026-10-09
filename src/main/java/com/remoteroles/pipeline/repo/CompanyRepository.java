package com.remoteroles.pipeline.repo;

import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.Company;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.remoteroles.pipeline.normalize.Geography;
import java.util.HashMap;
import java.util.Map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class CompanyRepository {

    private final JdbcClient db;

    public CompanyRepository(JdbcClient db) {
        this.db = db;
    }

    public List<Company> findActive() {
        return db.sql("""
                        select id, name, domain, ats_type, ats_token, status,
                               consecutive_failures, last_fetched_at, last_success_at, source_kind
                        from companies
                        where status = 'ACTIVE' and source_kind = 'ATS'
                        order by last_fetched_at nulls first, id
                        """)
                .query(CompanyRepository::mapCompany)
                .list();
    }

    public void markSuccess(long companyId) {
        db.sql("""
                        update companies
                           set last_fetched_at = now(),
                               last_success_at = now(),
                               consecutive_failures = 0,
                               last_error = null
                         where id = :id
                        """)
                .param("id", companyId)
                .update();
    }

    /**
     * Records a failure and retires the board once it has failed enough times in a
     * row.
     *
     * <p>Retiring sets status to DEAD rather than deleting the row: a board that
     * 404s today may be a company mid-migration between ATS vendors, and the name
     * and domain are worth keeping either way.
     */
    public void markFailure(long companyId, String error, int maxConsecutiveFailures) {
        db.sql("""
                        update companies
                           set last_fetched_at = now(),
                               consecutive_failures = consecutive_failures + 1,
                               last_error = :error,
                               status = case
                                   when consecutive_failures + 1 >= :maxFailures then 'DEAD'
                                   else status
                               end
                         where id = :id
                        """)
                .param("id", companyId)
                .param("error", truncate(error, 1000))
                .param("maxFailures", maxConsecutiveFailures)
                .update();
    }

    /** Immediately retires a board whose endpoint is permanently gone. */
    public void markDead(long companyId, String error) {
        db.sql("""
                        update companies
                           set last_fetched_at = now(), status = 'DEAD', last_error = :error
                         where id = :id
                        """)
                .param("id", companyId)
                .param("error", truncate(error, 1000))
                .update();
    }

    /**
     * Finds or creates the employer behind a feed posting.
     *
     * <p>Feed employers arrive one posting at a time with nothing but a name, so the
     * row is created on sight. Identity is the generated slug, which means an
     * employer appearing under slightly different capitalisation collapses onto one
     * company page instead of fragmenting into several thin ones.
     *
     * <p>Returns the company id. Concurrent runs race here, so the insert is written
     * to tolerate losing: {@code on conflict do nothing} followed by a read.
     */
    public long resolveFeedEmployer(String name, AtsType source) {
        db.sql("""
                        insert into companies (name, ats_type, ats_token, source_kind)
                        values (:name, :atsType, :token, 'FEED')
                        on conflict (ats_type, ats_token) do nothing
                        """)
                .param("name", name)
                .param("atsType", source.name())
                .param("token", name)
                .update();

        return db.sql("select id from companies where ats_type = :atsType and ats_token = :token")
                .param("atsType", source.name())
                .param("token", name)
                .query(Long.class)
                .single();
    }

    private static Company mapCompany(ResultSet rs, int rowNum) throws SQLException {
        return new Company(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("domain"),
                AtsType.valueOf(rs.getString("ats_type")),
                rs.getString("ats_token"),
                rs.getString("status"),
                rs.getInt("consecutive_failures"),
                rs.getTimestamp("last_fetched_at") == null ? null : rs.getTimestamp("last_fetched_at").toInstant(),
                rs.getTimestamp("last_success_at") == null ? null : rs.getTimestamp("last_success_at").toInstant(),
                rs.getString("source_kind")
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Resolves each employer's office country from payloads already stored.
     *
     * <p>Done here rather than threaded through every fetcher as another field on
     * FetchedPosting: an address is a property of the employer, not of the
     * posting, and five fetchers would each have had to learn a different JSON
     * shape to fill a column one pass can fill. The payload is kept anyway.
     *
     * <p>The candidate strings are not trustworthy. A Greenhouse office field
     * contains "Remote", "Worldwide", "Any Location", "08018 Barcelona" and "NY"
     * as often as it contains a country, and spells the same country "USA" and
     * "United States". Everything goes through the gazetteer, and anything that
     * does not resolve is dropped -- "HQ Remote" on a card would be worse than no
     * HQ at all.
     *
     * <p>Only fills blanks, so a board that stops publishing its address does not
     * erase what we already knew.
     *
     * @return how many employers gained a country on this pass
     */
    public int refreshHqCountries() {
        record Candidate(long companyId, String raw) {}
        List<Candidate> candidates = db.sql("""
                with latest as (
                  select distinct on (company_id, external_id)
                         company_id, ats_type, payload
                  from raw_postings
                  order by company_id, external_id, fetched_at desc
                )
                select l.company_id,
                       coalesce(
                         l.payload #>> '{address,postalAddress,addressCountry}',
                         l.payload #>> '{offices,0,location}'
                       ) as raw
                  from latest l
                  join companies c on c.id = l.company_id
                 where c.hq_country is null
                """)
                .query((rs, n) -> new Candidate(rs.getLong("company_id"), rs.getString("raw")))
                .list();

        // Most frequent resolved code per company wins: a board with offices in
        // several countries should report the one it posts from most.
        Map<Long, Map<String, Integer>> tally = new HashMap<>();
        for (Candidate c : candidates) {
            String code = Geography.countryCode(c.raw());
            if (code == null) continue;
            tally.computeIfAbsent(c.companyId(), k -> new HashMap<>())
                 .merge(code, 1, Integer::sum);
        }

        int updated = 0;
        for (Map.Entry<Long, Map<String, Integer>> e : tally.entrySet()) {
            String best = e.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (best == null) continue;
            updated += db.sql("update companies set hq_country = :c where id = :id and hq_country is null")
                    .param("c", best)
                    .param("id", e.getKey())
                    .update();
        }
        return updated;
    }
}
