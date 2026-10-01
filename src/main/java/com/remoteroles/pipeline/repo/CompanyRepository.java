package com.remoteroles.pipeline.repo;

import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.Company;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

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
}
