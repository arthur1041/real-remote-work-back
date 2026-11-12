package com.remoteroles.pipeline.newsletter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.remoteroles.pipeline.newsletter.PostingDecisionSender.Decided;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One message per employer per sitting, instead of one per posting.
 *
 * <p>This exists because of a real afternoon: a single employer submitted 107
 * roles, 79 were approved, and the sender put 79 separate emails in one inbox
 * and spent 243 of that day's 300 sends. The grouping that fixes it is the kind
 * of code that fails silently in both directions — a key that collapses too
 * much mails one employer's decisions to another employer's address, and a key
 * that collapses too little goes quietly back to one mail per posting — and
 * neither shows up in a log or in a staging click-through.
 *
 * <p>Nothing is sent. The sender is built with no database, no mailer and no
 * budget, because the parts under test read none of them; the copy is built
 * through the real methods and read.
 */
class PostingDecisionBatchTest {

    private final PostingDecisionSender sender =
            new PostingDecisionSender(null, null, null, "https://actuallyremote.work/", 10, 3);

    private static Decided approved(long id, long employerId, String title) {
        return new Decided(id, employerId, "jobs@northwind.example", title,
                "Northwind Systems", "Dana Whitfield", true, 800000L + id, "");
    }

    private static Decided rejected(long id, long employerId, String title, String note) {
        return new Decided(id, employerId, "jobs@northwind.example", title,
                "Northwind Systems", "Dana Whitfield", false, null, note);
    }

    private static List<Decided> approvals(long employerId, int count) {
        List<Decided> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(approved(i, employerId, "Role number " + i));
        }
        return rows;
    }

    @Nested
    class Grouping {

        @Test
        @DisplayName("seventy approvals from one employer are one message")
        void oneSitting() {
            Map<String, List<Decided>> groups = PostingDecisionSender.group(approvals(105, 70));

            assertEquals(1, groups.size());
            assertEquals(70, groups.values().iterator().next().size());
        }

        @Test
        @DisplayName("two employers never share a message")
        void neverCrossEmployers() {
            List<Decided> rows = new ArrayList<>(approvals(105, 3));
            rows.addAll(approvals(11, 2));

            Map<String, List<Decided>> groups = PostingDecisionSender.group(rows);

            assertEquals(2, groups.size());
            // The address is taken from the first row of each group, so a group
            // holding two employers would mail one of them the other's roles.
            for (List<Decided> group : groups.values()) {
                long employer = group.get(0).employerId();
                assertTrue(group.stream().allMatch(r -> r.employerId() == employer));
            }
        }

        @Test
        @DisplayName("an approval and a rejection stay two messages, even for one employer")
        void outcomesStaySeparate() {
            List<Decided> rows = List.of(
                    approved(1, 105, "Senior Platform Engineer"),
                    rejected(2, 105, "Staff Data Engineer", "A copy of the role above."),
                    approved(3, 105, "Product Designer"));

            Map<String, List<Decided>> groups = PostingDecisionSender.group(rows);

            assertEquals(2, groups.size());
            for (List<Decided> group : groups.values()) {
                boolean approved = group.get(0).approved();
                assertTrue(group.stream().allMatch(r -> r.approved() == approved));
            }
        }

        @Test
        @DisplayName("every decision lands in exactly one group")
        void nothingLostOrDoubled() {
            List<Decided> rows = new ArrayList<>();
            for (int i = 1; i <= 9; i++) {
                rows.add(approved(i, 105, "Role " + i));
            }
            for (int i = 10; i <= 13; i++) {
                rows.add(approved(i, 11, "Role " + i));
            }
            rows.add(rejected(14, 105, "Turned down", "Not worldwide."));

            List<Long> ids = PostingDecisionSender.group(rows).values().stream()
                    .flatMap(List::stream)
                    .map(Decided::id)
                    .toList();

            // The rows are stamped as notified per group, so a row in two
            // groups is a duplicate email and a row in none is a decision that
            // is never delivered and never retried again.
            assertEquals(rows.size(), ids.size());
            assertEquals(rows.size(), ids.stream().distinct().count());
        }
    }

    @Nested
    class BatchedCopy {

        @Test
        @DisplayName("the subject counts them and never names one role")
        void subjectCounts() {
            assertEquals("5 roles are live on Actually Remote",
                    sender.subject(approvals(105, 5)));
            assertEquals("2 submissions were not published", sender.subject(List.of(
                    rejected(1, 105, "Senior Platform Engineer", "Not worldwide."),
                    rejected(2, 105, "Staff Data Engineer", "A copy of the one above."))));
        }

        @Test
        @DisplayName("a group of one is still the single-decision message, unchanged")
        void oneIsNotABatch() {
            List<Decided> one = List.of(approved(1, 105, "Senior Platform Engineer"));

            // The one-role mail was written and reviewed on its own terms. A
            // batch format applied to a single item reads like a mail-merge of
            // one, so the group-aware methods have to hand it straight back.
            assertEquals("Senior Platform Engineer is live on Actually Remote",
                    sender.subject(one));
            assertTrue(sender.body(one).contains("is published and anyone can find it now"));
            assertFalse(sender.body(one).contains("1 roles"));
        }

        @Test
        @DisplayName("every approved role in the batch carries its own link")
        void everyRoleIsReachable() {
            List<Decided> group = approvals(105, 3);
            String text = sender.body(group);
            String html = sender.html(group);

            for (Decided row : group) {
                assertTrue(text.contains(row.title()), "missing from text: " + row.title());
                assertTrue(text.contains("/jobs/" + row.jobId()),
                        "missing link in text: " + row.jobId());
                assertTrue(html.contains(row.title()), "missing from html: " + row.title());
                assertTrue(html.contains("/jobs/" + row.jobId()),
                        "missing link in html: " + row.jobId());
            }
        }

        @Test
        @DisplayName("every rejection in the batch carries its own reason")
        void everyReasonSurvives() {
            List<Decided> group = List.of(
                    rejected(1, 105, "Senior Platform Engineer",
                            "The advert requires EU eligibility."),
                    rejected(2, 105, "Staff Data Engineer",
                            "The description is a copy of the role above."));
            String text = sender.body(group);
            String html = sender.html(group);

            // A refusal without its reason is the one message on this board
            // that cannot be re-derived by the person who got it.
            for (Decided row : group) {
                assertTrue(text.contains(row.note()), "missing from text: " + row.note());
                assertTrue(html.contains(row.note()), "missing from html: " + row.note());
            }
        }

        @Test
        @DisplayName("a rejection whose reason was never typed still reads as a reason")
        void emptyNoteFallsBack() {
            List<Decided> group = List.of(
                    rejected(1, 105, "Senior Platform Engineer", "A copy of another role."),
                    rejected(2, 105, "Staff Data Engineer", ""));

            assertTrue(sender.body(group).contains("It did not meet what this board lists"));
        }

        @Test
        @DisplayName("past the listing cap it says how many more, and lists no more")
        void capIsHonest() {
            // The cap is 3 in this instance. Gmail clips a message over about
            // 102KB, so a 107-role batch has to stop listing somewhere -- but
            // stopping silently would hide roles the employer is being told are
            // live.
            List<Decided> group = approvals(105, 7);
            String text = sender.body(group);
            String html = sender.html(group);

            assertEquals("and 4 more", sender.remainder(group));
            assertTrue(text.contains("and 4 more"));
            assertTrue(html.contains("and 4 more"));
            assertTrue(text.contains("7 roles are published"));
            assertTrue(text.contains("Role number 3"));
            assertFalse(text.contains("Role number 4"));
            assertFalse(html.contains("Role number 4"));
            // And the dashboard, which is where the unlisted ones are.
            assertTrue(text.contains("/employers"));
        }

        @Test
        @DisplayName("under the cap it says nothing about more")
        void noRemainderWhenAllListed() {
            assertEquals("", sender.remainder(approvals(105, 3)));
            assertFalse(sender.body(approvals(105, 3)).contains("more"));
        }

        @Test
        @DisplayName("carries no unsubscribe link")
        void noOptOut() {
            // This is the answer to something they submitted, not a list they
            // joined. An opt-out on it would offer to stop telling them the
            // outcome of their own requests.
            String html = sender.html(approvals(105, 4));
            assertFalse(html.contains("unsubscribe"));
            assertFalse(html.toLowerCase().contains("opt out"));
        }

        @Test
        @DisplayName("greets the contact once, not once per role")
        void oneGreeting() {
            String text = sender.body(approvals(105, 5));
            assertEquals(1, text.split("Hi Dana", -1).length - 1);
        }
    }
}
