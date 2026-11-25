package com.remoteroles.pipeline.newsletter;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The referral note in the candidate's chat.
 *
 * <p>Worth testing because it goes to every candidate account, unattended,
 * under the product's name: an amount the site does not pay, a dead link, or
 * an "I" in it reaches everybody before anybody reads it.
 */
class ReferralChatNoteTest {

    private final ReferralChatNote note = new ReferralChatNote(null, "https://actuallyremote.work/", true);

    @Test
    @DisplayName("the first note names the amount, both links, and that a reply reaches someone")
    void intro() {
        String text = note.intro("Ana Silva");
        assertTrue(text.startsWith("Hi Ana, "));
        assertTrue(text.contains("up to $100 per employer"));
        assertTrue(text.contains("https://actuallyremote.work/account/referrals"));
        assertTrue(text.contains("https://actuallyremote.work/refer"));
        assertTrue(text.contains("reply here"));
    }

    @Test
    @DisplayName("the reminder says it is one, and still carries the link")
    void reminder() {
        String text = note.reminder(null);
        assertTrue(text.startsWith("Hi there, a reminder"));
        assertTrue(text.contains("https://actuallyremote.work/account/referrals"));
    }

    @Test
    @DisplayName("never speaks as a person: no I, no my")
    void voice() {
        for (String text : new String[] {note.intro("Ana"), note.reminder("Ana")}) {
            assertFalse(text.matches("(?s).*\\b(I|my|My|I'm)\\b.*"), text);
        }
    }

    @Test
    @DisplayName("the amount matches the site's own ceiling, Growth annual")
    void amountMatchesTheSite() throws Exception {
        Path rules = Path.of("..", "web", "lib", "referral-rules.ts");
        Assumptions.assumeTrue(Files.exists(rules), "web checkout not beside the pipeline");
        String ts = Files.readString(rules);
        assertTrue(ts.contains("GROWTH: { MONTHLY: 2500, ANNUAL: 10000 }"),
                "Growth annual changed in referral-rules.ts; update MAX_EARNING");
        assertEquals("$100", ReferralChatNote.MAX_EARNING);
    }
}
