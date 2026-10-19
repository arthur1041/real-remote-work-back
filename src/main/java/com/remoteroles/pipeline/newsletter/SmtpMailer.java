package com.remoteroles.pipeline.newsletter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;

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

    /**
     * multipart/alternative when there is HTML, a plain message when there is not.
     *
     * <p>The order matters and {@link MimeMessageHelper} gets it right: the text
     * part is written first and the HTML second, because a client picks the last
     * part it understands. Reversed, every modern client would show the plain
     * text.
     */
    @Override
    public boolean send(String to, String subject, String text, String html, String unsubscribeUrl) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, html != null, "UTF-8");
            helper.setFrom(from());
            helper.setTo(to);
            helper.setSubject(subject);
            addUnsubscribeHeaders(message, unsubscribeUrl);
            if (html == null) {
                helper.setText(text);
            } else {
                helper.setText(text == null ? "" : text, html);
            }
            sender.send(message);
            log.info("mail sent: to={} subject=\"{}\"", LoggingMailer.redact(to), subject);
            return true;
        } catch (Exception e) {
            log.warn("mail failed: to={} subject=\"{}\" error={}",
                    LoggingMailer.redact(to), subject, e.getMessage());
            return false;
        }
    }

    /**
     * Declares the way out to the mail client, not just to the reader.
     *
     * <p>Every message already carries an opt-out link in its body, and the
     * provider cannot see it -- it does not read the HTML looking for the word
     * unsubscribe. This header is the only place a sender gets to say "this is
     * a managed list and here is how to leave it", and what it buys is the
     * native unsubscribe control Gmail draws beside the sender name.
     *
     * <p>That control is worth more than it looks. A reader who wants out and
     * cannot immediately see how presses "report spam" instead. Both actions
     * end the mail; only one of them spends the domain's reputation, and that
     * reputation is what delivers the confirmation links the product needs in
     * order to work at all. The messages most exposed to this are the cold
     * invitations, which is why it is worth having now rather than at the
     * volume where Gmail and Yahoo begin to require it.
     *
     * <p>The second header is RFC 8058 one-click. Without it a provider may
     * show the control but make the reader confirm on a web page, which is the
     * friction this exists to remove.
     */
    private static void addUnsubscribeHeaders(MimeMessage message, String unsubscribeUrl) {
        if (unsubscribeUrl == null || unsubscribeUrl.isBlank()) {
            return;
        }
        try {
            message.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
            message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        } catch (Exception e) {
            // A message that goes out without the header is worse than one that
            // does, and far better than one that does not go out at all.
            log.warn("could not set unsubscribe headers: {}", e.getMessage());
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
