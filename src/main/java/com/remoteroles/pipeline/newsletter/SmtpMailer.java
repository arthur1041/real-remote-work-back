package com.remoteroles.pipeline.newsletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real delivery, over SMTP.
 *
 * <p>Replaces {@link LoggingMailer} only when {@code newsletter.delivery.enabled}
 * is true, so the committed default still sends nothing. The two are mutually
 * exclusive by construction: each is conditional on the opposite value of the
 * same property, and neither can be active at the same time as the other.
 *
 * <p>Failures are logged and swallowed rather than thrown. Every caller is a
 * scheduled job working through a batch, and one address that bounces at SMTP
 * time -- a typo'd domain, a mailbox that no longer exists -- must not abort the
 * rest of the batch. The recipient is logged truncated, for the same reason
 * {@link LoggingMailer} does it: a log file full of subscriber addresses is a
 * copy of the list sitting somewhere with weaker access control.
 */
@Component
@ConditionalOnProperty(name = "newsletter.delivery.enabled", havingValue = "true")
public class SmtpMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final JavaMailSender sender;
    private final String fromAddress;
    private final String fromName;

    public SmtpMailer(
            JavaMailSender sender,
            @Value("${newsletter.from-address}") String fromAddress,
            @Value("${newsletter.from-name}") String fromName) {
        this.sender = sender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
            log.info("mail sent: to={} subject=\"{}\"", LoggingMailer.redact(to), subject);
        } catch (Exception e) {
            log.warn("mail failed: to={} subject=\"{}\" error={}",
                    LoggingMailer.redact(to), subject, e.getMessage());
        }
    }

    /** {@code Actually Remote <hello@actuallyremote.work>}, or the bare address. */
    private String from() {
        return fromName == null || fromName.isBlank()
                ? fromAddress
                : "%s <%s>".formatted(fromName, fromAddress);
    }

    @Override
    public boolean isDeliveryEnabled() {
        return true;
    }
}
