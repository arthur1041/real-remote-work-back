package com.remoteroles.pipeline.newsletter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who the weekly digest may reach, pinned.
 *
 * <p>The audience is the one setting in this system that can do damage by being
 * widened. Four of the six subscriber statuses must never receive mail, and
 * today nothing enforces that except an allowlist a later edit could extend
 * without anybody noticing -- the edit would look correct, the tests would pass,
 * and the first evidence would be a complaint from somebody who opted out
 * months earlier.
 *
 * <p>So the rule is asserted rather than commented. The interesting test is
 * {@link #everyStatusIsClassified()}: it fails when a status exists that nobody
 * has said yes or no about, which turns adding one into a decision instead of
 * an omission.
 */
class DigestAudienceTest {

    @ParameterizedTest(name = "audience \"{0}\" reaches nobody who opted out")
    @NullSource
    @ValueSource(strings = {"ALL", "CONFIRMED", "", "all", "EVERYONE", "PENDING", "INVITED"})
    @DisplayName("No audience setting, valid or not, selects a status that may never be mailed")
    void neverMailedIsUnreachable(String audience) {
        for (String status : SubscriberRepository.audienceStatuses(audience)) {
            assertFalse(
                    SubscriberRepository.NEVER_MAILED.contains(status),
                    "audience \"%s\" selected %s, which may never be mailed".formatted(audience, status));
        }
    }

    @Test
    @DisplayName("An unrecognised audience value sends to fewer people, not more")
    void unknownAudienceFailsClosed() {
        // "ALL" is the only widening value, and it is matched exactly. A typo in
        // the settings row, or a value some future migration writes, has to land
        // on the narrow set: the failure that costs a send is recoverable, the
        // one that mails everybody is not.
        assertEquals(List.of("CONFIRMED"), SubscriberRepository.audienceStatuses("all"));
        assertEquals(List.of("CONFIRMED"), SubscriberRepository.audienceStatuses("EVERYONE"));
        assertEquals(List.of("CONFIRMED"), SubscriberRepository.audienceStatuses(null));
        assertEquals(List.of("PENDING", "CONFIRMED"), SubscriberRepository.audienceStatuses("ALL"));
    }

    @Test
    @DisplayName("Invited leads are not part of the newsletter under any setting")
    void invitedIsNotAnAudience() {
        // Stated on its own because it is the one that reads as an oversight.
        // An invited address belongs to a separate list that happens to share a
        // table: it was written to once without asking, and it joins the
        // newsletter by accepting. Mailing it a digest would answer, on the
        // reader's behalf, the exact question the invitation asked.
        assertTrue(SubscriberRepository.NEVER_MAILED.contains("INVITED"));
        for (String audience : List.of("ALL", "CONFIRMED")) {
            assertFalse(SubscriberRepository.audienceStatuses(audience).contains("INVITED"));
        }
    }

    @Test
    @DisplayName("Every status is either mailable or explicitly never mailed")
    void everyStatusIsClassified() {
        Set<String> classified = new HashSet<>(SubscriberRepository.NEVER_MAILED);
        classified.addAll(SubscriberRepository.audienceStatuses("ALL"));

        assertEquals(
                new HashSet<>(SubscriberRepository.ALL_STATUSES),
                classified,
                "a subscriber status is neither in an audience nor in NEVER_MAILED: "
                        + "decide which, rather than leaving it to whichever list is edited next");
    }

    @Test
    @DisplayName("The statuses in code are the statuses the database allows")
    void codeMatchesTheCheckConstraint() throws IOException {
        Set<String> inDatabase = statusesFromLatestMigration();
        assertEquals(
                new LinkedHashSet<>(SubscriberRepository.ALL_STATUSES),
                inDatabase,
                "subscribers_status_check and SubscriberRepository.ALL_STATUSES disagree");
    }

    /**
     * Reads the status CHECK constraint back out of the migrations.
     *
     * <p>Parsing SQL with a regex is usually a mistake; here it is the point.
     * The two lists are duplicated across a language boundary, and a test that
     * only compared the code to itself would pass on the day somebody adds a
     * status in a migration and nowhere else -- which is precisely the day the
     * audience quietly stops being exhaustive.
     */
    private static Set<String> statusesFromLatestMigration() throws IOException {
        Path dir = Path.of("src/main/resources/db/migration");
        Pattern constraint = Pattern.compile(
                "constraint\\s+subscribers_status_check\\s*check\\s*\\(\\s*status\\s+in\\s*\\(([^)]*)\\)",
                Pattern.CASE_INSENSITIVE);
        Pattern version = Pattern.compile("^V(\\d+)__");

        List<Path> migrations;
        try (Stream<Path> files = Files.list(dir)) {
            migrations = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(DigestAudienceTest::versionOf))
                    .toList();
        }
        assertFalse(migrations.isEmpty(), "no migrations found at " + dir.toAbsolutePath());
        assertTrue(version.matcher(migrations.getLast().getFileName().toString()).find());

        Set<String> latest = null;
        for (Path migration : migrations) {
            // Whitespace is collapsed so that a constraint split over several
            // lines -- which is how they are written here -- still matches.
            String sql = Files.readString(migration).replaceAll("\\s+", " ");
            Matcher matcher = constraint.matcher(sql);
            while (matcher.find()) {
                List<String> found = new ArrayList<>();
                Matcher quoted = Pattern.compile("'([^']+)'").matcher(matcher.group(1));
                while (quoted.find()) {
                    found.add(quoted.group(1));
                }
                latest = new LinkedHashSet<>(found);
            }
        }

        assertTrue(latest != null && !latest.isEmpty(),
                "no subscribers_status_check constraint found in any migration");
        return latest;
    }

    private static int versionOf(Path path) {
        Matcher matcher = Pattern.compile("^V(\\d+)__").matcher(path.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
