package com.remoteroles.pipeline.employers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a reading is allowed to say about somebody's application.
 *
 * <p>This is the class that reads a real person's CV against a real role and
 * writes a verdict an employer acts on. Two kinds of mistake are expensive
 * here and neither throws: a claim with nothing behind it, which reads exactly
 * like a finding, and the model overruling something the candidate stated on
 * the form — being told you do not accept contract work by a machine, on an
 * application where you ticked that you do.
 *
 * <p>Nothing is sent and nothing is read from a database: `decide` is pure.
 */
class ApplicationScreenerTest {

    private static ApplicationScreener.Requirement field(String text) {
        return new ApplicationScreener.Requirement(1, text, true, "FIELD", "EMPLOYMENT_KIND");
    }

    private static ApplicationScreener.Requirement written(String text) {
        return new ApplicationScreener.Requirement(1, text, false, "MODEL", null);
    }

    @Nested
    @DisplayName("a declared field answers for itself")
    class DeclaredField {

        @Test
        @DisplayName("this application's answer beats the profile")
        void answerWins() {
            // Somebody who ticked full-time while job hunting may well take
            // this one on contract. Reading the profile instead would mark as
            // unmet a person who already said yes, on this form.
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    field("Open to contract work"), "UNMET", "they look full-time", "CV",
                    "CONTRACT", "FULL_TIME");
            assertEquals("MET", call.verdict());
            assertEquals("ANSWER", call.source());
            assertTrue(call.quote().contains("Asked on this application"));
        }

        @Test
        @DisplayName("with no answer on the form, the profile speaks")
        void profileSpeaksWhenTheFormDidNot() {
            // Applications sent before the question existed still have to be
            // readable.
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    field("Open to contract work"), "UNKNOWN", "", null,
                    "  ", "CONTRACT,FULL_TIME");
            assertEquals("MET", call.verdict());
            assertEquals("PROFILE", call.source());
            assertTrue(call.quote().contains("Their profile says"));
        }

        @Test
        @DisplayName("the model does not get a vote once the field has spoken")
        void modelIsOverruled() {
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    field("Open to contract work"), "MET", "sounds flexible", "CV",
                    "FULL_TIME", null);
            assertEquals("UNMET", call.verdict());
        }

        @Test
        @DisplayName("nothing declared anywhere is UNKNOWN, not UNMET")
        void silenceIsNotNo() {
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    field("Open to contract work"), "MET", "sounds flexible", "CV",
                    null, null);
            assertEquals("UNKNOWN", call.verdict());
            assertEquals("", call.quote());
            assertNull(call.source());
        }
    }

    @Nested
    @DisplayName("what the model says, cleaned")
    class ModelAnswers {

        @Test
        @DisplayName("no quote, no claim")
        void noQuoteNoClaim() {
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    written("Five years of Go"), "MET", "   ", "CV", null, null);
            assertEquals("UNKNOWN", call.verdict());
        }

        @Test
        @DisplayName("a verdict that is not one of the three is UNKNOWN")
        void invented() {
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    written("Five years of Go"), "PARTIAL", "three years of Go", "CV", null, null);
            assertEquals("UNKNOWN", call.verdict());
        }

        @Test
        @DisplayName("a source nobody can point at is dropped, and the verdict survives")
        void unknownSource() {
            // The verdict still has a quote behind it; what is refused is the
            // label saying where it came from.
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    written("Five years of Go"), "MET", "eight years of Go", "LINKEDIN", null, null);
            assertEquals("MET", call.verdict());
            assertNull(call.source());
        }

        @Test
        @DisplayName("a good answer is left exactly as it came")
        void goodAnswerSurvives() {
            ApplicationScreener.Call call = ApplicationScreener.decide(
                    written("Five years of Go"), "MET", "eight years of Go", "CV", null, null);
            assertEquals("MET", call.verdict());
            assertEquals("eight years of Go", call.quote());
            assertEquals("CV", call.source());
        }
    }

    @Test
    @DisplayName("the regimes are named the way a requirement names them")
    void kindsReadAsWords() {
        // The comparison is `requirement.text().contains(humanKind(code))`, so
        // these two vocabularies have to meet.
        assertEquals("full-time", ApplicationScreener.humanKind("FULL_TIME"));
        assertEquals("contract", ApplicationScreener.humanKind("CONTRACT"));
        // An unknown code still reads as words rather than as a constant.
        assertEquals("job share", ApplicationScreener.humanKind("JOB_SHARE"));
    }

    @Test
    @DisplayName("what is sent to the model is cut, and null is not a crash")
    void cutting() {
        assertEquals("", ApplicationScreener.cut(null, 10));
        assertEquals("abcde", ApplicationScreener.cut("abcdefgh", 5));
        assertEquals("abc", ApplicationScreener.cut("abc", 5));
    }

    @Nested
    @DisplayName("de que texto os requisitos saíram")
    class SourceHash {

        private ApplicationScreener.Role role(String title, String description, String kind) {
            return new ApplicationScreener.Role(1L, 1, title, description, kind);
        }

        @Test
        @DisplayName("o título não entra: editá-lo não é uma pergunta nova")
        void titleIsNotPartOfTheQuestion() {
            /* O que custa estar errado aqui: com o título dentro, corrigir uma
               digitação nele relê todas as candidaturas da vaga a preço cheio.
               Foi assim que o defeito de 16 Nov 2026 apareceu. */
            assertEquals(
                    ApplicationScreener.sourceHash(role("Platform Engineer", "Kubernetes, Go.", "FULL_TIME")),
                    ApplicationScreener.sourceHash(role("Platform Engineer (SRE)", "Kubernetes, Go.", "FULL_TIME")));
        }

        @Test
        @DisplayName("a descrição entra: mudá-la é uma pergunta nova")
        void descriptionIsTheQuestion() {
            assertNotEquals(
                    ApplicationScreener.sourceHash(role("X", "Kubernetes, Go.", "FULL_TIME")),
                    ApplicationScreener.sourceHash(role("X", "Kubernetes, Go, Rust.", "FULL_TIME")));
        }

        @Test
        @DisplayName("o tipo de contratação entra, porque gera um requisito")
        void employmentKindIsPartOfIt() {
            assertNotEquals(
                    ApplicationScreener.sourceHash(role("X", "Kubernetes.", "FULL_TIME")),
                    ApplicationScreener.sourceHash(role("X", "Kubernetes.", "CONTRACT")));
        }

        @Test
        @DisplayName("os dois campos não se confundem um com o outro")
        void theTwoFieldsCannotBleed() {
            /* Sem um separador que nenhum dos dois contém, "ab" + "c" e "a" +
               "bc" seriam a mesma vaga. */
            assertNotEquals(
                    ApplicationScreener.sourceHash(role("X", "Kubernetes", "FULL")),
                    ApplicationScreener.sourceHash(role("X", "KubernetesFULL", "")));
        }

        @Test
        @DisplayName("nulos não explodem: uma vaga sem descrição ainda tem hash")
        void nullsAreNotAnError() {
            assertNotNull(ApplicationScreener.sourceHash(role(null, null, null)));
        }
    }

    @Nested
    @DisplayName("o requisito de regime")
    class RequirementForKind {

        @Test
        @DisplayName("o artigo segue a palavra, e estágio começa por vogal")
        void articleFollowsTheWord() {
            /* "Works on a internship basis" era o que a vaga de estágio
               publicada pelo formulário mostrava na lista de requisitos, na
               quebra requisito a requisito e no CSV. */
            assertEquals("Works on an internship basis",
                    ApplicationScreener.requirementForKind("INTERNSHIP"));
            assertEquals("Works on a full-time basis",
                    ApplicationScreener.requirementForKind("FULL_TIME"));
            assertEquals("Works on a contract basis",
                    ApplicationScreener.requirementForKind("CONTRACT"));
        }

        @Test
        @DisplayName("a palavra sobrevive, que é o que o teste de regime procura")
        void theWordItselfIsUnchanged() {
            for (String code : new String[]{
                    "FULL_TIME", "PART_TIME", "CONTRACT", "TEMPORARY", "INTERNSHIP", "VOLUNTEER"}) {
                assertTrue(ApplicationScreener.requirementForKind(code)
                        .contains(ApplicationScreener.humanKind(code)), code);
            }
        }

        @Test
        @DisplayName("um código desconhecido ainda produz uma frase")
        void unknownCodeStillReads() {
            assertEquals("Works on a seasonal basis",
                    ApplicationScreener.requirementForKind("SEASONAL"));
        }
    }
}
