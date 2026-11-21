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
        assertEquals("MET", FitJudge.verdictFor(root, 1));
    }

    @Test
    @DisplayName("no quote, no claim: a verdict with no basis becomes UNKNOWN")
    void noBasisNoClaim() throws Exception {
        // The answer that arrives with nothing behind it is almost always the
        // one the model inferred, and a claim nobody can check is worse than
        // "I do not know" -- which costs a skill nothing and an essential
        // constraint only a little (DiscoveryConstraints).
        JsonNode met = read("""
                {"verdicts":[{"requirement":1,"verdict":"MET","basis":"  "}]}
                """);
        assertEquals("UNKNOWN", FitJudge.verdictFor(met, 1));

        JsonNode unmet = read("""
                {"verdicts":[{"requirement":1,"verdict":"UNMET"}]}
                """);
        assertEquals("UNKNOWN", FitJudge.verdictFor(unmet, 1));
    }

    @Test
    @DisplayName("UNKNOWN needs no basis, because it is the absence of a claim")
    void unknownNeedsNothing() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"unknown","basis":""}]}
                """);
        assertEquals("UNKNOWN", FitJudge.verdictFor(root, 1));
    }

    @Test
    @DisplayName("a requirement the answer skipped is UNKNOWN, not UNMET")
    void missingRequirement() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":2,"verdict":"MET","basis":"Rust"}]}
                """);
        assertEquals("UNKNOWN", FitJudge.verdictFor(root, 1));
    }

    @Test
    @DisplayName("a word that is not one of the four is UNKNOWN")
    void inventedVerdict() throws Exception {
        // Models answer "LIKELY" and "YES" given half a chance. PARTIAL was
        // in this list until 18 Nov 2026, when "12 years against 15+" became
        // an answer the ranking asks for -- see DiscoveryConstraints.
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"LIKELY","basis":"Some Go"}]}
                """);
        assertEquals("UNKNOWN", FitJudge.verdictFor(root, 1));
    }

    @Test
    @DisplayName("lower case and spacing from the model do not change the answer")
    void caseIsNormalised() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"met","basis":"Go since 2017"}]}
                """);
        assertEquals("MET", FitJudge.verdictFor(root, 1));
    }

    @Test
    @DisplayName("an answer with no verdicts at all scores nobody wrongly")
    void emptyAnswer() throws Exception {
        assertEquals("UNKNOWN", FitJudge.verdictFor(read("{}"), 1));
        assertEquals("UNKNOWN", FitJudge.verdictFor(read("{\"verdicts\":[]}"), 1));
    }

    @Test
    @DisplayName("a fenced answer is unwrapped, because models keep sending one")
    void fences() {
        assertEquals("{\"a\":1}", FitJudge.stripFence("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", FitJudge.stripFence("  {\"a\":1}  "));
        // A fence that is not closed is left alone rather than half-eaten.
        assertEquals("```json", FitJudge.stripFence("```json"));
    }

    /* ------------------------------------------- a nota, que agora é do modelo

       Até 17 Nov 2026 a nota saía da aritmética sobre os veredictos, e um
       essencial não atendido a travava em 4,0. Numa tela cuja função é achar
       quem o empregador não acharia sozinho, isso punha um sênior de dez anos
       de experiência adjacente abaixo de um júnior que tivesse a palavra certa
       escrita no perfil. O número passou a vir do modelo, e o que estes testes
       guardam é o que acontece quando ele vem errado. */

    @Test
    @DisplayName("a nota do modelo é lida e arredondada a uma casa")
    void fitIsRead() throws Exception {
        assertEquals(7.5, FitJudge.fitFrom(node("{\"fit\":7.46}")));
        assertEquals(10.0, FitJudge.fitFrom(node("{\"fit\":10}")));
        assertEquals(0.0, FitJudge.fitFrom(node("{\"fit\":0}")));
    }

    @Test
    @DisplayName("número em texto serve, porque o modelo às vezes manda assim")
    void fitAsText() throws Exception {
        assertEquals(6.2, FitJudge.fitFrom(node("{\"fit\":\"6.2\"}")));
    }

    @Test
    @DisplayName("fora de 0..10 é resposta malformada, não um extremo")
    void fitOutOfScale() throws Exception {
        assertEquals(-1, FitJudge.fitFrom(node("{\"fit\":85}")));
        assertEquals(-1, FitJudge.fitFrom(node("{\"fit\":-2}")));
    }

    @Test
    @DisplayName("sem nota, ou com nota que não é número, a entrada não pontua")
    void fitMissing() throws Exception {
        assertEquals(-1, FitJudge.fitFrom(node("{\"summary\":\"boa\"}")));
        assertEquals(-1, FitJudge.fitFrom(node("{\"fit\":\"muito bom\"}")));
        assertEquals(-1, FitJudge.fitFrom(node("{\"fit\":null}")));
    }

    private static JsonNode node(String raw) throws Exception {
        return new ObjectMapper().readTree(raw);
    }

    @Test
    @DisplayName("PARTIAL is a verdict the discovery accepts")
    void partialAccepted() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":2,"kind":"CONSTRAINT","verdict":"partial","basis":"12 years"}]}
                """);
        assertEquals("PARTIAL", FitJudge.verdictFor(root, 2));
        assertEquals(true, FitJudge.constraintFor(root, 2));
    }

    @Test
    @DisplayName("an unlabelled requirement is a skill, so it costs nothing")
    void unlabelledIsSkill() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":1,"verdict":"UNMET","basis":"no Go"},
                             {"requirement":2,"kind":"whatever","verdict":"UNMET","basis":"x"}]}
                """);
        assertEquals(false, FitJudge.constraintFor(root, 1));
        assertEquals(false, FitJudge.constraintFor(root, 2));
        assertEquals(false, FitJudge.constraintFor(root, 3));
    }

    @Test
    @DisplayName("the requirement number is read whether the model writes 3, \"3\" or \"3. ...\"")
    void requirementNumberForms() throws Exception {
        JsonNode root = read("""
                {"verdicts":[{"requirement":"0","verdict":"MET","basis":"a"},
                             {"requirement":"7. Seniority: senior","verdict":"UNMET","basis":"b"},
                             {"requirement":"#2","verdict":"PARTIAL","basis":"c"},
                             {"requirement":"Go in production","verdict":"MET","basis":"d"}]}
                """);
        assertEquals("MET", FitJudge.verdictFor(root, 0));
        assertEquals("UNMET", FitJudge.verdictFor(root, 7));
        assertEquals("PARTIAL", FitJudge.verdictFor(root, 2));
        assertEquals("UNKNOWN", FitJudge.verdictFor(root, 1));
    }
}
