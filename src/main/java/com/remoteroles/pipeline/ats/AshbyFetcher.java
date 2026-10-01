package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchedPosting;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Ashby boards.
 *
 * <p>The cleanest of the three: an explicit {@code isRemote} boolean, a
 * pre-rendered {@code descriptionHtml}, and location strings that are already
 * shaped like {@code "Remote (EMEA)"} -- which the region classifier reads directly.
 *
 * <p>Two things to respect. {@code isListed=false} marks postings the company has
 * deliberately unpublished, and republishing those would be both wrong and rude.
 * And Ashby serves the entire board in one response with no pagination, which for
 * a large employer means a payload in the tens of megabytes.
 */
@Component
public class AshbyFetcher implements AtsFetcher {

    private static final String URL =
            "https://api.ashbyhq.com/posting-api/job-board/%s?includeCompensation=true";

    private final AtsHttp http;

    public AshbyFetcher(AtsHttp http) {
        this.http = http;
    }

    @Override
    public AtsType supports() {
        return AtsType.ASHBY;
    }

    @Override
    public List<FetchedPosting> fetch(Company company) throws Exception {
        JsonNode root = http.getJson(URL.formatted(company.atsToken()));
        JsonNode jobs = root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            return List.of();
        }

        List<FetchedPosting> out = new ArrayList<>(jobs.size());
        for (JsonNode job : jobs) {
            if (Boolean.FALSE.equals(Json.bool(job, "isListed"))) {
                continue; // unpublished by the company; not ours to surface
            }

            String externalId = Json.text(job, "id");
            String title = Json.text(job, "title");
            String applyUrl = Json.text(job, "jobUrl");
            if (applyUrl == null) {
                applyUrl = Json.text(job, "applyUrl");
            }
            if (externalId == null || title == null || applyUrl == null) {
                continue;
            }

            out.add(new FetchedPosting(
                    externalId,
                    title,
                    applyUrl,
                    location(job),
                    Json.text(job, "descriptionHtml"),
                    Json.instant(job, "publishedAt"),
                    Json.bool(job, "isRemote"),
                    Json.text(job, "department"),
                    Json.text(job, "employmentType"),
                    job.toString()
            ));
        }
        return out;
    }

    /** Folds secondary locations in, so multi-region roles classify on the full picture. */
    private static String location(JsonNode job) {
        String primary = Json.text(job, "location");
        JsonNode secondary = job.get("secondaryLocations");
        if (secondary == null || !secondary.isArray() || secondary.isEmpty()) {
            return primary;
        }

        List<String> parts = new ArrayList<>();
        if (primary != null) {
            parts.add(primary);
        }
        for (JsonNode node : secondary) {
            // Entries appear both as bare strings and as {"location": "..."} objects.
            String value = node.isTextual() ? node.asText() : Json.text(node, "location");
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }
}
