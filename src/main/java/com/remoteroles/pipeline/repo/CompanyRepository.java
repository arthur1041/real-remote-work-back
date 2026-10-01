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
                               consecutive_failures, last_fetched_at, last_success_at
                        from companies
                        where status = 'ACTIVE'
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
                rs.getTimestamp("last_success_at") == null ? null : rs.getTimestamp("last_success_at").toInstant()
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
