package com.remoteroles.pipeline.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A location field can be wrong, and the description is where it shows.
 *
 * <p>On a feed the location field is often written by the aggregator rather
 * than by the employer. WeWorkRemotely stamped "Anywhere in the World" on a
 * Veeva role whose own description says it is based in Hungary, and on
 * forty-two others: Airbnb roles limited to the US states and Canadian
 * provinces the company is registered in, roles naming five permitted
 * countries, one based in Wisconsin, one in Costa Rica, several requiring a
 * European timezone. All of them carried a WORLDWIDE badge, which is the one
 * thing this board cannot get wrong.
 *
 * <p>Every string below is lifted from a real posting that was live when this
 * was written. The negatives matter as much as the positives: each is a phrase
 * that looks like a restriction and is not, and each cost a plausible pattern
 * its place in the rule.
 */
class DescriptionContradictsWorldwideTest {

    @ParameterizedTest(name = "restricted: {0}")
    @ValueSource(strings = {
            "This is a remote, full-time role based in Hungary (must be legally authorized to work in Europe).",
            "Location: This is a remote position based in the EMEA region",
            "However, candidates must be located in the United States, Canada (Ontario or British Columbia), Netherlands, Poland, or Czech Republic.",
            "While the position is Remote Eligible, you must live in a state where Airbnb, Inc. is registered.",
            "While the position is Remote Eligible, you must live in a province where Airbnb Canada, Inc. operates.",
            "Candidates must reside in an eligible location, which vary by role.",
            "We're only hiring those authorized to work in the United States.",
            "This role is based in the Wisconsin area, reporting to the Regional Director.",
            "This is a full-time remote position based in Costa Rica.",
            "This position requires residency in Dallas/Fort Worth, TX.",
            "Location Anywhere within European timezones",
            "As a fully remote company, our team operates within EU timezones.",
            "Fully remote work with flexible hours (within the CET time zone) from any location of your choice.",
            "Availability to collaborate within European time zones.",
            "Experience with ProductOps is a bonus. 4h overlap with CET time zone.",
            // Added after fourteen live postings got through the rule above.
            // Six of them say "open to candidates ... in <place>" without the
            // word "only", which was all the earlier pattern looked for; the
            // rest say "must be based in", the one spelling missing from a
            // line that already had located, reside and live.
            "This is a hybrid position open to candidates residing in Canada.",
            "This contract engagement is open to candidates based in the Netherlands.",
            "This is a remote position open to candidates residing in the US.",
            "Location Requirement: This role is open to candidates located in "
                    + "British Columbia or Ontario, Canada.",
            "Note: This is a remote role open to candidates located in Arizona, "
                    + "California, Colorado, Connecticut, District of Columbia, Florida",
            "Location The Director of MEAL must be based in Africa.",
            "Location: This role is remote (must be based in Chicago)",
            "Location: This role is remote (must be based in the Mid-Atlantic: "
                    + "Washington, D.C., DE, MD, PA, VA, WV)",
            "You must be based in the EU timezone to ensure collaboration with the team.",
            "This is a remote role that must be based in EST.",
    })
    @DisplayName("A posting that names where somebody must be loses the badge")
    void contradicts(String description) {
        assertTrue(
                RuleBasedLocationClassifier.descriptionContradictsWorldwide("<p>" + description + "</p>"),
                "should have been read as a restriction");
    }

    @ParameterizedTest(name = "not a restriction: {0}")
    @ValueSource(strings = {
            // The clause every international employer carries. It restricts
            // nobody -- it says the law of wherever you are still applies -- and
            // it was found on a genuinely worldwide posting.
            "Applicants must be authorized to work in the country in which they apply.",
            "You must have the right to work in your country of residence.",
            // Thrown out for matching these: a support promise, an absent
            // policy, a perk, and a share plan offered to some employees of a
            // role anyone may take.
            "We aim to respond to every applicant within 24-48 hours of applying.",
            "No on-call, no need to be in an office, and no strict core hours.",
            "Ability to work with a degree of flexibility outside our core hours of operation.",
            "Visa sponsorship option for relocation to NYC within 2+ years.",
            "Employee Stock Purchase Plan with discounted options for eligible employees (US only).",
            "Experience with identity integration and SSO.",
            "Payroll Client Services is responsible for resolving customer payroll inquiries.",
            "Official employment in accordance with the Russian Labor Code for residents of Russia; "
                    + "contractor collaboration for candidates from other countries.",
            "We collect information as defined under California law from job applicants "
                    + "who are residents of California.",
            "Location: Remote, with flexible overlap with client's timezone.",
            // The guards on the two patterns added above. Each is the same
            // words as a gate pointed at the whole world, and reading one as a
            // restriction would take a genuinely open role off the homepage --
            // the more expensive of the two mistakes this rule can make.
            "We are open to candidates from all backgrounds.",
            "This role is open to candidates with 5+ years of experience.",
            "We are open to candidates based in any country.",
            "You must be based in your country of residence.",
            "Open to candidates located anywhere in the world.",
    })
    @DisplayName("Boilerplate, perks and absent policies are not restrictions")
    void doesNotContradict(String description) {
        assertFalse(
                RuleBasedLocationClassifier.descriptionContradictsWorldwide("<p>" + description + "</p>"),
                "should not have been read as a restriction");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "<p></p>"})
    @DisplayName("An absent description vetoes nothing")
    void emptyIsSilent(String description) {
        assertFalse(RuleBasedLocationClassifier.descriptionContradictsWorldwide(description));
    }
}
