package com.remoteroles.pipeline.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cutting at a length, not at a code unit.
 *
 * <p>The case that sent this looking: 299 letters and one emoji, cut at 300,
 * left an unpaired surrogate — and PgJDBC writes one as a literal question
 * mark, measured against the running database on 16 Nov 2026. A model's
 * sentence then reaches the employer ending in "?", which reads as doubt the
 * model never expressed.
 */
class TextCutTest {

    @Test
    @DisplayName("a pair split by the cut is dropped whole")
    void pairNotHalved() {
        String value = "a".repeat(299) + "😀";
        String cut = Text.cut(value, 300);
        assertEquals(299, cut.length());
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
    }

    @Test
    @DisplayName("a pair that fits is kept whole")
    void pairKept() {
        String value = "a".repeat(298) + "😀";
        assertEquals(value, Text.cut(value, 300));
    }

    @Test
    @DisplayName("text at or under the length is untouched")
    void untouched() {
        assertEquals("abc", Text.cut("abc", 300));
        assertEquals("abcde", Text.cut("abcde", 5));
        assertEquals("", Text.cut("", 5));
    }

    @Test
    @DisplayName("null is empty, as every caller already assumed")
    void nullIsEmpty() {
        assertEquals("", Text.cut(null, 10));
    }

    @Test
    @DisplayName("plain text is cut where asked")
    void plainCut() {
        assertEquals("abc", Text.cut("abcdef", 3));
    }
}
