package com.remoteroles.pipeline.newsletter;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The employer digest's rule is "no news, no email", and what counts as news is
 * the part worth pinning down: a quiet week must send nothing, and anything an
 * employer would want to come back for must send something.
 */
class EmployerDigestTest {

    private final EmployerDigestSender sender =
            new EmployerDigestSender(null, null, null, "https://actuallyremote.work/");

    private static EmployerDigestSender.RoleWeek role(int views, int prev, int clicks, int apps, Double best) {
        return new EmployerDigestSender.RoleWeek(654, "Market Specialist", true, views, prev, clicks, apps, best);
    }

    private static EmployerDigestSender.Week week(List<EmployerDigestSender.RoleWeek> roles, int unread,
                                                  List<EmployerDigestSender.Titled> needsWork) {
        return new EmployerDigestSender.Week(137, "e@example.test", "Hillside", "Dana Whitfield", "tok",
                roles, unread, needsWork, List.of());
    }

    @Test
    void aQuietWeekSendsNothing() {
        assertFalse(EmployerDigestSender.hasNews(week(List.of(role(3, 0, 0, 0, null)), 0, List.of())));
        assertFalse(EmployerDigestSender.hasNews(week(List.of(), 0, List.of())));
    }

    @Test
    void anythingWorthComingBackForSendsSomething() {
        assertTrue(EmployerDigestSender.hasNews(week(List.of(role(2, 0, 0, 1, 9.0)), 0, List.of())));
        assertTrue(EmployerDigestSender.hasNews(week(List.of(), 1, List.of())));
        assertTrue(EmployerDigestSender.hasNews(week(List.of(),
                0, List.of(new EmployerDigestSender.Titled(652, "Hebrew Audio")))));
        assertTrue(EmployerDigestSender.hasNews(week(List.of(role(4, 0, 1, 0, null)), 0, List.of())));
        assertTrue(EmployerDigestSender.hasNews(week(List.of(role(10, 0, 0, 0, null)), 0, List.of())));
    }

    @Test
    void theSubjectAndTheButtonFollowWhatMattersMost() {
        var apps = week(List.of(role(36, 0, 12, 2, 9.0)), 0, List.of());
        assertEquals("Your week on Actually Remote: 2 new applications", sender.subject(apps));
        assertEquals("https://actuallyremote.work/employers/applications/654", sender.mainAction(apps).url());

        var messages = week(List.of(role(36, 0, 12, 2, 9.0)), 3, List.of());
        assertEquals("Your week on Actually Remote: 3 unread messages", sender.subject(messages));
        assertEquals("Read your messages", sender.mainAction(messages).label());
    }

    @Test
    void theRoleLineLeadsWithApplicationsAndTheirBestScore() {
        assertEquals("2 new applications, best screened 9/10 · 12 people clicked apply · 36 views",
                EmployerDigestSender.roleLine(role(36, 0, 12, 2, 9.0)));
    }

    @Test
    void aTrendIsOnlySaidWhenLastWeekWasBigEnough() {
        assertEquals("", EmployerDigestSender.trend(role(9, 3, 0, 0, null)));
        assertEquals(" (up 50% on last week)", EmployerDigestSender.trend(role(60, 40, 0, 0, null)));
        assertEquals("", EmployerDigestSender.trend(role(42, 40, 0, 0, null)));
        assertEquals("", EmployerDigestSender.trend(role(10, 40, 0, 0, null)));
    }

    @Test
    void itCarriesAWayOut() {
        var w = week(List.of(role(36, 0, 12, 2, 9.0)), 0, List.of());
        assertTrue(sender.html(w).contains("/api/employer-digest?token=tok"));
        assertTrue(sender.text(w).contains("/api/employer-digest?token=tok"));
    }
}
