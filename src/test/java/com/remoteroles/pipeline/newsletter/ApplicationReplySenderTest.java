package com.remoteroles.pipeline.newsletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The letter an employer sends to somebody who applied.
 *
 * <p>Two things here are expensive and neither shows in a log. The first is
 * writing to a person who withdrew between the press and the send: they said
 * the conversation was over, and a reply after that reads as the withdrawal
 * being ignored. The second is the HTML half — the body is text an employer
 * typed into a box, and a box whose contents reach a stranger's inbox as
 * markup is a link to anywhere carrying this product's name on the envelope.
 *
 * <p>Nothing is sent. The parts under test are pure.
 */
class ApplicationReplySenderTest {

    private static ApplicationReplySender.Pending pending(
            boolean withdrawn, boolean deleted, String to) {
        return new ApplicationReplySender.Pending(
                1, to, "About your application", "Hi Ada,\n\nThank you.", withdrawn, deleted);
    }

    @Test
    @DisplayName("a letter to somebody who withdrew is stopped, not sent")
    void withdrawnIsStopped() {
        assertNotNull(ApplicationReplySender.reasonToStop(pending(true, false, "a@b.test")));
        assertTrue(ApplicationReplySender.reasonToStop(pending(true, false, "a@b.test"))
                .contains("withdrawn"));
    }

    @Test
    @DisplayName("a deleted account has nobody to write to")
    void deletedIsStopped() {
        assertNotNull(ApplicationReplySender.reasonToStop(pending(false, true, "a@b.test")));
    }

    @Test
    @DisplayName("an address that is not one is stopped rather than attempted")
    void addressIsChecked() {
        assertNotNull(ApplicationReplySender.reasonToStop(pending(false, false, "nope")));
        assertNotNull(ApplicationReplySender.reasonToStop(pending(false, false, null)));
        assertNull(ApplicationReplySender.reasonToStop(pending(false, false, "ada@example.com")));
    }

    @Test
    @DisplayName("what the employer typed is escaped, never interpreted")
    void bodyIsEscaped() {
        String html = ApplicationReplySender.html("Hi <b>Ada</b> & co\nsecond line");
        assertFalse(html.contains("<b>Ada</b>"));
        assertTrue(html.contains("&lt;b&gt;Ada&lt;/b&gt;"));
        assertTrue(html.contains("&amp;"));
        // A single newline is a line break; it is how people type a signature.
        assertTrue(html.contains("<br>"));
    }

    @Test
    @DisplayName("blank lines become paragraphs, and empty ones nothing")
    void paragraphs() {
        String html = ApplicationReplySender.html("One.\n\nTwo.\n\n\n");
        assertEquals(2, html.split("<p>").length - 1);
    }
}
