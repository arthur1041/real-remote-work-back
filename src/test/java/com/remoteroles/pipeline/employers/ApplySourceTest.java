package com.remoteroles.pipeline.employers;

import static org.assertj.core.api.Assertions.assertThat;

import com.remoteroles.pipeline.employers.ApplySource.State;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The pages are real, fetched on 14 Nov 2026 from the two hosts that 351 of the
 * board's 384 open employer roles point at. Being wrong here takes a paying
 * customer's live role off the board, so the cases that matter are the two that
 * were one careless line away: micro1 answering 200 for a role that is gone, and
 * the word "closed" appearing on a Mercor page that is open.
 */
class ApplySourceTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = ApplySourceTest.class.getResourceAsStream("/applysource/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final String MICRO1 = "https://jobs.micro1.ai/post/5a618f4f-19bf-497f-83ba-c31c229283b8";
    private static final String MERCOR = "https://t.mercor.com/fgIIU";

    @Nested
    @DisplayName("micro1, which answers 200 either way")
    class Micro1 {

        @Test
        void a_live_role_reads_open() throws IOException {
            assertThat(ApplySource.read(MICRO1, 200, fixture("micro1-open.html")))
                    .isEqualTo(State.OPEN);
        }

        @Test
        void a_role_that_is_gone_reads_closed_despite_the_200() throws IOException {
            assertThat(ApplySource.read(MICRO1, 200, fixture("micro1-gone.html")))
                    .isEqualTo(State.CLOSED);
        }

        @Test
        void a_page_with_neither_marker_is_unknown_rather_than_open() {
            // A maintenance notice, a login wall, a redesign. Saying nothing is
            // not the same as saying the role is open.
            assertThat(ApplySource.read(MICRO1, 200, "<html><body>Service unavailable</body></html>"))
                    .isEqualTo(State.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("Mercor, where the word closed appears on a live page")
    class Mercor {

        @Test
        void a_live_role_reads_open_even_though_the_body_contains_closed() throws IOException {
            String body = fixture("mercor-open.html");
            assertThat(body).contains("closedListing");
            assertThat(body.toLowerCase()).contains("closed");
            assertThat(ApplySource.read(MERCOR, 200, body)).isEqualTo(State.OPEN);
        }

        @Test
        void a_populated_closed_listing_reads_closed() {
            assertThat(ApplySource.read(MERCOR, 200,
                    "{\"candidateStatus\":null,\"closedListing\":{\"at\":\"2026-11-01\"},\"x\":1}"))
                    .isEqualTo(State.CLOSED);
        }

        @Test
        void a_page_without_the_field_is_unknown() {
            assertThat(ApplySource.read(MERCOR, 200, "<html><body>hello</body></html>"))
                    .isEqualTo(State.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("what every host shares")
    class Everywhere {

        @Test
        void a_dead_link_is_closed() {
            assertThat(ApplySource.read(MERCOR, 404, "not found")).isEqualTo(State.CLOSED);
            assertThat(ApplySource.read(MICRO1, 410, "")).isEqualTo(State.CLOSED);
        }

        @Test
        void a_bot_wall_a_timeout_and_a_server_error_decide_nothing() {
            assertThat(ApplySource.read(MICRO1, 403, "<html>Attention Required</html>"))
                    .isEqualTo(State.UNKNOWN);
            assertThat(ApplySource.read(MICRO1, 0, "")).isEqualTo(State.UNKNOWN);
            assertThat(ApplySource.read(MICRO1, 500, "oops")).isEqualTo(State.UNKNOWN);
            assertThat(ApplySource.read(MICRO1, 200, "")).isEqualTo(State.UNKNOWN);
        }

        @Test
        void a_host_nobody_wrote_a_rule_for_is_never_guessed_at() {
            assertThat(ApplySource.read("https://careers.offerznow.com/x", 200, "Apply now"))
                    .isEqualTo(State.UNKNOWN);
            assertThat(ApplySource.readable("https://careers.offerznow.com/x")).isFalse();
            assertThat(ApplySource.readable("https://docs.google.com/forms/x")).isFalse();
        }

        @Test
        void the_two_hosts_that_carry_the_board_are_readable() {
            assertThat(ApplySource.readable(MICRO1)).isTrue();
            assertThat(ApplySource.readable(MERCOR)).isTrue();
            assertThat(ApplySource.readable("https://work.mercor.com/jobs/list_AAAB/x")).isTrue();
        }

        @Test
        void a_url_that_is_not_a_url_does_not_throw() {
            assertThat(ApplySource.readable("mailto:jobs@example.com")).isFalse();
            assertThat(ApplySource.read("not a url", 200, "x")).isEqualTo(State.UNKNOWN);
        }
    }
}
