package com.remoteroles.pipeline.employers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading what the model said about a profile, before it becomes a score.
 *
 * <p>This class ranks strangers against a role for an employer who is paying
 * to see the order. Every refusal tested here is a way a model's bad answer
 * could otherwise arrive on that screen wearing the clothes of a finding —
 * and none of them throws, so without a test the only symptom is a list in the
 * wrong order that looks exactly like a list in the right one.
 *
 * <p>Nothing is sent and nothing is read from a database: the methods under
 * test are pure.
 */
class DiscoveryRankerTest {

    private static final ObjectMapper json = new ObjectMapper();

    private static JsonNode read(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    @DisplayName("a verdict with a basis is taken as given")
    void plainVerdict() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"MET","basis":"Eight years of Go"}]}
                """);
        assertEquals("MET", DiscoveryRanker.verdictFor(root, 1));
    }

    @Test
    @DisplayName("no quote, no claim: a verdict with no basis becomes UNKNOWN")
    void noBasisNoClaim() throws Exception {
        // The answer that arrives with nothing behind it is almost always the
        // one the model inferred, and a claim nobody can check is worse than
        // "I do not know" -- which FitScore is careful to price at half.
        JsonNode met = read("""
                {"verdicts":[{"requirement":1,"verdict":"MET","basis":"  "}]}
                """);
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(met, 1));

        JsonNode unmet = read("""
                {"verdicts":[{"requirement":1,"verdict":"UNMET"}]}
                """);
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(unmet, 1));
    }

    @Test
    @DisplayName("UNKNOWN needs no basis, because it is the absence of a claim")
    void unknownNeedsNothing() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"unknown","basis":""}]}
                """);
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(root, 1));
    }

    @Test
    @DisplayName("a requirement the answer skipped is UNKNOWN, not UNMET")
    void missingRequirement() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":2,"verdict":"MET","basis":"Rust"}]}
                """);
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(root, 1));
    }

    @Test
    @DisplayName("a word that is not one of the three is UNKNOWN")
    void inventedVerdict() throws Exception {
        // Models answer "PARTIAL", "LIKELY" and "YES" given half a chance.
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"PARTIAL","basis":"Some Go"}]}
                """);
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(root, 1));
    }

    @Test
    @DisplayName("lower case and spacing from the model do not change the answer")
    void caseIsNormalised() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"met","basis":"Go since 2017"}]}
                """);
        assertEquals("MET", DiscoveryRanker.verdictFor(root, 1));
    }

    @Test
    @DisplayName("an answer with no verdicts at all scores nobody wrongly")
    void emptyAnswer() throws Exception {
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(read("{}"), 1));
        assertEquals("UNKNOWN", DiscoveryRanker.verdictFor(read("{\"verdicts\":[]}"), 1));
    }

    @Test
    @DisplayName("a fenced answer is unwrapped, because models keep sending one")
    void fences() {
        assertEquals("{\"a\":1}", DiscoveryRanker.stripFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", DiscoveryRanker.stripFence("  {\"a\":1}  "));
        // A fence that is not closed is left alone rather than half-eaten.
        assertEquals("```json", DiscoveryRanker.stripFence("```json"));
    }
}
