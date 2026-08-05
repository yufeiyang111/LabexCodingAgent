package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextUsageEstimatorTest {
    private final ContextUsageEstimator estimator = new ContextUsageEstimator();

    @Test
    void categorizesPromptAndToolResultContentWithConfiguredWindow() {
        ContextUsageEstimator.PromptContext context = ContextUsageEstimator.PromptContext.of(
                "rules", "memory", "session", "log", "checkpoint", "skill", "mcp",
                "mode", "language", "initial context");
        ContextUsageSnapshot snapshot = estimator.estimate("conversation", "session", "provider", "model", 1_000,
                "system prompt", List.of(Map.of("name", "read_file")), context,
                List.of(Map.of("role", "user", "content", "initial context"),
                        Map.of("role", "user", "content", "question"),
                        Map.of("role", "user", "content", "[Tool read_file result]\ncontents")), "NONE");

        Map<String, Object> payload = snapshot.toPayload();
        @SuppressWarnings("unchecked")
        Map<String, Integer> categories = (Map<String, Integer>) payload.get("categories");
        assertEquals(0, estimator.estimateTokens(""));
        assertEquals(estimator.estimateTokens("system prompt"), categories.get("systemPrompt"));
        assertEquals(estimator.estimateTokens("modelanguage"), categories.get("fixedInstructions"));
        assertEquals(estimator.estimateTokens("question"), categories.get("conversationMessages"));
        assertEquals(estimator.estimateTokens("[Tool read_file result]\ncontents"), categories.get("toolResults"));
        assertEquals("ESTIMATED_CHARS", payload.get("measurementSource"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void separatesProjectWorkspaceMemoryRecoveryAndCompactionSources() {
        String sessionContext = "<project_context>repo map</project_context>\n"
                + "<workspace_memory>durable file facts</workspace_memory>";
        ContextUsageEstimator.PromptContext context = ContextUsageEstimator.PromptContext.of(
                "project rules", "compressed conversation and tool records", sessionContext,
                "recent run tool log", "conversation checkpoint", "skills", "mcp",
                "mode", "language", "initial context");

        ContextUsageSnapshot snapshot = estimator.estimate("conversation", "session", "provider", "model", 10_000,
                "system", List.of(), context,
                List.of(Map.of("role", "user", "content", "initial context")), "NONE");

        Map<String, Integer> categories = (Map<String, Integer>) snapshot.toPayload().get("categories");
        assertEquals(estimator.estimateTokens("<workspace_memory>durable file facts</workspace_memory>"), categories.get("workspaceMemory"));
        assertEquals(estimator.estimateTokens("project rules<project_context>repo map</project_context>"),
                categories.get("projectContext"));
        assertEquals(estimator.estimateTokens("compressed conversation and tool records"),
                categories.get("conversationMemory"));
        assertEquals(estimator.estimateTokens("recent run tool log"), categories.get("runRecoveryContext"));
        assertEquals(estimator.estimateTokens("conversation checkpoint"), categories.get("compactionSummary"));
        assertFalse(categories.containsKey("compactedContext"));
        assertEquals("context-budget-v2", snapshot.toPayload().get("contextCategoryVersion"));
    }

    @Test
    void leavesPercentageUnknownWithoutConfiguredWindow() {
        ContextUsageSnapshot snapshot = estimator.estimate("conversation", "session", "provider", "model", null,
                "system", List.of(), new ContextUsageEstimator.PromptContext("", "", "", ""),
                List.of(), "NONE");
        assertNull(snapshot.toPayload().get("usagePercent"));
        assertNull(snapshot.toPayload().get("contextWindowTokens"));
    }


    @Test
    @SuppressWarnings("unchecked")
    void exposesASeparatedRedactedPreviewOfTheLastActualRequest() {
        String apiKey = "sk-abcdefghijklmnopqrstuvwxyz";
        String bearer = "Bearer abcdefghijklmnopqrstuvwxyz";
        ContextUsageSnapshot snapshot = estimator.estimate("conversation", "session", "provider", "model", 10_000,
                "system " + apiKey + " authorization: " + bearer,
                List.of(Map.of("name", "tool", "token", "tool-secret-value")),
                new ContextUsageEstimator.PromptContext("workspace password=workspace-secret", "skills", "fixed", "initial"),
                List.of(Map.of("role", "user", "content", "initial"),
                        Map.of("role", "user", "content", "question")), "NONE");

        Map<String, Object> preview = snapshot.toPreviewPayload();
        List<Map<String, Object>> sections = (List<Map<String, Object>>) preview.get("previewSections");
        String content = sections.stream().map(section -> String.valueOf(section.get("content")))
                .reduce("", (left, right) -> left + right);

        assertEquals("LAST_ACTUAL_REQUEST", preview.get("previewSource"));
        assertFalse(sections.isEmpty());
        assertTrue(sections.stream().anyMatch(section -> "conversationMessages".equals(section.get("key"))));
        assertFalse(content.contains(apiKey));
        assertFalse(content.contains("abcdefghijklmnopqrstuvwxyz"));
        assertFalse(content.contains("workspace-secret"));
        assertTrue(content.contains("[REDACTED]"));
    }



    @Test
    @SuppressWarnings("unchecked")
    void marksNextRequestSnapshotsAsPredictionsAndKeepsTheirMetadata() {
        ContextUsageEstimator.PromptContext context = new ContextUsageEstimator.PromptContext(
                "workspace", "skills", "fixed", "initial");
        ContextUsageSnapshot snapshot = estimator.estimateNextRequest("conversation", "preview-session", "provider", "model",
                10_000, "system", List.of(Map.of("name", "tool")), context,
                List.of(Map.of("role", "user", "content", "initial"),
                        Map.of("role", "user", "content", "draft request")),
                Map.of("nextUserMessageIncluded", true, "agentMode", "build"));

        Map<String, Object> payload = snapshot.toPreviewPayload();
        Map<String, Object> metadata = (Map<String, Object>) payload.get("previewMetadata");

        assertEquals("NEXT_REQUEST_ESTIMATE", payload.get("previewSource"));
        assertEquals(Boolean.TRUE, metadata.get("nextUserMessageIncluded"));
        assertEquals("build", metadata.get("agentMode"));
        assertTrue(((List<?>) payload.get("previewSections")).size() > 0);
    }

    @Test
    void classifiesDurableConversationCheckpointMessagesAsCompactionSummaries() {
        String checkpoint = "<conversation-checkpoint version=\"3\">summary</conversation-checkpoint>";

        Map<String, Integer> categories = estimator.estimateCategories("", List.of(),
                new ContextUsageEstimator.PromptContext("", "", "", ""),
                List.of(Map.of("role", "user", "content", checkpoint),
                        Map.of("role", "user", "content", "latest question")));

        assertEquals(estimator.estimateTokens(checkpoint), categories.get("compactionSummary"));
        assertEquals(estimator.estimateTokens("latest question"), categories.get("conversationMessages"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsNativeToolCallMetadataAndClassifiesRoleToolOutput() {
        Map<String, Object> assistant = Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "type", "function",
                        "function", Map.of("name", "run_tests", "arguments", "{\"command\":\"mvn test\"}"))));
        Map<String, Object> tool = Map.of(
                "role", "tool", "tool_call_id", "call-1", "name", "run_tests", "content", "BUILD SUCCESS");

        Map<String, Integer> categories = estimator.estimateCategories("", List.of(),
                new ContextUsageEstimator.PromptContext("", "", "", ""), List.of(assistant, tool));

        assertTrue(categories.getOrDefault("messageProtocol", 0) > 0);
        assertEquals(estimator.estimateTokens("BUILD SUCCESS"), categories.get("toolResults"));
        assertTrue(categories.get("messageProtocol") > estimator.estimateTokens("assistanttool"));
    }
}
