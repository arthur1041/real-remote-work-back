package com.remoteroles.pipeline.ats;

import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchedPosting;

import java.util.List;

/**
 * Reads every open posting from one company's board.
 *
 * <p>Implementations translate a vendor's JSON into {@link FetchedPosting} and do
 * nothing else: no classification, no persistence. Keeping them this thin is what
 * lets a new ATS be added in one file.
 */
public interface AtsFetcher {

    AtsType supports();

    List<FetchedPosting> fetch(Company company) throws Exception;
}
