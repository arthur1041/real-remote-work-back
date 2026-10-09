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
 *
 * <p>For the same reason, eligibility is read from the location field alone. A job
 * title is written to describe the work, so "global" in one is an adjective about
 * scope, not a statement about hiring.
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

    /**
     * Earns WORLDWIDE outright.
     *
     * <p>Bare "global" is in here and deliberately NOT in {@link #WORLDWIDE_EXPLICIT}.
     * Boards write it as the whole location -- "Global", "Remote, Global",
     * "Global - Remote" -- and thirty live postings sat in UNKNOWN for want of it.
     * Keeping it out of the explicit set means it still loses to a named country, so
     * "Global - London" stays a London job while "Remote, Global" becomes worldwide.
     */
    private static final Pattern WORLDWIDE = Pattern.compile(
            "\\b(work from anywhere|from anywhere|anywhere in the world|anywhere|worldwide|world-wide|"
                    + "globally|global remote|global|fully remote, global|any location|"
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
     * A band between two offsets, as in "UTC-10 to UTC+14".
     *
     * <p>Matched against the RAW location, not the normalised one. {@link #normalize}
     * turns a hyphen between word characters into a space, so by the time the string
     * reaches the single-zone pattern "utc-10" has become "utc 10" and only the upper
     * bound still looks like an offset. Five live postings reading
     * "Anywhere (UTC-10 to UTC+14)" were therefore stored as "UTC+14", which reads as
     * a restriction to one edge of the planet rather than the whole of it.
     */
    private static final Pattern TIMEZONE_BAND = Pattern.compile(
            "(utc|gmt)\\s*([+-]\\s*\\d{1,2})\\s*(?:to|through|\\u2013|\\u2014|-)\\s*"
                    + "(?:utc|gmt)?\\s*([+-]\\s*\\d{1,2})",
            Pattern.CASE_INSENSITIVE);

    /**
     * A named timezone. Searched before {@link #TIMEZONE_OVERLAP} because regex
     * alternation returns the <em>leftmost</em> match, not the most specific one:
     * folded into one pattern, "must overlap with CET" yields "overlap with" and
     * the actual zone is lost.
     */
    private static final Pattern TIMEZONE_ZONE = Pattern.compile(
            "\\b(utc\\s*[+-]\\s*\\d{1,2}|gmt\\s*[+-]\\s*\\d{1,2}|gmt|[ecmp][sd]t|cet|cest|eet|ist|jst|aest)\\b",
            Pattern.CASE_INSENSITIVE);

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

        // The title is evidence that a role is remote. It is NOT evidence of who may
        // be hired, and conflating the two put a worldwide badge on fourteen roles
        // that were plainly gated: "US External Affairs Associate, Global Affairs" in
        // Washington DC, "Financial Representative, Global Accounts Payable" in
        // Mohali, "Salesforce Platform Lead for Global Industrial Company" in a
        // posting whose location field reads "North America Only".
        //
        // In a title, "global" and "worldwide" describe the SCOPE OF THE WORK -- a
        // Global Head of Cloud Alliances manages alliances globally, from one
        // country. Eligibility is stated in the location field, so only the location
        // decides it. Checked against the corpus before changing: of the postings
        // whose title says anywhere or worldwide, every single one says it in the
        // location too, so the title has never been the sole source of a correct
        // WORLDWIDE. It was only ever the sole source of wrong ones.
        boolean saysWorldwide = WORLDWIDE.matcher(location).find();

        // "Work from anywhere" and "Worldwide" are remote signals in their own right.
        // Requiring the literal word "remote" dropped the most unambiguously
        // location-independent postings on the floor -- precisely the ones this site
        // exists to surface. This is the one job the title still does.
        boolean textSaysRemote = saysWorldwide
                || WORLDWIDE.matcher(title).find()
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

        String timezone = findTimezone(posting.locationRaw());

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
        //    The exemption for explicit phrasing holds against ONE co-named country
        //    and no further. That is the shape it was written for: WeWorkRemotely
        //    appends a single country to its own region label, as in "Anywhere in
        //    the World, United States of America", where the country is incidental.
        //
        //    A LIST is the opposite. "Anywhere in the World" followed by thirty-four
        //    enumerated European countries is an allowlist with a banner over it, and
        //    every such posting checked was genuinely gated -- one to Europe, one to
        //    Latin America and the Balkans. Letting the banner win there puts a
        //    worldwide badge on a role most readers cannot take, which is the single
        //    failure this site cannot afford.
        boolean explicit = WORLDWIDE_EXPLICIT.matcher(location).find() && countries.size() <= 1;
        boolean qualifiedByPlace =
                !explicit && (ANYWHERE_IN_PLACE.matcher(location).find() || !countries.isEmpty());

        if (saysWorldwide && !qualifiedByPlace) {
            // Last gate: a place named in the TITLE takes the badge away.
            //
            // This is the mirror of the rule above, and the asymmetry is the point.
            // A title promising openness ("Global Head of ...") describes the work
            // and earns nothing. A title naming a place -- "US Remote Technical
            // Support Advisor", "Data Architect (100% Remote) (EMEA Only)", "Account
            // Manager (US)", "Senior Solutions Engineer- LATAM" -- is the employer
            // writing down a restriction, and nineteen such roles were sitting on
            // the worldwide feed because only the location field was consulted.
            //
            // Believing the restriction and doubting the promise is not ad hoc: a
            // wrong COUNTRY badge disappoints one reader, a wrong WORLDWIDE badge
            // discredits the board. Where the two fields disagree, the narrower one
            // is the safe reading.
            //
            // Only regions and countries are read from a title, never cities or
            // subdivisions: "Austin", "Boston" and "Georgia" are also ordinary words
            // and names, and a title is prose in a way a location field is not.
            Set<String> titleRegions = findRegions(title);
            Set<String> titleCountries = namedCountries(title);

            if (!titleRegions.isEmpty() || !titleCountries.isEmpty()) {
                // Lower confidence than an ordinary gated role: the two fields
                // genuinely disagree, and this is the conservative reading of a
                // conflict rather than a posting that stated one thing clearly.
                if (!titleRegions.isEmpty()) {
                    String detail = String.join(", ", titleRegions);
                    if (!titleCountries.isEmpty()) {
                        detail += " (" + String.join(", ", titleCountries) + ")";
                    }
                    return new Classification(true, GeoScope.REGION, detail, timezone, 0.8, VERSION);
                }
                return titleCountries.size() > 1
                        ? new Classification(true, GeoScope.REGION,
                                String.join(", ", titleCountries), timezone, 0.8, VERSION)
                        : new Classification(true, GeoScope.COUNTRY,
                                titleCountries.iterator().next(), timezone, 0.8, VERSION);
            }

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
    /**
     * Country names only -- no states, provinces or cities.
     *
     * <p>Used when reading a title, where the looser tables do more harm than good:
     * a job title is prose, and "Austin" or "Boston" in one is as likely to be a
     * person as a place.
     */
    private static Set<String> namedCountries(String text) {
        Set<String> found = new LinkedHashSet<>();
        Geography.COUNTRIES.entrySet().stream()
                .sorted((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                .forEach(entry -> {
                    if (containsWord(text, entry.getKey())) {
                        found.add(entry.getValue());
                    }
                });
        return found;
    }

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

    /**
     * Reads a timezone requirement out of the location as the board wrote it.
     *
     * <p>Takes the raw string rather than the normalised one so that offset signs
     * survive; see {@link #TIMEZONE_BAND}. A band is reported whole, because half a
     * band is worse than none: "UTC+14" alone names the far edge of the world where
     * the posting said the whole of it.
     */
    private static String findTimezone(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher band = TIMEZONE_BAND.matcher(raw);
        if (band.find()) {
            return "UTC%s to UTC%s".formatted(
                    band.group(2).replaceAll("\\s+", ""), band.group(3).replaceAll("\\s+", ""));
        }
        Matcher zone = TIMEZONE_ZONE.matcher(raw);
        if (zone.find()) {
            return zone.group().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        }
        return TIMEZONE_OVERLAP.matcher(raw.toLowerCase(Locale.ROOT)).find()
                ? "OVERLAP_REQUIRED" : null;
    }
}
