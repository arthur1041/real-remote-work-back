package com.remoteroles.pipeline.domain;

import java.util.Locale;

/**
 * Normalized employment type.
 *
 * <p>Employers write the same thing a dozen ways — {@code FullTime},
 * {@code Full Time}, {@code Full-Time} and {@code CLT} all appear in the corpus and
 * all mean one job shape. A filter is only useful if they collapse.
 */
public enum EmploymentKind {
    FULL_TIME("Full-Time"),
    PART_TIME("Part-Time"),
    CONTRACT("Contract"),
    TEMPORARY("Temporary"),
    INTERNSHIP("Internship"),
    VOLUNTEER("Volunteer"),
    OTHER("Other");

    private final String label;

    EmploymentKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    /**
     * Maps an employer's wording onto the closed set.
     *
     * <p>Returns null for absent or unrecognised input rather than guessing
     * {@code OTHER}: a posting that never stated a type should not be filed under a
     * type, or the "Other" filter fills up with jobs nobody classified.
     *
     * <p>{@code CLT} is Brazil's standard employment contract — a permanent salaried
     * role, so it maps to FULL_TIME rather than to a contract type, which is what
     * the English word would wrongly suggest.
     */
    public static EmploymentKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (value) {
            case "fulltime", "full", "permanent", "clt", "employee" -> FULL_TIME;
            case "parttime", "part" -> PART_TIME;
            case "contract", "contractor", "freelance", "pj", "b2b" -> CONTRACT;
            case "temporary", "temp", "seasonal" -> TEMPORARY;
            case "intern", "internship", "trainee", "apprenticeship" -> INTERNSHIP;
            case "volunteer" -> VOLUNTEER;
            case "other" -> OTHER;
            default -> null;
        };
    }
}
