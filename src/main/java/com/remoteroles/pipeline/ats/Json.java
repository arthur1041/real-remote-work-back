package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Null-tolerant readers for third-party JSON, which is never as tidy as documented. */
final class Json {

    private Json() {
    }

    /** Returns the text at {@code field}, or null for missing, explicit-null, or blank. */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Follows a path of field names, stopping at the first missing link. */
    static String textAt(JsonNode node, String... path) {
        JsonNode cur = node;
        for (int i = 0; i < path.length - 1; i++) {
            if (cur == null) {
                return null;
            }
            cur = cur.get(path[i]);
        }
        return text(cur, path[path.length - 1]);
    }

    /** Reads an ISO-8601 timestamp, tolerating offsets and trailing Z. */
    static Instant instant(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    /** Reads epoch milliseconds, which Lever uses for {@code createdAt}. */
    static Instant epochMillis(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || !v.isNumber()) {
            return null;
        }
        return Instant.ofEpochMilli(v.asLong());
    }

    static Boolean bool(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return (v == null || !v.isBoolean()) ? null : v.asBoolean();
    }
}
