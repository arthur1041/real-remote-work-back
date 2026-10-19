package com.remoteroles.pipeline.newsletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final Limits fallback;

    public MailBudget(
            JdbcClient db,
            @Value("${newsletter.budget.daily:280}") int daily,
            @Value("${newsletter.budget.transactional-reserve:60}") int reserve) {
        this.db = db;
        this.fallback = limits(daily, reserve);
    }

    /**
     * What may be sent today, and how much of it to cold mail.
     *
     * @param daily messages of any kind
     * @param coldCeiling the point at which invitations stop
     */
    public record Limits(int daily, int coldCeiling) {}

    /**
     * Builds the pair, clamping whatever was typed into something coherent.
     *
     * <p>A reserve larger than the budget would make the cold ceiling negative,
     * which is arithmetically true and operationally absurd. A negative budget
     * is not a licence to send. These come from a form and an environment file,
     * which is to say from people, so neither is trusted as given.
     */
    public static Limits limits(int daily, int reserve) {
        int d = Math.max(0, daily);
        int r = Math.min(Math.max(0, reserve), d);
        return new Limits(d, d - r);
    }

    /**
     * The limits in force right now, read from the settings row.
     *
     * <p>From the database rather than configuration, because these are
     * operational decisions somebody makes while watching an import drain --
     * the same kind of thing as the cadence and the audience beside them, and
     * the wrong kind of thing to need a restart.
     *
     * <p>Falls back to the configured values when the row cannot be read, which
     * covers the window before the migration and any failure to reach the
     * database. Failing to a sane limit is better than failing to none.
     */
    private Limits currentLimits() {
        try {
            return db.sql("""
                            select mail_daily_budget, mail_transactional_reserve
                              from newsletter_settings where id = 1
                            """)
                    .query((rs, n) -> limits(
                            rs.getInt("mail_daily_budget"),
                            rs.getInt("mail_transactional_reserve")))
                    .optional()
                    .orElse(fallback);
        } catch (Exception e) {
            log.warn("could not read mail budget settings, using configured fallback: {}",
                    e.getMessage());
            return fallback;
        }
    }

    /** Messages that may go out today before cold mail stops. */
    public int coldCeiling() {
        return currentLimits().coldCeiling();
    }

    public int dailyBudget() {
        return currentLimits().daily();
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
        Limits limits = currentLimits();
        int ceiling = tier == Tier.COLD ? limits.coldCeiling() : limits.daily();
        if (ceiling <= 0) {
            return false;
        }
        int cold = tier == Tier.COLD ? 1 : 0;
        int daily = limits.daily();

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
                .param("coldLimit", limits.coldCeiling())
                .param("ceiling", ceiling)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * Counts a message that was sent regardless of the budget.
     *
     * <p>For the one kind there is: a template preview, which an operator asks
     * for by hand. Refusing it would block the person trying to find out why
     * mail looks wrong, at the moment they most need to look -- but leaving it
     * uncounted is worse than it sounds. The counter's only job is to mirror
     * what the relay has seen, and a counter that runs behind puts the system
     * back to discovering the limit by being refused, which is the thing this
     * class exists to prevent. It is especially wrong when the budget is set to
     * the provider's real ceiling rather than a cautious number under it.
     *
     * <p>So the slot is taken either way, and the ceiling is simply not
     * consulted. If previews push the day past its cap, the next claim is
     * refused — which is correct, because by then the relay is counting the
     * same messages.
     */
    public void record(Tier tier) {
        int cold = tier == Tier.COLD ? 1 : 0;
        Limits limits = currentLimits();
        db.sql("""
                        insert into mail_budget (day, sent, cold, daily_limit, cold_limit)
                        values (current_date, 1, :cold, :daily, :coldLimit)
                        on conflict (day) do update
                           set sent = mail_budget.sent + 1,
                               cold = mail_budget.cold + :cold,
                               daily_limit = excluded.daily_limit,
                               cold_limit = excluded.cold_limit
                        """)
                .param("cold", cold)
                .param("daily", limits.daily())
                .param("coldLimit", limits.coldCeiling())
                .update();
    }

    /**
     * Makes sure today's row exists, with the limits currently configured.
     *
     * <p>Without this the row appears only when the first message is claimed,
     * and until then the admin screen has no limits to show -- it cannot even
     * say "0 of 300", because it deliberately does not keep its own copy of
     * these numbers. A quiet morning would render as an absence, which is
     * indistinguishable on screen from the budget not working.
     *
     * <p>It also refreshes the limits on an already-written row. Since the
     * numbers moved into the settings table that matters less -- every claim
     * reads them -- but it keeps the day's row honest between sends, which is
     * what the admin screen reads.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "${newsletter.budget.open-day-cron:0 1 0 * * *}")
    public void openToday() {
        Limits limits = currentLimits();
        log.info("mail budget: {} a day, {} of it reserved for transactional mail "
                        + "(invitations stop at {})",
                limits.daily(), limits.daily() - limits.coldCeiling(), limits.coldCeiling());
        db.sql("""
                        insert into mail_budget (day, sent, cold, daily_limit, cold_limit)
                        values (current_date, 0, 0, :daily, :coldLimit)
                        on conflict (day) do update
                           set daily_limit = excluded.daily_limit,
                               cold_limit = excluded.cold_limit
                        """)
                .param("daily", limits.daily())
                .param("coldLimit", limits.coldCeiling())
                .update();
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
