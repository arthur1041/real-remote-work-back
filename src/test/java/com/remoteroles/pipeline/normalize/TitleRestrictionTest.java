package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Classification;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.domain.GeoScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the location field promises the world and the title names a place, the
 * title wins.
 *
 * <p>The mirror of {@link TitleIsNotEligibilityTest}, and the asymmetry between
 * them is deliberate: a title promising openness earns nothing, a title naming a
 * restriction takes the badge away. Every pair below is live board data.
 */
class TitleRestrictionTest {

    private final RuleBasedLocationClassifier classifier = new RuleBasedLocationClassifier();

    private Classification classify(String location, String title) {
        return classifier.classify(FetchedPosting.ats(
                "1", title, "https://example.com/apply", location,
                null, null, null, null, null, "{}"));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
            "'Anywhere in the World','US Remote Technical Support Advisor'",
            "'Anywhere in the World','Account Manager (US)'",
            "'Anywhere in the World','Solution Engineer, Presales - Public Sector (US Remote)'",
            "'Anywhere in the World','Director of Sales - Remote (anywhere in the US)'",
            "'Anywhere','Data Architect (100% Remote) (EMEA Only)'",
            "'Anywhere','Customer Experience Agent (Europe)'",
            "'Anywhere','Senior Backend Engineer - Client integrations (Europe)'",
            "'Anywhere in the World','Identity Strategist, EMEA'",
            "'Anywhere in the World','Legal Counsel, EMEA'",
            "'Anywhere in the World','Channel Account Executive, LATAM'",
            "'Anywhere in the World','Senior Solutions Engineer- LATAM'",
            "'Remote - Anywhere','Crypto Operations Associate - APAC'",
    })
    @DisplayName("a place in the title removes the worldwide badge")
    void titleRestrictionDemotes(String location, String title) {
        assertNotEquals(GeoScope.WORLDWIDE, classify(location, title).geoScope());
    }

    @ParameterizedTest(name = "{1} -> {2}")
    @CsvSource({
            "'Anywhere in the World','Identity Strategist, EMEA', EMEA",
            "'Anywhere in the World','Channel Account Executive, LATAM', LATAM",
            "'Remote - Anywhere','Crypto Operations Associate - APAC', APAC",
            "'Anywhere','Customer Experience Agent (Europe)', EU",
    })
    @DisplayName("the region the title names becomes the detail")
    void keepsTheNamedRegion(String location, String title, String expected) {
        Classification c = classify(location, title);
        assertEquals(GeoScope.REGION, c.geoScope());
        assertTrue(c.geoDetail().contains(expected), c.geoDetail());
    }

    @Test
    @DisplayName("a country in the title becomes a country gate")
    void keepsTheNamedCountry() {
        Classification c = classify("Anywhere in the World", "US Remote Technical Support Advisor");
        assertEquals(GeoScope.COUNTRY, c.geoScope());
        assertEquals("US", c.geoDetail());
    }

    @Test
    @DisplayName("a conflict is held at lower confidence than a plainly stated gate")
    void conflictIsLessCertain() {
        assertEquals(0.8, classify("Anywhere in the World", "Account Manager (US)").confidence(), 1e-9);
    }

    @Test
    @DisplayName("an openness claim in the title still earns nothing, in either direction")
    void opennessClaimUnchanged() {
        // Location decides: worldwide stays worldwide...
        assertEquals(GeoScope.WORLDWIDE,
                classify("Anywhere in the World", "Global Community Lead").geoScope());
        // ...and a gated location is not rescued by a "global" title.
        assertEquals(GeoScope.COUNTRY,
                classify("Washington, DC", "US External Affairs Associate, Global Affairs").geoScope());
    }

    @Test
    @DisplayName("ordinary titles on worldwide roles are untouched")
    void ordinaryTitlesSurvive() {
        for (String title : new String[]{
                "Senior Software Engineer", "Product Designer", "Customer Success Manager",
                "Japanese Localisation Specialist", "Head of Talent"}) {
            assertEquals(GeoScope.WORLDWIDE, classify("Anywhere in the World", title).geoScope(), title);
        }
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "'Anywhere (UTC-10 to UTC+14)', 'UTC-10 to UTC+14'",
            "'Anywhere (UTC-1 to UTC+3)', 'UTC-1 to UTC+3'",
            "'Anywhere (UTC-8 to UTC-2)', 'UTC-8 to UTC-2'",
            "'Remote (UTC+1)', 'UTC+1'",
    })
    @DisplayName("a timezone band is reported whole, not as its upper edge")
    void readsTheWholeBand(String location, String expected) {
        assertEquals(expected, classify(location, "Senior Engineer").timezoneRequirement());
    }

    @Test
    @DisplayName("a location with no timezone still reports none")
    void noTimezoneStaysNull() {
        assertNull(classify("Anywhere in the World", "Senior Engineer").timezoneRequirement());
    }
}
