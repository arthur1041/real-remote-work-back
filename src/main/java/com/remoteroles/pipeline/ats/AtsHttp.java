package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remoteroles.pipeline.config.IngestProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;

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
                // Redirects are handled by hand so the destination can be checked
                // against the allowlist. Following them automatically means the
                // request has already been sent before anything can object.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Hosts this crawler is permitted to contact.
     *
     * <p>Board URLs are built by substituting a token from the database into a
     * template, so the shape of that token decides where the request goes. Today
     * tokens only reach here from a hand-written migration, because
     * {@code findActive} filters to {@code source_kind = 'ATS'} — but that is an
     * implicit guarantee sitting in a different file, and the row next door holds
     * a company name harvested from a third-party feed.
     *
     * <p>So the guarantee is made explicit here instead: whatever the token, the
     * request has to land on one of these hosts.
     */
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "boards-api.greenhouse.io",
            "api.lever.co",
            "api.ashbyhq.com",
            "himalayas.app",
            "weworkremotely.com");

    /** Everything a sane board slug is made of, and nothing that can leave the path. */
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    /**
     * Rejects a token that could steer the request somewhere else.
     *
     * <p>A slash, a colon or an {@code @} in a token can escape the path segment or
     * rewrite the authority entirely. Callers validate before building a URL.
     */
    public static boolean isSafeToken(String token) {
        return token != null && SAFE_TOKEN.matcher(token).matches();
    }

    /** Throws unless the URL points at a host this crawler is allowed to contact. */
    private static URI requireAllowed(String url) throws URISyntaxException {
        URI uri = new URI(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();

        if (!"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("refusing non-https URL: " + url);
        }
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("refusing host outside the allowlist: " + host);
        }
        return uri;
    }

    /** GETs {@code url} and parses the body as JSON. */
    public JsonNode getJson(String url) throws Exception {
        URI uri = requireAllowed(url);
        HttpResponse<String> response = null;

        // At most one hop, and the destination is re-checked. A board redirecting
        // somewhere interesting is not a reason to follow it there.
        for (int hop = 0; hop <= 1; hop++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", props.userAgent())
                    .header("Accept", "application/json")
                    .timeout(props.requestTimeout())
                    .GET()
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code != 301 && code != 302 && code != 307 && code != 308) {
                break;
            }
            String location = response.headers().firstValue("location").orElse(null);
            if (location == null) {
                break;
            }
            uri = requireAllowed(uri.resolve(location).toString());
        }

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
