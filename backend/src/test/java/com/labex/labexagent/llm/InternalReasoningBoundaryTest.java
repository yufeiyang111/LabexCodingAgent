package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InternalReasoningBoundaryTest {

    @Test
    void stripsMarkdownEscapedReasoningTagsAcrossChunks() {
        StringBuilder reasoning = new StringBuilder();
        InternalReasoningBoundary.TagStreamFilter filter =
                new InternalReasoningBoundary.TagStreamFilter(reasoning::append);

        filter.push("\\");
        filter.push("<THINK\\>private plan\\</TH");
        filter.push("INK\\>");
        filter.flush();

        assertEquals("private plan", reasoning.toString());
    }

    @Test
    void keepsNestedEscapedReasoningPrivateUntilTheOuterBlockCloses() {
        StringBuilder reasoning = new StringBuilder();
        StringBuilder visible = new StringBuilder();
        InternalReasoningBoundary.VisibleStreamFilter filter =
                new InternalReasoningBoundary.VisibleStreamFilter(reasoning::append, visible::append);

        filter.push("Visible \\");
        filter.push("<think\\>outer \\<thinking\\>inner\\</thinking\\> tail\\</think\\> answer");
        filter.flush();

        assertEquals("Visible  answer", visible.toString());
        assertEquals("outer inner tail", reasoning.toString());
    }

    @Test
    void removesSelfClosingReasoningProtocolWithoutHidingFollowingText() {
        assertEquals("before  after", InternalReasoningBoundary.stripVisible("before <think/> after"));
        assertEquals("before  after", InternalReasoningBoundary.stripVisible("before \\<thinking/\\> after"));
    }

    @Test
    void sanitizesEveryUserVisibleReasoningEventField() {
        var sanitized = InternalReasoningBoundary.sanitizeEventPayload("THINK", java.util.Map.of(
                "content", "\\<think\\>content\\</think\\>",
                "summary", "\\<thinking\\>summary\\</thinking\\>",
                "message", "\\<think\\>message\\</think\\>",
                "detail", "\\<think\\>detail\\</think\\>"));

        @SuppressWarnings("unchecked")
        var payload = (java.util.Map<String, Object>) sanitized;
        assertEquals("content", payload.get("content"));
        assertEquals("", payload.get("summary"));
        assertEquals("", payload.get("message"));
        assertEquals("", payload.get("detail"));
    }



    @Test
    void sanitizesCandidateFinalDeltasBeforeTheyReachTheClient() {
        var sanitized = InternalReasoningBoundary.sanitizeEventPayload("FINAL_CANDIDATE_DELTA",
                java.util.Map.of("delta", "visible <think>private</think> answer"));

        @SuppressWarnings("unchecked")
        var payload = (java.util.Map<String, Object>) sanitized;
        assertEquals("visible  answer", payload.get("delta"));
    }

}
