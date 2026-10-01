package com.remoteroles.pipeline.newsletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default: logs what would have been sent, sends nothing.
 *
 * <p>Active whenever {@code newsletter.delivery.enabled} is false or absent, which
 * is the committed state. It exists so the rest of the system can be written, run
 * and tested against a real {@link Mailer} without a single message escaping.
 *
 * <p>The recipient is logged truncated. A log file full of complete subscriber
 * addresses is a copy of the mailing list sitting somewhere with weaker access
 * control than the database it came from.
 */
@Component
@ConditionalOnProperty(name = "newsletter.delivery.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("mail NOT sent (delivery disabled): to={} subject=\"{}\" bodyChars={}",
                redact(to), subject, body == null ? 0 : body.length());
    }

    @Override
    public boolean isDeliveryEnabled() {
        return false;
    }

    /** {@code someone@example.com} becomes {@code s***@example.com}. */
    static String redact(String email) {
        if (email == null || email.isBlank()) {
            return "(none)";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
