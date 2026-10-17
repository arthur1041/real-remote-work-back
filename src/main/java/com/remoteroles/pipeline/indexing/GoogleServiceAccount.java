package com.remoteroles.pipeline.indexing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An access token for a Google service account, signed locally.
 *
 * <p>Written by hand rather than pulled in with Google's auth library, which
 * arrives with a transitive tree far larger than the thirty lines it would save.
 * The whole exchange is one RS256 JWT and one form post, and the JDK has both
 * the crypto and the HTTP client.
 *
 * <p>Tokens are cached until shortly before they expire. Google issues them for
 * an hour, and fetching a new one per call would turn every notification into
 * two round trips.
 */
public class GoogleServiceAccount {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    /** Refresh a minute early, so a token never expires mid-request. */
    private static final Duration EARLY = Duration.ofMinutes(1);

    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String scope;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private String cachedToken;
    private Instant cachedUntil = Instant.EPOCH;

    public GoogleServiceAccount(Path keyFile, String scope) throws IOException {
        JsonNode key = json.readTree(Files.readString(keyFile));
        this.clientEmail = key.path("client_email").asText();
        this.privateKey = parsePrivateKey(key.path("private_key").asText());
        this.scope = scope;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** The service account's own address, which has to be an owner in Search Console. */
    public String clientEmail() {
        return clientEmail;
    }

    public synchronized String accessToken() throws IOException, InterruptedException {
        if (cachedToken != null && Instant.now().isBefore(cachedUntil)) {
            return cachedToken;
        }

        String assertion = signedAssertion();
        String form = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + assertion;

        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(TOKEN_URI))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("token request failed: " + response.statusCode());
        }

        JsonNode body = json.readTree(response.body());
        cachedToken = body.path("access_token").asText();
        cachedUntil = Instant.now().plusSeconds(body.path("expires_in").asLong(3600)).minus(EARLY);
        return cachedToken;
    }

    private String signedAssertion() throws IOException {
        long now = Instant.now().getEpochSecond();
        String header = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = encode("""
                {"iss":"%s","scope":"%s","aud":"%s","iat":%d,"exp":%d}"""
                .formatted(clientEmail, scope, TOKEN_URI, now, now + 3600));

        String signingInput = header + "." + claims;
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(privateKey);
            rsa.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signingInput + "." + base64Url(rsa.sign());
        } catch (Exception e) {
            throw new IOException("could not sign the assertion", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) throws IOException {
        String body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            throw new IOException("the service account key could not be read", e);
        }
    }

    private static String encode(String value) {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
