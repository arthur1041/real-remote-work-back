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

    void send(String to, String subject, String body);

    /**
     * Whether anything would actually leave the building.
     *
     * <p>Call sites that must not pretend mail was delivered — a double opt-in flow
     * marking someone as "confirmation sent", say — check this first.
     */
    boolean isDeliveryEnabled();
}
