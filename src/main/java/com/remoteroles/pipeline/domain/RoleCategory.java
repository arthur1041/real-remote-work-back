package com.remoteroles.pipeline.domain;

/**
 * A small, closed set of role families.
 *
 * <p>Closed on purpose. These values become URLs, and a URL set that grows every
 * time an employer invents a department name cannot be a stable SEO surface. New
 * members are a deliberate decision, not a side effect of the data.
 */
public enum RoleCategory {
    ENGINEERING,
    DATA_AI,
    DESIGN,
    PRODUCT,
    SECURITY,
    SALES,
    MARKETING,
    SUPPORT,
    OPERATIONS,
    FINANCE_LEGAL,
    PEOPLE,
    OTHER;

    /** URL-safe form: {@code DATA_AI} becomes {@code data-ai}. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }

    public static RoleCategory fromSlug(String slug) {
        if (slug == null) {
            return null;
        }
        try {
            return valueOf(slug.toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
