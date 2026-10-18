package com.remoteroles.pipeline.newsletter;

import java.util.List;

/**
 * The house style for every message this system sends.
 *
 * <p>One place, because four senders each inventing their own markup is four
 * places for the brand to drift and four places to fix when a client renders
 * something badly. The tokens below are the site's, copied rather than imported:
 * the stylesheet lives in the web app and this is a different process, so the
 * colours are duplicated on purpose and the comment naming each one is what
 * keeps the duplication honest.
 *
 * <h2>Why it looks like 2003 under the hood</h2>
 *
 * <p>Tables, inline styles, no stylesheet. Not nostalgia — Outlook renders mail
 * through Word's layout engine, which has no flexbox, no grid and no
 * {@code max-width}, and Gmail strips most of a {@code <style>} block. Anything
 * written the way the website is written collapses into a single unstyled column
 * in the two clients most of this list reads mail in. A nested table with inline
 * styles is the one layout that survives all of them.
 *
 * <h2>Why there are no images</h2>
 *
 * <p>The site's mark is an SVG, which Gmail and Outlook do not render at all, and
 * a PNG would be blocked by default in most clients until the reader chooses to
 * load it. So the wordmark is text: it is always visible, it costs no request,
 * and it is the same two stacked words in the same emerald as the site header.
 * A logo that only appears for readers who trust you already is decoration, not
 * identity.
 *
 * <h2>Escaping</h2>
 *
 * <p>Job titles and company names come from third-party feeds, so every value
 * that reaches this class goes through {@link #escape}. The same reasoning as the
 * JSON-LD helper in the web app: a title containing {@code <} is not a
 * hypothetical, and an unescaped one here would break the layout for every
 * recipient of that issue.
 */
final class EmailLayout {

    private EmailLayout() {}

    // --- the site's tokens -------------------------------------------------
    // From web/app/globals.css. Translucent values are flattened, because email
    // clients are unreliable with rgba() and there is nothing animated here that
    // needs real transparency: --line over --paper, then over --surface.
    static final String BRAND = "#0b7d57";        // --brand
    static final String BRAND_DEEP = "#065c3f";   // --brand-deep
    static final String BRAND_SOFT = "#e3f3eb";   // --brand-soft
    static final String INK = "#0e1512";          // --ink
    static final String MUTED = "#5c6b63";        // --muted
    static final String PAPER = "#f7f6f2";        // --paper
    static final String SURFACE = "#ffffff";      // --surface
    static final String LINE_ON_PAPER = "#dfdfdc";
    static final String LINE_ON_SURFACE = "#e9e9e5";
    static final String RADIUS = "20px";          // --radius
    static final String RADIUS_SM = "14px";       // --radius-sm

    /**
     * Manrope first, then the system stack.
     *
     * <p>The webfont is requested in the head as well. Apple Mail will honour it
     * and Gmail will not, so the fallbacks are what most readers actually see —
     * which is why the stack leads with the native UI faces rather than Arial.
     */
    static final String FONT =
            "'Manrope',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";

    /**
     * The shell: paper ground, wordmark, one white card, footer.
     *
     * <p>{@code preheader} is the line the inbox shows next to the subject. Left
     * out, clients fill it with whatever text comes first — usually "View in
     * browser" or the opening half-sentence — so it is written deliberately and
     * then hidden.
     */
    static String document(String preheader, String contentHtml, String footerHtml) {
        return SHELL
                .replace("{{FONT}}", FONT)
                .replace("{{PAPER}}", PAPER)
                .replace("{{SURFACE}}", SURFACE)
                .replace("{{LINE}}", LINE_ON_SURFACE)
                .replace("{{RADIUS}}", RADIUS)
                .replace("{{BRAND}}", BRAND)
                .replace("{{MUTED}}", MUTED)
                .replace("{{PREHEADER}}", escape(preheader))
                .replace("{{CONTENT}}", contentHtml)
                .replace("{{FOOTER}}", footerHtml);
    }

    private static final String SHELL = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="x-apple-disable-message-reformatting">
            <meta name="color-scheme" content="light only">
            <meta name="supported-color-schemes" content="light only">
            <link href="https://fonts.googleapis.com/css2?family=Manrope:wght@500;700;800&display=swap" rel="stylesheet">
            <!--[if mso]><style>body,table,td,a{font-family:'Segoe UI',Arial,sans-serif !important}</style><![endif]-->
            <style>
              /* Everything here is a nicety. The inline styles already lay the
                 message out; this only tightens it on a phone, in the clients
                 that keep a style block at all. */
              @media (max-width:600px){
                .card{padding:26px 20px !important}
                .h1{font-size:23px !important}
              }
            </style>
            </head>
            <body style="margin:0;padding:0;width:100%;background:{{PAPER}};-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;">
            <div style="display:none;font-size:1px;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden;mso-hide:all;color:{{PAPER}};">{{PREHEADER}}</div>
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:{{PAPER}};">
              <tr>
                <td align="center" style="padding:28px 14px 40px;">
                  <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="600" style="width:100%;max-width:600px;">

                    <tr>
                      <td style="padding:0 6px 18px;font-family:{{FONT}};">
                        <a href="https://actuallyremote.work" style="text-decoration:none;color:{{BRAND}};">
                          <span style="display:block;font-size:17px;font-weight:800;letter-spacing:-0.02em;line-height:1.1;color:{{BRAND}};">Actually</span>
                          <span style="display:block;font-size:17px;font-weight:800;letter-spacing:-0.02em;line-height:1.1;color:{{BRAND}};">Remote</span>
                        </a>
                      </td>
                    </tr>

                    <tr>
                      <td class="card" style="background:{{SURFACE}};border:1px solid {{LINE}};border-radius:{{RADIUS}};padding:34px 32px;font-family:{{FONT}};">
            {{CONTENT}}
                      </td>
                    </tr>

                    <tr>
                      <td style="padding:22px 10px 0;font-family:{{FONT}};font-size:13px;line-height:1.6;color:{{MUTED}};">
            {{FOOTER}}
                      </td>
                    </tr>

                  </table>
                </td>
              </tr>
            </table>
            </body>
            </html>
            """;

    // --- blocks -------------------------------------------------------------

    /** The one big line at the top of the card. */
    static String heading(String text) {
        return """
                <h1 class="h1" style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:800;letter-spacing:-0.02em;color:%s;">%s</h1>
                """.formatted(INK, escape(text));
    }

    /** Body copy. Pass {@code true} for the larger opening paragraph. */
    static String paragraph(String html, boolean lede) {
        String size = lede ? "17px" : "15px";
        String colour = lede ? INK : MUTED;
        return """
                <p style="margin:0 0 16px;font-size:%s;line-height:1.6;color:%s;">%s</p>
                """.formatted(size, colour, html);
    }

    static String paragraph(String html) {
        return paragraph(html, false);
    }

    /**
     * The pill button, in the brand green.
     *
     * <p>A table rather than a styled anchor because Outlook ignores padding on
     * an inline element, which turns the button into bare underlined text. The
     * {@code bgcolor} attribute is there for the same reason: older clients drop
     * the CSS background and keep the attribute.
     */
    static String button(String href, String label) {
        return """
                <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:6px 0 20px;">
                  <tr>
                    <td align="center" bgcolor="%s" style="border-radius:999px;">
                      <a href="%s" style="display:inline-block;padding:14px 30px;font-family:%s;font-size:16px;font-weight:700;line-height:1;color:#ffffff;text-decoration:none;border-radius:999px;">%s</a>
                    </td>
                  </tr>
                </table>
                """.formatted(BRAND, escape(href), FONT, escape(label));
    }

    /** A quieter link for the secondary action. */
    static String textLink(String href, String label) {
        return """
                <p style="margin:0 0 16px;font-size:15px;line-height:1.6;">
                  <a href="%s" style="color:%s;font-weight:700;text-decoration:none;border-bottom:1px solid %s;">%s</a>
                </p>
                """.formatted(escape(href), BRAND_DEEP, BRAND_SOFT, escape(label));
    }

    /** Two numbers side by side, in the brand tint. Used where a count is the argument. */
    static String stats(List<String[]> pairs) {
        StringBuilder cells = new StringBuilder();
        for (String[] pair : pairs) {
            cells.append("""
                    <td width="50%%" style="padding:16px 18px;">
                      <div style="font-size:24px;font-weight:800;letter-spacing:-0.02em;color:%s;line-height:1.1;">%s</div>
                      <div style="font-size:13px;font-weight:600;color:%s;padding-top:4px;">%s</div>
                    </td>
                    """.formatted(BRAND_DEEP, escape(pair[0]), MUTED, escape(pair[1])));
        }
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="background:%s;border-radius:%s;margin:0 0 22px;">
                  <tr>%s</tr>
                </table>
                """.formatted(BRAND_SOFT, RADIUS_SM, cells);
    }

    /** The "Worldwide" pill, same tint as the badge on the site. */
    static String badge(String label) {
        return """
                <span style="display:inline-block;padding:3px 9px;border-radius:999px;background:%s;color:%s;font-size:11px;font-weight:800;letter-spacing:0.04em;text-transform:uppercase;">%s</span>
                """.formatted(BRAND_SOFT, BRAND_DEEP, escape(label));
    }

    /**
     * One role, as a row.
     *
     * <p>The whole row is the link rather than a "view" control beside it: on a
     * phone the row is the tap target, and a four-item list with four small
     * links in it is four chances to miss.
     */
    static String roleRow(String title, String company, String href, boolean last) {
        String border = last ? "" : "border-bottom:1px solid " + LINE_ON_SURFACE + ";";
        String link = href == null || href.isBlank() ? null : escape(href);

        // A linked title takes the brand green, an unlinked one stays ink. On the
        // site a card announces itself with a hover state and a cursor; email has
        // neither, so colour is the only thing left that says "this is the thing
        // to tap". Dark enough that it still reads as a heading.
        String titleColour = link == null ? INK : BRAND_DEEP;
        String titleHtml = """
                <div style="font-size:16px;font-weight:700;letter-spacing:-0.01em;color:%s;line-height:1.35;">%s</div>
                <div style="font-size:14px;color:%s;padding-top:2px;">%s</div>
                """.formatted(titleColour, escape(title), MUTED, escape(company));

        String inner = link == null
                ? titleHtml
                : """
                        <a href="%s" style="text-decoration:none;color:%s;">%s</a>
                        """.formatted(link, titleColour, titleHtml);

        return """
                <tr>
                  <td style="padding:14px 2px;%s">%s</td>
                </tr>
                """.formatted(border, inner);
    }

    /** Wraps {@link #roleRow} output. */
    static String roleList(String rowsHtml) {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:4px 0 22px;">
                %s
                </table>
                """.formatted(rowsHtml);
    }

    /** A small all-caps label above a block. */
    static String eyebrow(String text) {
        return """
                <p style="margin:0 0 8px;font-size:11px;font-weight:800;letter-spacing:0.08em;text-transform:uppercase;color:%s;">%s</p>
                """.formatted(BRAND_DEEP, escape(text));
    }

    /** Hairline, for separating the ask from the detail. */
    static String rule() {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:4px 0 20px;">
                  <tr><td style="border-top:1px solid %s;font-size:0;line-height:0;">&nbsp;</td></tr>
                </table>
                """.formatted(LINE_ON_SURFACE);
    }

    /** A labelled value, for the operator mail where the fields matter more than the prose. */
    static String field(String label, String valueHtml) {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 12px;">
                  <tr>
                    <td width="110" valign="top" style="font-size:12px;font-weight:800;letter-spacing:0.06em;text-transform:uppercase;color:%s;padding-top:3px;">%s</td>
                    <td valign="top" style="font-size:15px;line-height:1.5;color:%s;">%s</td>
                  </tr>
                </table>
                """.formatted(MUTED, escape(label), INK, valueHtml);
    }

    /**
     * The footer: who sent this, and how to stop it.
     *
     * <p>{@code unsubscribeUrl} may be null for mail to the operator, which has
     * nothing to unsubscribe from. For everything else it is not optional, and
     * not only because the law says so — a message a reader cannot leave is one
     * they report instead.
     */
    static String footer(String siteUrl, String unsubscribeUrl, String reason) {
        StringBuilder out = new StringBuilder();
        if (reason != null && !reason.isBlank()) {
            out.append("""
                    <p style="margin:0 0 8px;">%s</p>
                    """.formatted(reason));
        }
        out.append("""
                <p style="margin:0 0 8px;">
                  <a href="%s" style="color:%s;font-weight:700;text-decoration:none;">Actually Remote</a>
                  &nbsp;·&nbsp; Remote roles with no country requirement.
                </p>
                """.formatted(escape(siteUrl), BRAND_DEEP));
        if (unsubscribeUrl != null && !unsubscribeUrl.isBlank()) {
            out.append("""
                    <p style="margin:0;">
                      <a href="%s" style="color:%s;text-decoration:underline;">Unsubscribe</a>
                    </p>
                    """.formatted(escape(unsubscribeUrl), MUTED));
        }
        return out.toString();
    }

    /** HTML-escapes a value for use in text or in an attribute. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
