package com.remoteroles.pipeline.newsletter;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one message on this site sent to somebody who did not just do something.
 *
 * <p>Two things are expensive to get wrong here and neither shows up in a log.
 * The first is asking a person for a document they have already given, which
 * reads as a product that does not know its own state. The second is the
 * wording: this is the only mail the board sends that a filter has any reason
 * to weigh as a campaign, and it travels on the same domain as the login codes
 * and the employer mail. A message that scores badly does not bounce, it
 * silently lands in a folder nobody opens, and takes the confirmation links
 * with it.
 *
 * <p>Nothing is sent. The copy is built through the real methods and read.
 */
class CvRequestSenderTest {

    /** Only `siteUrl` is read by the parts under test; nothing here connects. */
    private final CvRequestSender sender =
            new CvRequestSender(null, null, null, "https://actuallyremote.work/");

    private static CvRequestSender.Pending pending(
            boolean hasResume, boolean optedOut, String status) {
        return new CvRequestSender.Pending(
                1, "ada@example.com", "Ada Lovelace", "tok", hasResume, optedOut, status);
    }

    @Nested
    class Skipping {

        @Test
        @DisplayName("sends when the account still has no CV")
        void sends() {
            assertNull(CvRequestSender.reasonToSkip(pending(false, false, "ACTIVE")));
        }

        @Test
        @DisplayName("refuses once the document has arrived")
        void arrived() {
            // The whole reason the conditions are checked twice. Fifteen seconds
            // pass between the admin pressing the button and the send, and a
            // message asking for a CV that is already on file is the one failure
            // this feature can produce that a person actually sees.
            assertEquals("a CV arrived before this was sent",
                    CvRequestSender.reasonToSkip(pending(true, false, "ACTIVE")));
        }

        @Test
        @DisplayName("refuses somebody who asked for no more mail")
        void optedOut() {
            assertEquals("opted out of mail",
                    CvRequestSender.reasonToSkip(pending(false, true, "ACTIVE")));
        }

        @Test
        @DisplayName("refuses a suspended account")
        void suspended() {
            assertEquals("account is suspended",
                    CvRequestSender.reasonToSkip(pending(false, false, "SUSPENDED")));
        }

        @Test
        @DisplayName("the CV wins over every other reason")
        void orderOfReasons() {
            // Not arbitrary: the reason is written into the row and read on the
            // admin page, and "they already have one" is the answer to the
            // question the operator was asking.
            assertEquals("a CV arrived before this was sent",
                    CvRequestSender.reasonToSkip(pending(true, true, "SUSPENDED")));
        }
    }

    @Nested
    class Greeting {

        @Test
        @DisplayName("uses the first name alone")
        void firstOnly() {
            assertEquals("Ada", CvRequestSender.firstName("Ada Lovelace"));
            assertEquals("Ada", CvRequestSender.firstName("  Ada  Lovelace "));
        }

        @Test
        @DisplayName("falls back to something that still reads like a greeting")
        void missing() {
            // "Hi ," is worse than no name at all, and a blank full_name is
            // reachable: the column is filled from a form.
            assertEquals("there", CvRequestSender.firstName(null));
            assertEquals("there", CvRequestSender.firstName("   "));
        }

        @Test
        @DisplayName("truncates a name long enough to be a paste")
        void long_() {
            assertEquals(40, CvRequestSender.firstName("x".repeat(200)).length());
        }
    }

    @Nested
    class Copy {

        private final String text = sender.body(pending(false, false, "ACTIVE"));
        private final String html = sender.html(pending(false, false, "ACTIVE"));

        @Test
        @DisplayName("greets the person by their first name, in both halves")
        void greets() {
            assertTrue(text.startsWith("Hi Ada,"), text.substring(0, 20));
            assertTrue(html.contains("Hi Ada"));
        }

        @Test
        @DisplayName("carries the way out in both halves")
        void optOut() {
            // EmailLayout.footer refuses to render without one, and the text
            // part has no footer helper to refuse on its behalf -- so the text
            // half is the one that can quietly lose it.
            assertTrue(text.contains("/unsubscribe?token=tok"));
            assertTrue(html.contains("/unsubscribe?token=tok"));
        }

        @Test
        @DisplayName("sends them to the page that actually takes the file")
        void link() {
            assertTrue(text.contains("https://actuallyremote.work/account"));
            assertTrue(html.contains("https://actuallyremote.work/account"));
        }

        @Test
        @DisplayName("the trailing slash on the configured site url is not doubled")
        void noDoubleSlash() {
            // The constructor is given "https://actuallyremote.work/" above, on
            // purpose: SITE_URL has carried a trailing slash in this project
            // before, and "…work//account" is a link that works everywhere
            // except in the eye of a reader deciding whether to trust it.
            assertFalse(text.contains("work//"));
            assertFalse(html.contains("work//"));
        }

        @Test
        @DisplayName("says nothing a filter reads as a campaign")
        void notSpammy() {
            // Each of these was a deliberate choice in the copy rather than an
            // accident worth guarding: the subject and body are plain on
            // purpose, and the next person to edit them should have to delete
            // a test to make them shouty.
            String all = (text + " " + CvRequestSender.class.getName()).toLowerCase(Locale.ROOT);
            for (String word : List.of(
                    "act now", "urgent", "don't miss out", "free", "limited time",
                    "click here", "guarantee", "risk-free", "100%", "$$$")) {
                assertFalse(all.contains(word), "spam trigger in the copy: " + word);
            }
            assertFalse(text.contains("!"), "no exclamation marks");
            // The html half is checked on the words rather than the document:
            // `<!doctype html>` carries one and is not copy.
            assertFalse(stripMarkup(html).contains("!"), "no exclamation marks");
        }

        @Test
        @DisplayName("the subject is a statement, not a shout")
        void subject() {
            String subject = subjectLine();
            assertNotNull(subject);
            assertFalse(subject.contains("!"));
            assertFalse(subject.equals(subject.toUpperCase(Locale.ROOT)));
            // Gmail truncates around sixty characters on a phone. A subject
            // that is cut mid-word is read as one, which is the opposite of
            // what a plain subject is for.
            assertTrue(subject.length() <= 60, subject);
        }

        @Test
        @DisplayName("both halves say the same thing")
        void halvesAgree() {
            // The Mailer contract spells this out: a text part that is a stub
            // scores worse than no text part at all, and a filter comparing the
            // two is looking for an HTML half selling what the text half does
            // not mention.
            for (String claim : List.of(
                    "no CV on it yet",
                    "The CV is the part an employer opens",
                    "A PDF, up to 10 MB")) {
                assertTrue(text.contains(claim), "missing from the text half: " + claim);
                assertTrue(html.contains(claim), "missing from the html half: " + claim);
            }
        }

        @Test
        @DisplayName("never says I or my under the product's name")
        void noFirstPerson() {
            // docs/CLAUDE.md, and it applies to every surface including this
            // one. The message is from a board, not from a person.
            for (String banned : List.of(" I ", " I'", "my ", "My ")) {
                assertFalse(text.contains(banned), "first person in the copy: " + banned);
            }
        }

        /**
         * The words a reader sees, without the document around them.
         *
         * <p>Comments and stylesheets go first and tags second. The layout is
         * full of exclamation marks that nobody reads -- `<!doctype html>`,
         * the `<!--[if mso]>` block, and every `!important` in the responsive
         * styles -- and a check that counted those would be measuring
         * EmailLayout rather than the copy written here.
         */
        private static String stripMarkup(String html) {
            return html
                    .replaceAll("(?s)<!--.*?-->", " ")
                    .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
                    .replaceAll("<[^>]*>", " ");
        }

        private String subjectLine() {
            try {
                var field = CvRequestSender.class.getDeclaredField("SUBJECT");
                field.setAccessible(true);
                return (String) field.get(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
