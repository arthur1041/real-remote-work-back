package com.remoteroles.pipeline.domain;

/**
 * Benefits we can recognise in a job description.
 *
 * <p>A closed set, because each value becomes a filter URL on the site. An open
 * vocabulary scraped from whatever employers write would produce thousands of
 * near-duplicate tags ("health cover", "medical plan", "healthcare") that each
 * match a handful of postings and none of which are worth a page.
 *
 * <p>Absence means <em>not mentioned</em>, never <em>not offered</em>. About half
 * of postings describe no benefits at all, and the site has to say so rather than
 * imply the job is worse.
 */
public enum Benefit {
    UNLIMITED_PTO("Unlimited PTO", "🏖️"),
    PAID_TIME_OFF("Paid time off", "📅"),
    HEALTH_INSURANCE("Health insurance", "🏥"),
    DENTAL_VISION("Dental & vision", "🦷"),
    PARENTAL_LEAVE("Parental leave", "👶"),
    RETIREMENT_PLAN("Retirement plan", "🏦"),
    STOCK_OPTIONS("Stock options", "📈"),
    EQUIPMENT_BUDGET("Equipment budget", "💻"),
    COWORKING_STIPEND("Coworking stipend", "🏢"),
    LEARNING_BUDGET("Learning budget", "📚"),
    WELLNESS_STIPEND("Wellness stipend", "🧘"),
    FLEXIBLE_HOURS("Flexible hours", "🕒"),
    FOUR_DAY_WEEK("4-day week", "🗓️"),
    TEAM_RETREATS("Team retreats", "✈️"),
    VISA_SPONSORSHIP("Visa sponsorship", "🛂");

    private final String label;
    private final String emoji;

    Benefit(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }

    /** URL-safe form: {@code STOCK_OPTIONS} becomes {@code stock-options}. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
