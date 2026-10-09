package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Reads pay out of an Ashby posting.
 *
 * <p>Its own class, and public, because two callers need the identical answer:
 * the fetcher, as postings arrive, and the backfill that replays payloads already
 * stored. Writing the same rules a second time in SQL is how the employer-country
 * column first filled up with "Remote" and "08018 Barcelona" -- so there is one
 * implementation, and the backfill calls it.
 */
public final class AshbyCompensation {

    private AshbyCompensation() {
    }

    /** A salary the employer stated, in one currency, over one period. */
    public record Pay(BigDecimal min, BigDecimal max, String currency, String period) {
    }

    /**
     * Reads the stated salary out of Ashby's compensation block.
     *
     * <p>Pay arrives as a list of tiers, each a list of typed components, and only
     * the {@code Salary} ones are pay: the same list also carries
     * {@code EquityPercentage}, {@code EquityCashValue}, {@code Bonus} and
     * {@code Commission}. Reading those as salary would print an equity grant of
     * {@code 0.5} as a yearly wage, so the type is checked before the numbers.
     *
     * <p>Tiers are geographic bands -- "Tier 1 - New York City", "Tier 2 - ..." --
     * and a card has one line for pay, so the bands are folded into their envelope:
     * the lowest minimum the employer published to the highest maximum. That is a
     * range they actually stated, which is the only kind this site prints.
     *
     * <p>Returns null the moment the data stops being unambiguous: no salary
     * component, mixed currencies, or mixed periods across tiers. A posting whose
     * tiers are quoted in both USD and EUR has no single honest range, and showing
     * the wrong currency is worse than showing nothing.
     */
    public static Pay read(JsonNode job) {
        JsonNode tiers = job.path("compensation").path("compensationTiers");
        if (!tiers.isArray()) {
            return null;
        }

        BigDecimal min = null;
        BigDecimal max = null;
        String currency = null;
        String period = null;

        for (JsonNode tier : tiers) {
            for (JsonNode component : tier.path("components")) {
                if (!"Salary".equals(Json.text(component, "compensationType"))) {
                    continue;
                }

                String componentCurrency = Json.text(component, "currencyCode");
                String componentPeriod = period(Json.text(component, "interval"));
                if (componentCurrency == null || componentPeriod == null) {
                    continue; // an unpriced or open-ended component says nothing
                }
                if (currency == null) {
                    currency = componentCurrency;
                    period = componentPeriod;
                } else if (!currency.equals(componentCurrency) || !period.equals(componentPeriod)) {
                    return null; // no single range can describe both
                }

                BigDecimal low = amount(component, "minValue");
                BigDecimal high = amount(component, "maxValue");
                if (low != null) {
                    min = (min == null || low.compareTo(min) < 0) ? low : min;
                }
                if (high != null) {
                    max = (max == null || high.compareTo(max) > 0) ? high : max;
                }
            }
        }

        if (min == null && max == null) {
            return null;
        }
        // A one-sided band is still worth printing: "from USD 90k" beats silence.
        return new Pay(min, max, currency, period);
    }

    /** Positive amounts only: Ashby writes an unset bound as null, but also as zero. */
    private static BigDecimal amount(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            return null;
        }
        BigDecimal amount = value.decimalValue();
        return amount.signum() > 0 ? amount : null;
    }

    /** Maps Ashby's interval onto the period names the rest of the pipeline uses. */
    private static String period(String interval) {
        if (interval == null) {
            return null;
        }
        return switch (interval) {
            case "1 YEAR" -> "annual";
            case "1 MONTH" -> "monthly";
            case "1 WEEK" -> "weekly";
            case "1 DAY" -> "daily";
            case "1 HOUR" -> "hourly";
            // "NONE" is an equity grant, not a wage. "2 WEEKS" is deliberately absent:
            // calling it weekly would halve the figure, and the site has no biweekly
            // period to render it as. Anything unrecognised is left unstated.
            default -> null;
        };
    }
}
