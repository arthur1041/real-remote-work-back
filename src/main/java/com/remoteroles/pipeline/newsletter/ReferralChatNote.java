package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells candidates about the referral program, in their chat on the site.
 *
 * <p><strong>Why the chat.</strong> Owner, 25 Nov 2026: the chat is the one
 * place a candidate opens on their own and keeps coming back to, and in-app
 * notes are how platforms of this kind tell people about a program. The note
 * arrives as a message from "Actually Remote" in the candidate's one SITE
 * conversation (V90), with the unread badge; it is not emailed --
 * {@link UnreadMessageNotifier} skips anything with a campaign -- and it is
 * not pushed, because the site's push only knows conversations with an
 * employer.
 *
 * <p><strong>How often.</strong> Rare on purpose, because a note that repeats
 * is a note people learn to ignore, and the badge on this chat usually means
 * an employer wrote:
 * <ul>
 *   <li>the first note {@value #FIRST_AFTER_DAYS} days after a verified
 *   sign-up, so it never lands in the same sitting as creating the account;</li>
 *   <li>a reminder every {@value #REMIND_EVERY_DAYS} days after that, at
 *   most {@value #MAX_NOTES} notes in all;</li>
 *   <li>never to somebody already referring -- a visit through their link in
 *   the last {@value #REMIND_EVERY_DAYS} days, or an employer they brought --
 *   because telling them about a program they are using is noise;</li>
 *   <li>never again to somebody who closed the conversation, which is the
 *   opt-out, and never to a closed, suspended or unverified account;</li>
 *   <li>at most {@value #PER_RUN} a day, so the day this ships does not put
 *   every account's badge up at once and fill the owner's inbox with the
 *   answers.</li>
 * </ul>
 *
 * <p>The owner reads and answers replies in /admin/messages.
 */
@Service
public class ReferralChatNote {

    private static final Logger log = LoggerFactory.getLogger(ReferralChatNote.class);

    static final int FIRST_AFTER_DAYS = 3;
    static final int REMIND_EVERY_DAYS = 90;
    static final int MAX_NOTES = 4;
    static final int PER_RUN = 150;

    static final String INTRO = "REFERRAL_INTRO";
    static final String REMINDER = "REFERRAL_REMINDER";

    /**
     * The most one employer can earn a referrer, as the site states it. Kept in
     * step by hand with REFERRAL_MAX_CENTS in web/lib/referral-rules.ts (Growth
     * annual); the test pins it so a change there fails here.
     */
    static final String MAX_EARNING = "$100";

    private final JdbcClient db;
    private final String siteUrl;
    private final boolean enabled;

    public ReferralChatNote(
            JdbcClient db,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${referrals.chat-note.enabled:true}") boolean enabled) {
        this.db = db;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        this.enabled = enabled;
    }

    record Due(long candidateId, String name, int notesSoFar) {}

    @Scheduled(cron = "${referrals.chat-note.cron:0 0 15 * * *}")
    public void run() {
        if (enabled) send();
    }

    /** Returns how many notes were written. */
    public int send() {
        List<Due> due = db.sql("""
                with notes as (
                  select c.candidate_id, count(*) as n, max(m.created_at) as last_at
                    from conversation_messages m
                    join conversations c on c.id = m.conversation_id
                   where c.kind = 'SITE' and m.campaign in ('REFERRAL_INTRO', 'REFERRAL_REMINDER')
                   group by c.candidate_id
                )
                select cand.id, cand.full_name, coalesce(n.n, 0) as notes
                  from candidates cand
             left join notes n on n.candidate_id = cand.id
                 where cand.status = 'ACTIVE'
                   and cand.deleted_at is null
                   and cand.email_verified_at is not null
                   and cand.created_at < now() - make_interval(days => :first)
                   and coalesce(n.n, 0) < :max
                   and (n.last_at is null or n.last_at < now() - make_interval(days => :every))
                   and not exists (select 1 from conversations s
                                    where s.kind = 'SITE' and s.candidate_id = cand.id
                                      and s.candidate_blocked_at is not null)
                   and not exists (select 1 from referral_visits v
                                    where v.candidate_id = cand.id
                                      and v.visited_at > now() - make_interval(days => :every))
                   and not exists (select 1 from employers e
                                    where e.referred_by_candidate_id = cand.id)
                 order by n.last_at nulls first, cand.created_at desc
                 limit :limit
                """)
                .param("first", FIRST_AFTER_DAYS)
                .param("max", MAX_NOTES)
                .param("every", REMIND_EVERY_DAYS)
                .param("limit", PER_RUN)
                .query((rs, i) -> new Due(rs.getLong("id"), rs.getString("full_name"), rs.getInt("notes")))
                .list();

        int sent = 0;
        for (Due d : due) {
            Long conversation = db.sql("""
                    with made as (
                      insert into conversations (kind, candidate_id) values ('SITE', :id)
                      on conflict (candidate_id) where kind = 'SITE' do nothing
                      returning id
                    )
                    select id from made
                    union all
                    select id from conversations where kind = 'SITE' and candidate_id = :id
                    limit 1
                    """)
                    .param("id", d.candidateId())
                    .query(Long.class)
                    .single();
            boolean first = d.notesSoFar() == 0;
            db.sql("""
                    insert into conversation_messages (conversation_id, sender, body, campaign, notified_at)
                    values (:c, 'SITE', :body, :campaign, now())
                    """)
                    .param("c", conversation)
                    .param("body", first ? intro(d.name()) : reminder(d.name()))
                    .param("campaign", first ? INTRO : REMINDER)
                    .update();
            db.sql("update conversations set last_message_at = now() where id = :c")
                    .param("c", conversation)
                    .update();
            sent++;
        }
        if (sent > 0) log.info("referral chat notes: {} written", sent);
        return sent;
    }

    /** The first note. Says what, how much, where, and that a reply reaches a person. */
    String intro(String name) {
        return """
                Hi %s, a quick note from Actually Remote.

                Know a company that hires remotely? Refer it to us and earn a \
                commission when it subscribes to a plan: up to %s per employer, \
                paid by PayPal.

                Your link and code are here: %s/account/referrals
                How it works: %s/refer

                Any questions, just reply here.""".formatted(
                        UnreadMessageNotifier.firstName(name), MAX_EARNING, siteUrl, siteUrl);
    }

    /** Every later note: shorter, and it says it is a reminder. */
    String reminder(String name) {
        return """
                Hi %s, a reminder that referring a company that hires remotely \
                earns you a commission when it subscribes: up to %s per employer.

                Your link and code: %s/account/referrals

                Questions? Reply here.""".formatted(
                        UnreadMessageNotifier.firstName(name), MAX_EARNING, siteUrl);
    }
}
