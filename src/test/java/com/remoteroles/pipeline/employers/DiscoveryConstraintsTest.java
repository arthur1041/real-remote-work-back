package com.remoteroles.pipeline.employers;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.remoteroles.pipeline.employers.DiscoveryConstraints.Check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The basic criteria of a role against a profile, as numbers.
 *
 * <p>The cases are the owner's own: "LATAM with 15+ years" must not come back
 * as the Middle East with five, and a little below what was asked must rank
 * lower rather than disappear.
 */
class DiscoveryConstraintsTest {

    private static Check constraint(String verdict, boolean essential) {
        return new Check(1, "r", essential, true, verdict, "MODEL");
    }

    @Test
    @DisplayName("outside the role's location is zero whatever the model thought")
    void outsideLocation() {
        assertEquals(0.0, DiscoveryConstraints.adjust(9.5, false, List.of()).score());
    }

    @Test
    @DisplayName("an essential constraint unmet puts a strong profile below every one that meets it")
    void essentialUnmet() {
        double strongButShort = DiscoveryConstraints.adjust(9.0, true,
                List.of(constraint("UNMET", true))).score();
        double modestButMeets = DiscoveryConstraints.adjust(4.0, true,
                List.of(constraint("MET", true))).score();
        assertEquals(2.3, strongButShort);
        assertTrue(strongButShort < DiscoveryConstraints.ESSENTIAL_UNMET_CAP);
        assertTrue(modestButMeets > strongButShort);
    }

    @Test
    @DisplayName("two people who miss the same constraint keep the order the model gave them")
    void unmetKeepsTheOrder() {
        // Until 20 Nov 2026 this was min(score, 2.5): a 9.0 and a 3.0 both
        // read 2.5, and which came first was the order of the vectors.
        double senior = DiscoveryConstraints.adjust(9.0, true, List.of(constraint("UNMET", true))).score();
        double junior = DiscoveryConstraints.adjust(3.0, true, List.of(constraint("UNMET", true))).score();
        assertTrue(senior > junior);
        assertEquals(1.3, junior);
        // And nobody the model read at 1 or above falls off the list for it:
        // the web shows from SHOWN_FLOOR up.
        assertTrue(DiscoveryConstraints.adjust(1.2, true, List.of(constraint("UNMET", true))).score()
                >= DiscoveryRanker.SHOWN_FLOOR);
    }

    @Test
    @DisplayName("close but short costs places, it does not remove the person")
    void partialCosts() {
        double partial = DiscoveryConstraints.adjust(8.0, true, List.of(constraint("PARTIAL", true))).score();
        assertEquals(6.5, partial);
        assertTrue(partial > DiscoveryConstraints.ESSENTIAL_UNMET_CAP);
    }

    @Test
    @DisplayName("a skill unmet is the model's call, and costs nothing here")
    void skillsAreJudgement() {
        // The 17 Nov 2026 rule: a senior in Go is not pinned under a junior
        // who wrote "Python". Only constraints are arithmetic.
        Check skill = new Check(1, "Python", true, false, "UNMET", "MODEL");
        assertEquals(8.0, DiscoveryConstraints.adjust(8.0, true, List.of(skill)).score());
    }

    @Test
    @DisplayName("silence on an essential constraint is a small tiebreak, not a penalty")
    void unknownIsSmall() {
        assertEquals(7.7, DiscoveryConstraints.adjust(8.0, true, List.of(constraint("UNKNOWN", true))).score());
        assertEquals(8.0, DiscoveryConstraints.adjust(8.0, true, List.of(constraint("UNKNOWN", false))).score());
    }

    @Test
    @DisplayName("the years a requirement asks for, in the ways postings say it")
    void requiredYears() {
        assertEquals(15, DiscoveryConstraints.requiredYears("15+ years of experience in sales"));
        assertEquals(3, DiscoveryConstraints.requiredYears("3+ years with Go in production"));
        assertEquals(5, DiscoveryConstraints.requiredYears("At least 5 years building APIs"));
        assertEquals(3, DiscoveryConstraints.requiredYears("3-5 years of experience"));
        assertEquals(8, DiscoveryConstraints.requiredYears("8 or more years"));
        assertEquals(10, DiscoveryConstraints.requiredYears("minimum of 10 yrs"));
        assertNull(DiscoveryConstraints.requiredYears("experience with Kubernetes"));
        assertNull(DiscoveryConstraints.requiredYears("a 2026 graduate"));
    }

    @Test
    @DisplayName("12 of 15 years is a little below; 10 of 15 is not")
    void yearsVerdict() {
        assertEquals("MET", DiscoveryConstraints.yearsVerdict(15, 16));
        assertEquals("PARTIAL", DiscoveryConstraints.yearsVerdict(15, 12));
        assertEquals("UNMET", DiscoveryConstraints.yearsVerdict(15, 10));
        assertNull(DiscoveryConstraints.yearsVerdict(15, null));
    }

    @Test
    @DisplayName("the code's years can lower the model's verdict, never raise it")
    void combineOnlyLowers() {
        // Twelve years of career is not twelve years of Go: MET from the code
        // leaves the model's UNMET standing.
        assertEquals("UNMET", DiscoveryConstraints.combine("MET", "UNMET"));
        assertEquals("UNKNOWN", DiscoveryConstraints.combine("MET", "UNKNOWN"));
        // Six years of career cannot be ten of anything, whatever the model read.
        assertEquals("UNMET", DiscoveryConstraints.combine("UNMET", "MET"));
        assertEquals("PARTIAL", DiscoveryConstraints.combine("PARTIAL", "MET"));
        assertEquals("UNMET", DiscoveryConstraints.combine("PARTIAL", "UNMET"));
        assertEquals("MET", DiscoveryConstraints.combine(null, "MET"));
    }

    @Test
    @DisplayName("the level a title asks for becomes a requirement line")
    void seniorityFromTitle() {
        assertEquals("Seniority: senior level (from the title)",
                DiscoveryConstraints.seniorityRequirement("Senior Backend Engineer (Go, Kubernetes)"));
        assertNull(DiscoveryConstraints.seniorityRequirement("Backend Engineer"));
        assertNull(DiscoveryConstraints.seniorityRequirement("Lead Generation Specialist"));
    }

    @Test
    @DisplayName("the owner's case: LATAM, 15+ years, and a Middle East profile with five")
    void ownersCase() {
        // Outside the location: zero, before anything else is weighed.
        assertEquals(0.0, DiscoveryConstraints.adjust(8.5, false,
                List.of(new Check(1, "15+ years", true, true, "UNMET", "YEARS"))).score());
        // Inside it, with twelve of fifteen years: down, but above anyone who misses.
        double twelve = DiscoveryConstraints.adjust(8.5, true,
                List.of(new Check(1, "15+ years", true, true, "PARTIAL", "YEARS"))).score();
        double five = DiscoveryConstraints.adjust(9.5, true,
                List.of(new Check(1, "15+ years", true, true, "UNMET", "YEARS"))).score();
        assertEquals(7.0, twelve);
        assertTrue(twelve > five);
    }

    @Test
    @DisplayName("deductions stop at 2.0 and at half the model's score, so a partial fit never reaches zero")
    void deductionsAreLimited() {
        // 18 Nov 2026, the EU test role: a senior with 9 years the model read
        // at 4.0 went to 0.7 under two timezone PARTIALs and an UNKNOWN.
        List<Check> stacked = List.of(constraint("PARTIAL", true), constraint("PARTIAL", true),
                constraint("UNKNOWN", true));
        assertEquals(2.0, DiscoveryConstraints.adjust(4.0, true, stacked).score());
        assertEquals(6.0, DiscoveryConstraints.adjust(8.0, true, stacked).score());
        // A weak read is halved at most, never zeroed by the arithmetic.
        assertEquals(1.0, DiscoveryConstraints.adjust(2.0, true, stacked).score());
        // Only the model's own zero, or the location, makes a zero.
        assertEquals(0.0, DiscoveryConstraints.adjust(0.0, true, stacked).score());
    }

    @Test
    @DisplayName("a timezone the description already asks for is not added a second time")
    void timezoneCovered() {
        assertTrue(DiscoveryConstraints.coversTimezone(List.of("Go in production",
                "Able to work at least 4 hours overlapping with CET")));
        assertTrue(DiscoveryConstraints.coversTimezone(List.of("Works in a Latin American time zone")));
        // The two the test roles of 18 Nov 2026 actually extracted.
        assertTrue(DiscoveryConstraints.coversTimezone(List.of("Working Central European hours")));
        assertTrue(DiscoveryConstraints.coversTimezone(List.of(
                "Working at least 4 hours overlapping with US Eastern time")));
        assertEquals(false, DiscoveryConstraints.coversTimezone(List.of("5+ years of support leadership",
                "Fluent English")));
    }

    @Test
    @DisplayName("a level the description already asks for is not added from the title")
    void seniorityCovered() {
        assertTrue(DiscoveryConstraints.coversSeniority(List.of("Senior-level experience with React")));
        assertTrue(DiscoveryConstraints.coversSeniority(List.of("Previous experience as a team lead")));
        // "lead" as a verb is not a level.
        assertEquals(false, DiscoveryConstraints.coversSeniority(List.of("Lead customer escalations",
                "8+ years of full-stack work")));
    }
}
