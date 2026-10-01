package com.remoteroles.pipeline.ats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Board tokens are substituted into a URL template, so their shape decides where
 * the request lands. These cover the characters that let a token stop being a
 * path segment, and the host allowlist that catches anything they miss.
 */
class AtsHttpSecurityTest {

    private final AtsHttp http = new AtsHttp(
            new com.remoteroles.pipeline.config.IngestProperties(
                    1, "test", java.time.Duration.ofSeconds(5), 3,
                    new com.remoteroles.pipeline.config.IngestProperties.Schedule(false, ""),
                    new com.remoteroles.pipeline.config.IngestProperties.Feed(1, 1, 0)));

    @ParameterizedTest
    @ValueSource(strings = {"stripe", "cockroachlabs", "scale-ai", "foo.bar", "a_b", "x1"})
    @DisplayName("ordinary board slugs pass")
    void acceptsRealTokens(String token) {
        assertTrue(AtsHttp.isSafeToken(token), token);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "evil.com/x",      // escapes the path segment
            "../../admin",     // traverses
            "a@evil.com",      // rewrites the authority
            "a:b",             // scheme-ish
            "a?x=1",           // appends a query
            "a#frag",
            "a b",
            "//evil.com",
            "%2e%2e%2f",
            "",
    })
    @DisplayName("anything that could steer the request is refused")
    void rejectsSteeringTokens(String token) {
        assertFalse(AtsHttp.isSafeToken(token), token);
    }

    @Test
    void rejectsNullToken() {
        assertFalse(AtsHttp.isSafeToken(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://169.254.169.254/latest/meta-data/",  // cloud metadata
            "https://localhost/admin",
            "https://10.0.0.1/",
            "https://evil.com/boards",
            "http://boards-api.greenhouse.io/v1/boards/x/jobs", // plain http
            "file:///etc/passwd",
    })
    @DisplayName("the allowlist refuses every host that is not a known board API")
    void refusesHostsOutsideAllowlist(String url) {
        // The shape check alone cannot stop these, because they would arrive as a
        // whole URL rather than as a token. This is the layer that does.
        assertThrows(Exception.class, () -> http.getJson(url), url);
    }
}
