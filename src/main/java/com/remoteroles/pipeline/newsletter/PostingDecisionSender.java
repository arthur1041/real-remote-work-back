package com.remoteroles.pipeline.newsletter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Tells the employer what was decided about their role.
 *
 * <p>The counterpart to {@link ReviewAlerter}, which tells the moderator a role
 * is waiting. That half was built first because nothing moves without it; this
 * half is the one the employer experiences. Until now the decision existed only
 * on a page they had to think to revisit, which makes a review that took an
 * hour feel like one that never happened.
 *
 * <p>A rejection matters more here than an approval. The note explaining what
 * was wrong is already written at review time and already stored on the row --
 * it was simply never delivered to the one person it was written for. An
 * approval they will eventually notice; a rejection they will not, and the
 * role sits rejected with the fix sitting unread beside it.
 *
 * <p>Runs on a short cycle. The queue being empty costs one indexed lookup, so
 * what decides when a message actually goes is the settle window below, not the
 * cron.
 *
 * <p>Each decision is announced once, by the stamp on the row. An edit clears
 * that stamp along with the review stamp, so the decision on the edited version
 * is announced as its own decision rather than being swallowed as a repeat.
 *
 * <p><b>One message per employer per sitting, not per posting.</b> This sent one
 * email per decision until 12 Nov 2026, which is correct for the employer who
 * posts one role and ruinous for the one who does not: a single employer
 * submitted <b>107 roles</b>, and approving 79 of them produced <b>79 separate
 * emails to the same address in one afternoon</b> — 243 of that day's allowance
 * of 300 sends, with 28 submissions still waiting and the whole thing about to
 * hit the ceiling. An employer reading 79 receipts for work they did in one
 * sitting is also just worse than reading one.
 *
 * <p>So the pending decisions are grouped by employer, and a group is held
 * until that employer has had no new decision for `settle-minutes`. While a
 * moderator is working through a backlog, nothing goes out; when they stop, one
 * message lists everything. It needs no hook into either approval path --
 * neither the Gemini reviewer nor the admin screen mails anything, they only
 * set the status, so both are picked up by the same sweep and both batch.
 *
 * <p>Approvals and rejections stay separate messages, which is the one thing
 * not collapsed. An approval is a receipt and a rejection is a refusal carrying
 * a reason per posting; a reader who has to sort one from the other in a single
 * list is being handed the moderator's work.
 */
@Service
public class PostingDecisionSender {

    private static final Logger log = LoggerFactory.getLogger(PostingDecisionSender.class);

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;
    private final int settleMinutes;
    private final int maxListed;

    public PostingDecisionSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl,
            @Value("${employer.decision-notice.settle-minutes:10}") int settleMinutes,
            @Value("${employer.decision-notice.max-roles-listed:40}") int maxListed) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        // Zero would mean "send immediately", which is the behaviour this
        // replaced; it is allowed, because a board with one submission a week
        // has no batch to form and no reason to wait ten minutes.
        this.settleMinutes = Math.max(0, settleMinutes);
        this.maxListed = Math.max(1, maxListed);
    }

    /**
     * One decided submission.
     *
     * @param jobId the published row, present on an approval and null otherwise
     * @param note  what the moderator wrote, which only a rejection carries
     */
    record Decided(long id, long employerId, String email, String title, String company,
                   String contact, boolean approved, Long jobId, String note) {

        /** Employer and outcome: the two things that have to match to share a message. */
        String groupKey() {
            return employerId + (approved ? ":approved" : ":rejected");
        }
    }

    /**
     * How the message opens, or nothing at all.
     *
     * <p>This email had no greeting: it began with the role's own title,
     * which reads as a system announcing an outcome. A board whose whole
     * claim is that a person read the posting can afford one line that sounds
     * like one.
     *
     * <p>Empty rather than "Hi ," on the accounts made before the name was
     * asked for. A greeting with a hole in it is worse than none, and the
     * paragraph below it stands on its own either way.
     */
    private static String greeting(Decided row) {
        String name = row.contact();
        if (name == null || name.isBlank()) return "";
        // First word only. Somebody who typed their full name is not expecting
        // to be greeted with all of it.
        String first = name.trim().split("\\s+")[0];
        return "Hi %s,%n%n".formatted(first);
    }

    @Scheduled(cron = "${employer.decision-notice.cron:0 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many messages were sent, which is no longer how many decisions went out. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }

        // The `not exists` is the settle window, and it is per employer: a
        // decision is held while ANY undelivered decision of that employer's is
        // newer than the window. One moderator working down a queue therefore
        // produces one message at the end, however long they take, while an
        // employer who submitted one role waits exactly the window and no
        // longer. Keyed on `reviewed_at`, which both approval paths set -- the
        // Gemini reviewer publishes through the web app and the admin screen
        // writes the same columns, so neither needs to know this exists.
        //
        // A null `reviewed_at` never matches the comparison, so a row with one
        // reads as quiet and goes out rather than being held forever by a
        // column nobody filled in.
        //
        // The limit is generous rather than small because the point is to put a
        // whole sitting in one message: 107 submissions from a single employer
        // is a real number here, not a hypothetical. Anything past it is the
        // next cycle's problem and costs a second message.
        List<Decided> waiting = db.sql("""
                        select p.id, p.employer_id, e.email, p.title, e.company_name,
                               e.contact_name, p.status, p.job_id,
                               coalesce(p.review_note, '') as review_note
                          from employer_postings p
                          join employers e on e.id = p.employer_id
                         where p.decision_notified_at is null
                           and p.status in ('APPROVED', 'REJECTED')
                           and not exists (
                                 select 1
                                   from employer_postings q
                                  where q.employer_id = p.employer_id
                                    and q.decision_notified_at is null
                                    and q.status in ('APPROVED', 'REJECTED')
                                    and q.reviewed_at
                                        > now() - cast(:settle as int) * interval '1 minute')
                         order by p.employer_id, p.status, p.reviewed_at
                         limit 500
                        """)
                .param("settle", settleMinutes)
                .query((rs, n) -> new Decided(
                        rs.getLong("id"),
                        rs.getLong("employer_id"),
                        rs.getString("email"),
                        rs.getString("title"),
                        rs.getString("company_name"),
                        rs.getString("contact_name"),
                        "APPROVED".equals(rs.getString("status")),
                        rs.getObject("job_id") == null ? null : rs.getLong("job_id"),
                        rs.getString("review_note")))
                .list();

        if (waiting.isEmpty()) {
            return 0;
        }

        Map<String, List<Decided>> groups = group(waiting);

        int sent = 0;
        int announced = 0;
        for (List<Decided> group : groups.values()) {
            // Transactional: this answers something a person asked for, and it
            // is the tier the day's reserve exists to protect. Held rather than
            // dropped if the budget is gone -- the rows stay unstamped and go
            // out tomorrow, now as one message instead of twenty.
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("posting decision notices held: the day's whole mail budget is spent");
                break;
            }
            // Stamped before sending, as everywhere else here, and the whole
            // group in one statement: stamping afterwards would mail the same
            // batch every cycle until somebody noticed, and stamping row by row
            // would leave a crash in the middle with half a batch delivered and
            // the other half about to arrive on its own.
            db.sql("update employer_postings set decision_notified_at = now() where id in (:ids)")
                    .param("ids", group.stream().map(Decided::id).toList())
                    .update();
            mailer.send(group.get(0).email(), subject(group), body(group), html(group));
            sent++;
            announced += group.size();
        }

        if (sent > 0) {
            log.info("posting decision notices sent: {} message(s) for {} decision(s)",
                    sent, announced);
        }
        return sent;
    }

    /**
     * One message's worth of rows per entry: same employer, same outcome.
     *
     * <p>Separate from the sweep and pure, because this is the half where being
     * wrong is expensive and invisible. A key that collapsed too much would
     * send one employer's decisions to another employer's address; a key that
     * collapsed too little would quietly go back to one mail per posting, which
     * is the bug this exists to fix and which nothing in a log would announce.
     *
     * <p>Insertion-ordered, so messages go out in the order the query returned
     * the rows rather than in hash order. That matters only for reading the log
     * and costs nothing.
     */
    static Map<String, List<Decided>> group(List<Decided> rows) {
        Map<String, List<Decided>> groups = new LinkedHashMap<>();
        for (Decided row : rows) {
            groups.computeIfAbsent(row.groupKey(), k -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    /**
     * The three group-aware halves, each of which hands a group of one straight
     * back to the single-decision version.
     *
     * <p>That delegation is the point rather than an optimisation: one role
     * approved is still the common case, that message was written and looked at
     * carefully, and a batch format applied to a single item reads like a
     * mail-merge of one. Nothing about the one-role mail changed here.
     */
    String subject(List<Decided> group) {
        if (group.size() == 1) {
            return subject(group.get(0));
        }
        return group.get(0).approved()
                ? "%d roles are live on Actually Remote".formatted(group.size())
                : "%d submissions were not published".formatted(group.size());
    }

    String body(List<Decided> group) {
        return group.size() == 1 ? body(group.get(0)) : batchBody(group);
    }

    String html(List<Decided> group) {
        return group.size() == 1 ? html(group.get(0)) : batchHtml(group);
    }

    /**
     * How many of a batch are listed, and the sentence that accounts for the
     * rest.
     *
     * <p>Not unlimited, because Gmail clips a message over about 102KB and
     * replaces the tail with a "view entire message" link. A listed role is
     * roughly 500 bytes of HTML, so forty of them is about 20KB and safe, while
     * the 107-submission employer who started all this would have produced a
     * message clipped in the middle -- at which point the last roles in it are
     * less visible than if they had never been listed.
     */
    String remainder(List<Decided> group) {
        int hidden = group.size() - Math.min(group.size(), maxListed);
        return hidden == 0 ? "" : "and %d more".formatted(hidden);
    }

    private String batchBody(List<Decided> group) {
        Decided first = group.get(0);
        List<Decided> listed = group.subList(0, Math.min(group.size(), maxListed));
        StringBuilder items = new StringBuilder();

        if (first.approved()) {
            for (Decided row : listed) {
                items.append("  %s%n  %s%n%n".formatted(row.title(), roleUrl(row)));
            }
            if (!remainder(group).isEmpty()) {
                items.append("  ... %s, all of them in your dashboard.%n%n"
                        .formatted(remainder(group)));
            }
            return greeting(first) + """
                    %d roles are published and anyone can find them now:

                    %sThey are in the featured section for as long as they are open, with the \
                    newest taking the most turns in it, and each one appears in the \
                    searches that match it. Edit any of them or take them \
                    down any time from your dashboard:

                    %s/employers

                    — Actually Remote
                    %s
                    """.formatted(group.size(), items, siteUrl, siteUrl);
        }

        for (Decided row : listed) {
            items.append("  %s%n    %s%n%n".formatted(row.title(), note(row)));
        }
        if (!remainder(group).isEmpty()) {
            items.append("  ... %s, each with its reason in your dashboard.%n%n"
                    .formatted(remainder(group)));
        }
        return greeting(first) + """
                %d submissions were reviewed and not published, each for the reason \
                under it:

                %sNothing was lost. They are all still in your dashboard, and editing one \
                sends it back for review — there is no need to fill the form in again:

                %s/employers

                — Actually Remote
                %s
                """.formatted(group.size(), items, siteUrl, siteUrl);
    }

    private String batchHtml(List<Decided> group) {
        Decided first = group.get(0);
        List<Decided> listed = group.subList(0, Math.min(group.size(), maxListed));
        String card;

        if (first.approved()) {
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < listed.size(); i++) {
                Decided row = listed.get(i);
                rows.append(EmailLayout.roleRow(
                        row.title(), row.company(), roleUrl(row), i == listed.size() - 1));
            }
            card = EmailLayout.eyebrow("Published")
                    + EmailLayout.heading("%d roles are live".formatted(group.size()))
                    + EmailLayout.paragraph(
                            "They are on the board and anyone can find them now.", true)
                    + EmailLayout.roleList(rows.toString())
                    + (remainder(group).isEmpty() ? ""
                            : EmailLayout.paragraph("&hellip; " + remainder(group)
                                    + ", all of them in "
                                    + EmailLayout.textLink(siteUrl + "/employers",
                                            "your dashboard")
                                    + "."))
                    + EmailLayout.rule()
                    + EmailLayout.paragraph(
                            "Each sits in the featured section for as long as it is open, "
                                    + "the newest taking the most turns in it, and appears "
                                    + "in the searches that match it. Edit any of them or take "
                                    + "them down any time from "
                                    + EmailLayout.textLink(siteUrl + "/employers",
                                            "your dashboard")
                                    + ".");
        } else {
            StringBuilder fields = new StringBuilder();
            for (Decided row : listed) {
                /* The title raw, the reason escaped, and that asymmetry is
                   `EmailLayout.field`'s: it escapes the label itself and takes
                   the value as markup. Escaping the title here too put the
                   entity in front of the employer -- "Sales &amp;amp;
                   Partnerships" for a role called "Sales & Partnerships", and
                   two of this board's own categories are spelt with an
                   ampersand. */
                fields.append(EmailLayout.field(row.title(), EmailLayout.escape(note(row))));
            }
            card = EmailLayout.eyebrow("Not published")
                    + EmailLayout.heading("%d submissions were turned down"
                            .formatted(group.size()))
                    + EmailLayout.paragraph(
                            "Each was reviewed, and the reason is under its title.", true)
                    + fields
                    + (remainder(group).isEmpty() ? ""
                            : EmailLayout.paragraph("&hellip; " + remainder(group)
                                    + ", each with its reason in "
                                    + EmailLayout.textLink(siteUrl + "/employers",
                                            "your dashboard")
                                    + "."))
                    + EmailLayout.rule()
                    + EmailLayout.paragraph(
                            "Nothing was lost. They are all still in your dashboard, and "
                                    + "editing one sends it back for review &mdash; there is "
                                    + "no need to fill the form in again.")
                    + EmailLayout.button(siteUrl + "/employers", "Open the submissions");
        }

        return EmailLayout.document(
                first.approved()
                        ? "%d roles are live.".formatted(group.size())
                        : "%d submissions were not published.".formatted(group.size()),
                card,
                // No unsubscribe, for the same reason as the single version: this
                // is the answer to something they submitted, not a list they
                // joined.
                EmailLayout.footer(siteUrl, null, null));
    }

    private String subject(Decided row) {
        return row.approved()
                ? "%s is live on Actually Remote".formatted(row.title())
                : "%s was not published".formatted(row.title());
    }

    private String roleUrl(Decided row) {
        return row.jobId() == null ? siteUrl : siteUrl + "/jobs/" + row.jobId();
    }

    /**
     * Plain text.
     *
     * <p>The approval leads with the address of the thing, because that is the
     * entire content of the message and anything before it is in the way. The
     * rejection leads with the reason, for the same reason inverted: the outcome
     * is already in the subject line, and what the reader needs is the sentence
     * that tells them what to change.
     */
    private String body(Decided row) {
        if (row.approved()) {
            return greeting(row) + """
                    %s is published and anyone can find it now:

                    %s

                    It is in the featured section for as long as it is open, with the \
                    newest roles taking the most turns in it, and it appears in \
                    the searches that match it. Edit or take it down any time from \
                    your dashboard:

                    %s/employers

                    — Actually Remote
                    %s
                    """.formatted(row.title(), roleUrl(row), siteUrl, siteUrl);
        }

        return greeting(row) + """
                %s was reviewed and not published.

                  %s

                Nothing was lost. The submission is still in your dashboard, and \
                editing it sends it back for review — there is no need to fill the \
                form in again:

                %s/employers

                — Actually Remote
                %s
                """.formatted(row.title(), note(row), siteUrl, siteUrl);
    }

    /** The laid-out half. */
    private String html(Decided row) {
        String card = row.approved()
                ? EmailLayout.eyebrow("Published")
                        + EmailLayout.heading(row.title())
                        + EmailLayout.paragraph(
                                "It is on the board and anyone can find it now.", true)
                        + EmailLayout.button(roleUrl(row), "See it on the board")
                        + EmailLayout.rule()
                        + EmailLayout.paragraph(
                                "It sits in the featured section for as long as it is "
                                        + "open, with the newest taking the most turns in it, "
                                        + "and it appears in the searches that match it. Edit it or "
                                        + "take it down any time from "
                                        + EmailLayout.textLink(siteUrl + "/employers",
                                                "your dashboard")
                                        + ".")
                : EmailLayout.eyebrow("Not published")
                        + EmailLayout.heading(row.title())
                        + EmailLayout.paragraph("It was reviewed and turned down.", true)
                        + EmailLayout.field("Why", EmailLayout.escape(note(row)))
                        + EmailLayout.rule()
                        + EmailLayout.paragraph(
                                "Nothing was lost. The submission is still in your dashboard, "
                                        + "and editing it sends it back for review &mdash; "
                                        + "there is no need to fill the form in again.")
                        + EmailLayout.button(siteUrl + "/employers", "Open the submission");

        return EmailLayout.document(
                row.approved()
                        ? "%s is live.".formatted(row.title())
                        : "%s was not published.".formatted(row.title()),
                card,
                // No unsubscribe. This is the answer to something they submitted,
                // not a list they joined, and an opt-out on it would offer to
                // stop telling them the outcome of their own requests.
                EmailLayout.footer(siteUrl, null, null));
    }

    /** The moderator's note, or a usable sentence when the field came through empty. */
    private String note(Decided row) {
        String note = row.note() == null ? "" : row.note().trim();
        return note.isBlank()
                ? "It did not meet what this board lists. Reply to this message for the detail."
                : note;
    }

    /**
     * Sends both halves to one address, for looking at.
     *
     * <p>Separately addressable because they are not variants of one message.
     * An approval is a receipt and a rejection is a refusal carrying a reason,
     * and a change to one says nothing about the other -- so reviewing a change
     * to either should not put the other in the inbox beside it.
     */
    public void sendPreview(String to) {
        sendApprovedPreview(to);
        sendRejectedPreview(to);
        sendApprovedBatchPreview(to);
        sendRejectedBatchPreview(to);
    }

    public void sendApprovedPreview(String to) {
        /* The two previews carry a contact name and no contact name on purpose.
           Nothing else exercises this class -- there is no test for it -- so the
           pair is what shows that the greeting appears when the account has a
           name and that the message still opens properly when it does not,
           which is every account made before the name was asked for. */
        Decided row = new Decided(
                0, 0, to, "Senior Platform Engineer", "Northwind Systems", "Dana Whitfield",
                true, 849298L, "");
        mailer.send(to, "[preview] " + subject(row), body(row), html(row));
    }

    public void sendRejectedPreview(String to) {
        Decided row = new Decided(
                0, 0, to, "Senior Platform Engineer", "Northwind Systems", null, false, null,
                "The advert says candidates must be eligible to work in the EU, which does "
                        + "not match the worldwide eligibility declared on the form.");
        mailer.send(to, "[preview] " + subject(row), body(row), html(row));
    }

    /**
     * The batched halves, previewable in their own right.
     *
     * <p>Four previews rather than two, because the batch is not a longer
     * version of the single message -- it has its own subject, its own opening
     * sentence and, in the approved case, a list layout that the single message
     * does not use at all. The thing that produced it, an employer submitting a
     * hundred roles at once, is also not something an operator can conjure up
     * to look at.
     */
    public void sendApprovedBatchPreview(String to) {
        List<Decided> group = List.of(
                new Decided(0, 0, to, "Senior Platform Engineer", "Northwind Systems",
                        "Dana Whitfield", true, 849298L, ""),
                new Decided(0, 0, to, "Staff Data Engineer", "Northwind Systems",
                        "Dana Whitfield", true, 849301L, ""),
                new Decided(0, 0, to, "Product Designer (Worldwide)", "Northwind Systems",
                        "Dana Whitfield", true, 849305L, ""));
        mailer.send(to, "[preview] " + subject(group), body(group), html(group));
    }

    public void sendRejectedBatchPreview(String to) {
        List<Decided> group = List.of(
                new Decided(0, 0, to, "Senior Platform Engineer", "Northwind Systems",
                        "Dana Whitfield", false, null,
                        "The advert says candidates must be eligible to work in the EU, which "
                                + "does not match the worldwide eligibility declared on the "
                                + "form."),
                new Decided(0, 0, to, "Staff Data Engineer", "Northwind Systems",
                        "Dana Whitfield", false, null,
                        "The description is a copy of the role above with the title changed."),
                // Deliberately empty, which is what exercises the fallback
                // sentence in note(): a rejection whose reason never got typed
                // still has to read as a reason.
                new Decided(0, 0, to, "Product Designer (Worldwide)", "Northwind Systems",
                        "Dana Whitfield", false, null, ""));
        mailer.send(to, "[preview] " + subject(group), body(group), html(group));
    }
}
