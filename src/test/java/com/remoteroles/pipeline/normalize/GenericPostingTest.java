package com.remoteroles.pipeline.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expensive mistake here is the false positive: a pattern that hides a real
 * job is worse than one that lets a talent pool through, because nobody ever
 * finds out. Every rejection below is a title taken off the live board.
 */
class GenericPostingTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Talent Pool",
            "Design Talent Pool",
            "[Talent Pool] Business Development Specialist",
            "Senior Product Builder - Talent Pool",
            "Chief Executive Officer [B2B SaaS] - Talent Pool",
            "Account Executive Talent Pool EMEA",
            "Talent Pipeline",
            "Join our Talent Community!",
            "Your Chance to Join Our Talent Community!",
            "Express Your Interest / Join Our Talent Community",
            "General Application",
            "Can't find a role for you? Submit a general application.",
            "Future Opportunities",
            "Future Opportunities in Sales",
            "Future Opportunities: Early Career Sales Talent",
            "Expression of Interest: SMB & Commercial Account Executive",
            "Apply to NISC's Sales & Marketing Teams",
            "Apply to NISC's Member Support Division",
    })
    @DisplayName("funnels are recognised")
    void detectsFunnels(String title) {
        assertTrue(GenericPosting.isNotARole(title), title);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // "pipeline" on its own is an engineering word, not a funnel.
            "Product Manager, Detection Pipeline",
            "Account Executive, Product Sales (Data Pipeline)",
            "Pipeline Programs Manager",
            "Senior Data Engineer – AWS Data Lake & Pipeline Architecture",
            "Software Engineer, Stripe Data Pipeline",
            // "community" and "join" are ordinary advert words.
            "Global Community Lead",
            "Community Manager, Developer Relations",
            "Join our Engineering team as a Backend Engineer",
            // Ordinary roles that brush the vocabulary.
            "Talent Acquisition Partner",
            "Head of Talent",
            "Senior Recruiter, Technical Talent",
            "Application Security Engineer",
            "Solutions Architect, Applications",
            "Interest Rate Derivatives Analyst",
            "Senior Software Engineer",
    })
    @DisplayName("real jobs are left alone")
    void keepsRealJobs(String title) {
        assertFalse(GenericPosting.isNotARole(title), title);
    }
}
