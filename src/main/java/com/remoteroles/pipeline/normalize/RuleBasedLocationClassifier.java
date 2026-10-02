package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Classification;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.domain.GeoScope;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic classifier, tuned against the spellings live boards actually emit.
 *
 * <p>The one decision worth defending: a bare {@code "Remote"} is classified
 * {@link GeoScope#UNKNOWN}, never {@link GeoScope#WORLDWIDE}. Most postings that
 * say only "Remote" turn out to be country-gated once you read the body, so
 * promoting them would quietly fill the "work from anywhere" feed with roles that
 * reject most of the people reading it. WORLDWIDE has to be earned by an explicit
 * phrase -- "anywhere", "worldwide", "globally". Being honestly unsure is worth
 * more than being confidently wrong, because the no-geo-gate promise is the only
 * reason anyone picks this site over Indeed.
 */
@Component
public class RuleBasedLocationClassifier implements LocationClassifier {

    static final String VERSION = "RULES_V1";

    /**
     * "anywhere" immediately qualified by a place, as in "Anywhere in France".
     *
     * <p>This is a restriction written to look like freedom, and it was reading as
     * the opposite: the worldwide test fired on the bare word "anywhere" before
     * anything looked at the country beside it, so 41 postings carried a WORLDWIDE
     * badge above the words "Anywhere in Belgium".
     *
     * <p>"anywhere in the world" is the one phrase of this shape that genuinely
     * means unrestricted, so it is excluded.
     */
    private static final Pattern ANYWHERE_IN_PLACE =
            Pattern.compile("\\banywhere\\s+in\\s+(?!the world\\b)");

    /**
     * Phrasing that is unambiguous on its own.
     *
     * <p>These beat a co-named country. WeWorkRemotely postings arrive as
     * "Anywhere in the World, United States of America" -- its region field is the
     * eligibility and the country beside it is supplementary, so treating that
     * country as a gate demotes a genuinely unrestricted role.
     *
     * <p>Bare "anywhere" is deliberately not in here. It is the word that appears
     * in "Anywhere in France".
     */
    private static final Pattern WORLDWIDE_EXPLICIT = Pattern.compile(
            "\\banywhere in the world\\b|\\bworldwide\\b|\\bworld-wide\\b|\\bglobally\\b");

    /** Earns WORLDWIDE outright. */
    private static final Pattern WORLDWIDE = Pattern.compile(
            "\\b(work from anywhere|from anywhere|anywhere in the world|anywhere|worldwide|world-wide|"
                    + "globally|global remote|fully remote, global|any location|"
                    + "location independent|location-independent|no location requirement)\\b");

    /** Indicates remote work without saying how open it is. */
    private static final Pattern REMOTE = Pattern.compile(
            "\\b(remote|remoto|remota|wfh|work from home|home office|"
                    + "distributed|telecommute|teletrabajo|virtual)\\b");

    /** Rules a posting out however often "remote" appears elsewhere. */
    private static final Pattern DISQUALIFYING = Pattern.compile(
            "\\b(hybrid|on-?site|in-?office|in-?person|"
                    + "relocation required|must relocate|commutable|commuting distance)\\b");

    /**
     * A named timezone. Searched before {@link #TIMEZONE_OVERLAP} because regex
     * alternation returns the <em>leftmost</em> match, not the most specific one:
     * folded into one pattern, "must overlap with CET" yields "overlap with" and
     * the actual zone is lost.
     */
    private static final Pattern TIMEZONE_ZONE = Pattern.compile(
            "\\b(utc[+-]\\d{1,2}|gmt[+-]\\d{1,2}|gmt|[ecmp][sd]t|cet|cest|eet|ist|jst|aest)\\b");

    /** An overlap requirement with no zone named: still a geo gate, just a vaguer one. */
    private static final Pattern TIMEZONE_OVERLAP = Pattern.compile(
            "\\b(\\d{1,2}\\s*hours? overlap|overlap with|core hours|business hours)\\b");

    /** Splits "Remote, Canada; Remote, US" and "Poland - Remote OR Romania - Remote". */
    private static final Pattern LOCATION_SPLIT = Pattern.compile("\\s*(?:;|\\bor\\b|/|\\|)\\s*");

    @Override
    public Classification classify(FetchedPosting posting) {
        String location = normalize(posting.locationRaw());
        String title = normalize(posting.title());

        // 1. An explicit "not remote" from the ATS is final. Lever and Ashby both
        //    state workplace type, and they know better than our regexes do.
        if (Boolean.FALSE.equals(posting.remoteHint())) {
            return Classification.notRemote(VERSION);
        }

        boolean atsSaysRemote = Boolean.TRUE.equals(posting.remoteHint());
        // "Work from anywhere" and "Worldwide" are remote signals in their own right.
        // Requiring the literal word "remote" dropped the most unambiguously
        // location-independent postings on the floor -- precisely the ones this site
        // exists to surface.
        boolean saysWorldwide = WORLDWIDE.matcher(location).find() || WORLDWIDE.matcher(title).find();
        boolean textSaysRemote = saysWorldwide
                || REMOTE.matcher(location).find()
                || REMOTE.matcher(title).find();

        if (!atsSaysRemote && !textSaysRemote) {
            return Classification.notRemote(VERSION);
        }

        // 2. "Hybrid" in the location beats the word "remote" sitting next to it:
        //    "Remote - 2 days in office" is not a remote job.
        if (DISQUALIFYING.matcher(location).find() && !atsSaysRemote) {
            return Classification.notRemote(VERSION);
        }

        String timezone = findTimezone(location);

        // 3. Resolve places first. This has to happen before the worldwide test,
        //    because the words that promise the world and the words that take it
        //    away appear in the same string.
        Set<String> regions = findRegions(location);
        Set<String> countries = findCountries(location);

        // 4. WORLDWIDE, and only on terms.
        //
        //    A named country disqualifies it outright: "Anywhere in France" and
        //    "Ontario, Canada - Remote, Anywhere" are eligibility gates whatever
        //    the adjective. So is any "anywhere in <place>" phrasing.
        //
        //    Named regions do not disqualify, because a posting like "Home based -
        //    Americas; APAC; EMEA; Worldwide" lists worldwide as one of its own
        //    options. Erring the other way would cost real worldwide roles, and
        //    erring this way costs none -- the posting still lists its regions.
        //    Unambiguous phrasing is exempt: "anywhere in the world" states the
        //    eligibility outright, and a country listed beside it is supplementary.
        boolean explicit = WORLDWIDE_EXPLICIT.matcher(location).find();
        boolean qualifiedByPlace =
                !explicit && (ANYWHERE_IN_PLACE.matcher(location).find() || !countries.isEmpty());

        if (saysWorldwide && !qualifiedByPlace) {
            // A stated timezone band contradicts "anywhere", so drop confidence
            // rather than silently honouring a promise the posting does not make.
            double confidence = timezone == null ? 0.95 : 0.65;
            return new Classification(true, GeoScope.WORLDWIDE, null, timezone, confidence, VERSION);
        }

        if (!regions.isEmpty()) {
            String detail = String.join(", ", regions);
            if (!countries.isEmpty()) {
                detail += " (" + String.join(", ", countries) + ")";
            }
            return new Classification(true, GeoScope.REGION, detail, timezone, 0.85, VERSION);
        }

        // 5. Several countries listed is a region in practice, e.g.
        //    "Remote, Canada; Remote, United States".
        if (countries.size() > 1) {
            return new Classification(
                    true, GeoScope.REGION, String.join(", ", countries), timezone, 0.85, VERSION);
        }
        if (countries.size() == 1) {
            return new Classification(
                    true, GeoScope.COUNTRY, countries.iterator().next(), timezone, 0.9, VERSION);
        }

        // 6. Remote, but the posting never says how open. Deliberately not WORLDWIDE.
        //    Confidence reflects who told us: an ATS boolean is firmer than a regex.
        double confidence = atsSaysRemote ? 0.6 : 0.45;
        return new Classification(true, GeoScope.UNKNOWN, null, timezone, confidence, VERSION);
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // Fold punctuation that separates tokens into spaces so "US-Remote" and
        // "US - Remote" and "US Remote" all reduce to the same thing, while keeping
        // the dots in "U.S." long enough for the alias table to match them.
        return raw.toLowerCase(Locale.ROOT)
                .replace(' ', ' ')
                .replaceAll("[()\\[\\]]", " ")
                .replaceAll("(?<=\\w)-(?=\\w)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Set<String> findRegions(String location) {
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : Geography.REGIONS.entrySet()) {
            for (String alias : entry.getValue()) {
                if (containsWord(location, alias)) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Resolves country codes from country names, then from states and provinces.
     *
     * <p>Order matters: countries, then subdivisions, then cities. All three feed one
     * de-duplicated set of codes, so "Remote - Ontario, Canada" yields a single CA
     * rather than looking like a two-country region.
     */
    private static Set<String> findCountries(String location) {
        Set<String> found = new LinkedHashSet<>();
        // Longest alias first so "united states" wins before bare "us" can match,
        // and so matching "south korea" does not also report "korea".
        Geography.COUNTRIES.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .forEach(entry -> {
                    if (containsWord(location, entry.getKey())) {
                        found.add(entry.getValue());
                    }
                });

        for (Map.Entry<String, String> entry : Geography.SUBDIVISIONS.entrySet()) {
            if (containsWord(location, entry.getKey())) {
                found.add(entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : Geography.CITIES.entrySet()) {
            if (containsWord(location, entry.getKey())) {
                found.add(entry.getValue());
            }
        }
        return found;
    }

    /**
     * Word-boundary containment. Needed because a naive {@code contains} makes
     * "us" match "Austin" and "Houston", which silently mislabels a pile of
     * onsite US jobs as country-gated remote ones.
     */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static String findTimezone(String location) {
        Matcher zone = TIMEZONE_ZONE.matcher(location);
        if (zone.find()) {
            return zone.group().toUpperCase(Locale.ROOT);
        }
        return TIMEZONE_OVERLAP.matcher(location).find() ? "OVERLAP_REQUIRED" : null;
    }
}
