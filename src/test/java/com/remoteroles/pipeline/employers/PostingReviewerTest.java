package com.remoteroles.pipeline.employers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code on either side of the model, which is the expensive half.
 *
 * <p>What the model concludes about a posting is a judgement; what this class
 * does with the answer is a rule, and getting it wrong publishes a job nobody
 * read on a board whose entire pitch is that somebody did. So every test here
 * asks the same question: when the answer is anything other than two clear
 * yeses, does it refer?
 */
class PostingReviewerTest {

    private static String reply(String inner) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(inner) + "}]}}]}";
    }

    @Nested
    @DisplayName("the answer")
    class Answer {

        @Test
        @DisplayName("publishes only on two clear yeses")
        void approvesWhenBothTrue() {
            var v = PostingReviewer.parse(reply("{\"consistent\":true,\"genuine\":true,\"concern\":null,\"quote\":null}"));
            assertTrue(v.approved());
            assertEquals(null, v.note());
        }

        @Test
        @DisplayName("refers when the text disagrees with what was ticked, and says so")
        void refersOnInconsistent() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":false,\"genuine\":true,"
                    + "\"concern\":\"The posting requires the person to be based in Germany.\","
                    + "\"quote\":\"You must reside in Germany.\"}"));
            assertFalse(v.approved());
            assertTrue(v.note().startsWith("The text appears to disagree"));
            assertTrue(v.note().contains("You must reside in Germany."));
        }

        @Test
        @DisplayName("refers when it is not a job opening")
        void refersOnNotGenuine() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":true,\"genuine\":false,"
                    + "\"concern\":\"This advertises a paid training course.\",\"quote\":null}"));
            assertFalse(v.approved());
            assertTrue(v.note().startsWith("It doubts this is a genuine job opening."));
            assertFalse(v.note().contains("quoted"));
        }

        @Test
        @DisplayName("refers when a field is missing — a silence is not a yes")
        void refersOnMissingField() {
            assertFalse(PostingReviewer.parse(reply("{\"consistent\":true}")).approved());
            assertFalse(PostingReviewer.parse(reply("{\"genuine\":true}")).approved());
        }

        @Test
        @DisplayName("refers when the answer is a word rather than a boolean")
        void refersOnStringBoolean() {
            assertFalse(PostingReviewer.parse(
                    reply("{\"consistent\":\"true\",\"genuine\":\"true\"}")).approved());
        }

        @Test
        @DisplayName("refers on anything that is not the shape of an answer")
        void refersOnRubbish() {
            assertFalse(PostingReviewer.parse("not json at all").approved());
            assertFalse(PostingReviewer.parse("{}").approved());
            assertFalse(PostingReviewer.parse(reply("still not json")).approved());
            assertFalse(PostingReviewer.parse("{\"candidates\":[]}").approved());
        }
    }

    @Nested
    @DisplayName("what the employer ticked")
    class Eligibility {

        @Test
        @DisplayName("is put to the model in words, not in the column's codes")
        void readsAsEnglish() {
            assertEquals("Anyone, anywhere. No country requirement at all.",
                    PostingReviewer.eligibility("WORLDWIDE", null, ""));
            assertEquals("Anyone, anywhere, except people in: Cuba, Iran.",
                    PostingReviewer.eligibility("WORLDWIDE_EXCEPT", null, "Cuba, Iran"));
            assertEquals("Only people in: Brazil.",
                    PostingReviewer.eligibility("COUNTRY", "Brazil", ""));
        }

        @Test
        @DisplayName("keeps a region's exclusions, which are a second restriction")
        void regionWithExclusions() {
            assertEquals("Only people in these regions: EMEA. Excluding: Russia.",
                    PostingReviewer.eligibility("REGION", "EMEA", "Russia"));
        }

        @Test
        @DisplayName("says so rather than inventing one when the scope is missing")
        void unknownScope() {
            assertEquals("Not stated.", PostingReviewer.eligibility(null, null, ""));
            assertEquals("Not stated.", PostingReviewer.eligibility("SOMETHING_NEW", "x", ""));
        }
    }
}
