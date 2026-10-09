package com.remoteroles.pipeline.normalize;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Board entries that are not individual roles.
 *
 * <p>Talent pools, "general application" forms and "apply to our X team" funnels
 * are real entries on a company's board, but there is no job behind them: no
 * duties, no title worth matching, and nothing a reader can be hired into. Ninety
 * of them were live, forty-eight of those badged worldwide.
 *
 * <p>Carrying them costs more than the shelf space. Each one is an indexable page
 * in the sitemap emitting JobPosting structured data, and that markup describes a
 * specific open role -- which these are not. On a site funded by search traffic,
 * invalid markup is not a cosmetic problem: Search Console acts against the
 * property, not the page.
 *
 * <p>The pattern is deliberately narrow, and was checked against the corpus before
 * being trusted. Two tokens from the first draft had to go: "pipeline" matches
 * "Data Pipeline Engineer" and "Software Engineer, Stripe Data Pipeline", and a
 * bare "join our" matches any number of ordinary adverts. What is left only fires
 * on phrases that have no reading as a single job.
 */
public final class GenericPosting {

    private GenericPosting() {
    }

    private static final Pattern NOT_A_ROLE = Pattern.compile(
            "talent\\s+(pool|community|network|pipeline)"
                    + "|general application"
                    + "|speculative application"
                    + "|expression of interest"
                    + "|future opportunit"
                    + "|^apply to\\b"
                    + "|^join our\\b.*\\b(community|pool|network)\\b"
                    + "|can'?t find a role",
            Pattern.CASE_INSENSITIVE);

    /** Whether this title describes a funnel rather than a job. */
    public static boolean isNotARole(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return NOT_A_ROLE.matcher(title.toLowerCase(Locale.ROOT).trim()).find();
    }
}
