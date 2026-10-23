package com.remoteroles.pipeline.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two guards around the model, both earned by a wrong answer.
 *
 * <p>Neither needs the network. What the model concludes is measured against
 * hand-checked postings and recorded in {@link ScopeReviewer}'s own comment;
 * what is tested here is the code on either side of it, which is where the
 * failures actually were.
 */
class ScopeReviewerTest {

    @Nested
    @DisplayName("the aggregator's preamble")
    class Preamble {

        @Test
        @DisplayName("is cut, because it reads as a location requirement and is not one")
        void stripsHeadquarters() {
            String html = "<p>\n  <strong>Headquarters:</strong> Remote - United States\n</p>"
                    + "<p>Who we are. We build things.</p>";
            assertEquals("Who we are. We build things.", ScopeReviewer.plainText(html));
        }

        @Test
        @DisplayName("is cut when the board adds its URL line too")
        void stripsHeadquartersAndUrl() {
            String html = "<p><strong>Headquarters:</strong> Charlotte, NC</p>"
                    + "<p><strong>URL:</strong> https://example.com</p>"
                    + "<p>The role.</p>";
            assertEquals("The role.", ScopeReviewer.plainText(html));
        }

        @Test
        @DisplayName("is only cut at the front, never mid-posting")
        void leavesLaterMentionsAlone() {
            String html = "<p>About the job.</p>"
                    + "<p><strong>Headquarters:</strong> Remote - US</p>";
            assertTrue(ScopeReviewer.plainText(html).contains("Headquarters"),
                    "a headquarters line further down is the employer's own text");
        }

        @Test
        @DisplayName("leaves a posting that has none untouched")
        void noPreamble() {
            assertEquals("Just a description.", ScopeReviewer.plainText("<p>Just a description.</p>"));
        }

        @Test
        @DisplayName("survives an absent description")
        void nullSafe() {
            assertEquals("", ScopeReviewer.plainText(null));
            assertEquals("", ScopeReviewer.plainText(""));
        }
    }

    @Nested
    @DisplayName("a restriction has to name somewhere")
    class Place {

        @Test
        @DisplayName("accepts a place that is a place")
        void accepts() {
            assertTrue(ScopeReviewer.named("Germany"));
            assertTrue(ScopeReviewer.named("British Columbia or Ontario, Canada"));
            assertTrue(ScopeReviewer.named("CET timezone (+/- 3 hours)"));
        }

        @Test
        @DisplayName("rejects the categories the model reaches for on flexibility text")
        void rejectsGeneric() {
            // All of these came back from live postings whose text says you may
            // work anywhere inside wherever you already are -- which names
            // nowhere and restricts nobody.
            assertFalse(ScopeReviewer.named("country of employment"));
            assertFalse(ScopeReviewer.named("their country"));
            assertFalse(ScopeReviewer.named("your own country"));
            assertFalse(ScopeReviewer.named("wherever you live"));
            assertFalse(ScopeReviewer.named("the region"));
        }

        @Test
        @DisplayName("rejects an absent place")
        void rejectsMissing() {
            assertFalse(ScopeReviewer.named(null));
            assertFalse(ScopeReviewer.named("   "));
        }
    }

    @Nested
    @DisplayName("a restriction has to be quotable")
    class Quote {

        private static final String TEXT =
                "This is a remote position open to candidates residing in the US. Apply soon.";

        @Test
        @DisplayName("accepts the sentence that is really there")
        void accepts() {
            assertTrue(ScopeReviewer.quoted("open to candidates residing in the US", TEXT));
        }

        @Test
        @DisplayName("accepts it through different whitespace and punctuation")
        void toleratesFormatting() {
            assertTrue(ScopeReviewer.quoted("open to   candidates, residing in the U.S.", TEXT));
        }

        @Test
        @DisplayName("rejects a sentence the posting does not contain")
        void rejectsInvented() {
            assertFalse(ScopeReviewer.quoted("you must live in Germany to apply", TEXT));
        }

        @Test
        @DisplayName("rejects a missing quote, which is how one wrong answer arrived")
        void rejectsNull() {
            assertFalse(ScopeReviewer.quoted(null, TEXT));
            assertFalse(ScopeReviewer.quoted("", TEXT));
        }

        @Test
        @DisplayName("matches across an entity the flattening left behind")
        void toleratesEntities() {
            // "&nbsp;" survives tag-stripping as the letters "nbsp", which the
            // model's quote never has. This rejected five real restrictions.
            assertTrue(ScopeReviewer.quoted(
                    "This role will be remote, and based in the United States.",
                    ScopeReviewer.plainText(
                            "<p>This role will be remote,&nbsp; and based in the United States.</p>")));
        }

        @Test
        @DisplayName("matches a quote that is not in the Latin alphabet")
        void handlesNonLatin() {
            // "this role is based in Japan", from a live Zscaler posting that
            // was refused because the comparison deleted every character.
            String text = ScopeReviewer.plainText("<p>この職務は日本を拠点とし、リージョナルディレクター直属となります。</p>");
            assertTrue(ScopeReviewer.quoted("この職務は日本を拠点とし", text));
        }

        @Test
        @DisplayName("rejects a fragment too short to mean anything")
        void rejectsTooShort() {
            assertFalse(ScopeReviewer.quoted("the US", TEXT),
                    "two words match half the corpus and prove nothing");
        }
    }
}
