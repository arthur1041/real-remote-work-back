package com.remoteroles.pipeline.newsletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * How much mail may still go out today.
 *
 * <p>The relay enforces a daily quota, and until this existed the system had no
 * idea what it was. It found out by being refused, which is the expensive way:
 * a lead is stamped as invited before the attempt, so every refusal spent a
 * person who would never be written to again. Two hundred invitations draining
 * at 150 an hour can reach a quota in the middle of the night, and the evidence
 * would have been a log line nobody read.
 *
 * <h2>Why a claim, not a check</h2>
 *
 * <p>Asking "is there room?" and then sending is two steps, and four schedulers
 * run here -- invitations every two minutes, confirmations every minute, the
 * review alerter every minute, the digest weekly. Between the question and the
 * answer another of them can spend the last slot. So the counter advances and
 * the caller is told whether it was allowed in one statement: the row either
 * takes the increment or the {@code where} rejects it, and nothing in between
 * is observable.
 *
 * <h2>Why cold mail stops first</h2>
 *
 * <p>A confirmation is somebody standing at a form waiting for it, and a review
 * alert is an employer who was promised an answer within a day. An invitation
 * is a stranger who has waited this long already. When the day's budget runs
 * low, the thing that must not be the casualty is the mail the product depends
 * on to work at all -- so invitations stop at a line short of the real cap, and
 * the gap between the two is reserved for everything else.
 *
 * <p>The reserve is not an emergency measure; it is held back from the first
 * message of the day. A budget that only protects transactional mail once it is
 * nearly exhausted protects it on the one day it was already too late.
 */
@Component
public class MailBudget {

    private static final Logger log = LoggerFactory.getLogger(MailBudget.class);

    /**
     * What a message is for, which decides how much of the day it may use.
     *
     * <p>TRANSACTIONAL is anything somebody is waiting for: a confirmation link,
     * the weekly digest they asked for, an alert about a posting in the review
     * queue. COLD is the invitation to an address that has not asked for
     * anything.
     */
    public enum Tier {
        TRANSACTIONAL,
        COLD,
    }

    private final JdbcClient db;
    private final int daily;
    private final int reserve;

    public MailBudget(
            JdbcClient db,
            @Value("${newsletter.budget.daily:280}") int daily,
            @Value("${newsletter.budget.transactional-reserve:60}") int reserve) {
        this.db = db;
        this.daily = Math.max(0, daily);
        // Clamped rather than trusted. A reserve larger than the budget would
        // make the cold ceiling negative, which reads as "no invitations ever"
        // -- true to the arithmetic and almost certainly not what was meant by
        // whoever set the two numbers.
        this.reserve = Math.min(Math.max(0, reserve), this.daily);
        log.info("mail budget: {} a day, {} of it reserved for transactional mail "
                        + "(invitations stop at {})",
                this.daily, this.reserve, coldCeiling());
    }

    /** Messages that may go out today before cold mail stops. */
    public int coldCeiling() {
        return daily - reserve;
    }

    public int dailyBudget() {
        return daily;
    }

    /**
     * Takes one message's worth of today's budget, or refuses.
     *
     * <p>Call this before doing anything that cannot be undone -- before
     * stamping a row as sent, not after. A refusal then costs nothing: the lead
     * stays queued and goes out tomorrow, which is the whole point of knowing
     * the limit rather than discovering it.
     *
     * @return true when the message may be sent
     */
    public boolean claim(Tier tier) {
        int ceiling = tier == Tier.COLD ? coldCeiling() : daily;
        if (ceiling <= 0) {
            return false;
        }
        int cold = tier == Tier.COLD ? 1 : 0;

        // The guard is on the conflict path only, which is correct: the insert
        // creates the day's first row, and one message is always within a
        // ceiling that has already been checked above as positive.
        return db.sql("""
                        insert into mail_budget (day, sent, cold, daily_limit, cold_limit)
                        values (current_date, 1, :cold, :daily, :coldLimit)
                        on conflict (day) do update
                           set sent = mail_budget.sent + 1,
                               cold = mail_budget.cold + :cold,
                               daily_limit = excluded.daily_limit,
                               cold_limit = excluded.cold_limit
                         where mail_budget.sent < :ceiling
                        returning sent
                        """)
                .param("cold", cold)
                .param("daily", daily)
                .param("coldLimit", coldCeiling())
                .param("ceiling", ceiling)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /** What has been spent today. Both counts are zero before the first send. */
    public record Spent(int total, int cold) {}

    public Spent spentToday() {
        return db.sql("select sent, cold from mail_budget where day = current_date")
                .query((rs, n) -> new Spent(rs.getInt("sent"), rs.getInt("cold")))
                .optional()
                .orElse(new Spent(0, 0));
    }
}
