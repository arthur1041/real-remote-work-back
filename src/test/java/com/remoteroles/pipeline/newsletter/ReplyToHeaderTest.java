package com.remoteroles.pipeline.newsletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import jakarta.mail.Address;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a reply goes, read off the message the real code path builds.
 *
 * <p>Written on 24 Nov 2026, after a candidate answered an employer's message
 * and the answer reached this site's inbox instead: employer replies left
 * without a Reply-To, so the mail client answered the From address. Nothing is
 * sent here; {@link SmtpMailer#compose} builds the message without a
 * connection.
 */
class ReplyToHeaderTest {

    private final SmtpMailer mailer =
            new SmtpMailer(new JavaMailSenderImpl(), "hello@example.com", "Actually Remote");

    private static String only(Address[] addresses) {
        assertEquals(1, addresses.length);
        return ((InternetAddress) addresses[0]).getAddress();
    }

    @Test
    @DisplayName("An employer's letter is answered to the employer, and still sent from the site")
    void replyGoesToEmployer() throws Exception {
        MimeMessage m = mailer.compose("ada@example.com", "hiring@acme.example",
                "About your application", "Hi Ada", "<p>Hi Ada</p>", null);
        m.saveChanges();
        assertEquals("hiring@acme.example", only(m.getReplyTo()));
        assertEquals("hello@example.com", only(m.getFrom()));
    }

    @Test
    @DisplayName("Without a reply address the header is absent, so replies fall back to From")
    void noReplyToWhenUnknown() throws Exception {
        MimeMessage m = mailer.compose("ada@example.com", null, "Subject", "text", null, null);
        m.saveChanges();
        assertEquals(null, m.getHeader("Reply-To"));
    }

    @Test
    @DisplayName("A value that is not an address is ignored rather than written")
    void garbageIsIgnored() throws Exception {
        MimeMessage m = mailer.compose("ada@example.com", "not an address", "Subject", "text", null, null);
        m.saveChanges();
        assertEquals(null, m.getHeader("Reply-To"));
    }
}
