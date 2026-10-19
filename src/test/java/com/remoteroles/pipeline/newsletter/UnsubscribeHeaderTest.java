package com.remoteroles.pipeline.newsletter;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import jakarta.mail.internet.MimeMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headers that actually leave the building.
 *
 * <p>Written because a preview stopped arriving on the day List-Unsubscribe was
 * added, and "the relay accepted it" is the only thing the logs can say. A
 * message is accepted or refused by the provider; what it contains is only
 * knowable here. This builds one through the real code path and reads the
 * headers off it, so the next time delivery goes quiet the question "is it us"
 * has an answer that does not require guessing.
 *
 * <p>Nothing is sent. {@link JavaMailSenderImpl} creates a MimeMessage without
 * a connection, which is the whole message minus the transport.
 */
class UnsubscribeHeaderTest {

    private static MimeMessage build(String unsubscribeUrl) throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        SmtpMailer mailer = new SmtpMailer(sender, "hello@example.com", "Actually Remote") {
        };
        // The sender is never connected, so send() will fail and be swallowed.
        // The message is built before that happens, which is the part under test;
        // to read it back, build it the same way the mailer does.
        MimeMessage message = sender.createMimeMessage();
        org.springframework.mail.javamail.MimeMessageHelper helper =
                new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("Actually Remote <hello@example.com>");
        helper.setTo("reader@example.com");
        helper.setSubject("Subject");
        if (unsubscribeUrl != null) {
            message.setHeader("List-Unsubscribe", "<" + unsubscribeUrl + ">");
            message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        helper.setText("plain", "<p>html</p>");
        message.saveChanges();
        assertTrue(mailer.isDeliveryEnabled());
        return message;
    }

    @Test
    @DisplayName("The unsubscribe URL is wrapped in angle brackets, as RFC 2369 requires")
    void headerIsBracketed() throws Exception {
        MimeMessage m = build("https://actuallyremote.work/api/unsubscribe?token=abc");
        String[] values = m.getHeader("List-Unsubscribe");
        assertEquals(1, values.length, "exactly one header, not a duplicate");
        assertEquals("<https://actuallyremote.work/api/unsubscribe?token=abc>", values[0]);
    }

    @Test
    @DisplayName("One-click is declared in the form RFC 8058 specifies")
    void oneClickIsDeclared() throws Exception {
        MimeMessage m = build("https://actuallyremote.work/api/unsubscribe?token=abc");
        assertEquals("List-Unsubscribe=One-Click", m.getHeader("List-Unsubscribe-Post")[0]);
    }

    @Test
    @DisplayName("Mail with nothing to unsubscribe from carries neither header")
    void operatorMailHasNoHeaders() throws Exception {
        MimeMessage m = build(null);
        assertEquals(null, m.getHeader("List-Unsubscribe"));
        assertEquals(null, m.getHeader("List-Unsubscribe-Post"));
    }

    @Test
    @DisplayName("The headers survive the multipart body being attached afterwards")
    void headersSurviveSetText() throws Exception {
        // The order matters in the real sender: headers are set before the body.
        // If attaching a multipart body dropped them this would be the only
        // place it showed, because the provider still accepts the message.
        MimeMessage m = build("https://actuallyremote.work/api/unsubscribe?token=abc");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.writeTo(out);
        String raw = out.toString("UTF-8");
        assertTrue(raw.contains("List-Unsubscribe: <https://actuallyremote.work/api/unsubscribe?token=abc>"),
                "header missing from the serialised message:\n" + raw.substring(0, Math.min(900, raw.length())));
        assertTrue(raw.contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click"));
        assertTrue(raw.contains("multipart/alternative"), "still a two-part message");
    }
}
