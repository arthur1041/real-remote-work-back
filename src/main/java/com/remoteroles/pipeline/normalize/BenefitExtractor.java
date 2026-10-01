package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Benefit;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds benefits mentioned in a job description.
 *
 * <p>Phrase matching against a closed vocabulary, tuned on the corpus: across 611
 * worldwide postings this finds at least one benefit in roughly half. The other
 * half genuinely describe none, and the site shows nothing rather than inventing
 * something.
 *
 * <p>This reads prose written by hundreds of different people, so it is tuned to
 * miss rather than to over-claim. A missing chip is a small loss; a chip promising
 * health insurance that the posting never offered is a reason to distrust the whole
 * site.
 */
@Component
public class BenefitExtractor {

    /** Descriptions run to thousands of words; this bounds the regex work. */
    private static final int MAX_SCAN_CHARS = 40_000;

    private static final Map<Benefit, Pattern> PATTERNS = Map.ofEntries(
            Map.entry(Benefit.UNLIMITED_PTO,
                    p("unlimited (pto|paid time off|vacation|holidays?|leave)")),
            Map.entry(Benefit.PAID_TIME_OFF,
                    p("\\bpto\\b|paid time off|paid vacation|annual leave|paid holidays?")),
            Map.entry(Benefit.HEALTH_INSURANCE,
                    p("health insurance|healthcare|health care|medical insurance|health coverage")),
            Map.entry(Benefit.DENTAL_VISION,
                    p("\\bdental\\b|vision insurance|vision coverage")),
            Map.entry(Benefit.PARENTAL_LEAVE,
                    p("parental leave|maternity leave|paternity leave")),
            Map.entry(Benefit.RETIREMENT_PLAN,
                    p("401\\s?\\(?k\\)?|\\bpension\\b|retirement (plan|savings|contribution)")),
            Map.entry(Benefit.STOCK_OPTIONS,
                    p("stock options|share options|\\brsus?\\b|equity (package|grant|compensation)"
                            + "|equity in the company")),
            Map.entry(Benefit.EQUIPMENT_BUDGET,
                    p("equipment (budget|allowance|stipend)|home office (budget|stipend|allowance)"
                            + "|hardware budget|laptop provided|work from home stipend")),
            Map.entry(Benefit.COWORKING_STIPEND,
                    p("co-?working (stipend|allowance|budget|membership|space)")),
            Map.entry(Benefit.LEARNING_BUDGET,
                    p("learning (budget|stipend|allowance)|education (budget|stipend|allowance)"
                            + "|professional development (budget|stipend|allowance)"
                            + "|training budget|conference budget")),
            Map.entry(Benefit.WELLNESS_STIPEND,
                    p("wellness (stipend|budget|allowance|programme|program)"
                            + "|gym (membership|stipend|allowance)|fitness (stipend|allowance)")),
            Map.entry(Benefit.FLEXIBLE_HOURS,
                    p("flexible (hours|schedule|working hours|work hours)|async(hronous)? work"
                            + "|set your own (hours|schedule)")),
            Map.entry(Benefit.FOUR_DAY_WEEK,
                    p("4-?day (work )?week|four-?day (work )?week|32-hour week")),
            Map.entry(Benefit.TEAM_RETREATS,
                    p("team retreats?|company retreats?|annual offsite|team offsites?")),
            Map.entry(Benefit.VISA_SPONSORSHIP,
                    p("visa sponsorship|sponsor(ship)? (a )?visa|relocation (support|assistance|package)")));

    /**
     * Pairs where the first implies the second, so only the stronger is kept.
     * "Unlimited PTO" alongside "Paid time off" is noise, not two benefits.
     */
    private static final Map<Benefit, Benefit> SUPERSEDES =
            Map.of(Benefit.UNLIMITED_PTO, Benefit.PAID_TIME_OFF);

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public Set<Benefit> extract(String descriptionHtml) {
        EnumSet<Benefit> found = EnumSet.noneOf(Benefit.class);
        if (descriptionHtml == null || descriptionHtml.isBlank()) {
            return found;
        }

        String text = toText(descriptionHtml);
        PATTERNS.forEach((benefit, pattern) -> {
            if (pattern.matcher(text).find()) {
                found.add(benefit);
            }
        });

        SUPERSEDES.forEach((stronger, weaker) -> {
            if (found.contains(stronger)) {
                found.remove(weaker);
            }
        });
        return found;
    }

    /** Strips tags and collapses whitespace so phrases are not split by markup. */
    private static String toText(String html) {
        String text = html.length() > MAX_SCAN_CHARS ? html.substring(0, MAX_SCAN_CHARS) : html;
        return text.replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;?", " ")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
