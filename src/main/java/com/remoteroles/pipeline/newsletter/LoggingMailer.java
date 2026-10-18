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

    // True, although nothing was sent. The return value reports whether the
    // provider refused, and there is no provider; a false here would read as an
    // outage to callers that back off on failure, and stall a queue in exactly
    // the configuration where nothing can go wrong.
    @Override
    public boolean send(String to, String subject, String text, String html) {
        log.info("mail NOT sent (delivery disabled): to={} subject=\"{}\" textChars={} htmlChars={}",
                redact(to), subject,
                text == null ? 0 : text.length(),
                html == null ? 0 : html.length());
        return true;
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
