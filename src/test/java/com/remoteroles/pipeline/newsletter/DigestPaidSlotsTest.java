package com.remoteroles.pipeline.newsletter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sold block: how much of the email it can be, and who gets which part of
 * it.
 *
 * <p>Worth testing because the thing being checked is a promise with money
 * behind it. An employer pays for their role to go out in the weekly email,
 * and the only two ways to break that are to let the block swallow the issue
 * or to let a role fall out of the ring and be sent to nobody. Neither failure
 * is visible from the outside -- the email looks fine either way.
 */
class DigestPaidSlotsTest {

    private static List<DigestSender.Role> roles(int n) {
        List<DigestSender.Role> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new DigestSender.Role(i, "Role " + i, "Company " + i, "Anywhere"));
        }
        return out;
    }

    @Nested
    @DisplayName("how much can be sold")
    class Size {

        @Test
        @DisplayName("is a third of the issue at the size the issue actually is")
        void aThirdOfFifteen() {
            assertEquals(5, DigestSender.paidSlots(15));
        }

        @Test
        @DisplayName("stays a minority at every size an issue could be set to")
        void alwaysAMinority() {
            for (int issue = 3; issue <= 200; issue++) {
                int paid = DigestSender.paidSlots(issue);
                assertTrue(paid * 2 < issue,
                        "an issue of " + issue + " would carry " + paid + " sold places");
            }
        }

        @Test
        @DisplayName("never reaches zero, so a small issue still carries what was paid for")
        void neverZero() {
            assertEquals(1, DigestSender.paidSlots(1));
            assertEquals(1, DigestSender.paidSlots(5));
        }
    }

    @Nested
    @DisplayName("which part each reader gets")
    class Slice {

        @Test
        @DisplayName("is everything, while there is less paid than there are places")
        void everybodyGetsAllOfASmallList() {
            List<DigestSender.Role> paid = roles(3);
            // The day the first customer subscribes, and the case to be right
            // about first.
            assertSame(paid, DigestSender.sliceFor(paid, 5, 1, 1));
            assertSame(paid, DigestSender.sliceFor(paid, 5, 999, 7));
        }

        @Test
        @DisplayName("is exactly the places available, once there is more paid than that")
        void fillsTheSlots() {
            assertEquals(5, DigestSender.sliceFor(roles(100), 5, 42, 3).size());
        }

        @Test
        @DisplayName("reaches every paying role across the audience — nobody is sent to nobody")
        void coversEveryRole() {
            List<DigestSender.Role> paid = roles(100);
            Set<Long> reached = new HashSet<>();
            for (long reader = 1; reader <= 790; reader++) {
                DigestSender.sliceFor(paid, 5, reader, 12)
                        .forEach(r -> reached.add(r.id()));
            }
            assertEquals(100, reached.size(),
                    "a paid role that reaches no reader at all is the failure this guards");
        }

        @Test
        @DisplayName("spreads them evenly enough that no customer is quietly worth less")
        void spreadsEvenly() {
            List<DigestSender.Role> paid = roles(100);
            Map<Long, Integer> seen = new HashMap<>();
            for (long reader = 1; reader <= 790; reader++) {
                DigestSender.sliceFor(paid, 5, reader, 12)
                        .forEach(r -> seen.merge(r.id(), 1, Integer::sum));
            }
            int min = seen.values().stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = seen.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            // 790 readers x 5 places / 100 roles is 39.5 each on average. The
            // spread is what matters: one role on 60 and another on 20 would
            // mean two customers paying the same for different things.
            assertTrue(min >= 30 && max <= 50,
                    "impressions ran from " + min + " to " + max + " across the paid roles");
        }

        @Test
        @DisplayName("moves on next week, so one reader is not sent the same five for ever")
        void movesBetweenIssues() {
            List<DigestSender.Role> paid = roles(100);
            List<Long> thisWeek = DigestSender.sliceFor(paid, 5, 7, 1)
                    .stream().map(DigestSender.Role::id).toList();
            List<Long> nextWeek = DigestSender.sliceFor(paid, 5, 7, 2)
                    .stream().map(DigestSender.Role::id).toList();
            assertTrue(!thisWeek.equals(nextWeek), "the same reader got the same five twice");
        }

        @Test
        @DisplayName("sends the same issue twice rather than two different ones")
        void isDeterministic() {
            List<DigestSender.Role> paid = roles(40);
            assertEquals(DigestSender.sliceFor(paid, 5, 3, 9),
                    DigestSender.sliceFor(paid, 5, 3, 9));
        }
    }
}
