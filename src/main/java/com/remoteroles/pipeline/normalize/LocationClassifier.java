package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Classification;
import com.remoteroles.pipeline.domain.FetchedPosting;

/**
 * Decides whether a posting is remote and how geographically open it is.
 *
 * <p>An interface with one method on purpose. The rule-based implementation is
 * cheap, deterministic and good at the 80% of strings that are formulaic; an
 * LLM-backed implementation handles the tail. Because every row records which
 * classifier produced it, the two can run side by side and be compared on real
 * data instead of argued about.
 */
public interface LocationClassifier {

    Classification classify(FetchedPosting posting);
}
