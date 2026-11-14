package com.remoteroles.pipeline.employers;

import java.net.URI;
import java.util.Locale;

/**
 * Asks the page an employer's Apply button points at whether the role is still open.
 *
 * <p><strong>Why this exists.</strong> Every other listing on this board has a
 * liveness signal and the directly posted ones have none. An ATS role is closed
 * by {@code JobRepository.closeStale} when it disappears from a successful fetch
 * of that company's board; a feed role retires on the date the publisher stated,
 * through {@code closeExpired}. Both of those exclude {@code ats_type = 'DIRECT'}
 * -- deliberately, because a direct posting's {@code last_seen_at} is frozen at
 * submission and the first crawl after approval would close it. So a role an
 * employer typed in here stays up until that employer closes it, and measured on
 * 14 Nov 2026, <strong>380 of 384 open employer roles carried no closing date at
 * all</strong>. Four did.
 *
 * <p>That is a real problem and it is about to get bigger: one recruiter account
 * holds 327 of those, all published on 12 and 13 November, all pointing at job
 * pages owned by somebody else. In three months nobody will know which of them
 * are real.
 *
 * <p><strong>What this is not.</strong> It is not an expiry. Age decides when to
 * ask, never whether the role stays. The answer comes from the employer's own
 * source or it does not come at all, and {@link State#UNKNOWN} is a first-class
 * answer that closes nothing.
 *
 * <p><strong>Why rules and not a model.</strong> 351 of the 384 open roles point
 * at two hosts, and both publish the answer as a literal field. A rule is
 * reproducible, free, instant, and auditable -- when a machine takes a paying
 * customer's role off the board, "the field {@code closedListing} was not null"
 * is a sentence you can check against a saved page, and "the model thought it
 * looked closed" is not. The long tail of one-off hosts is where a model would
 * earn its place, and those are left {@link State#UNKNOWN} here for a person.
 *
 * <p><strong>The trap, found while measuring.</strong> micro1 answers <em>200
 * OK</em> for a role that is gone, and the live Mercor page contains the word
 * "closed" -- in {@code "closedListing":null}, a field that says the opposite.
 * A detector written against the status code sees everything as alive for ever;
 * one written against the word closes live roles. Both were one careless line
 * away, which is why the markers below are exact and why the fixtures in
 * {@code src/test/resources/applysource} are real pages rather than hand-written
 * ones.
 */
public final class ApplySource {

    /** What the source says. UNKNOWN is an answer, and it is the safe one. */
    public enum State { OPEN, CLOSED, UNKNOWN }

    /**
     * How much of a page is read before the answer is called.
     *
     * <p>The markers live in the server-rendered payload near the top; the real
     * pages measured are 41 KB (micro1) and 66 KB (Mercor). 256 KB is wide
     * enough for both to grow several times over and narrow enough that a batch
     * of eighty cannot cost more than a few megabytes on a box where the
     * pipeline is capped at 576 MB and uses 175.
     */
    public static final int MAX_BODY_BYTES = 256 * 1024;

    private ApplySource() {}

    /** Whether this host is one whose answer can be read. */
    public static boolean readable(String applyUrl) {
        String host = host(applyUrl);
        return host.equals("jobs.micro1.ai") || host.endsWith("mercor.com");
    }

    /**
     * Reads the answer out of a fetched page.
     *
     * @param applyUrl the URL that was fetched, which decides how to read it
     * @param status   the HTTP status, after redirects
     * @param body     the first {@link #MAX_BODY_BYTES} of the response, or empty
     */
    public static State read(String applyUrl, int status, String body) {
        String host = host(applyUrl);

        // A dead link is the one signal that needs no parsing, and the only one
        // that means the same thing everywhere. Mercor's short links answer 404
        // for an id it does not know; micro1 does not, which is the next case.
        if (status == 404 || status == 410) return State.CLOSED;

        // Anything else that is not a page is a question we could not ask: a
        // timeout, a bot wall, a 500 at their end, a redirect to a careers
        // index. None of that is evidence about the role.
        if (status != 200 || body == null || body.isBlank()) return State.UNKNOWN;

        if (host.equals("jobs.micro1.ai")) return micro1(body);
        if (host.endsWith("mercor.com")) return mercor(body);
        return State.UNKNOWN;
    }

    /**
     * micro1 renders the answer into the page's own data, and says it twice.
     *
     * <p>A role that is gone carries, in the Next.js payload, {@code
     * "error":"This job is currently closed and not accepting applications."},
     * and in the social metadata {@code og:description = "This job posting is no
     * longer available"}. A live one carries neither and is roughly twice the
     * size. Either marker is enough; both are required to be absent before the
     * page counts as open, and the page must also look like a job page at all --
     * without that last check an error page, a maintenance notice or a login
     * wall would all read as "open" by saying nothing.
     */
    private static State micro1(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("this job is currently closed")
                || lower.contains("this job posting is no longer available")
                || lower.contains("job not found")) {
            return State.CLOSED;
        }
        // The marker of a real posting, present on the live fixture and absent
        // from the gone one. "No closing marker" is not the same as "open".
        return lower.contains("job_qualifying_question_list") || lower.contains("apply on job")
                ? State.OPEN
                : State.UNKNOWN;
    }

    /**
     * Mercor publishes a field for exactly this and it is null while the role is open.
     *
     * <p>{@code "closedListing":null} appears on the live fixture. The closed
     * shape was not available to measure when this was written -- no role of
     * theirs on the board had ended yet -- so the rule is deliberately the
     * conservative half: a populated {@code closedListing} is closed, a null one
     * is open, and anything else, including a page whose shape has changed, is
     * UNKNOWN rather than a guess. The short link answering 404 is handled above
     * and is the case that has been seen.
     */
    private static State mercor(String body) {
        int at = body.indexOf("\"closedListing\"");
        if (at < 0) return State.UNKNOWN;

        String after = body.substring(at + "\"closedListing\"".length(),
                Math.min(body.length(), at + "\"closedListing\"".length() + 24));
        String value = after.replace(":", "").trim();
        if (value.startsWith("null")) return State.OPEN;
        if (value.startsWith("{") || value.startsWith("\"")) return State.CLOSED;
        return State.UNKNOWN;
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
