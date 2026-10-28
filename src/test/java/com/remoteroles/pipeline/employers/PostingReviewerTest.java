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

    /** The text the model is pretending to have read, for the quote guard. */
    private static final String POSTING =
            "We are hiring a support engineer. You must reside in Germany. "
            + "Mon-Fri 07.00-16.00 CST. Apply with your CV.";

    private static String reply(String inner) {
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(inner) + "}]}}]}";
    }

    /**
     * The employer's own questions, which are the one thing on a submission
     * that nothing in the catalogue vouches for.
     *
     * <p>Being wrong here is expensive in a way the other two answers are
     * not: a question asking an applicant's age or nationality is put in
     * front of every person who applies, by this board, with its name on it.
     */
    @Nested
    @DisplayName("the employer's own questions")
    class OwnQuestions {

        @Test
        @DisplayName("reach the model numbered, one per line")
        void formatsThem() {
            assertEquals(
                    "1. Which part of this role interests you most?\n2. When could you start?",
                    PostingReviewer.questionList(
                            "[{\"q\":\"Which part of this role interests you most?\",\"long\":true},"
                            + "{\"q\":\"When could you start?\",\"long\":false}]"));
        }

        @Test
        @DisplayName("are \"(none)\" when there are none, or when the column cannot be read")
        void noneWhenEmpty() {
            assertEquals("(none)", PostingReviewer.questionList("[]"));
            assertEquals("(none)", PostingReviewer.questionList(null));
            assertEquals("(none)", PostingReviewer.questionList(""));
            // Not JSON, and not a reason to stop reading the posting itself.
            assertEquals("(none)", PostingReviewer.questionList("{oops"));
            // An entry with no question in it contributes nothing.
            assertEquals("(none)", PostingReviewer.questionList("[{\"long\":true}]"));
        }

        @Test
        @DisplayName("refer the posting when one of them should not be asked")
        void refersOnUnfair() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":true,\"genuine\":true,\"fair\":false,"
                    + "\"concern\":\"It asks for the applicant's date of birth.\",\"quote\":null}"),
                    POSTING, true);
            assertFalse(v.approved());
            assertTrue(v.note().startsWith("One of the questions the employer added"), v.note());
            assertTrue(v.note().contains("date of birth"), v.note());
        }

        @Test
        @DisplayName("publish when all three are yes")
        void approvesWhenFair() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":true,\"genuine\":true,\"fair\":true,"
                    + "\"concern\":null,\"quote\":null}"), POSTING, true);
            assertTrue(v.approved());
        }

        @Test
        @DisplayName("refer when the posting has questions and the answer skipped them")
        void refersWhenFairMissingAndQuestionsExist() {
            var v = PostingReviewer.parse(
                    reply("{\"consistent\":true,\"genuine\":true}"), POSTING, true);
            assertFalse(v.approved());
            assertTrue(v.note().contains("questions on the form"), v.note());
        }

        @Test
        @DisplayName("do not demand that answer from a posting with no questions")
        void silentWhenThereAreNone() {
            // Almost every posting. Refusing these for a missing field would
            // turn priority review off for the whole board on the day a model
            // stopped emitting it.
            var v = PostingReviewer.parse(
                    reply("{\"consistent\":true,\"genuine\":true}"), POSTING, false);
            assertTrue(v.approved());
        }
    }

    @Nested
    @DisplayName("the answer")
    class Answer {

        @Test
        @DisplayName("publishes only on two clear yeses")
        void approvesWhenBothTrue() {
            var v = PostingReviewer.parse(reply("{\"consistent\":true,\"genuine\":true,\"concern\":null,\"quote\":null}"), POSTING, false);
            assertTrue(v.approved());
            assertEquals(null, v.note());
        }

        @Test
        @DisplayName("refers when the text disagrees with what was ticked, and says so")
        void refersOnInconsistent() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":false,\"genuine\":true,"
                    + "\"concern\":\"The posting requires the person to be based in Germany.\","
                    + "\"quote\":\"You must reside in Germany.\"}"), POSTING, false);
            assertFalse(v.approved());
            assertTrue(v.note().startsWith("The text appears to disagree"));
            assertTrue(v.note().contains("You must reside in Germany."));
        }

        @Test
        @DisplayName("refers when it is not a job opening")
        void refersOnNotGenuine() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":true,\"genuine\":false,"
                    + "\"concern\":\"This advertises a paid training course.\",\"quote\":null}"), POSTING, false);
            assertFalse(v.approved());
            assertTrue(v.note().startsWith("It doubts this is a genuine job opening."));
            assertFalse(v.note().contains("quoted"));
        }

        @Test
        @DisplayName("refers when a field is missing — a silence is not a yes")
        void refersOnMissingField() {
            assertFalse(PostingReviewer.parse(reply("{\"consistent\":true}"), POSTING, false).approved());
            assertFalse(PostingReviewer.parse(reply("{\"genuine\":true}"), POSTING, false).approved());
        }

        @Test
        @DisplayName("refers when the answer is a word rather than a boolean")
        void refersOnStringBoolean() {
            assertFalse(PostingReviewer.parse(
                    reply("{\"consistent\":\"true\",\"genuine\":\"true\"}"), POSTING, false).approved());
        }

        @Test
        @DisplayName("refers on anything that is not the shape of an answer")
        void refersOnRubbish() {
            assertFalse(PostingReviewer.parse("not json at all", POSTING, false).approved());
            assertFalse(PostingReviewer.parse("{}", POSTING, false).approved());
            assertFalse(PostingReviewer.parse(reply("still not json"), POSTING, false).approved());
            assertFalse(PostingReviewer.parse("{\"candidates\":[]}", POSTING, false).approved());
        }
    }

    @Nested
    @DisplayName("the quotation it offers as evidence")
    class Quote {

        @Test
        @DisplayName("is shown when it really is in the posting")
        void keepsRealQuote() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":false,\"genuine\":true,\"concern\":\"Germany only.\","
                    + "\"quote\":\"You must reside in Germany.\"}"), POSTING, false);
            assertTrue(v.note().contains("It quoted"));
            assertTrue(v.note().contains("You must reside in Germany."));
        }

        @Test
        @DisplayName("survives different punctuation and case, which the model reflows")
        void ignoresPunctuation() {
            assertTrue(PostingReviewer.quoted("you MUST reside, in Germany", POSTING));
        }

        @Test
        @DisplayName("is dropped when the posting does not contain it")
        void dropsInventedQuote() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":false,\"genuine\":true,\"concern\":\"US only.\","
                    + "\"quote\":\"Applicants must hold a US green card.\"}"), POSTING, false);
            assertFalse(v.note().contains("It quoted"));
            assertTrue(v.note().contains("not in the posting"));
            // The finding itself is kept: what was dropped is the evidence,
            // not the concern, and the posting still goes to a person.
            assertTrue(v.note().contains("US only."));
            assertFalse(v.approved());
        }

        @Test
        @DisplayName("rejects a fragment too short to mean anything")
        void rejectsShortFragment() {
            assertFalse(PostingReviewer.quoted("in", POSTING));
            assertFalse(PostingReviewer.quoted(null, POSTING));
        }
    }

    @Nested
    @DisplayName("the daily ceiling on one account")
    class Cap {

        @Test
        @DisplayName("refers rather than refuses, and says nothing is wrong with the posting")
        void capRefers() {
            var v = PostingReviewer.Verdict.capped(20);
            assertFalse(v.approved());
            assertTrue(v.note().contains("20"));
            assertTrue(v.note().contains("daily limit"));
            assertTrue(v.note().contains("Nothing is wrong with the posting"));
        }

        @Test
        @DisplayName("names no model, so it costs nothing and counts for nothing tomorrow")
        void capCostsNothing() {
            assertEquals(null, PostingReviewer.Verdict.capped(20).model());
            assertEquals(null, PostingReviewer.Verdict.capped(20).findings());
        }

        @Test
        @DisplayName("a reading that failed also names no model")
        void failuresDoNotCount() {
            assertEquals(null, PostingReviewer.Verdict.refer("It could not run (HTTP 503).").model());
            assertEquals(null,
                    PostingReviewer.parse("not json at all", POSTING, false).model());
        }

        @Test
        @DisplayName("a reading that answered is attributed, which is what the ceiling counts")
        void answersAreAttributed() {
            var v = PostingReviewer.parse(reply(
                    "{\"consistent\":true,\"genuine\":true,\"concern\":null,\"quote\":null}"), POSTING, false)
                    .by("gemini-3.1-flash-lite");
            assertEquals("gemini-3.1-flash-lite", v.model());
            assertTrue(v.approved());
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
