package com.remoteroles.pipeline.newsletter;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request for a change (V86) is its own message: never batched with a
 * refusal, and worded as something to fix with the way straight to the form.
 */
class PostingNeedsWorkTest {

    private final PostingDecisionSender sender =
            new PostingDecisionSender(null, null, null, "https://actuallyremote.work", 10, 40);

    private static PostingDecisionSender.Decided row(long id, boolean needsWork) {
        return new PostingDecisionSender.Decided(id, 7, "e@example.test", "Role " + id, "Co", null,
                false, null, "Set the country to Israel.", needsWork);
    }

    @Test
    void aChangeRequestIsNeverBatchedWithARefusal() {
        Map<String, List<PostingDecisionSender.Decided>> groups =
                PostingDecisionSender.group(List.of(row(1, true), row(2, false), row(3, true)));
        assertEquals(2, groups.size());
        assertEquals(2, groups.get("7:needs-work").size());
        assertEquals(1, groups.get("7:rejected").size());
    }

    @Test
    void itReadsAsAFixAndLinksToTheForm() {
        List<PostingDecisionSender.Decided> one = List.of(row(652, true));
        assertEquals("One change before Role 652 goes live", sender.subject(one));
        String html = sender.html(one);
        assertTrue(html.contains("/employers/post?edit=652"));
        assertTrue(html.contains("Set the country to Israel."));
        assertTrue(!html.contains("turned down"));
        assertEquals("2 roles need one change before they go live",
                sender.subject(List.of(row(1, true), row(2, true))));
    }
}
