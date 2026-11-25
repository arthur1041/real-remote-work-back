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

    /** `amount`, `unit` and `detail` are the price in parts for the card; `price` is the one-line form for plain text. */
    record Offer(String name, String price, String amount, String unit, String detail,
                 List<String> lines, Link primary, Link secondary) {}

    /** `eyebrow` and `heading` open the card; the subject is only what the inbox shows. */
    record Letter(String subject, String eyebrow, String heading, String preheader, String intro,
                  List<Offer> offers, String outro) {}

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
            JsonNode parts = o.path("priceParts");
            offers.add(new Offer(
                    o.path("name").asText(),
                    o.path("price").asText(),
                    parts.path("amount").asText(""),
                    parts.path("unit").asText(""),
                    parts.path("detail").asText(""),
                    lines,
                    link(o.path("primary")),
                    link(o.path("secondary"))));
        }
        if (offers.isEmpty()) throw new IllegalArgumentException("no plans in the offer");
        String subject = root.path("subject").asText();
        return new Letter(
                subject,
                root.path("eyebrow").asText(""),
                root.path("heading").asText(subject),
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
            for (String line : offer.lines()) {
                out.append(line.endsWith(":") ? "" : "  - ").append(line).append('\n');
            }
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
                .append(letter.eyebrow().isBlank() ? "" : EmailLayout.eyebrow(letter.eyebrow()))
                .append(EmailLayout.heading(letter.heading()))
                .append(EmailLayout.paragraph(EmailLayout.escape(letter.intro()), true));
        for (Offer offer : letter.offers()) {
            card.append(planCard(offer));
        }
        card.append(EmailLayout.paragraph(EmailLayout.escape(letter.outro())));
        return EmailLayout.document(
                letter.preheader(),
                card.toString(),
                EmailLayout.footer(siteUrl, null,
                        "You are getting this because you have an employer account on Actually Remote."));
    }

    /**
     * One plan as a card: the name and the price large on a tinted band, then
     * what it adds as a checklist, then the button. Tables throughout, because
     * that is what survives Outlook; the rounded corners are a bonus the
     * clients that honour them get.
     */
    static String planCard(Offer offer) {
        String price = offer.amount().isBlank()
                ? "<p style=\"margin:0;font-size:15px;font-weight:700;color:%s;\">%s</p>"
                        .formatted(EmailLayout.INK, EmailLayout.escape(offer.price()))
                : """
                  <p style="margin:0;line-height:1.1;"><span style="font-size:34px;font-weight:800;letter-spacing:-0.02em;color:%s;">%s</span> <span style="font-size:15px;color:%s;">%s</span></p>
                  <p style="margin:6px 0 0;font-size:13px;line-height:1.45;color:%s;">%s</p>
                  """.formatted(EmailLayout.INK, EmailLayout.escape(offer.amount()),
                        EmailLayout.MUTED, EmailLayout.escape(offer.unit()),
                        EmailLayout.MUTED, EmailLayout.escape(offer.detail()));

        StringBuilder body = new StringBuilder();
        List<String> lines = offer.lines();
        // A line ending in a colon ("Everything in Starter, plus:") introduces
        // the list rather than being one of its items.
        if (!lines.isEmpty() && lines.get(0).endsWith(":")) {
            body.append("<p style=\"margin:0 0 10px;font-size:15px;line-height:1.5;font-weight:700;color:%s;\">%s</p>"
                    .formatted(EmailLayout.INK, EmailLayout.escape(lines.get(0))));
            lines = lines.subList(1, lines.size());
        }
        StringBuilder rows = new StringBuilder();
        for (String line : lines) {
            rows.append("""
                    <tr>
                      <td width="24" valign="top" style="padding:1px 0 9px;font-size:15px;font-weight:800;color:%s;">&#10003;</td>
                      <td valign="top" style="padding:0 0 9px;font-size:15px;line-height:1.5;color:%s;">%s</td>
                    </tr>
                    """.formatted(EmailLayout.BRAND, EmailLayout.INK, EmailLayout.escape(line)));
        }
        body.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 10px;\">")
            .append(rows).append("</table>")
            .append(EmailLayout.button(offer.primary().url(), offer.primary().label()));
        if (offer.secondary() != null) {
            body.append(EmailLayout.textLink(offer.secondary().url(), offer.secondary().label()));
        }

        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 22px;border:1px solid %s;border-radius:%s;border-collapse:separate;">
                  <tr>
                    <td bgcolor="%s" style="padding:20px 22px 18px;background:%s;border-radius:%s %s 0 0;">
                      <p style="margin:0 0 10px;font-size:12px;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;color:%s;">%s</p>
                      %s
                    </td>
                  </tr>
                  <tr>
                    <td style="padding:20px 22px 6px;">
                      %s
                    </td>
                  </tr>
                </table>
                """.formatted(
                        EmailLayout.LINE_ON_SURFACE, EmailLayout.RADIUS_SM,
                        EmailLayout.BRAND_SOFT, EmailLayout.BRAND_SOFT, EmailLayout.RADIUS_SM, EmailLayout.RADIUS_SM,
                        EmailLayout.BRAND_DEEP, EmailLayout.escape(offer.name()),
                        price, body);
    }

}
