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
     * @param html may be null, for mail that is genuinely text
     */
    void send(String to, String subject, String text, String html);

    /** Text only. */
    default void send(String to, String subject, String text) {
        send(to, subject, text, null);
    }

    /**
     * Whether anything would actually leave the building.
     *
     * <p>Call sites that must not pretend mail was delivered — a double opt-in flow
     * marking someone as "confirmation sent", say — check this first.
     */
    boolean isDeliveryEnabled();
}
