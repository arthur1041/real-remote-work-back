package com.remoteroles.pipeline.newsletter;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends the paid-plan offers an administrator queued from the employer list.
 *
 * <p><strong>The postman, not the author.</strong> What the email says -- the
 * subject, the prices, the lines under each plan, where each button goes -- is
 * written by the site into `employer_plan_offers.content` (see V85), because
 * the site is where the prices and the plan lists live. This class lays that
 * out in the house template and sends it. A price changed on the site is a
 * price changed in the next offer, with nothing here to edit.
 *
 * <p>Marked sent before the send, like the other senders here: a crash between
 * the two costs one offer, and the other order sends it twice.
 */
@Service
public class PlanOfferSender {

    private static final Logger log = LoggerFactory.getLogger(PlanOfferSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient db;
    private final Mailer mailer;
    private final MailBudget budget;
    private final String siteUrl;

    public PlanOfferSender(
            JdbcClient db,
            Mailer mailer,
            MailBudget budget,
            @Value("${newsletter.site-url}") String siteUrl) {
        this.db = db;
        this.mailer = mailer;
        this.budget = budget;
        this.siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
    }

    record Link(String label, String url) {}

    record Offer(String name, String price, List<String> lines, Link primary, Link secondary) {}

    record Letter(String subject, String preheader, String intro, List<Offer> offers, String outro) {}

    record Pending(long id, String email, String content) {}

    @Scheduled(cron = "${employer.plan-offer.cron:*/30 * * * * *}")
    public void run() {
        sendPending();
    }

    /** Returns how many were sent. */
    public int sendPending() {
        if (!mailer.isDeliveryEnabled()) {
            return 0;
        }
        List<Pending> waiting = db.sql("""
                        select id, email, content::text as content
                          from employer_plan_offers
                         where sent_at is null and failed_at is null
                         order by created_at
                         limit 10
                        """)
                .query((rs, n) -> new Pending(rs.getLong("id"), rs.getString("email"), rs.getString("content")))
                .list();

        int sent = 0;
        for (Pending row : waiting) {
            Letter letter;
            try {
                letter = parse(row.content());
            } catch (Exception e) {
                // A row the site wrote wrong is a bug to see, not a retry loop.
                db.sql("update employer_plan_offers set failed_at = now(), failure = :why where id = :id")
                        .param("why", "unreadable content: " + e.getMessage())
                        .param("id", row.id())
                        .update();
                log.warn("plan offer {} not sent: unreadable content", row.id(), e);
                continue;
            }
            if (!budget.claim(MailBudget.Tier.TRANSACTIONAL)) {
                log.warn("plan offers held: the day's whole mail budget is spent");
                break;
            }
            if (db.sql("update employer_plan_offers set sent_at = now() where id = :id and sent_at is null")
                    .param("id", row.id())
                    .update() == 0) {
                continue;
            }
            mailer.send(row.email(), letter.subject(), text(letter), html(letter));
            sent++;
        }
        if (sent > 0) {
            log.info("plan offers sent: {}", sent);
        }
        return sent;
    }

    static Letter parse(String json) throws Exception {
        JsonNode root = JSON.readTree(json);
        List<Offer> offers = new ArrayList<>();
        for (JsonNode o : root.path("offers")) {
            List<String> lines = new ArrayList<>();
            o.path("lines").forEach(l -> lines.add(l.asText()));
            offers.add(new Offer(
                    o.path("name").asText(),
                    o.path("price").asText(),
                    lines,
                    link(o.path("primary")),
                    link(o.path("secondary"))));
        }
        if (offers.isEmpty()) throw new IllegalArgumentException("no plans in the offer");
        return new Letter(
                root.path("subject").asText(),
                root.path("preheader").asText(),
                root.path("intro").asText(),
                offers,
                root.path("outro").asText());
    }

    private static Link link(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        return new Link(node.path("label").asText(), node.path("url").asText());
    }

    String text(Letter letter) {
        StringBuilder out = new StringBuilder(letter.intro()).append("\n\n");
        for (Offer offer : letter.offers()) {
            out.append(offer.name().toUpperCase()).append('\n')
               .append(offer.price()).append("\n\n");
            for (String line : offer.lines()) out.append("  - ").append(line).append('\n');
            out.append('\n').append(offer.primary().label()).append(":\n")
               .append(offer.primary().url()).append("\n\n");
            if (offer.secondary() != null) {
                out.append(offer.secondary().label()).append(":\n")
                   .append(offer.secondary().url()).append("\n\n");
            }
        }
        return out.append(letter.outro()).append("\n\n— Actually Remote\n").append(siteUrl).append('\n')
                  .toString();
    }

    String html(Letter letter) {
        StringBuilder card = new StringBuilder()
                .append(EmailLayout.eyebrow(letter.offers().size() == 1 ? "A plan for you" : "The paid plans"))
                .append(EmailLayout.heading(letter.subject()))
                .append(EmailLayout.paragraph(EmailLayout.escape(letter.intro()), true));
        for (int i = 0; i < letter.offers().size(); i++) {
            Offer offer = letter.offers().get(i);
            if (i > 0) card.append(EmailLayout.rule());
            card.append(EmailLayout.eyebrow(offer.name()))
                .append(EmailLayout.paragraph("<strong>" + EmailLayout.escape(offer.price()) + "</strong>"));
            StringBuilder items = new StringBuilder();
            for (String line : offer.lines()) {
                items.append("<li style=\"margin:0 0 6px;\">").append(EmailLayout.escape(line)).append("</li>");
            }
            card.append("<ul style=\"margin:0 0 18px;padding-left:20px;font-size:15px;line-height:1.55;\">")
                .append(items).append("</ul>")
                .append(EmailLayout.button(offer.primary().url(), offer.primary().label()));
            if (offer.secondary() != null) {
                card.append(EmailLayout.textLink(offer.secondary().url(), offer.secondary().label()));
            }
        }
        card.append(EmailLayout.rule())
            .append(EmailLayout.paragraph(EmailLayout.escape(letter.outro())));
        return EmailLayout.document(
                letter.preheader(),
                card.toString(),
                EmailLayout.footer(siteUrl, null,
                        "You are getting this because you have an employer account on Actually Remote."));
    }
}
