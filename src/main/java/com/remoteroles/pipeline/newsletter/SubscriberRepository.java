package com.remoteroles.pipeline.newsletter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Subscriber reads for the pipeline side.
 *
 * <p>Capture itself happens in the website, which owns the form; this exists for
 * the sending job that does not exist yet, and for counting.
 */
@Repository
public class SubscriberRepository {

    private final JdbcClient db;

    public SubscriberRepository(JdbcClient db) {
        this.db = db;
    }

    public record Counts(int pending, int confirmed, int unsubscribed) {}

    public Counts counts() {
        return db.sql("""
                        select
                          count(*) filter (where status = 'PENDING')      as pending,
                          count(*) filter (where status = 'CONFIRMED')    as confirmed,
                          count(*) filter (where status = 'UNSUBSCRIBED') as unsubscribed
                        from subscribers
                        """)
                .query((rs, n) -> new Counts(
                        rs.getInt("pending"), rs.getInt("confirmed"), rs.getInt("unsubscribed")))
                .single();
    }

    /**
     * The only set that may ever be mailed.
     *
     * <p>Deliberately not "all subscribers". PENDING rows are addresses somebody
     * typed into a form; nothing proves the person who owns the address asked for
     * anything. Mailing them is how a sending domain gets blocked.
     */
    public List<String> confirmedAudience(int limit) {
        return db.sql("""
                        select email from subscribers
                        where status = 'CONFIRMED'
                        order by created_at
                        limit :limit
                        """)
                .param("limit", limit)
                .query(String.class)
                .list();
    }
}
