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
            // One account per role: the shape the ring was first tested in.
            out.add(new DigestSender.Role(i, "Role " + i, "Company " + i, "Anywhere", i));
        }
        return out;
    }

    /** `n` roles all published by one account, ids starting at `firstId`. */
    private static List<DigestSender.Role> account(long employer, int n, long firstId) {
        List<DigestSender.Role> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long id = firstId + i;
            out.add(new DigestSender.Role(id, "Role " + id, "Company " + employer, "Anywhere", employer));
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
            assertEquals(Set.copyOf(paid), Set.copyOf(DigestSender.sliceFor(paid, 5, 1, 1)));
            assertEquals(Set.copyOf(paid), Set.copyOf(DigestSender.sliceFor(paid, 5, 999, 7)));
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

    @Nested
    @DisplayName("how it is shared between accounts")
    class Accounts {

        // The pool of 24 Nov 2026: one account with 50 eligible roles, one
        // with a single role.
        private final List<DigestSender.Role> pool = merged(account(105, 50, 1), account(137, 1, 1000));

        private static List<DigestSender.Role> merged(List<DigestSender.Role> a, List<DigestSender.Role> b) {
            List<DigestSender.Role> out = new ArrayList<>(a);
            out.addAll(b);
            return out;
        }

        @Test
        @DisplayName("puts an account with one role in every reader's block, not one in fifty-one")
        void smallAccountReachesEveryone() {
            for (long reader = 1; reader <= 790; reader++) {
                List<DigestSender.Role> theirs = DigestSender.sliceFor(pool, 5, reader, 12);
                assertEquals(5, theirs.size());
                assertTrue(theirs.stream().anyMatch(r -> r.employer() == 137),
                        "reader " + reader + " got five roles from one account while another was paying");
            }
        }

        @Test
        @DisplayName("still sends every one of the big account's roles to somebody")
        void bigAccountStillCovered() {
            Set<Long> reached = new HashSet<>();
            for (long reader = 1; reader <= 790; reader++) {
                DigestSender.sliceFor(pool, 5, reader, 12).stream()
                        .filter(r -> r.employer() == 105)
                        .forEach(r -> reached.add(r.id()));
            }
            assertEquals(50, reached.size());
        }

        @Test
        @DisplayName("gives each of several accounts a turn before anyone gets a second")
        void dealtInTurn() {
            List<DigestSender.Role> three = merged(merged(account(1, 40, 1), account(2, 3, 100)), account(3, 2, 200));
            for (long reader = 1; reader <= 200; reader++) {
                List<DigestSender.Role> theirs = DigestSender.sliceFor(three, 5, reader, 4);
                Set<Long> accounts = new HashSet<>();
                theirs.subList(0, 3).forEach(r -> accounts.add(r.employer()));
                assertEquals(Set.of(1L, 2L, 3L), accounts,
                        "the first three places of reader " + reader + " were not one from each account");
                assertEquals(5, Set.copyOf(theirs).size(), "a role was sent twice to one reader");
            }
        }

        @Test
        @DisplayName("moves which account comes first between readers")
        void firstPlaceRotates() {
            Set<Long> leaders = new HashSet<>();
            for (long reader = 1; reader <= 20; reader++) {
                leaders.add(DigestSender.sliceFor(pool, 5, reader, 12).get(0).employer());
            }
            assertEquals(Set.of(105L, 137L), leaders);
        }

        @Test
        @DisplayName("leaves no place empty when only one account has roles")
        void oneAccountFillsTheBlock() {
            assertEquals(5, DigestSender.sliceFor(account(105, 50, 1), 5, 3, 3).size());
        }
    }
}
