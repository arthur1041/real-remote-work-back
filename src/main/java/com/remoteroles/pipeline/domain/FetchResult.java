package com.remoteroles.pipeline.domain;

import java.util.List;

/**
 * The outcome of polling one board.
 *
 * <p>Sealed so that every call site is forced to handle failure explicitly. That
 * matters more than it looks: the expiry sweep must run on {@link Success} only.
 * Treating a failed fetch as "this board returned zero jobs" would close every
 * posting a company has the first time their ATS returns a 503.
 */
public sealed interface FetchResult {

    record Success(Company company, List<FetchedPosting> postings) implements FetchResult {}

    record Failure(Company company, String reason, Throwable cause) implements FetchResult {}
}
