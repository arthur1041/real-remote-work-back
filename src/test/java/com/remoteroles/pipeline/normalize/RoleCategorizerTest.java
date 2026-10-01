package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.RoleCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Titles here are taken from live board output, not invented. */
class RoleCategorizerTest {

    private final RoleCategorizer categorizer = new RoleCategorizer();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'Senior Software Engineer',                ENGINEERING",
            "'Staff Infrastructure Engineer',           ENGINEERING",
            "'Physical Network & Structured Cabling Engineer', ENGINEERING",
            "'Product Manager, Payments',               PRODUCT",
            "'Senior Product Designer',                 DESIGN",
            "'Strategic Account Executive, Poland',     SALES",
            "'Partner Development Representative',      SALES",
            "'Associate Renewals Manager, India',       SALES",
            "'Senior Technical Support Engineer',       SUPPORT",
            "'Technical Sourcer, Research SWE',         PEOPLE",
            "'Director of FP&A and Analytics',          FINANCE_LEGAL",
            "'Program Manager, Government Trusted Access', OPERATIONS",
            "'Lifecycle Marketing Manager',             MARKETING",
            "'Machine Learning Engineer',               DATA_AI",
            "'Senior Data Scientist',                   DATA_AI"
    })
    void categorizesRealTitles(String title, RoleCategory expected) {
        assertEquals(expected, categorizer.categorize(title, null), title);
    }

    @Test
    @DisplayName("specific rules beat the broad engineering rule")
    void specificityBeatsBreadth() {
        // "engineer" is in 40% of titles; if it were checked first these would all
        // collapse into ENGINEERING and the category pages would be meaningless.
        assertEquals(RoleCategory.SECURITY, categorizer.categorize("Security Engineer", null));
        assertEquals(RoleCategory.DATA_AI, categorizer.categorize("Data Engineer", null));
        assertEquals(RoleCategory.SUPPORT, categorizer.categorize("Support Engineer", null));
        assertEquals(RoleCategory.SALES, categorizer.categorize("Solutions Engineer", null));
    }

    @Test
    @DisplayName("an explicit title outranks a vague department")
    void titleBeatsDepartment() {
        assertEquals(RoleCategory.ENGINEERING,
                categorizer.categorize("Senior Backend Engineer", "Go To Market"));
    }

    @Test
    @DisplayName("department rescues a title that says nothing")
    void departmentIsTheFallback() {
        assertEquals(RoleCategory.ENGINEERING,
                categorizer.categorize("Member of Technical Staff", "Engineering - Backend"));
        assertEquals(RoleCategory.OTHER,
                categorizer.categorize("Member of Technical Staff", "Scaling"));
    }

    @Test
    void handlesMissingInput() {
        assertEquals(RoleCategory.OTHER, categorizer.categorize(null, null));
        assertEquals(RoleCategory.OTHER, categorizer.categorize("  ", ""));
    }

    @Test
    @DisplayName("slugs round-trip, since they become URLs")
    void slugsRoundTrip() {
        for (RoleCategory category : RoleCategory.values()) {
            assertEquals(category, RoleCategory.fromSlug(category.slug()), category.name());
        }
        assertEquals(RoleCategory.DATA_AI, RoleCategory.fromSlug("data-ai"));
        assertEquals(null, RoleCategory.fromSlug("not-a-category"));
    }
}
