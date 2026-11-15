package com.remoteroles.pipeline.employers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The number an employer reads as a judgement about a person.
 *
 * <p>Three rules are priced here and each of them costs somebody something if
 * it slips: what silence is worth, what an essential requirement weighs, and
 * what a contradicted essential does to the rest of the score. None of them
 * fails loudly when it changes — the list simply comes back in a different
 * order, and nobody can tell from looking at it that it is the wrong one.
 */
class FitScoreTest {

    @Test
    @DisplayName("an essential requirement weighs twice a nice-to-have")
    void weights() {
        assertEquals(2, FitScore.weight(true));
        assertEquals(1, FitScore.weight(false));
    }

    @Test
    @DisplayName("UNKNOWN is worth half, never zero")
    void unknownIsHalf() {
        // Scoring silence as failure is treating UNKNOWN as UNMET by a quieter
        // route: in the number, where nobody sees it happen.
        assertEquals(0.5, FitScore.credit("UNKNOWN", false));
        assertEquals(1.0, FitScore.credit("UNKNOWN", true));
        assertEquals(0.0, FitScore.credit("UNMET", true));
        assertEquals(2.0, FitScore.credit("MET", true));
    }

    @Test
    @DisplayName("only a contradicted essential caps the score")
    void whatCaps() {
        assertTrue(FitScore.capsTheScore("UNMET", true));
        assertFalse(FitScore.capsTheScore("UNMET", false));
        assertFalse(FitScore.capsTheScore("UNKNOWN", true));
        assertFalse(FitScore.capsTheScore("MET", true));
    }

    @Test
    @DisplayName("everything met is ten")
    void perfect() {
        assertEquals(10.0, FitScore.outOfTen(4, 4, false));
    }

    @Test
    @DisplayName("somebody strong everywhere but the one hard line stops at 4")
    void cappedBelowWhatTheyEarned() {
        // Five nice-to-haves met and one essential contradicted: 5 of 7 points
        // is 7.1, and the employer would read that as a good candidate for a
        // role they cannot legally or technically take.
        double uncapped = FitScore.outOfTen(5, 7, false);
        assertTrue(uncapped > 7.0);
        assertEquals(4.0, FitScore.outOfTen(5, 7, true));
    }

    @Test
    @DisplayName("a capped score below the cap is left alone")
    void capIsACeilingNotAFloor() {
        // The cap must not promote anybody. 1 of 7 is 1.4 and stays there.
        assertEquals(1.4, FitScore.outOfTen(1, 7, true));
    }

    @Test
    @DisplayName("a role with no requirements scores nobody, rather than everybody")
    void noRequirements() {
        // Dividing by zero into a perfect ten would put every profile at the
        // top of a list whose employer never said what they wanted.
        assertEquals(0.0, FitScore.outOfTen(0, 0, false));
    }

    @Test
    @DisplayName("the stored score is one decimal, because the column and the screen are")
    void rounding() {
        assertEquals(6.7, FitScore.outOfTen(2, 3, false));
    }
}
