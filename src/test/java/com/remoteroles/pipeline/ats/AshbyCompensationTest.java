package com.remoteroles.pipeline.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ashby is the one board that states pay as typed data, and these fix the line
 * between reading it and inventing it.
 *
 * <p>The failure that matters is not a missing salary -- it is a wrong one. An
 * equity percentage printed as a wage, or a euro range labelled USD, is the kind
 * of error that makes a reader distrust every other number on the site, so most
 * of what follows asserts that nothing is returned.
 */
class AshbyCompensationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode job(String compensationJson) {
        try {
            return MAPPER.readTree("{\"compensation\":" + compensationJson + "}");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static JsonNode tier(String... components) {
        return job("{\"compensationTiers\":[{\"components\":[" + String.join(",", components) + "]}]}");
    }

    private static final String SALARY_CAD =
            "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                    + "\"currencyCode\":\"CAD\",\"minValue\":101000,\"maxValue\":110000}";

    @Test
    @DisplayName("a plain yearly band is read as stated")
    void readsYearlyBand() {
        AshbyCompensation.Pay pay = AshbyCompensation.read(tier(SALARY_CAD));
        assertEquals("CAD", pay.currency());
        assertEquals("annual", pay.period());
        assertEquals(0, pay.min().intValue() - 101000);
        assertEquals(0, pay.max().intValue() - 110000);
    }

    @Test
    @DisplayName("equity sitting beside a salary is not treated as pay")
    void ignoresEquityComponent() {
        AshbyCompensation.Pay pay = AshbyCompensation.read(tier(
                "{\"compensationType\":\"EquityPercentage\",\"interval\":\"NONE\","
                        + "\"currencyCode\":null,\"minValue\":0.05,\"maxValue\":0.1}",
                SALARY_CAD));
        assertEquals(0, pay.min().intValue() - 101000);
        assertEquals("CAD", pay.currency());
    }

    @Test
    @DisplayName("a posting with only equity has no salary")
    void equityAloneIsNotSalary() {
        assertNull(AshbyCompensation.read(tier(
                "{\"compensationType\":\"EquityPercentage\",\"interval\":\"NONE\","
                        + "\"currencyCode\":null,\"minValue\":0.5,\"maxValue\":1.0}")));
    }

    @Test
    @DisplayName("bonus and commission are not salary either")
    void bonusAndCommissionAreNotSalary() {
        assertNull(AshbyCompensation.read(tier(
                "{\"compensationType\":\"Bonus\",\"interval\":\"1 YEAR\","
                        + "\"currencyCode\":\"USD\",\"minValue\":5000,\"maxValue\":9000}",
                "{\"compensationType\":\"Commission\",\"interval\":\"1 YEAR\","
                        + "\"currencyCode\":\"USD\",\"minValue\":10000,\"maxValue\":20000}")));
    }

    @Test
    @DisplayName("geographic tiers fold into the envelope the employer published")
    void foldsTiersIntoEnvelope() {
        JsonNode node = job("{\"compensationTiers\":["
                + "{\"title\":\"Tier 1 - New York City\",\"components\":["
                + "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                + "\"currencyCode\":\"USD\",\"minValue\":212500,\"maxValue\":300000}]},"
                + "{\"title\":\"Tier 2 - Austin\",\"components\":["
                + "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                + "\"currencyCode\":\"USD\",\"minValue\":180000,\"maxValue\":260000}]}]}");
        AshbyCompensation.Pay pay = AshbyCompensation.read(node);
        assertEquals(0, pay.min().intValue() - 180000);
        assertEquals(0, pay.max().intValue() - 300000);
    }

    @Test
    @DisplayName("tiers quoted in different currencies yield nothing")
    void refusesMixedCurrencies() {
        assertNull(AshbyCompensation.read(job("{\"compensationTiers\":["
                + "{\"components\":[{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                + "\"currencyCode\":\"USD\",\"minValue\":90000,\"maxValue\":120000}]},"
                + "{\"components\":[{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                + "\"currencyCode\":\"EUR\",\"minValue\":80000,\"maxValue\":100000}]}]}")));
    }

    @Test
    @DisplayName("an hourly rate beside a yearly one yields nothing")
    void refusesMixedPeriods() {
        assertNull(AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                        + "\"currencyCode\":\"USD\",\"minValue\":90000,\"maxValue\":120000}",
                "{\"compensationType\":\"Salary\",\"interval\":\"1 HOUR\","
                        + "\"currencyCode\":\"USD\",\"minValue\":45,\"maxValue\":60}")));
    }

    @Test
    @DisplayName("hourly and monthly intervals carry their period through")
    void mapsSubAnnualIntervals() {
        assertEquals("hourly", AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"1 HOUR\","
                        + "\"currencyCode\":\"USD\",\"minValue\":45,\"maxValue\":60}")).period());
        assertEquals("monthly", AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"1 MONTH\","
                        + "\"currencyCode\":\"PLN\",\"minValue\":18000,\"maxValue\":24000}")).period());
    }

    @Test
    @DisplayName("a biweekly figure is left unstated rather than halved")
    void refusesBiweekly() {
        assertNull(AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"2 WEEKS\","
                        + "\"currencyCode\":\"USD\",\"minValue\":4000,\"maxValue\":5000}")));
    }

    @Test
    @DisplayName("a one-sided band is still pay")
    void keepsOpenEndedBand() {
        AshbyCompensation.Pay pay = AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                        + "\"currencyCode\":\"GBP\",\"minValue\":90000,\"maxValue\":null}"));
        assertEquals(0, pay.min().intValue() - 90000);
        assertNull(pay.max());
    }

    @Test
    @DisplayName("zeroed and null bounds are absent, not free")
    void treatsZeroAsAbsent() {
        assertNull(AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                        + "\"currencyCode\":\"USD\",\"minValue\":0,\"maxValue\":0}")));
    }

    @Test
    @DisplayName("a salary with no currency is not printable")
    void refusesMissingCurrency() {
        assertNull(AshbyCompensation.read(tier(
                "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\","
                        + "\"currencyCode\":null,\"minValue\":90000,\"maxValue\":120000}")));
    }

    @Test
    @DisplayName("boards that publish no compensation block are unaffected")
    void toleratesAbsentCompensation() {
        assertNull(AshbyCompensation.read(MAPPER.createObjectNode()));
        assertNull(AshbyCompensation.read(job("null")));
        assertNull(AshbyCompensation.read(job("{\"compensationTiers\":[]}")));
    }
}
