package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.RoleCategory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sorts a posting into one role family, from its title and the employer's own
 * department label.
 *
 * <p>Order of evaluation is the whole design. The rules are checked most-specific
 * first because the broad ones would otherwise swallow everything: "Security
 * Engineer" is security before it is engineering, "Data Engineer" is data, and
 * "Technical Support Engineer" is support. {@code ENGINEERING} is evaluated last
 * precisely because "engineer" appears in 1,002 of 2,500 titles and would win every
 * contest it entered.
 */
@Component
public class RoleCategorizer {

    /**
     * Ordered rules. The first whose keywords appear in title-then-department wins.
     *
     * <p>Judgment calls worth naming: pre-sales titles ("Solutions Engineer",
     * "Customer Engineer") are filed under {@code SALES}, because the work is
     * revenue work whatever the title says. {@code SECURITY} is separate from
     * engineering rather than folded in, since it is a distinct job market with its
     * own searchers.
     */
    private static final List<Map.Entry<RoleCategory, List<String>>> RULES = List.of(
            Map.entry(RoleCategory.SECURITY, List.of(
                    "security", "appsec", "infosec", "trust and safety", "trust & safety",
                    "compliance", "grc", "penetration test")),

            // Before DATA_AI so "Director of FP&A and Analytics" reads as finance.
            Map.entry(RoleCategory.FINANCE_LEGAL, List.of(
                    "fp&a", "finance", "financial", "accounting", "accountant", "controller",
                    "treasury", "tax", "payroll", "audit", "legal", "counsel", "paralegal",
                    "contracts")),

            Map.entry(RoleCategory.PEOPLE, List.of(
                    "recruit", "sourcer", "talent", "people partner", "people operations",
                    "human resources", "hris", "compensation and benefits", "onboarding specialist")),

            Map.entry(RoleCategory.DESIGN, List.of(
                    "designer", "design", "ux", "ui ", "user experience", "user research",
                    "brand studio", "illustrator", "creative director")),

            Map.entry(RoleCategory.DATA_AI, List.of(
                    "data scientist", "data engineer", "data analyst", "analytics engineer",
                    "machine learning", "deep learning", "applied ai", "applied scientist",
                    "research scientist", "research engineer", "ai engineer", "mlops",
                    "data platform", "analytics", "statistician")),

            Map.entry(RoleCategory.SUPPORT, List.of(
                    "support", "customer success", "customer experience", "customer care",
                    "technical account manager", "user operations", "community manager",
                    "helpdesk", "service desk")),

            Map.entry(RoleCategory.PRODUCT, List.of(
                    "product manager", "product management", "product owner", "product lead",
                    "group product", "technical product", "product operations")),

            Map.entry(RoleCategory.SALES, List.of(
                    "account executive", "account manager", "sales", "business development",
                    "revenue", "solutions engineer", "solution engineer", "sales engineer",
                    "pre-sales", "presales", "customer engineer", "partner manager",
                    "partner development", "partnerships", "channel", "renewals",
                    "quota", "territory",
                    // Entry-level revenue titles. "representative" is safe this late:
                    // SUPPORT is evaluated first, so customer reps are already claimed.
                    "representative", "sales development", "sdr", "bdr",
                    "account development")),

            Map.entry(RoleCategory.MARKETING, List.of(
                    "marketing", "growth", "demand generation", "content strategist",
                    "content writer", "copywriter", "seo", "social media", "brand",
                    "communications", "public relations", "events", "campaign")),

            Map.entry(RoleCategory.OPERATIONS, List.of(
                    "operations", "program manager", "project manager", "chief of staff",
                    "business operations", "strategy", "procurement", "logistics",
                    "office manager", "executive assistant")),

            // Last: "engineer" appears in 40% of titles and would win everything.
            Map.entry(RoleCategory.ENGINEERING, List.of(
                    "engineer", "engineering", "developer", "software", "programmer",
                    "architect", "sre", "devops", "infrastructure", "platform", "backend",
                    "frontend", "front-end", "back-end", "full stack", "fullstack",
                    "mobile", "ios", "android", "qa", "test automation", "technical writer"))
    );

    public RoleCategory categorize(String title, String department) {
        String haystack = normalize(title);
        String fallback = normalize(department);

        // Title first across every rule, then department across every rule. A
        // sales-sounding department should not outrank an explicit engineering title.
        for (var rule : RULES) {
            if (matchesAny(haystack, rule.getValue())) {
                return rule.getKey();
            }
        }
        for (var rule : RULES) {
            if (matchesAny(fallback, rule.getValue())) {
                return rule.getKey();
            }
        }
        return RoleCategory.OTHER;
    }

    private static boolean matchesAny(String haystack, List<String> keywords) {
        if (haystack.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Pads with spaces so a keyword written as {@code "ui "} can anchor on a boundary. */
    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return " " + raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim() + " ";
    }
}
