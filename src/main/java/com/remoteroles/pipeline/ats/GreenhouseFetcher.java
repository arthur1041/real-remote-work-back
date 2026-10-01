package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchedPosting;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Greenhouse boards.
 *
 * <p>{@code content=true} is required or descriptions come back absent entirely.
 *
 * <p>Greenhouse tells us nothing about remoteness -- there is no workplace-type
 * field -- so {@code remoteHint} is always null here and the classifier has to
 * work from {@code location.name} text such as {@code "Remote "}. Greenhouse is
 * also the largest source by volume, which is precisely why the text classifier
 * has to be good.
 */
@Component
public class GreenhouseFetcher implements AtsFetcher {

    private static final String URL = "https://boards-api.greenhouse.io/v1/boards/%s/jobs?content=true";

    private final AtsHttp http;

    public GreenhouseFetcher(AtsHttp http) {
        this.http = http;
    }

    @Override
    public AtsType supports() {
        return AtsType.GREENHOUSE;
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
            String externalId = Json.text(job, "id");
            String title = Json.text(job, "title");
            String applyUrl = Json.text(job, "absolute_url");
            if (externalId == null || title == null || applyUrl == null) {
                continue; // nothing usable without these three
            }

            // Greenhouse double-encodes the description: the JSON string contains
            // "&lt;p&gt;" rather than "<p>". Skip this and every listing renders
            // as visible tag soup.
            String description = Json.text(job, "content");
            if (description != null) {
                description = StringEscapeUtils.unescapeHtml4(description);
            }

            // first_published is when the role went live; updated_at moves on every
            // edit and would make old roles look new.
            var postedAt = Json.instant(job, "first_published");
            if (postedAt == null) {
                postedAt = Json.instant(job, "updated_at");
            }

            out.add(FetchedPosting.ats(
                    externalId,
                    title,
                    applyUrl,
                    Json.textAt(job, "location", "name"),
                    description,
                    postedAt,
                    null,
                    firstDepartment(job),
                    null,
                    job.toString()
            ));
        }
        return out;
    }

    private static String firstDepartment(JsonNode job) {
        JsonNode departments = job.get("departments");
        if (departments != null && departments.isArray() && !departments.isEmpty()) {
            return Json.text(departments.get(0), "name");
        }
        return null;
    }
}
