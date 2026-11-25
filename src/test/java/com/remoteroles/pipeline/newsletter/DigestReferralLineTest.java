package com.remoteroles.pipeline.newsletter;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The referral line rides one weekly issue a month, never two and never none.
 *
 * <p>Worth a test because both failures are invisible from one email: a line
 * every week is what turns the newsletter into a pitch at people looking for
 * work, and a month without it is a program nobody hears about.
 */
class DigestReferralLineTest {

    @Test
    @DisplayName("falls on exactly one issue in every month, whatever weekday it is sent")
    void onceAMonth() {
        for (int weekday = 0; weekday < 7; weekday++) {
            LocalDate day = LocalDate.of(2026, 1, 1).plusDays(weekday);
            // Four years of weekly issues, indexed by month: 1 is Jan 2026.
            int[] perMonth = new int[4 * 12 + 1];
            while (day.getYear() < 2030) {
                if (DigestSender.referralLineDue(day)) {
                    perMonth[(day.getYear() - 2026) * 12 + day.getMonthValue()]++;
                }
                day = day.plusWeeks(1);
            }
            for (int m = 1; m <= 4 * 12; m++) {
                assertEquals(1, perMonth[m], "month index " + m + ", start weekday " + weekday);
            }
        }
    }
}
