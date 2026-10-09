package com.remoteroles.pipeline.normalize;

import com.remoteroles.pipeline.domain.Classification;
import com.remoteroles.pipeline.domain.FetchedPosting;
import com.remoteroles.pipeline.domain.GeoScope;
import com.remoteroles.pipeline.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/**
 * Re-runs the scope decision over listings already stored.
 *
 * <p>{@code Classification.classifiedBy} exists so that a better classifier can be
 * replayed over the rows it beats, and until now the only way to do that was a full
 * ingest -- sixty third-party APIs, plus a Himalayas crawl that is rate-limited to
 * a budget and resumes from a cursor. When the change is a rule rather than a
 * source, every one of those requests is wasted, and the wrong badges stay up until
 * the crawl comes round again.
 *
 * <p>Nothing is fetched here. The classifier reads a title and a location string,
 * and both are on the row.
 */
@Service
public class ScopeRechecker {

    private static final Logger log = LoggerFactory.getLogger(ScopeRechecker.class);

    private final JobRepository jobs;
    private final RuleBasedLocationClassifier classifier;

    public ScopeRechecker(JobRepository jobs, RuleBasedLocationClassifier classifier) {
        this.jobs = jobs;
        this.classifier = classifier;
    }

    /**
     * What the recheck did.
     *
     * @param moves scope it moved to, counted, so the result can be read at a glance
     *              rather than inferred from a total.
     */
    public record Result(int checked, int changed, Map<String, Integer> moves) {
    }

    /** Rechecks every open WORLDWIDE listing and corrects the ones that no longer earn it. */
    public Result recheckWorldwide() {
        List<JobRepository.ScopeCandidate> candidates = jobs.worldwideForRecheck();
        Map<String, Integer> moves = new LinkedHashMap<>();
        int changed = 0;

        for (JobRepository.ScopeCandidate candidate : candidates) {
            // The hint is true because every candidate is already an open remote
            // listing; see JobRepository.worldwideForRecheck for why that matters.
            Classification verdict = classifier.classify(FetchedPosting.ats(
                    String.valueOf(candidate.id()), candidate.title(), "",
                    candidate.locationRaw(), null, null, Boolean.TRUE, null, null, "{}"));

            if (verdict.geoScope() == GeoScope.WORLDWIDE) {
                // Still worldwide, but the verdict can differ in its detail: the
                // timezone reader learned to keep both ends of a band, and five
                // roles reading "Anywhere (UTC-10 to UTC+14)" were stored as
                // "UTC+14" -- the far edge of the planet where the posting said
                // the whole of it. Writing only on a scope change left those
                // uncorrected, so anything that moved gets written.
                if (!Objects.equals(verdict.timezoneRequirement(), candidate.timezoneRequirement())) {
                    changed += jobs.updateScope(candidate.id(), verdict);
                    moves.merge("TIMEZONE_CORRECTED", 1, Integer::sum);
                }
                continue;
            }
            // A re-run that says "not remote at all" is not something to act on from
            // a location string alone: the row was ingested as remote on evidence
            // this method does not have. Left alone and counted, not written.
            if (!verdict.isRemote() || verdict.geoScope() == null) {
                moves.merge("SKIPPED_NOT_REMOTE", 1, Integer::sum);
                continue;
            }

            changed += jobs.updateScope(candidate.id(), verdict);
            moves.merge(verdict.geoScope().name(), 1, Integer::sum);
            log.info("recheck: job {} {} -> {} ({})",
                    candidate.id(), candidate.geoScope(), verdict.geoScope(), candidate.locationRaw());
        }
        return new Result(candidates.size(), changed, moves);
    }
}
