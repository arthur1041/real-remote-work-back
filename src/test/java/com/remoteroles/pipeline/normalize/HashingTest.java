package com.remoteroles.pipeline.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HashingTest {

    @Test
    @DisplayName("the same posting hashes the same way twice")
    void stable() {
        assertEquals(
                Hashing.contentHash("Engineer", "Remote", "https://a.test", "<p>hi</p>"),
                Hashing.contentHash("Engineer", "Remote", "https://a.test", "<p>hi</p>"));
    }

    @Test
    @DisplayName("an edited description produces a new hash")
    void sensitiveToContent() {
        assertNotEquals(
                Hashing.contentHash("Engineer", "Remote", "https://a.test", "<p>hi</p>"),
                Hashing.contentHash("Engineer", "Remote", "https://a.test", "<p>bye</p>"));
    }

    @Test
    @DisplayName("field boundaries cannot be forged by shifting text between fields")
    void fieldsAreDelimited() {
        assertNotEquals(
                Hashing.contentHash("ab", "c", null, null),
                Hashing.contentHash("a", "bc", null, null));
    }

    @Test
    @DisplayName("decoration that differs between repostings is stripped for identity")
    void identityIgnoresDecoration() {
        String a = Hashing.dedupeKey("acme.com", "Acme", "Senior Engineer (Remote, US)");
        String b = Hashing.dedupeKey("acme.com", "Acme", "Senior Engineer - Remote");
        String c = Hashing.dedupeKey("acme.com", "Acme", "Senior Engineer");
        assertEquals(a, c);
        assertEquals(b, c);
    }

    @Test
    @DisplayName("identity is anchored on domain, since a slug is unique only within one ATS")
    void differentCompaniesDoNotCollide() {
        assertNotEquals(
                Hashing.dedupeKey("neon.tech", "Neon", "Engineer"),
                Hashing.dedupeKey("neon-other.com", "Neon", "Engineer"));
    }

    @Test
    void distinctRolesStayDistinct() {
        assertNotEquals(
                Hashing.dedupeKey("acme.com", "Acme", "Senior Engineer"),
                Hashing.dedupeKey("acme.com", "Acme", "Staff Engineer"));
    }
}
