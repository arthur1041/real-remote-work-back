package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.remoteroles.pipeline.domain.AtsType;
import com.remoteroles.pipeline.domain.Company;
import com.remoteroles.pipeline.domain.FetchedPosting;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lever boards.
 *
 * <p>The response is a bare JSON array, not an object with a {@code jobs} key.
 *
 * <p>Lever does carry an explicit {@code workplaceType}, which is worth far more
 * than any amount of text inference: when it says {@code remote} we know, and
 * when it says {@code onsite} or {@code hybrid} we can reject the posting
 * outright no matter how often the word "remote" appears in the description.
 */
@Component
public class LeverFetcher implements AtsFetcher {

    private static final String URL = "https://api.lever.co/v0/postings/%s?mode=json";

    private final AtsHttp http;

    public LeverFetcher(AtsHttp http) {
        this.http = http;
    }

    @Override
    public AtsType supports() {
        return AtsType.LEVER;
    }

    @Override
    public List<FetchedPosting> fetch(Company company) throws Exception {
        JsonNode root = http.getJson(URL.formatted(company.atsToken()));
        if (!root.isArray()) {
            return List.of();
        }

        List<FetchedPosting> out = new ArrayList<>(root.size());
        for (JsonNode job : root) {
            String externalId = Json.text(job, "id");
            String title = Json.text(job, "text"); // Lever calls the title "text"
            String applyUrl = Json.text(job, "hostedUrl");
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
                    Json.text(job, "description"),
                    Json.epochMillis(job, "createdAt"),
                    remoteHint(job),
                    Json.textAt(job, "categories", "team"),
                    Json.textAt(job, "categories", "commitment"),
                    job.toString()
            ));
        }
        return out;
    }

    /**
     * Prefers the full location list: a role open in "Lisbon, Berlin, Remote" is a
     * very different proposition from one whose primary location happens to be Lisbon.
     */
    private static String location(JsonNode job) {
        JsonNode categories = job.get("categories");
        if (categories == null) {
            return null;
        }
        JsonNode all = categories.get("allLocations");
        if (all != null && all.isArray() && !all.isEmpty()) {
            List<String> parts = new ArrayList<>(all.size());
            all.forEach(n -> {
                if (!n.isNull() && !n.asText().isBlank()) {
                    parts.add(n.asText().trim());
                }
            });
            if (!parts.isEmpty()) {
                return String.join(", ", parts);
            }
        }
        return Json.text(categories, "location");
    }

    private static Boolean remoteHint(JsonNode job) {
        String workplaceType = Json.text(job, "workplaceType");
        if (workplaceType == null) {
            return null;
        }
        return switch (workplaceType.toLowerCase()) {
            case "remote" -> Boolean.TRUE;
            case "onsite", "on-site", "hybrid" -> Boolean.FALSE;
            default -> null;
        };
    }
}
