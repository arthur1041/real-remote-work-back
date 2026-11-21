package com.remoteroles.pipeline.employers;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one question the discovery and the screening both ask, and what an
 * application adds to it.
 *
 * <p>The rule under test is the owner's of 20 Nov 2026: the same material
 * gives the same score. Nothing here calls a model; what can be held still is
 * that the two questions are the same text when the material is the same, and
 * differ only by what the person actually wrote for the role.
 */
class FitJudgeTest {

    private static final ObjectMapper json = new ObjectMapper();

    private static JsonNode read(String text) throws Exception {
        return json.readTree(text);
    }

    /** The developer of the end-to-end test of 20 Nov 2026, as the account has him. */
    private static final String ACCOUNT = """
            {"COUNTRY":"BR","SENIORITY":"MID","YEARS_EXPERIENCE":"4",
             "SKILLS":"Python, Django, PostgreSQL, Go, Docker","EMPLOYMENT_KIND":"FULL_TIME,CONTRACT",
             "SALARY_EXPECTATION":"USD 50000","LANGUAGES":"en, pt","TIMEZONE":null,"AVAILABLE_FROM":null}
            """;

    @Test
    @DisplayName("an application sent as the form prefilled it adds nothing, and reads as none")
    void prefilledIsNotNew() throws Exception {
        // His real answers: every field as it came from the account. "USD
        // 50,000" is the account's 50000 USD; FULL_TIME is a contract it lists.
        String answers = """
                {"EMAIL":"x@example.test","FULL_NAME":"Rafael Duarte","COUNTRY":"BR","SENIORITY":"MID",
                 "YEARS_EXPERIENCE":"4","SKILLS":"Python, Django, PostgreSQL, Go, Docker",
                 "EMPLOYMENT_KIND":"FULL_TIME","SALARY_EXPECTATION":"USD 50,000"}
                """;
        assertEquals("", ApplicationScreener.applicationText("", read(answers), read("[]"), read(ACCOUNT)));
    }

    @Test
    @DisplayName("an answer changed for this role is the person speaking, and is kept")
    void changedIsNew() throws Exception {
        String text = ApplicationScreener.applicationText("", read("""
                {"SENIORITY":"SENIOR","COUNTRY":"BR"}
                """), read("[]"), read(ACCOUNT));
        assertEquals("seniority: SENIOR", text);
    }

    @Test
    @DisplayName("who the person is never reaches the judge")
    void noIdentity() throws Exception {
        String text = ApplicationScreener.applicationText("", read("""
                {"FULL_NAME":"Rafael Duarte","EMAIL":"r@example.test","LINKEDIN":"https://linkedin.com/in/x"}
                """), read("[]"), read(ACCOUNT));
        assertEquals("", text);
    }

    @Test
    @DisplayName("the covering note and the employer's own questions are always new")
    void ownQuestionsAreNew() throws Exception {
        String text = ApplicationScreener.applicationText("I led the Postgres migration.", read("{}"), read("""
                [{"id":"q1","q":"Years of Go?","a":"1"},
                 {"id":"q2","q":"Which clouds?","a":["AWS","GCP"]},
                 {"id":"q3","q":"Skipped","a":""}]
                """), read(ACCOUNT));
        assertTrue(text.startsWith("Covering note: I led the Postgres migration."));
        assertTrue(text.contains("Q: Years of Go?\nA: 1"));
        assertTrue(text.contains("A: AWS | GCP"));
        assertFalse(text.contains("Skipped"));
    }

    @Test
    @DisplayName("a contract the account does not list is new")
    void contractNotListed() {
        assertTrue(ApplicationScreener.sameAsAccount("EMPLOYMENT_KIND", "FULL_TIME", "FULL_TIME,CONTRACT"));
        assertFalse(ApplicationScreener.sameAsAccount("EMPLOYMENT_KIND", "PART_TIME", "FULL_TIME,CONTRACT"));
        assertFalse(ApplicationScreener.sameAsAccount("COUNTRY", "PT", ""));
    }

    @Test
    @DisplayName("with nothing written, the screening's question is the discovery's, word for word")
    void sameMaterialSameQuestion() {
        // The whole of "same material, same score", as far as code can hold
        // it: the model is asked at temperature zero, so the question is the
        // part that has to be identical.
        FitJudge.Facts facts = new FitJudge.Facts("Senior Backend Engineer", null, 4, true,
                "title: Senior Backend Engineer\n", "country: BR\n");
        List<FitJudge.Requirement> judged = List.of(
                new FitJudge.Requirement(1, 1, "5+ years with Go", true));
        String discovery = FitJudge.prompt("A backend role.", facts, judged, "Summary.", FitJudge.NO_APPLICATION);
        String screening = FitJudge.prompt("A backend role.", facts, judged, "Summary.", "");
        assertEquals(discovery, screening);
        assertTrue(discovery.contains("APPLICATION\nnone"));
        assertFalse(discovery.contains("has\nnot applied"));
    }
}
