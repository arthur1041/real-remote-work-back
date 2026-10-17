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

    /** One address waiting to be asked whether it really wants this. */
    public record Awaiting(long id, String email, String confirmToken, String unsubscribeToken) {}

    /**
     * Addresses captured but never asked to confirm, oldest first.
     *
     * <p>Oldest first is deliberate: if a backlog ever forms, the people who have
     * been waiting longest are the ones least likely to remember signing up, and
     * they are the ones whose confirmation is most at risk of being marked spam.
     */
    public List<Awaiting> awaitingConfirmation(int limit) {
        return db.sql("""
                        select id, email, confirm_token, unsubscribe_token
                        from subscribers
                        where status = 'PENDING' and confirmation_sent_at is null
                        order by created_at
                        limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Awaiting(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("confirm_token"),
                        rs.getString("unsubscribe_token")))
                .list();
    }

    /**
     * Stamps an address as asked.
     *
     * <p>Called before the send, not after. A crash between the two costs one
     * unsent confirmation, which a person can fix; stamping afterwards would,
     * on the same crash, re-select the row next minute and keep mailing the same
     * person until someone noticed.
     */
    public void markConfirmationSent(long id) {
        db.sql("update subscribers set confirmation_sent_at = now() where id = :id")
                .param("id", id)
                .update();
    }
}
