package com.remoteroles.pipeline.newsletter;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** A lead that has been recorded but not yet contacted. */
    public record Lead(long id, String email, String confirmToken, String unsubscribeToken) {}

    /**
     * Leads whose single invitation has not gone out yet.
     *
     * <p>Oldest first, and capped by the caller. A list of cold addresses is
     * exactly the shape that gets a new sending domain filtered, so these go out
     * in small batches rather than all at once.
     */
    public List<Lead> awaitingInvite(int limit) {
        // Due means asked for and not yet satisfied: a request newer than the
        // last send. Import and resend both set the request, so this one
        // predicate covers the first invitation and the single reminder without
        // either of them needing a flag to clear.
        //
        // The ceiling is here rather than only in the admin, because a cap that
        // lives in a button is a cap until somebody calls the function another
        // way.
        return db.sql("""
                        select id, email, confirm_token, unsubscribe_token
                        from subscribers
                        where status = 'INVITED'
                          and invite_count < 2
                          and invite_requested_at is not null
                          and (invited_at is null or invited_at < invite_requested_at)
                        order by invite_requested_at
                        limit :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Lead(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("confirm_token"),
                        rs.getString("unsubscribe_token")))
                .list();
    }

    /**
     * Records that the one invitation has been sent.
     *
     * <p>Stamped before the send, like every other mail stamp here. There is no
     * second invitation by design, so the failure that matters is sending twice,
     * not failing to send once.
     */
    public void markInvited(long id) {
        db.sql("""
                        update subscribers
                           set invited_at = now(), invite_count = invite_count + 1
                         where id = :id
                        """)
                .param("id", id)
                .update();
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

    /** A confirmed recipient, with the token that lets them leave. */
    public record Recipient(long id, String email, String unsubscribeToken) {}

    /**
     * Everyone who may be mailed a digest.
     *
     * <p>CONFIRMED only, and the unsubscribe token travels with the address
     * because every message has to carry a way out. Building the list without it
     * would mean either a second query per recipient or a newsletter with no
     * unsubscribe link, and the second is both illegal and the fastest route to
     * a blocked sending domain.
     */
    public List<Recipient> digestAudience(String audience, int limit) {
        // The status list is an allowlist built in one place, and the extra
        // clause is the one case a status cannot express: a PENDING row that
        // carries an unsubscribed_at is somebody who left and whose address was
        // typed into the form again. Returning them to PENDING is how a person
        // rejoins; it must not be how a stranger puts them back. Only their own
        // click on the confirmation link -- which is what CONFIRMED means --
        // starts the mail again.
        String statuses = audienceStatuses(audience).stream()
                .map("'%s'"::formatted)
                .collect(Collectors.joining(", ", "(", ")"));

        return db.sql("""
                        select id, email, unsubscribe_token
                        from subscribers
                        where status in %s
                          and (status = 'CONFIRMED' or unsubscribed_at is null)
                        order by created_at
                        limit :limit
                        """.formatted(statuses))
                .param("limit", limit)
                .query((rs, n) -> new Recipient(
                        rs.getLong("id"),
                        rs.getString("email"),
                        rs.getString("unsubscribe_token")))
                .list();
    }

    /**
     * Every status a subscriber row may hold, as the CHECK constraint defines it.
     *
     * <p>Duplicated from the migration on purpose, and
     * {@code DigestAudienceTest} reads the migration back and fails if the two
     * ever disagree. A status added to the database without being classified
     * below is a status nobody decided about, and the safe default -- not
     * mailing it -- is only safe until somebody widens the audience without
     * noticing it is there.
     */
    static final List<String> ALL_STATUSES =
            List.of("PENDING", "CONFIRMED", "UNSUBSCRIBED", "BOUNCED", "BLOCKED", "INVITED");

    /**
     * Statuses no digest may ever reach, under any audience setting.
     *
     * <p>Not configurable, and not a matter of taste. Opting out has to stick.
     * An address blocked by hand was blocked for a reason that widening the
     * audience does not undo, and a bounced one costs sending reputation every
     * time it is tried. INVITED is the subtle one: it is a stranger who was
     * written to once without asking, and letting "everyone" reach them would
     * turn a single invitation into the subscription that invitation exists to
     * ask about. Invited addresses are a separate list that happens to share a
     * table; they join this one by accepting, and not before.
     */
    static final Set<String> NEVER_MAILED =
            Set.of("UNSUBSCRIBED", "BOUNCED", "BLOCKED", "INVITED");

    /**
     * The statuses one audience setting selects.
     *
     * <p>An allowlist rather than an exclusion, and the fallback is the narrow
     * set rather than the wide one: an audience value this does not recognise --
     * a typo in the settings row, a value written by a future migration -- sends
     * to fewer people than intended, never more.
     */
    static List<String> audienceStatuses(String audience) {
        return "ALL".equals(audience)
                ? List.of("PENDING", "CONFIRMED")
                : List.of("CONFIRMED");
    }

    /** Records that a digest went to this address. */
    public void markDigestSent(long id) {
        db.sql("update subscribers set last_sent_at = now() where id = :id")
                .param("id", id)
                .update();
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
