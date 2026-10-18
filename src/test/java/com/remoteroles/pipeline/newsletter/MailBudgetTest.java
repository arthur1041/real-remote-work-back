package com.remoteroles.pipeline.newsletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The arithmetic that decides how much of a day cold mail may have.
 *
 * <p>Two numbers come from configuration, which means they arrive as whatever
 * somebody typed into an environment file. The combinations that matter are the
 * ones where that typing was wrong: a reserve larger than the budget, a
 * negative anything, a budget of zero. Each has a reading that is arithmetically
 * correct and operationally absurd -- a negative ceiling, mail that stops
 * forever, a reserve that protects nothing -- and the point of these is that the
 * absurd readings cannot be reached.
 *
 * <p>The database is deliberately null. Everything asserted here has to be
 * decidable without one, and a claim that cannot possibly be allowed must be
 * refused before any statement is sent; passing null is what proves it.
 */
class MailBudgetTest {

    private static MailBudget budget(int daily, int reserve) {
        return new MailBudget(null, daily, reserve);
    }

    @Test
    @DisplayName("The ordinary case: invitations stop short of the real cap")
    void reserveIsHeldBack() {
        MailBudget b = budget(280, 60);
        assertEquals(280, b.dailyBudget());
        assertEquals(220, b.coldCeiling());
    }

    @Test
    @DisplayName("A reserve bigger than the budget stops cold mail, it does not go negative")
    void oversizedReserveClamps() {
        // Arithmetically this is a ceiling of -20, and a signed comparison
        // against it would behave strangely rather than obviously. Clamped, the
        // configuration says something true: every message today is reserved
        // for mail somebody is waiting for.
        MailBudget b = budget(100, 120);
        assertEquals(0, b.coldCeiling());
        assertFalse(b.claim(MailBudget.Tier.COLD), "no database call should be needed to refuse this");
    }

    @Test
    @DisplayName("A negative reserve reserves nothing rather than inflating the budget")
    void negativeReserveIsZero() {
        MailBudget b = budget(100, -50);
        assertEquals(100, b.coldCeiling());
    }

    @Test
    @DisplayName("A budget of zero refuses both tiers without touching the database")
    void zeroBudgetSendsNothing() {
        MailBudget b = budget(0, 0);
        assertEquals(0, b.dailyBudget());
        assertFalse(b.claim(MailBudget.Tier.COLD));
        assertFalse(b.claim(MailBudget.Tier.TRANSACTIONAL));
    }

    @Test
    @DisplayName("A negative budget is zero, not a licence to send")
    void negativeBudgetIsZero() {
        MailBudget b = budget(-10, 0);
        assertEquals(0, b.dailyBudget());
        assertFalse(b.claim(MailBudget.Tier.TRANSACTIONAL));
    }
}
