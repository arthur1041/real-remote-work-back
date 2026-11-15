package com.remoteroles.pipeline.employers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index the whole of discovery is ordered by.
 *
 * <p>Two things here are worth a test and neither announces itself when it
 * breaks. The vector literal is pasted into SQL rather than bound, so a
 * malformed one is a statement that fails at three in the morning for every
 * candidate at once — and `Float.toString` is the part that decides whether it
 * is well formed. The prompt is the other: what it forbids is what keeps a
 * phone number out of an embedding that similarity can reach, and prompts
 * drift when they are edited by somebody who did not write them.
 */
class SemanticIndexerTest {

    @Test
    @DisplayName("a vector reads as the literal pgvector expects")
    void literal() {
        assertEquals("[1.0,2.5,-0.75]", SemanticIndexer.literal(new float[] {1f, 2.5f, -0.75f}));
        assertEquals("[]", SemanticIndexer.literal(new float[] {}));
    }

    @Test
    @DisplayName("no space, no exponent trouble, nothing a cast would refuse")
    void literalShape() {
        String out = SemanticIndexer.literal(new float[] {0.000012f, 123456.78f, -0f});
        assertFalse(out.contains(" "), "a space in the literal is a cast that fails");
        assertTrue(out.startsWith("[") && out.endsWith("]"));
        // One comma fewer than there are numbers, which is the only way the
        // cast can succeed.
        assertEquals(2, out.chars().filter(c -> c == ',').count());
    }

    @Test
    @DisplayName("what is sent to the model is cut, and null is not a crash")
    void cutting() {
        assertEquals("", SemanticIndexer.cut(null, 10));
        assertEquals("abcde", SemanticIndexer.cut("abcdefgh", 5));
        assertEquals("abc", SemanticIndexer.cut("abc", 5));
    }

    @Test
    @DisplayName("the summary prompt still forbids everything that identifies a person")
    void promptKeepsItsRules() {
        // The summary is embedded, and a vector is reachable by similarity
        // from the employer's side. An address inside one is an address that
        // leaks through a search nobody can audit.
        String prompt = SemanticIndexerTestAccess.candidatePrompt();
        assertTrue(prompt.contains("Never include a name, email address, phone number"));
        assertTrue(prompt.contains("Never invent"));
        assertTrue(prompt.contains("English only"));
        // The exact word the writer checks for. If the prompt stops asking for
        // it, every empty profile is stored as a summary of nothing.
        assertTrue(prompt.contains("NOTHING"));
    }

    @Test
    @DisplayName("the role prompt asks for the work, not the company's self-portrait")
    void postingPromptIsAboutTheWork() {
        String prompt = SemanticIndexerTestAccess.postingPrompt();
        assertTrue(prompt.contains("for a job-matching index"));
        // Named in order to be excluded: half a job description is the
        // company's self-portrait, which matches everybody and therefore
        // nobody. The rule has to stay in the prompt, not out of it.
        assertTrue(prompt.contains("Leave out the company's description of itself"));
        assertTrue(prompt.contains("its benefits"));
        assertTrue(prompt.contains("Never invent a requirement"));
    }
}
