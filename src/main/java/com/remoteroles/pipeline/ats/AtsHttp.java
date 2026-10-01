package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remoteroles.pipeline.config.IngestProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared JSON-over-HTTP access for the ATS fetchers.
 *
 * <p>One {@link HttpClient} for the whole application: it is thread-safe, pools
 * connections, and under virtual threads a blocking {@code send} is exactly the
 * right call -- the carrier thread is released while we wait, so a few thousand
 * concurrent fetches cost a few thousand cheap stack frames rather than an
 * {@code ExecutorService} tuned by guesswork.
 */
@Component
public class AtsHttp {

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final IngestProperties props;

    public AtsHttp(IngestProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** GETs {@code url} and parses the body as JSON. */
    public JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", props.userAgent())
                .header("Accept", "application/json")
                .timeout(props.requestTimeout())
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();

        if (status == 404) {
            // The board moved or was taken down. Distinct from a transient fault:
            // retrying will not help, so say so plainly.
            throw new BoardGoneException("board not found (404): " + url);
        }
        if (status == 429 || status == 503) {
            // Distinct from a generic failure: the caller must stop, not retry. A
            // 429 here arrives as a Cloudflare challenge page, and answering a bot
            // challenge is not something we do -- the only correct response is to
            // back off and come back later.
            throw new RateLimitedException("rate limited (HTTP " + status + ") by " + url);
        }
        if (status != 200) {
            throw new IllegalStateException("HTTP " + status + " from " + url);
        }
        return mapper.readTree(response.body());
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    /** Thrown when the host is asking us to slow down or stop. */
    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    /** Thrown when a board endpoint is permanently gone rather than briefly failing. */
    public static class BoardGoneException extends RuntimeException {
        public BoardGoneException(String message) {
            super(message);
        }
    }
}
