package com.remoteroles.pipeline.newsletter;

/**
 * Sends one message.
 *
 * <p>The seam that keeps "collect addresses" separate from "send mail". Nothing in
 * this codebase sends anything yet: the only implementation wired by default is
 * {@link LoggingMailer}, which records the attempt and delivers nothing.
 *
 * <p>Switching SMTP on is then a configuration change plus one implementation,
 * rather than a change to any calling code.
 */
public interface Mailer {

    /**
     * Sends one message as both plain text and HTML.
     *
     * <p>Both halves, always. The HTML is what the reader sees; the text part is
     * what a plain-text client, a screen reader and a spam filter see, and a
     * message that carries only the HTML half scores worse at the filter than
     * the same message carrying both. They have to say the same thing — a text
     * part that is a stub saying "view this in a browser" is the pattern being
     * guarded against, not a way around it.
     *
     * <p>Returns whether the provider accepted it. Most callers ignore that:
     * they are working through a batch, and one address the relay refuses must
     * not abort the rest. The caller that does care is the one sending cold
     * mail, where a refusal is almost never about the address -- it is a quota,
     * an expired key, a relay that is down -- and continuing would spend the
     * whole queue on a wall.
     *
     * @param html may be null, for mail that is genuinely text
     * @param unsubscribeUrl the address a List-Unsubscribe header points at,
     *        or null for mail nobody can unsubscribe from
     * @return true when the provider accepted the message
     */
    boolean send(String to, String subject, String text, String html, String unsubscribeUrl);

    /**
     * Sends one message whose replies belong to somebody else.
     *
     * <p>For mail written by an employer and delivered by this site
     * ({@link ApplicationReplySender}). It leaves from this site's address,
     * because that is the domain that can sign it, but the person reading it
     * is answering the employer, and without this header their answer came
     * back to this site's inbox instead -- found on 24 Nov 2026, the day after
     * the first seven of these went out, when a candidate's reply to an
     * employer's two questions arrived at hello@ and nowhere else.
     *
     * <p>The default ignores the reply address, which is right for the logging
     * mailer: nothing is sent, so nothing can be answered.
     *
     * @param replyTo where a reply should go, or null to leave it unset
     */
    default boolean sendWithReplyTo(String to, String replyTo, String subject, String text, String html) {
        return send(to, subject, text, html, null);
    }

    /** No unsubscribe header. For mail to the operator, which has no list to leave. */
    default boolean send(String to, String subject, String text, String html) {
        return send(to, subject, text, html, null);
    }

    /** Text only. */
    default boolean send(String to, String subject, String text) {
        return send(to, subject, text, null, null);
    }

    /**
     * Whether anything would actually leave the building.
     *
     * <p>Call sites that must not pretend mail was delivered — a double opt-in flow
     * marking someone as "confirmation sent", say — check this first.
     */
    boolean isDeliveryEnabled();
}
