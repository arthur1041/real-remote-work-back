package com.remoteroles.pipeline.employers;

/**
 * How a list of verdicts becomes a number out of ten.
 *
 * <p><strong>Pulled out of the two readers because it is one rule, and it was
 * written twice.</strong> `ApplicationScreener` scores an application against
 * a role's requirements and `DiscoveryRanker` scores a profile against the
 * same requirements; the arithmetic was copied from the first into the second,
 * comment and all, which is two places for it to drift and one of them nobody
 * would be looking at.
 *
 * <p>Three decisions live here, and each costs somebody something if it
 * changes:
 *
 * <ul>
 *   <li><strong>An essential requirement weighs twice a nice-to-have.</strong>
 *   Not ten times: a role is more than its one hard line, and a scale where a
 *   single UNMET essential zeroes everything turns the score into a yes/no
 *   with extra steps.</li>
 *   <li><strong>UNKNOWN is worth half, never zero.</strong> Scoring silence as
 *   failure is treating UNKNOWN as UNMET by a quieter route — in the number,
 *   where nobody sees it — and it punishes the candidate for what a summary
 *   did not happen to say.</li>
 *   <li><strong>A contradicted essential caps the score at 4.</strong> Costing
 *   only its own weight is not enough: somebody who cannot do the one thing
 *   the role requires would still outrank somebody who can by being strong on
 *   everything else, and the ranking would stop meaning what the employer
 *   reads it to mean.</li>
 * </ul>
 */
final class FitScore {

    private FitScore() {}

    /** What a contradicted essential caps the score at. */
    static final double CAP_WITH_ESSENTIAL_MISSED = 4.0;

    static double weight(boolean essential) {
        return essential ? 2 : 1;
    }

    /** What one verdict adds to the total. */
    static double credit(String verdict, boolean essential) {
        double weight = weight(essential);
        if ("MET".equals(verdict)) return weight;
        if ("UNKNOWN".equals(verdict)) return weight / 2;
        return 0;
    }

    /** Whether this verdict is the kind that caps the whole score. */
    static boolean capsTheScore(String verdict, boolean essential) {
        return essential && "UNMET".equals(verdict);
    }

    /**
     * The score as it is stored: out of ten, one decimal, capped if need be.
     *
     * <p>No requirements at all is 0 rather than 10. A role nobody wrote
     * requirements for has not been answered by anybody, and dividing by zero
     * into a perfect score would put every profile at the top of a list its
     * employer never defined.
     */
    static double outOfTen(double points, double possible, boolean essentialMissed) {
        double score = possible == 0 ? 0 : (points / possible) * 10;
        if (essentialMissed) score = Math.min(score, CAP_WITH_ESSENTIAL_MISSED);
        return Math.round(score * 10) / 10.0;
    }
}
