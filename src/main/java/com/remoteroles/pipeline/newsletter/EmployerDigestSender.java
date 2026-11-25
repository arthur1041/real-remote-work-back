package com.remoteroles.pipeline.newsletter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The weekly summary for employers: what happened on their roles in the last
 * seven days, and what is waiting for them.
 *
 * <p><strong>Why it exists.</strong> The commonest failure here is not the
 * product breaking but the employer not coming back. On 25 Nov 2026 the one
 * real Growth account had two applications screened 9.0 and 3.5, both still
 * NEW, unopened; most Free accounts posted once and never returned. This brings
 * each of them back to the thing that is waiting, once a week.
 *
 * <p><strong>No news, no email.</strong> Asked for in those words by the owner,
 * and the rule the whole class turns on ({@link #hasNews}). A week with no
 * application, no message, nothing to fix and almost no traffic sends nothing:
 * "your role had 0 views" teaches somebody to stop reading these.
 *
 * <p><strong>Small numbers are said carefully.</strong> The board is weeks old.
 * The concrete things lead (an application, a message, a role to fix) and the
 * traffic follows; a change against last week is only stated when last week was
 * big enough for a percentage to mean anything ({@link #trend}).
 *
 * <p><strong>One main button</strong>, for the most valuable thing that week
 * ({@link #mainAction}): unread messages, then new applications, then a role to
 * fix, then the dashboard.
 *
 * <p>Recurring and unasked-for, so it carries an unsubscribe -- the one employer
 * mail that does (V88). The upgrade pitch planned for Free accounts is held out
 * of this first version on purpose; see docs/TODOs.md.
 */
@Service
public class EmployerDigestSender {

    private static final Logger log = LoggerFactory.getLogger(EmployerDigestSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How many roles are listed before "and N more". Anabel's account has 510 live. */
    static final int ROLES_LISTED = 8;

    /** Below this many views in a week, traffic alone is not news. */
    static final int VIEWS_WORTH_SAYING = 10;

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public EmployerDigestSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    /** One live role's week. `bestScore` is the highest screening score among this week's applications, or null. */
    record RoleWeek(long postingId, String title, boolean takesApplications, int views, int previousViews,
                    int clicks, int applications, Double bestScore) {

        boolean quiet() {
            return views == 0 && clicks == 0 && applications == 0;
        }
    }

    record Titled(long postingId, String title) {}

    record Week(long employerId, String email, String company, String contact, String optOutToken,
                List<RoleWeek> roles, int unreadMessages, List<Titled> needsWork, List<Titled> expiring) {

        int views() { return roles.stream().mapToInt(RoleWeek::views).sum(); }
        int clicks() { return roles.stream().mapToInt(RoleWeek::clicks).sum(); }
        int applications() { return roles.stream().mapToInt(RoleWeek::applications).sum(); }
    }

    record Action(String label, String url) {}

    @Scheduled(cron = "${employer.digest.cron:0 0 12 * * MON}")
    public void run() {
        sendWeek();
    }

    /** Returns how many digests went out. */
    public int sendWeek() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }
        int sent = 0;
        int quiet = 0;
        for (long employerId : eligible()) {
            Week week = weekOf(employerId);
            if (week == null || !hasNews(week)) {
                quiet++;
                continue;
            }
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("employer digest stopped after {}: the day's mail budget is spent", sent);
                break;
            }
            // Recorded before sending: a crash between the two costs one digest,
            // the other order sends it twice next run.
            db.sql("insert into employer_digests (employer_id, summary) values (:id, cast(:summary as jsonb))")
                    .param("id", employerId)
                    .param("summary", summaryJson(week))
                    .update();
            mailer.send(week.email(), subject(week), text(week), html(week), oneClickStop(week));
            sent++;
        }
        log.info("employer digest: {} sent, {} with nothing new this week", sent, quiet);
        return sent;
    }

    /**
     * Accounts that could be sent one: active, confirmed, not opted out, not
     * sent one in the last six days, and with a role live, closed this week, or
     * waiting on a change.
     */
    private List<Long> eligible() {
        return db.sql("""
                        select e.id
                          from employers e
                         where e.status = 'ACTIVE'
                           and e.email_verified_at is not null
                           and e.digest_opt_out_at is null
                           and not exists (select 1 from employer_digests d
                                            where d.employer_id = e.id
                                              and d.sent_at > now() - interval '6 days')
                           and exists (select 1 from employer_postings p
                                         left join jobs j on j.id = p.job_id
                                        where p.employer_id = e.id
                                          and ((p.status = 'APPROVED'
                                                and (j.closed_at is null
                                                     or j.closed_at > now() - interval '7 days'))
                                               or (p.status = 'REJECTED' and p.needs_work)))
                         order by e.id
                        """)
                .query(Long.class)
                .list();
    }

    /** Everything one employer's digest says. Null if the account vanished between the two reads. */
    Week weekOf(long employerId) {
        Map<String, Object> e = db.sql("""
                        select email, company_name, contact_name, digest_opt_out_token
                          from employers where id = :id
                        """)
                .param("id", employerId)
                .query()
                .listOfRows()
                .stream().findFirst().orElse(null);
        if (e == null) return null;

        List<RoleWeek> roles = db.sql("""
                        select p.id, p.title, p.apply_via_platform,
                               coalesce(sum(s.views) filter (where s.day > current_date - 7), 0) as views,
                               coalesce(sum(s.views) filter (where s.day <= current_date - 7
                                                              and s.day > current_date - 14), 0) as prev_views,
                               coalesce(sum(s.apply_clicks) filter (where s.day > current_date - 7), 0) as clicks,
                               (select count(*) from job_applications a
                                 where a.posting_id = p.id
                                   and a.created_at > now() - interval '7 days') as applications,
                               (select max(sc.score) from job_applications a
                                  join application_screenings sc on sc.application_id = a.id
                                 where a.posting_id = p.id
                                   and a.created_at > now() - interval '7 days') as best_score
                          from employer_postings p
                          join jobs j on j.id = p.job_id
                     left join job_daily_stats s on s.job_id = p.job_id
                         where p.employer_id = :id
                           and p.status = 'APPROVED'
                           and (j.closed_at is null or j.closed_at > now() - interval '7 days')
                         group by p.id
                        """)
                .param("id", employerId)
                .query((rs, n) -> new RoleWeek(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getBoolean("apply_via_platform"),
                        rs.getInt("views"),
                        rs.getInt("prev_views"),
                        rs.getInt("clicks"),
                        rs.getInt("applications"),
                        rs.getObject("best_score") == null ? null : rs.getDouble("best_score")))
                .list();
        roles = new ArrayList<>(roles);
        // Applications first, then traffic: the order the email reads in.
        roles.sort((a, b) -> a.applications() != b.applications()
                ? Integer.compare(b.applications(), a.applications())
                : a.clicks() != b.clicks()
                        ? Integer.compare(b.clicks(), a.clicks())
                        : Integer.compare(b.views(), a.views()));

        int unread = db.sql("""
                        select count(*) from conversation_messages m
                          join conversations c on c.id = m.conversation_id
                         where c.employer_id = :id
                           and m.sender = 'CANDIDATE'
                           and c.employer_blocked_at is null
                           and (c.employer_read_at is null or m.created_at > c.employer_read_at)
                        """)
                .param("id", employerId)
                .query(Integer.class)
                .single();

        List<Titled> needsWork = db.sql("""
                        select id, title from employer_postings
                         where employer_id = :id and status = 'REJECTED' and needs_work
                         order by reviewed_at desc
                        """)
                .param("id", employerId)
                .query((rs, n) -> new Titled(rs.getLong("id"), rs.getString("title")))
                .list();

        List<Titled> expiring = db.sql("""
                        select p.id, p.title from employer_postings p
                          join jobs j on j.id = p.job_id
                         where p.employer_id = :id and p.status = 'APPROVED' and j.closed_at is null
                           and p.expires_at between current_date and current_date + 7
                         order by p.expires_at
                        """)
                .param("id", employerId)
                .query((rs, n) -> new Titled(rs.getLong("id"), rs.getString("title")))
                .list();

        return new Week(employerId,
                (String) e.get("email"),
                (String) e.get("company_name"),
                (String) e.get("contact_name"),
                (String) e.get("digest_opt_out_token"),
                roles, unread, needsWork, expiring);
    }

    /** Whether this week is worth an email at all. The rule the class is built on. */
    static boolean hasNews(Week w) {
        return w.applications() > 0
                || w.unreadMessages() > 0
                || !w.needsWork().isEmpty()
                || !w.expiring().isEmpty()
                || w.clicks() > 0
                || w.views() >= VIEWS_WORTH_SAYING;
    }

    /** The one thing most worth doing this week. */
    Action mainAction(Week w) {
        if (w.unreadMessages() > 0) {
            return new Action("Read your messages", siteUrl + "/employers/messages");
        }
        if (w.applications() > 0) {
            List<RoleWeek> with = w.roles().stream().filter(r -> r.applications() > 0).toList();
            String url = with.size() == 1
                    ? siteUrl + "/employers/applications/" + with.get(0).postingId()
                    : siteUrl + "/employers";
            return new Action(w.applications() == 1 ? "Read the application" : "Read the applications", url);
        }
        if (!w.needsWork().isEmpty()) {
            return new Action("Make the change", siteUrl + "/employers/post?edit=" + w.needsWork().get(0).postingId());
        }
        return new Action("Open your dashboard", siteUrl + "/employers");
    }

    static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    String subject(Week w) {
        if (w.unreadMessages() > 0) {
            return "Your week on Actually Remote: " + plural(w.unreadMessages(), "unread message", "unread messages");
        }
        if (w.applications() > 0) {
            return "Your week on Actually Remote: " + plural(w.applications(), "new application", "new applications");
        }
        if (!w.needsWork().isEmpty()) {
            return "Your week on Actually Remote: a role needs one change";
        }
        if (w.clicks() > 0) {
            return "Your week on Actually Remote: " + plural(w.clicks(), "person", "people") + " clicked apply";
        }
        return "Your week on Actually Remote: " + plural(w.views(), "view", "views") + " on your roles";
    }

    /**
     * Against last week, only when last week is big enough for a percentage to
     * mean something. "Up 200%" on three views going to nine is the kind of
     * number that makes the whole email read as noise.
     *
     * <p>Only a rise is said. The first real render (25 Nov 2026) listed
     * "down 71% on last week" against a role with seven views: on numbers this
     * small a fall is mostly noise, and it is the line that teaches somebody the
     * email is bad news.
     */
    static String trend(RoleWeek r) {
        if (r.previousViews() < 20) return "";
        long change = Math.round(100.0 * (r.views() - r.previousViews()) / r.previousViews());
        return change >= 10 ? " (up " + change + "% on last week)" : "";
    }

    /** One line about one role. Leads with applications, then clicks, then views. */
    static String roleLine(RoleWeek r) {
        List<String> parts = new ArrayList<>();
        if (r.applications() > 0) {
            String apps = plural(r.applications(), "new application", "new applications");
            if (r.bestScore() != null) {
                apps += ", best screened " + formatScore(r.bestScore()) + "/10";
            }
            parts.add(apps);
        }
        if (r.clicks() > 0) parts.add(plural(r.clicks(), "person", "people") + " clicked apply");
        if (r.views() > 0) parts.add(plural(r.views(), "view", "views") + trend(r));
        return String.join(" · ", parts);
    }

    private static String formatScore(double score) {
        return score == Math.rint(score) ? String.valueOf((long) score) : String.valueOf(score);
    }

    /** The opening sentence: the week in one line, concrete things first. */
    static String lede(Week w) {
        List<String> said = new ArrayList<>();
        if (w.applications() > 0) said.add(plural(w.applications(), "new application", "new applications"));
        if (w.unreadMessages() > 0) said.add(plural(w.unreadMessages(), "unread message", "unread messages"));
        if (w.clicks() > 0) said.add(plural(w.clicks(), "apply click", "apply clicks"));
        if (w.views() > 0) said.add(plural(w.views(), "view", "views"));
        if (said.isEmpty()) return "Here is what needs you on your roles this week.";
        String joined = said.size() == 1 ? said.get(0)
                : String.join(", ", said.subList(0, said.size() - 1)) + " and " + said.get(said.size() - 1);
        return "In the last seven days your roles had " + joined + ".";
    }

    private List<RoleWeek> listed(Week w) {
        return w.roles().stream().filter(r -> !r.quiet()).limit(ROLES_LISTED).toList();
    }

    private int unlisted(Week w) {
        return (int) w.roles().stream().filter(r -> !r.quiet()).count() - listed(w).size();
    }

    String text(Week w) {
        StringBuilder out = new StringBuilder();
        if (w.contact() != null && !w.contact().isBlank()) {
            out.append("Hi ").append(w.contact().trim().split("\\s+")[0]).append(",\n\n");
        }
        out.append(lede(w)).append("\n\n");
        if (w.unreadMessages() > 0 || !w.needsWork().isEmpty() || !w.expiring().isEmpty()) {
            out.append("WAITING FOR YOU\n");
            if (w.unreadMessages() > 0) {
                out.append("  - ").append(plural(w.unreadMessages(), "unread message", "unread messages"))
                   .append(" from candidates: ").append(siteUrl).append("/employers/messages\n");
            }
            for (Titled t : w.needsWork()) {
                out.append("  - ").append(t.title()).append(" needs one change before it goes live: ")
                   .append(siteUrl).append("/employers/post?edit=").append(t.postingId()).append('\n');
            }
            for (Titled t : w.expiring()) {
                out.append("  - ").append(t.title()).append(" closes within a week\n");
            }
            out.append('\n');
        }
        List<RoleWeek> listed = listed(w);
        if (!listed.isEmpty()) {
            out.append("YOUR ROLES THIS WEEK\n");
            for (RoleWeek r : listed) {
                out.append("  ").append(r.title()).append("\n    ").append(roleLine(r)).append('\n');
            }
            if (unlisted(w) > 0) {
                out.append("  ... and ").append(plural(unlisted(w), "more role", "more roles"))
                   .append(", all in your dashboard.\n");
            }
            out.append('\n');
        }
        Action action = mainAction(w);
        out.append(action.label()).append(":\n").append(action.url()).append("\n\n")
           .append("— Actually Remote\n").append(siteUrl).append("\n\n")
           .append("You get this summary once a week, only when something happened on your roles. ")
           .append("Stop it: ").append(stopUrl(w)).append('\n');
        return out.toString();
    }

    String html(Week w) {
        StringBuilder card = new StringBuilder()
                .append(EmailLayout.eyebrow("Your week"))
                .append(EmailLayout.heading(w.company() + " on Actually Remote"))
                .append(EmailLayout.paragraph(EmailLayout.escape(lede(w)), true));

        if (w.unreadMessages() > 0 || !w.needsWork().isEmpty() || !w.expiring().isEmpty()) {
            StringBuilder items = new StringBuilder();
            if (w.unreadMessages() > 0) {
                items.append(item("<a href=\"%s\" style=\"color:%s;font-weight:700;\">%s</a> from candidates".formatted(
                        EmailLayout.escape(siteUrl + "/employers/messages"), EmailLayout.BRAND_DEEP,
                        EmailLayout.escape(plural(w.unreadMessages(), "unread message", "unread messages")))));
            }
            for (Titled t : w.needsWork()) {
                items.append(item("<strong>%s</strong> needs one change before it goes live &mdash; <a href=\"%s\" style=\"color:%s;font-weight:700;\">make it</a>".formatted(
                        EmailLayout.escape(t.title()),
                        EmailLayout.escape(siteUrl + "/employers/post?edit=" + t.postingId()),
                        EmailLayout.BRAND_DEEP)));
            }
            for (Titled t : w.expiring()) {
                items.append(item("<strong>%s</strong> closes within a week".formatted(EmailLayout.escape(t.title()))));
            }
            card.append(EmailLayout.eyebrow("Waiting for you"))
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 18px;\">")
                .append(items).append("</table>");
        }

        List<RoleWeek> listed = listed(w);
        if (!listed.isEmpty()) {
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < listed.size(); i++) {
                RoleWeek r = listed.get(i);
                rows.append(EmailLayout.roleRow(
                        r.title(), roleLine(r),
                        siteUrl + (r.applications() > 0
                                ? "/employers/applications/" + r.postingId()
                                : "/employers"),
                        i == listed.size() - 1 && unlisted(w) == 0));
            }
            card.append(EmailLayout.eyebrow("Your roles this week"))
                .append(EmailLayout.roleList(rows.toString()));
            if (unlisted(w) > 0) {
                card.append(EmailLayout.paragraph("&hellip; and " + plural(unlisted(w), "more role", "more roles")
                        + ", all in your dashboard."));
            }
        }

        Action action = mainAction(w);
        card.append(EmailLayout.button(action.url(), action.label()));

        return EmailLayout.document(
                EmailLayout.escape(lede(w)),
                card.toString(),
                EmailLayout.footer(siteUrl, stopUrl(w),
                        "You get this once a week, only when something happened on your roles."));
    }

    private static String item(String html) {
        return """
                <tr>
                  <td width="18" valign="top" style="padding:0 0 9px;font-size:15px;color:%s;">&bull;</td>
                  <td valign="top" style="padding:0 0 9px;font-size:15px;line-height:1.5;color:%s;">%s</td>
                </tr>
                """.formatted(EmailLayout.BRAND, EmailLayout.INK, html);
    }

    /** The page a person lands on from the footer link. */
    String stopUrl(Week w) {
        return siteUrl + "/api/employer-digest?token=" + w.optOutToken();
    }

    /** The same address, POSTed to by mail clients for one-click unsubscribe (RFC 8058). */
    private String oneClickStop(Week w) {
        return stopUrl(w);
    }

    private static String summaryJson(Week w) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("views", w.views());
        s.put("clicks", w.clicks());
        s.put("applications", w.applications());
        s.put("unread", w.unreadMessages());
        s.put("needsWork", w.needsWork().size());
        s.put("expiring", w.expiring().size());
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** One employer's digest as it would go out this week, to another address, whether or not it has news. */
    public boolean sendPreview(String to, long employerId) {
        Week week = weekOf(employerId);
        if (week == null) return false;
        mailer.send(to, "[preview] " + subject(week), text(week), html(week));
        return true;
    }
}
