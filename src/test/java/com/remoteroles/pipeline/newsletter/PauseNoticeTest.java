package com.remoteroles.pipeline.newsletter;

import static org.assertj.core.api.Assertions.assertThat;

import com.remoteroles.pipeline.newsletter.PauseNoticeSender.Paused;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The message that makes an automatic takedown reversible.
 *
 * <p>Two things are expensive here and neither shows up in a log. Grouping that
 * collapses too much puts one employer's listings in another employer's inbox;
 * grouping that collapses too little sends one mail per role, and the account
 * this was built for holds 327 of them — a sweep finding forty closed would
 * spend more than the day's whole allowance on one address.
 *
 * <p>And the copy has a job: say what was read, say nothing was deleted, and say
 * how to put it back. A takedown notice missing the third of those is a dead
 * end.
 *
 * <p>Nothing is sent. The sender is built with no database, no mailer and no
 * budget, because the parts under test read none of them.
 */
class PauseNoticeTest {

    private final PauseNoticeSender sender =
            new PauseNoticeSender(null, null, null, "https://actuallyremote.work/", 3);

    private static Paused paused(long id, long employerId, String title) {
        return new Paused(id, employerId, "careers@northwind.example", "Dana Whitfield", title);
    }

    private static List<Paused> many(long employerId, int count) {
        List<Paused> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) rows.add(paused(i, employerId, "Role number " + i));
        return rows;
    }

    @Nested
    @DisplayName("grouping")
    class Grouping {

        @Test
        void one_entry_per_employer_however_many_roles() {
            List<Paused> rows = new ArrayList<>(many(7, 40));
            rows.addAll(many(9, 2));
            Map<Long, List<Paused>> groups = PauseNoticeSender.group(rows);

            assertThat(groups).hasSize(2);
            assertThat(groups.get(7L)).hasSize(40);
            assertThat(groups.get(9L)).hasSize(2);
        }

        @Test
        void two_employers_are_never_mixed() {
            Map<Long, List<Paused>> groups = PauseNoticeSender.group(
                    List.of(paused(1, 7, "Theirs"), paused(2, 9, "Somebody else's")));

            assertThat(groups.get(7L)).extracting(Paused::title).containsExactly("Theirs");
            assertThat(groups.get(9L)).extracting(Paused::title)
                    .containsExactly("Somebody else's");
        }

        @Test
        void an_empty_sweep_groups_into_nothing() {
            assertThat(PauseNoticeSender.group(List.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("what the message says")
    class Copy {

        @Test
        void a_single_role_is_named_in_the_subject() {
            assertThat(sender.subject(List.of(paused(1, 7, "Staff Data Engineer"))))
                    .isEqualTo("A role of yours was paused: Staff Data Engineer");
        }

        @Test
        void several_roles_are_counted_rather_than_listed_in_the_subject() {
            assertThat(sender.subject(many(7, 12))).isEqualTo("12 of your roles were paused");
        }

        @Test
        void the_body_says_what_was_read_nothing_was_deleted_and_how_to_undo() {
            String body = sender.body(List.of(paused(1, 7, "Staff Data Engineer")));

            assertThat(body).contains("no longer accepting applications");
            assertThat(body).contains("Nothing was deleted");
            assertThat(body).contains("activate it");
            assertThat(body).contains("https://actuallyremote.work/employers");
            assertThat(body).contains("Staff Data Engineer");
        }

        @Test
        void a_long_list_is_cut_and_says_how_many_are_left() {
            // maxListed is 3 for this sender.
            String body = sender.body(many(7, 10));

            assertThat(body).contains("Role number 3");
            assertThat(body).doesNotContain("Role number 4");
            assertThat(body).contains("7 more");
        }

        @Test
        void the_greeting_is_dropped_rather_than_left_with_a_hole() {
            Paused anonymous = new Paused(1, 7, "careers@northwind.example", null, "A role");
            assertThat(sender.body(List.of(anonymous))).doesNotContain("Hi ");
            assertThat(sender.body(List.of(paused(1, 7, "A role")))).startsWith("Hi Dana,");
        }

        @Test
        void the_html_half_carries_the_same_three_claims_and_a_way_back() {
            String html = sender.html(many(7, 2));

            assertThat(html).contains("no longer accepting applications");
            assertThat(html).contains("Nothing was deleted");
            assertThat(html).contains("https://actuallyremote.work/employers");
            assertThat(html).contains("Role number 1").contains("Role number 2");
        }

        @Test
        void the_product_never_speaks_in_the_first_person() {
            // The voice rule that applies to every surface with the product's
            // name on it, checked here because an apology is the easiest place
            // to slip into "we".
            String body = sender.body(many(7, 4));
            assertThat(body.toLowerCase()).doesNotContain(" we ").doesNotContain(" our ");
            assertThat(sender.html(many(7, 4)).toLowerCase())
                    .doesNotContain(" we ").doesNotContain(" our ");
        }
    }
}
