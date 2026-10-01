package com.remoteroles.pipeline.normalize;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Content hashing and cross-source identity. */
public final class Hashing {

    private Hashing() {
    }

    /**
     * Fingerprints the fields a user would notice changing.
     *
     * <p>Deliberately excludes timestamps. Several ATS bump {@code updated_at} on
     * every internal edit, so hashing it would make every posting look modified on
     * every run, defeating the raw layer's version dedupe and the "has this changed"
     * check that keeps re-classification cheap.
     */
    public static String contentHash(String title, String location, String applyUrl, String description) {
        return sha256(String.join("\u0000",
                nullSafe(title), nullSafe(location), nullSafe(applyUrl), nullSafe(description)));
    }

    /**
     * Identity for the same role arriving from different sources.
     *
     * <p>Keyed on company domain rather than ATS slug, because a slug is only
     * unique within one ATS -- {@code neon} resolves on two different vendors and
     * need not be the same company.
     */
    public static String dedupeKey(String companyDomain, String companyName, String title) {
        String anchor = companyDomain != null ? companyDomain : nullSafe(companyName);
        return sha256(normalizeForIdentity(anchor) + "\u0000" + normalizeForIdentity(title));
    }

    /**
     * Strips the decoration that differs between postings of the same role:
     * trailing location suffixes, req IDs, punctuation, casing.
     */
    static String normalizeForIdentity(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT)
                // "Senior Engineer (Remote, US)" and "Senior Engineer - Remote" are one role.
                .replaceAll("\\s*[\\(\\[][^)\\]]*[\\)\\]]\\s*$", " ")
                .replaceAll("\\s+[-\u2013\u2014]\\s+(remote|hybrid|on-?site).*$", " ")
                .replaceAll("\\s*#\\s*\\w+\\s*$", " ")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return s;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
