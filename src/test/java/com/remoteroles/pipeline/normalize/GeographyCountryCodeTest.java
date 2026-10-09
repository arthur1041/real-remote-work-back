package com.remoteroles.pipeline.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The employer-address resolver.
 *
 * <p>Every string here was taken from a real board payload. The rejections
 * matter more than the matches: these came back from a first pass that trusted
 * the boards, and each one would have printed "HQ Remote" or "HQ 08018
 * Barcelona" on a job card.
 */
class GeographyCountryCodeTest {

    @Nested
    @DisplayName("resolves")
    class Resolves {

        @ParameterizedTest
        @CsvSource({
            "'United States', US",
            "'USA', US",
            "'US', US",
            "'United Kingdom', GB",
            "'France', FR",
            "'Brazil', BR",
            "'Germany', DE",
        })
        @DisplayName("country names and the aliases boards actually write")
        void plainCountries(String raw, String expected) {
            assertEquals(expected, Geography.countryCode(raw));
        }

        @ParameterizedTest
        @CsvSource({
            "'Los Angeles, California, United States', US",
            "'Austin, Texas, United States', US",
            "'London, United Kingdom', GB",
        })
        @DisplayName("office strings, by reading from the right")
        void officeStrings(String raw, String expected) {
            // Greenhouse writes "City, Region, Country"; the country is last, and
            // "California" must not win just because it appears earlier.
            assertEquals(expected, Geography.countryCode(raw));
        }
    }

    @Nested
    @DisplayName("rejects")
    class Rejects {

        @ParameterizedTest
        @ValueSource(strings = {
            "Remote", "Remote - US", "Worldwide", "Any Location",
            "AMER", "North America", "Europe",
            "08018 Barcelona", "Princeton", "NY", "New York City", "California",
        })
        @DisplayName("everything a board put in an office field that is not a country")
        void notCountries(String raw) {
            assertNull(Geography.countryCode(raw),
                    "\"" + raw + "\" is not a country, and a card saying \"HQ " + raw
                            + "\" is worse than a card saying nothing");
        }

        @Test
        @DisplayName("blank and null")
        void empties() {
            assertNull(Geography.countryCode(null));
            assertNull(Geography.countryCode("   "));
        }
    }
}
