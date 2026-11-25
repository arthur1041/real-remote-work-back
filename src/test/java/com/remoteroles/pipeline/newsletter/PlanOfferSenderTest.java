package com.remoteroles.pipeline.newsletter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offer is written by the site and only laid out here, so what is worth
 * checking is that what the site wrote arrives whole: every plan, every
 * button, and a row that cannot be read refused rather than sent half empty.
 */
class PlanOfferSenderTest {

    private static final String TWO = """
            {"subject":"Starter and Growth, for Northwind","preheader":"p","intro":"Here is what they add.",
             "offers":[
               {"name":"Starter","price":"$9.90 a month billed yearly","lines":["Up to 5 roles","Featured"],
                "primary":{"label":"Choose Starter, yearly","url":"https://pay.example/a?client_reference_id=7"},
                "secondary":{"label":"Or month to month","url":"https://pay.example/m"}},
               {"name":"Growth","price":"$39.90 a month billed yearly","lines":["Everything in Starter, plus:","Discovery"],
                "primary":{"label":"Choose Growth, yearly","url":"https://pay.example/g"},"secondary":null}],
             "outro":"Cancel whenever you like."}
            """;

    private final PlanOfferSender sender = new PlanOfferSender(null, null, null, "https://actuallyremote.work/");

    @Test
    void everyPlanAndButtonArrives() throws Exception {
        PlanOfferSender.Letter letter = PlanOfferSender.parse(TWO);
        assertEquals(2, letter.offers().size());
        String html = sender.html(letter);
        String text = sender.text(letter);
        for (String s : new String[] {"Choose Starter, yearly", "Or month to month", "Choose Growth, yearly",
                "Everything in Starter, plus:", "Cancel whenever you like."}) {
            assertTrue(html.contains(s), "html is missing " + s);
            assertTrue(text.contains(s), "text is missing " + s);
        }
        // The payer reference has to survive escaping, or a payment cannot be matched to the account.
        assertTrue(html.contains("client_reference_id=7"));
        assertTrue(text.contains("https://pay.example/a?client_reference_id=7"));
    }

    @Test
    void anOfferWithNoPlansIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PlanOfferSender.parse("{\"subject\":\"x\",\"offers\":[]}"));
    }
}
