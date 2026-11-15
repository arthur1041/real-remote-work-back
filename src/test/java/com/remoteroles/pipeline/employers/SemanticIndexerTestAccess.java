package com.remoteroles.pipeline.employers;

import java.lang.reflect.Field;

/**
 * Reads the two prompts out of {@link SemanticIndexer} for the test.
 *
 * <p>They are private constants and should stay private: nothing in the
 * product has any business reading them. Reflection here rather than widening
 * the field, because a test is not a reason to change what the class offers.
 */
final class SemanticIndexerTestAccess {

    private SemanticIndexerTestAccess() {}

    static String candidatePrompt() {
        return constant("CANDIDATE_PROMPT");
    }

    static String postingPrompt() {
        return constant("POSTING_PROMPT");
    }

    private static String constant(String name) {
        try {
            Field field = SemanticIndexer.class.getDeclaredField(name);
            field.setAccessible(true);
            return (String) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "SemanticIndexer." + name + " is gone or renamed; the prompt test"
                    + " exists because the rules in it are the ones keeping personal"
                    + " data out of an embedding.", e);
        }
    }
}
