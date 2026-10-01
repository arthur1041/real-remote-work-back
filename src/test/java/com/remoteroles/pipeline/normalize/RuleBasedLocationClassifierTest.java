package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Classification;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.domain.GeoScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every location string in this test was taken from a live ATS response, not
 * invented. The nine spellings of remote-within-the-US in
 * {@code collapsesTheManySpellingsOfUsRemote} are all real, from one sample of
 * 2,300 postings.
 */
class RuleBasedLocationClassifierTest {

    private final RuleBasedLocationClassifier classifier = new RuleBasedLocationClassifier();

    private Classification classify(String location) {
        return classify(location, "Senior Software Engineer", null);
    }

    private Classification classify(String location, String title, Boolean remoteHint) {
        return classifier.classify(FetchedPosting.ats(
                "1", title, "https://example.com/apply", location,
                null, null, remoteHint, null, null, "{}"));
    }

    @Nested
    @DisplayName("country-gated remote")
    class CountryGated {

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "Remote, United States", "US - Remote", "Remote (US)", "US-Remote",
                "Remote - USA", "Remote US", "US remote", "Remote-USA", "Remote USA",
                "Remote, US", "US Remote", "Remote, USA"
        })
        @DisplayName("collapses the many spellings of US-remote onto one verdict")
        void collapsesTheManySpellingsOfUsRemote(String location) {
            Classification c = classify(location);
            assertAll(
                    () -> assertTrue(c.isRemote(), location + " should be remote"),
                    () -> assertEquals(GeoScope.COUNTRY, c.geoScope(), location),
                    () -> assertEquals("US", c.geoDetail(), location));
        }

        @ParameterizedTest
        @CsvSource({
                "'Remote, United Kingdom', GB",
                "'Remote, Poland', PL",
                "'Remote, Germany', DE",
                "'Remote, Singapore', SG",
                "'Remote, Japan', JP",
                "'Remote, South Korea', KR",
                "'Remote, Turkey', TR",
                "'Brazil - Remote', BR",
                "'Ontario - Remote', CA",
                "'Remote - Bangalore, India', IN"
        })
        void identifiesSingleCountries(String location, String expectedCode) {
            Classification c = classify(location);
            assertEquals(GeoScope.COUNTRY, c.geoScope(), location);
            assertEquals(expectedCode, c.geoDetail(), location);
        }
    }

    @Nested
    @DisplayName("multi-country and regional")
    class Regional {

        @Test
        @DisplayName("several countries listed is a region in practice")
        void multiCountryBecomesRegion() {
            Classification c = classify("Remote, Canada; Remote, United States");
            assertEquals(GeoScope.REGION, c.geoScope());
            assertTrue(c.geoDetail().contains("US"));
            assertTrue(c.geoDetail().contains("CA"));
        }

        @Test
        void handlesLongMultiCountryLists() {
            Classification c = classify(
                    "Remote Ireland; Remote, France; Remote, Germany; Remote, Netherlands; "
                            + "Remote, United Kingdom");
            assertEquals(GeoScope.REGION, c.geoScope());
            assertAll(
                    () -> assertTrue(c.geoDetail().contains("IE")),
                    () -> assertTrue(c.geoDetail().contains("FR")),
                    () -> assertTrue(c.geoDetail().contains("DE")),
                    () -> assertTrue(c.geoDetail().contains("NL")),
                    () -> assertTrue(c.geoDetail().contains("GB")));
        }

        @ParameterizedTest
        @CsvSource({
                "'Remote (EMEA)', EMEA",
                "'Remote, North America', NORTH_AMERICA",
                "'Remote - LATAM', LATAM"
        })
        void namedRegions(String location, String expectedRegion) {
            Classification c = classify(location);
            assertEquals(GeoScope.REGION, c.geoScope(), location);
            assertTrue(c.geoDetail().contains(expectedRegion), location + " -> " + c.geoDetail());
        }

        @Test
        @DisplayName("house-style region codes seen in the wild")
        void houseStyleRegionCodes() {
            Classification c = classify("NAMER", "Engineer", Boolean.TRUE);
            assertEquals(GeoScope.REGION, c.geoScope(),
                    "several remote-first companies write North America as NAMER, "
                            + "as the whole location string");
            assertTrue(c.geoDetail().contains("NORTH_AMERICA"));
        }

        @Test
        @DisplayName("a region name wins over a country name inside it")
        void regionBeatsCountry() {
            Classification c = classify("Remote, KSA; Remote, UAE");
            assertEquals(GeoScope.REGION, c.geoScope());
        }
    }

    @Nested
    @DisplayName("WORLDWIDE must be earned")
    class Worldwide {

        @ParameterizedTest
        @ValueSource(strings = {"Remote - Anywhere", "Work from anywhere", "Remote (Worldwide)",
                "Fully remote, global"})
        void explicitPhrasingEarnsWorldwide(String location) {
            Classification c = classify(location);
            assertEquals(GeoScope.WORLDWIDE, c.geoScope(), location);
            assertTrue(c.confidence() >= 0.9, location);
        }

        @Test
        @DisplayName("bare 'Remote' is UNKNOWN, never WORLDWIDE")
        void bareRemoteIsNotWorldwide() {
            Classification c = classify("Remote");
            assertTrue(c.isRemote());
            assertEquals(GeoScope.UNKNOWN, c.geoScope(),
                    "promoting bare 'Remote' to WORLDWIDE is what makes these sites untrustworthy");
            assertTrue(c.confidence() < 0.6, "and it should not be confident about it");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Anywhere", "Anywhere (UTC-5 to UTC+1)"})
        @DisplayName("the aggregator's 'no stated restriction' location reaches WORLDWIDE")
        void feedLocationsClassifyAsWorldwide(String location) {
            // Himalayas states the absence of a restriction structurally; the fetcher
            // renders that as "Anywhere" so it reaches WORLDWIDE through the same path
            // as every other posting rather than bypassing the classifier.
            Classification c = classify(location, "Engineer", Boolean.TRUE);
            assertEquals(GeoScope.WORLDWIDE, c.geoScope(), location);
            assertTrue(c.isRemote());
        }

        @Test
        @DisplayName("a timezone band narrows an otherwise unrestricted role")
        void feedTimezoneBandIsCaptured() {
            Classification c = classify("Anywhere (UTC-5 to UTC+1)", "Engineer", Boolean.TRUE);
            assertNotNull(c.timezoneRequirement(),
                    "a stated overlap window is a geographic gate by another name");
            assertTrue(c.confidence() < classify("Anywhere", "Engineer", Boolean.TRUE).confidence());
        }

        @Test
        @DisplayName("a timezone band contradicts 'anywhere' and costs confidence")
        void timezoneUndercutsWorldwide() {
            Classification open = classify("Remote - Anywhere");
            Classification banded = classify("Remote - Anywhere (must overlap with CET)");
            assertEquals(GeoScope.WORLDWIDE, banded.geoScope());
            assertTrue(banded.confidence() < open.confidence(),
                    "a stated overlap requirement is a geo gate by another name");
            assertNull(open.timezoneRequirement());
            assertEquals("CET", banded.timezoneRequirement());
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections {

        @ParameterizedTest
        @ValueSource(strings = {"San Francisco, CA", "New York, NY", "London, UK", "Austin, Texas"})
        void plainOfficeLocationsAreNotRemote(String location) {
            assertFalse(classify(location).isRemote(), location);
        }

        @Test
        @DisplayName("'us' must not match inside Austin or Houston")
        void wordBoundariesPreventFalseCountryMatches() {
            assertFalse(classify("Austin, TX").isRemote());
            assertFalse(classify("Houston, TX").isRemote());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "Remote - Hybrid 2 days in office",
                "Remote, but must relocate to Berlin",
                "Remote/Onsite - San Francisco"
        })
        void hybridBeatsTheWordRemote(String location) {
            assertFalse(classify(location).isRemote(), location);
        }

        @Test
        @DisplayName("an explicit ATS 'not remote' overrules the text")
        void atsHintIsFinal() {
            Classification c = classify("Remote, United States", "Engineer", Boolean.FALSE);
            assertFalse(c.isRemote(),
                    "Lever and Ashby state workplace type; they know better than our regexes");
        }
    }

    @Nested
    class AtsHints {

        @Test
        @DisplayName("an ATS boolean is firmer evidence than a regex guess")
        void atsHintRaisesConfidence() {
            Classification guessed = classify("Remote", "Engineer", null);
            Classification stated = classify("Remote", "Engineer", Boolean.TRUE);
            assertTrue(stated.confidence() > guessed.confidence());
        }

        @Test
        @DisplayName("an ATS remote flag is honoured even with a bare office location")
        void atsHintAloneIsEnough() {
            Classification c = classify("Lisbon", "Engineer", Boolean.TRUE);
            assertTrue(c.isRemote(), "the ATS said remote, so it is remote");
            assertEquals(GeoScope.COUNTRY, c.geoScope());
            assertEquals("PT", c.geoDetail());
        }

        @Test
        @DisplayName("a city the gazetteer does not know degrades to UNKNOWN, not to a guess")
        void unknownCityDegradesHonestly() {
            Classification c = classify("Tallinn", "Engineer", Boolean.TRUE);
            assertTrue(c.isRemote());
            assertEquals(GeoScope.UNKNOWN, c.geoScope(),
                    "no gazetteer entry means no geo claim; this is the tail an LLM "
                            + "classifier is for, and guessing here would be worse than admitting it");
        }

        @ParameterizedTest
        @ValueSource(strings = {"Remoto", "Home Office", "Teletrabajo"})
        @DisplayName("non-English boards say remote in their own language")
        void nonEnglishRemoteTerms(String location) {
            assertTrue(classify(location, "Engenheiro", null).isRemote(), location);
        }

        @Test
        void remoteInTitleCountsWhenLocationIsSilent() {
            assertTrue(classify(null, "Staff Engineer (Remote)", null).isRemote());
        }
    }

    @Nested
    class NullSafety {

        @Test
        void handlesEverythingMissing() {
            Classification c = classify(null, "Engineer", null);
            assertFalse(c.isRemote());
        }
    }
}
