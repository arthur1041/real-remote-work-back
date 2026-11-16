package com.remoteroles.pipeline.normalize;

/**
 * Cutting text to a length without cutting a character in half.
 *
 * <p><strong>One copy, where there were two.</strong> {@code cut} stood in
 * {@code SemanticIndexer} and again in {@code ApplicationScreener}, written
 * the same way both times, and both were wrong the same way.
 *
 * <p>{@code substring} counts UTF-16 code units. Everything outside the Basic
 * Multilingual Plane — every emoji, the rarer CJK characters — is two of them,
 * and a cut landing between the two leaves an unpaired surrogate.
 *
 * <p><strong>What that does here, measured on 16 Nov 2026 against the running
 * database.</strong> The driver does not refuse it: PgJDBC writes the unpaired
 * surrogate as a literal question mark. So a model's sentence cut at 300
 * characters reaches the employer's screen ending in "?", which reads as
 * punctuation. A summary that says "Three years of Kubernetes?" is doubt the
 * model never expressed, and nothing in the row says the character was ever
 * anything else. The web side of this fault left a visible black diamond;
 * this one leaves something that looks deliberate, which is worse.
 *
 * <p>Pairs only, like {@code lib/text.ts} on the other side: a combining mark
 * separated from its letter is a readable character, and joining those is a
 * grapheme segmenter rather than a bounds check.
 */
public final class Text {

    private Text() {}

    public static String cut(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        // Character.isHighSurrogate on the last kept unit: its pair is on the
        // other side of the cut, so drop it with the rest.
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }
}
