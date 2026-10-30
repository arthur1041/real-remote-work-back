package com.remoteroles.pipeline.newsletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily notice about messages nobody has read.
 *
 * <p>Two things are expensive here and neither appears in a log. The first is
 * the wording: this travels on the domain that also carries the login codes
 * and the employer mail, and a notice that scores as a campaign does not
 * bounce — it lands in a folder nobody opens and takes the confirmation links
 * with it. The second is the promise the copy makes about itself. The body
 * says this is sent at most once per message, and that sentence is only true
 * because of the `notified_at` stamp; a copy change that drops it would leave
 * a claim the system no longer keeps.
 *
 * <p>Nothing is sent. The copy is built through the real methods and read.
 */
class UnreadMessageNotifierTest {

    /** Only `siteUrl` is read by the parts under test; nothing here connects. */
    private final UnreadMessageNotifier notifier =
            new UnreadMessageNotifier(null, null, null, "https://actuallyremote.work/");

    private static UnreadMessageNotifier.Waiting candidate(int unread) {
        return new UnreadMessageNotifier.Waiting(
                "CANDIDATE", 1, "ada@example.com", "Ada Lovelace", unread, "tok-123");
    }

    private static UnreadMessageNotifier.Waiting employer(int unread) {
        return new UnreadMessageNotifier.Waiting(
                "EMPLOYER", 2, "jobs@example.com", "Example Ltd", unread, null);
    }

    @Nested
    class Subject {

        @Test
        @DisplayName("carries no number")
        void noCount() {
            // A count in the subject is what a filter scores hardest and what
            // makes a notice read like a campaign. The number belongs in the
            // first line, where the person has already decided to look.
            assertFalse(UnreadMessageNotifier.subject(candidate(7)).matches(".*\\d.*"));
            assertFalse(UnreadMessageNotifier.subject(employer(12)).matches(".*\\d.*"));
        }

        @Test
        @DisplayName("says one message or several, and nothing urgent")
        void plural() {
            assertEquals("A message about your application",
                    UnreadMessageNotifier.subject(candidate(1)));
            assertEquals("Messages waiting for you",
                    UnreadMessageNotifier.subject(candidate(2)));
        }
    }

    @Nested
    class Copy {

        @Test
        @DisplayName("counts in words at one, in digits above it")
        void counting() {
            assertEquals("one message", UnreadMessageNotifier.count(1));
            assertEquals("4 messages", UnreadMessageNotifier.count(4));
        }

        @Test
        @DisplayName("greets by first name, and greets a blank name anyway")
        void greeting() {
            assertEquals("Ada", UnreadMessageNotifier.firstName("Ada Lovelace"));
            assertEquals("there", UnreadMessageNotifier.firstName("   "));
            assertEquals("there", UnreadMessageNotifier.firstName(null));
        }

        @Test
        @DisplayName("promises, in both halves, that it does not keep asking")
        void doesNotRepeat() {
            // The sentence is only true because of the `notified_at` stamp. It
            // is asserted here so that removing one without the other is a
            // failing test rather than a claim the system stopped keeping.
            for (String copy : new String[] {
                    notifier.body(candidate(3)), notifier.html(candidate(3)) }) {
                assertTrue(copy.contains("at most once"), copy);
                assertTrue(copy.contains("does not keep asking"), copy);
            }
        }

        @Test
        @DisplayName("never carries what was written")
        void noMessageBody() {
            // /privacy promises a message is deleted ninety days after the last
            // one in its conversation. A copy sitting in a mailbox forever is
            // outside anything this site can keep that promise about, so the
            // link is the only way to read it.
            String body = notifier.body(candidate(2));
            assertTrue(body.contains("https://actuallyremote.work/account/messages"), body);
            assertFalse(body.toLowerCase().contains("wrote:"), body);
        }

        @Test
        @DisplayName("sends each side to its own inbox")
        void sides() {
            assertTrue(notifier.body(candidate(1))
                    .contains("https://actuallyremote.work/account/messages"));
            assertTrue(notifier.body(employer(1))
                    .contains("https://actuallyremote.work/employers/messages"));
        }

        @Test
        @DisplayName("trailing slash on the site url never doubles")
        void slash() {
            // The constructor is handed "https://…/" above on purpose: a
            // doubled slash in a link is the kind of defect that only shows up
            // in somebody else's mailbox.
            assertFalse(notifier.body(candidate(1)).contains("work//"));
            assertFalse(notifier.html(candidate(1)).contains("work//"));
        }
    }

    @Nested
    class OptOut {

        @Test
        @DisplayName("offers the candidate a way out, in both halves")
        void candidateCanLeave() {
            String body = notifier.body(candidate(1));
            String html = notifier.html(candidate(1));
            assertTrue(body.contains("/unsubscribe?token=tok-123"), body);
            assertTrue(html.contains("/unsubscribe?token=tok-123"), html);
        }

        @Test
        @DisplayName("says nothing about opting out to an employer")
        void employerHasNoToken() {
            // An employer's notice is about their own hiring and has no
            // newsletter token to offer; a dead link would be worse than none.
            assertFalse(notifier.body(employer(1)).contains("unsubscribe"));
            assertFalse(notifier.html(employer(1)).contains("unsubscribe"));
        }
    }

    @Nested
    class Escaping {

        @Test
        @DisplayName("escapes the one thing somebody else typed")
        void name() {
            // The name is the only value in the HTML that a person chose.
            var hostile = new UnreadMessageNotifier.Waiting(
                    "CANDIDATE", 1, "x@example.com", "<script>x</script> Smith", 1, "t");
            assertTrue(notifier.html(hostile).contains("&lt;script&gt;"));
            assertFalse(notifier.html(hostile).contains("<script>"));
        }
    }
}
