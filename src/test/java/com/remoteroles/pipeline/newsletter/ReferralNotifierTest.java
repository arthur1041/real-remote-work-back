package com.remoteroles.pipeline.newsletter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The words the referral notices put in front of a candidate. The amounts and
 * dates are promises -- the same ones /terms and the referrals page make -- so
 * a message that rounds them, or that names the employer the referral page is
 * careful never to name, is a defect worth a test.
 */
class ReferralNotifierTest {

    private final ReferralNotifier notifier =
            new ReferralNotifier(null, null, null, "https://actuallyremote.work/");

    private static ReferralNotifier.Notice earning(int cents, String cycle, String paypal) {
        return new ReferralNotifier.Notice(1, "ana@example.com", "Ana Maria Silva", cents, cycle,
                Instant.parse("2027-01-21T12:00:00Z"), paypal);
    }

    @Test
    void amountsAreWholeDollarsWhenRound() {
        assertThat(ReferralNotifier.money(2500)).isEqualTo("$25");
        assertThat(ReferralNotifier.money(10000)).isEqualTo("$100");
        assertThat(ReferralNotifier.money(2550)).isEqualTo("$25.50");
    }

    @Test
    void subscribedNoticeStatesAmountPlanAndConfirmationDate() {
        var n = earning(10000, "ANNUAL", "ana@paypal.example");
        assertThat(notifier.subscribedSubject(n)).isEqualTo("You earned $100 — confirms on 21 January 2027");
        assertThat(notifier.subscribedText(n))
                .startsWith("Hi Ana,")
                .contains("an annual Growth plan")
                .contains("21 January 2027")
                .doesNotContain("Add your PayPal email");
    }

    @Test
    void withoutPaypalTheNoticesAskForIt() {
        var n = earning(2500, "MONTHLY", null);
        assertThat(notifier.subscribedText(n)).contains("a monthly Growth plan").contains("Add your PayPal email");
        assertThat(notifier.confirmedText(n)).contains("add the email address of your PayPal account");
        assertThat(notifier.confirmedHtml(n)).contains("Add your PayPal email");
    }

    @Test
    void starterIsNamedAsStarter() {
        var n = new ReferralNotifier.Notice(1, "ana@example.com", "Ana", 500, "STARTER", "MONTHLY",
                Instant.parse("2027-01-21T12:00:00Z"), "ana@paypal.example", false);
        assertThat(notifier.subscribedText(n))
                .contains("subscribed to a monthly Starter plan")
                .contains("That earns you $5.");
    }

    @Test
    void aMoveUpSaysSoAndCallsTheAmountAnother() {
        // Starter then Growth: the second earning is the difference, and
        // reading "you earned $20" for a $25 plan would look like a mistake.
        var n = new ReferralNotifier.Notice(2, "ana@example.com", "Ana", 2000, "GROWTH", "MONTHLY",
                Instant.parse("2027-01-21T12:00:00Z"), "ana@paypal.example", true);
        assertThat(notifier.subscribedText(n))
                .contains("moved up to a monthly Growth plan")
                .contains("That earns you another $20.");
        assertThat(notifier.subscribedHtml(n)).contains("another <strong>$20</strong>");
    }

    @Test
    void confirmedNoticeSaysWhereTheMoneyGoes() {
        var n = earning(2500, "MONTHLY", "ana@paypal.example");
        assertThat(notifier.confirmedSubject(n)).isEqualTo("Your $25 referral earning is confirmed");
        assertThat(notifier.confirmedText(n)).contains("sent by PayPal to ana@paypal.example");
    }

    @Test
    void signupNoticePromisesNothingYetAndLinksToThePage() {
        var n = earning(0, null, null);
        assertThat(notifier.signupText(n))
                .contains("Nothing is earned yet")
                .contains("https://actuallyremote.work/account/referrals");
    }

    @Test
    void noNoticeNamesAnEmployer() {
        // The Notice carries no employer at all: the rows the notifier reads
        // select none, and this pins that a later change does not add one.
        assertThat(ReferralNotifier.Notice.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("company", "companyName", "employer", "employerName");
    }
}
