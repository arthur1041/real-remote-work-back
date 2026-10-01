package com.remoteroles.pipeline.ats;

import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.FetchedPosting;

import java.util.List;

/**
 * Reads postings from an aggregator feed rather than from one company's board.
 *
 * <p>Differs from {@link AtsFetcher} in two ways that matter downstream. It is not
 * scoped to a company, so each posting carries its own employer name; and it is
 * paginated and may be crawled partially, so a posting's absence from a run means
 * nothing and the expiry sweep must not treat it as closed.
 *
 * <p>Implementations return only postings with no geographic restriction. That
 * filter belongs here rather than later: these feeds are overwhelmingly
 * country-gated — about 99 postings in 100 — and the country-gated ones are
 * already covered, with better data, by the ATS boards.
 */
public interface FeedFetcher {

    AtsType source();

    List<FetchedPosting> fetch() throws Exception;
}
