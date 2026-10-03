package com.remoteroles.pipeline.normalize;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Country and region vocabulary, built from the spellings that actually occur on
 * live boards rather than from an ISO list.
 *
 * <p>A sample of 2,300 postings produced 135 distinct "remote-ish" location
 * strings, including nine separate spellings of remote-within-the-United-States
 * ({@code "Remote, United States"}, {@code "US - Remote"}, {@code "Remote (US)"},
 * {@code "US-Remote"}, {@code "Remote - USA"}, {@code "Remote US"},
 * {@code "US remote"}, {@code "Remote-USA"}, {@code "Remote USA"}). Alias tables
 * are not optional here; they are the job.
 */
final class Geography {

    private Geography() {
    }

    /**
     * Multi-country regions. Checked before countries, because "Remote, North
     * America" names a region even though it contains no country name.
     */
    static final Map<String, Set<String>> REGIONS = new LinkedHashMap<>();

    /** Country aliases to a canonical ISO-3166 alpha-2 code. */
    static final Map<String, String> COUNTRIES = new LinkedHashMap<>();

    /**
     * States and provinces, mapped to the country that contains them.
     *
     * <p>Earns its keep because boards write subnational remote scopes constantly:
     * {@code "Ontario - Remote"} and {@code "California - Remote"} are country-gated
     * roles that name no country. Without this they fall through to UNKNOWN and
     * never reach a country filter.
     *
     * <p>Genuinely ambiguous names are left out rather than guessed at. "Georgia" is
     * both a US state and a sovereign country; "Washington" is a state, a capital and
     * part of other place names. Mislabelling those is worse than declining to label
     * them, so they fall through to the classifier's UNKNOWN branch.
     */
    static final Map<String, String> SUBDIVISIONS = new LinkedHashMap<>();

    /**
     * Major hiring cities, mapped to their country.
     *
     * <p>Intentionally short. A real gazetteer has tens of thousands of entries and
     * a stack of collisions ("Cambridge", "Birmingham", "Santiago"), so this covers
     * only hubs that recur across tech boards and leaves the rest to degrade to
     * UNKNOWN. The long tail of place names is the clearest argument for putting an
     * LLM classifier behind the rules rather than growing this map forever.
     */
    static final Map<String, String> CITIES = new LinkedHashMap<>();

    static {
        REGIONS.put("EMEA", Set.of("emea"));
        REGIONS.put("APAC", Set.of("apac", "asia pacific", "asia-pacific"));
        REGIONS.put("LATAM", Set.of("latam", "latin america", "south america"));
        REGIONS.put("EU", Set.of("european union", "eu only", "eu-only", "europe", "european"));
        // "NAMER" is Zapier's and others' house style for North America; it appears
        // as the entire location string, so without it those roles look unscoped.
        REGIONS.put("NORTH_AMERICA", Set.of("north america", "northamerica", "namer"));
        // "AMER" is the counterpart to NAMER in several boards' house style --
        // Supabase ships "Remote, AMER" beside "Remote, Global" -- and without it
        // those roles landed in UNKNOWN, one description-scan away from being read
        // as worldwide. containsWord is boundary-aware, so this cannot match inside
        // "America" or "Americas".
        REGIONS.put("AMERICAS", Set.of("americas", "amer"));
        REGIONS.put("MENA", Set.of("mena", "middle east"));
        REGIONS.put("AFRICA", Set.of("africa"));
        REGIONS.put("NORDICS", Set.of("nordics", "nordic", "scandinavia"));
        REGIONS.put("DACH", Set.of("dach"));
        REGIONS.put("BENELUX", Set.of("benelux"));
        REGIONS.put("ANZ", Set.of("anz", "australia and new zealand"));

        // United States: by far the most-aliased value in the wild.
        country("US", "united states of america", "united states", "u.s.a.", "u.s.", "usa", "us");
        country("CA", "canada");
        country("GB", "united kingdom", "great britain", "england", "scotland", "wales", "u.k.", "uk");
        country("IE", "ireland");
        country("DE", "germany", "deutschland");
        country("FR", "france");
        country("ES", "spain");
        country("PT", "portugal");
        country("IT", "italy");
        country("NL", "netherlands", "holland");
        country("BE", "belgium");
        country("CH", "switzerland");
        country("AT", "austria");
        country("PL", "poland");
        country("RO", "romania");
        country("CZ", "czechia", "czech republic");
        country("SE", "sweden");
        country("NO", "norway");
        country("DK", "denmark");
        country("FI", "finland");
        country("EE", "estonia");
        country("LT", "lithuania");
        country("LV", "latvia");
        country("UA", "ukraine");
        country("TR", "turkey", "türkiye");
        country("IL", "israel");
        country("AE", "united arab emirates", "uae");
        country("SA", "saudi arabia", "ksa");
        country("ZA", "south africa");
        country("NG", "nigeria");
        country("KE", "kenya");
        country("EG", "egypt");
        country("IN", "india");
        country("SG", "singapore");
        country("JP", "japan");
        country("KR", "south korea", "korea");
        country("CN", "china");
        country("HK", "hong kong");
        country("TW", "taiwan");
        country("AU", "australia");
        country("NZ", "new zealand");
        country("PH", "philippines");
        country("ID", "indonesia");
        country("MY", "malaysia");
        country("TH", "thailand");
        country("VN", "vietnam");
        country("BR", "brazil", "brasil");
        country("MX", "mexico", "méxico");
        country("AR", "argentina");
        country("CL", "chile");
        country("CO", "colombia");
        country("PE", "peru");
        country("UY", "uruguay");
        country("CR", "costa rica");

        // Canadian provinces: "Ontario - Remote" appears often enough to matter.
        subdivision("CA", "ontario", "quebec", "québec", "british columbia", "alberta",
                "manitoba", "saskatchewan", "nova scotia", "new brunswick",
                "newfoundland", "prince edward island");

        // US states. "georgia" and "washington" are deliberately absent -- see
        // the SUBDIVISIONS javadoc.
        subdivision("US", "alabama", "alaska", "arizona", "arkansas", "california",
                "colorado", "connecticut", "delaware", "florida", "hawaii", "idaho",
                "illinois", "indiana", "iowa", "kansas", "kentucky", "louisiana",
                "maine", "maryland", "massachusetts", "michigan", "minnesota",
                "mississippi", "missouri", "montana", "nebraska", "nevada",
                "new hampshire", "new jersey", "new mexico", "new york state",
                "north carolina", "north dakota", "ohio", "oklahoma", "oregon",
                "pennsylvania", "rhode island", "south carolina", "south dakota",
                "tennessee", "texas", "utah", "vermont", "virginia", "west virginia",
                "wisconsin", "wyoming");

        city("US", "san francisco", "new york city", "new york", "nyc", "sf bay area",
                "seattle", "los angeles", "boston", "chicago", "denver", "austin",
                "atlanta", "washington d.c.", "washington dc", "washington, d.c.");
        city("GB", "london", "manchester", "edinburgh");
        city("DE", "berlin", "munich", "münchen", "hamburg");
        city("PT", "lisbon", "lisboa", "porto");
        city("ES", "madrid", "barcelona", "valencia");
        city("NL", "amsterdam", "rotterdam", "utrecht");
        city("FR", "paris", "lyon");
        city("IE", "dublin");
        city("PL", "warsaw", "krakow", "kraków", "wroclaw");
        city("CA", "toronto", "vancouver", "montreal", "montréal", "ottawa");
        city("IN", "bangalore", "bengaluru", "mumbai", "hyderabad", "pune", "delhi");
        city("SG", "singapore city");
        city("AU", "sydney", "melbourne", "brisbane");
        city("JP", "tokyo");
        city("BR", "sao paulo", "são paulo");
        city("MX", "mexico city");
        city("AE", "dubai", "abu dhabi");
        city("IL", "tel aviv");
    }


    private static void city(String country, String... names) {
        for (String name : names) {
            CITIES.put(name, country);
        }
    }

    private static void subdivision(String country, String... names) {
        for (String name : names) {
            SUBDIVISIONS.put(name, country);
        }
    }

    private static void country(String code, String... aliases) {
        for (String alias : aliases) {
            COUNTRIES.put(alias, code);
        }
    }
}
