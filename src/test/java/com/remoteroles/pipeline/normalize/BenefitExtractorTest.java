package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Benefit;
import com.remoteroles.pipeline.domain.EmploymentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenefitExtractorTest {

    private final BenefitExtractor extractor = new BenefitExtractor();

    @Test
    @DisplayName("finds benefits across HTML markup")
    void findsAcrossMarkup() {
        Set<Benefit> found = extractor.extract(
                "<ul><li>Unlimited PTO</li><li>Health insurance &amp; dental</li>"
                        + "<li>$2,000 equipment budget</li><li>Stock options</li></ul>");
        assertTrue(found.contains(Benefit.UNLIMITED_PTO));
        assertTrue(found.contains(Benefit.HEALTH_INSURANCE));
        assertTrue(found.contains(Benefit.DENTAL_VISION));
        assertTrue(found.contains(Benefit.EQUIPMENT_BUDGET));
        assertTrue(found.contains(Benefit.STOCK_OPTIONS));
    }

    @Test
    @DisplayName("a phrase split by a tag is still found")
    void phrasesSurviveTags() {
        // Markup lands mid-phrase constantly in real postings.
        assertTrue(extractor.extract("<p>Flexible <strong>hours</strong></p>")
                .contains(Benefit.FLEXIBLE_HOURS));
    }

    @Test
    @DisplayName("unlimited PTO supersedes plain paid time off")
    void strongerBenefitWins() {
        Set<Benefit> found = extractor.extract("Unlimited PTO and paid time off");
        assertTrue(found.contains(Benefit.UNLIMITED_PTO));
        assertFalse(found.contains(Benefit.PAID_TIME_OFF),
                "showing both is noise, not two benefits");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "We are hiring a backend engineer to work on our platform.",
            "<p>Responsibilities: ship code, review PRs.</p>",
    })
    @DisplayName("says nothing when the posting says nothing")
    void noFalsePositives(String description) {
        assertTrue(extractor.extract(description).isEmpty(),
                "a benefit the posting never offered is worse than no chip at all");
    }

    @Test
    void handlesMissingDescription() {
        assertTrue(extractor.extract(null).isEmpty());
        assertTrue(extractor.extract("   ").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "FullTime, FULL_TIME", "'Full Time', FULL_TIME", "Full-Time, FULL_TIME",
            "CLT, FULL_TIME", "Contractor, CONTRACT", "Contract, CONTRACT",
            "'Part Time', PART_TIME", "Temporary, TEMPORARY", "Intern, INTERNSHIP",
            "Volunteer, VOLUNTEER", "Other, OTHER"
    })
    @DisplayName("the many spellings of one employment type collapse")
    void employmentKindNormalises(String raw, EmploymentKind expected) {
        assertEquals(expected, EmploymentKind.from(raw), raw);
    }

    @Test
    @DisplayName("an unstated type stays null rather than becoming Other")
    void unstatedTypeIsNull() {
        // Otherwise the "Other" filter fills with jobs nobody ever classified.
        assertEquals(null, EmploymentKind.from(null));
        assertEquals(null, EmploymentKind.from(""));
        assertEquals(null, EmploymentKind.from("Weekend warrior"));
    }

    @Test
    @DisplayName("slugs round-trip, since they become filter URLs")
    void slugsAreUrlSafe() {
        for (Benefit b : Benefit.values()) {
            assertTrue(b.slug().matches("[a-z0-9-]+"), b.name() + " -> " + b.slug());
        }
        for (EmploymentKind k : EmploymentKind.values()) {
            assertTrue(k.slug().matches("[a-z0-9-]+"), k.name());
        }
    }
}
