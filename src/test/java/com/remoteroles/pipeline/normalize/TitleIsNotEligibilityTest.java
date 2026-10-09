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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job title describes the work, not who may be hired.
 *
 * <p>Every case below is a real pairing taken from the live board, where reading
 * the title as an eligibility statement produced a WORLDWIDE badge on a role that
 * names its country in the location field. Fourteen postings carried one at once,
 * and the badge is the entire product -- a reader who finds one wrong has no
 * reason to trust any of the others.
 */
class TitleIsNotEligibilityTest {

    private final RuleBasedLocationClassifier classifier = new RuleBasedLocationClassifier();

    private Classification classify(String location, String title) {
        return classifier.classify(FetchedPosting.ats(
                "1", title, "https://example.com/apply", location,
                null, null, null, null, null, "{}"));
    }

    @ParameterizedTest(name = "\"{1}\" in \"{0}\" is not worldwide")
    @CsvSource({
            // A country or city in the location field, "Global" in the title.
            "'Washington, DC','US External Affairs Associate, Global Affairs'",
            "'Mohali, IND','Financial Representative, Global Accounts Payable'",
            "'Doha, Qatar','Product Marketing Manager, Global Public Sector'",
            "'Remote - Georgia','Manager, Field Engineering - Global Telecommunications'",
            // A region in the location field.
            "'APAC','Growth Director (Global P2P)'",
            "'EMEA','Senior Compliance Manager - Global FCC Investigations'",
            "'Americas Remote','Global Controller'",
            "'Home Based - Americas','Global Head of Cloud Alliances'",
            "'Home Based - Americas; Home based - EMEA','Global Treasury Analyst'",
            "'Europe (+/- 3 hours)','Global Field Marketing Manager'",
            // The location field says "Only" in so many words.
            "'North America Only','Salesforce Platform Lead for Global Industrial Company'",
            // No geography at all is UNKNOWN, which the title cannot promote.
            "'Remote','Global Developer Engagement Representative, Part-Time'",
    })
    @DisplayName("\"Global\" in a title never earns WORLDWIDE")
    void titleDoesNotEarnWorldwide(String location, String title) {
        assertNotEquals(GeoScope.WORLDWIDE, classify(location, title).geoScope());
    }

    @ParameterizedTest(name = "{0} resolves to {1}")
    @CsvSource({
            "'Washington, DC', US",
            "'Mohali, IND', IN",
            "'Doha, Qatar', QA",
            "'Bangalore, IND', IN",
            "'IND-Remote', IN",
    })
    @DisplayName("the gazetteer gaps these roles fell through are closed")
    void resolvesToItsCountry(String location, String expected) {
        Classification c = classify(location, "Global Head of Engineering");
        assertEquals(GeoScope.COUNTRY, c.geoScope());
        assertEquals(expected, c.geoDetail());
    }

    @ParameterizedTest(name = "{0} is a region")
    @CsvSource({
            "'APAC', APAC",
            "'EMEA', EMEA",
            "'North America Only', NORTH_AMERICA",
    })
    @DisplayName("region-only locations classify as regions, not as the world")
    void resolvesToItsRegion(String location, String expected) {
        Classification c = classify(location, "Global Controller");
        assertEquals(GeoScope.REGION, c.geoScope());
        assertTrue(c.geoDetail().contains(expected), c.geoDetail());
    }

    @Test
    @DisplayName("a bare \"Remote\" with a Global title stays honestly unsure")
    void bareRemoteStaysUnknown() {
        assertEquals(GeoScope.UNKNOWN,
                classify("Remote", "Global Developer Engagement Representative").geoScope());
    }

    @Test
    @DisplayName("the title still counts as evidence the role is remote at all")
    void titleStillProvesRemoteness() {
        // No "remote" anywhere in the location, and no ATS boolean: without the
        // title this posting would be discarded as an onsite job.
        Classification c = classify("", "Staff Engineer - Work From Anywhere");
        assertTrue(c.isRemote());
    }

    @Test
    @DisplayName("a location that does say it is still worldwide")
    void locationStillEarnsWorldwide() {
        assertEquals(GeoScope.WORLDWIDE,
                classify("Anywhere in the World", "Senior Data Engineer").geoScope());
        assertEquals(GeoScope.WORLDWIDE,
                classify("Any Location", "DevOps Engineer").geoScope());
        // The shape that motivated keeping the title as a remoteness signal.
        assertEquals(GeoScope.WORLDWIDE,
                classify("Anywhere", "AI Inference Engineer (100% remote Worldwide)").geoScope());
    }
}
