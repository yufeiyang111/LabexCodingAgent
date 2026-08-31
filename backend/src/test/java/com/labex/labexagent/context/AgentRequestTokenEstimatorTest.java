package com.labex.labexagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.fixtures.OpenAiImageProtocolFixture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRequestTokenEstimatorTest {

    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void countsSystemToolsNativeToolProtocolAndOutputReserve() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "name", "operator", "content", "run verification"),
                Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                        "id", "call-1",
                        "type", "function",
                        "function", Map.of("name", "run_tests", "arguments", "{\"command\":\"mvn test\"}")))),
                Map.of("role", "tool", "tool_call_id", "call-1", "name", "run_tests", "content", "BUILD SUCCESS"));
        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of("name", "run_tests", "description", "Run the repository test suite")));

        AgentRequestTokenEstimator.Estimate estimate = estimator.estimate(
                "system instructions", tools, messages, 4_096, 512);

        assertTrue(estimate.systemPromptTokens() > 0);
        assertTrue(estimate.toolSchemaTokens() > 0);
        assertTrue(estimate.messageTokens() > estimator.estimateMessages(List.of(
                Map.of("role", "user", "content", "run verification"))));
        assertEquals(estimate.inputTokens() + 512, estimate.totalWithReservedOutputTokens());
        assertEquals(3_584, estimate.inputCapacityTokens());
        assertFalse(estimate.overflowsInputCapacity());
    }


    @Test
    void assignsDataUrlImagesAnIndependentVisualBudgetInsteadOfCountingBase64AsText() {
        Map<String, Object> imagePart = OpenAiImageProtocolFixture.imagePart();

        AgentRequestTokenEstimator.ValueEstimate estimate = estimator.analyzeValue(imagePart);

        assertTrue(OpenAiImageProtocolFixture.dataUrlChars() > 480_000);
        assertEquals(1, estimate.imageCount());
        assertEquals(8_192, estimate.imageInputTokens());
        assertTrue(estimate.textTokens() < 1_000);
        assertTrue(estimate.totalTokens() <= 9_500,
                () -> "image data URL must not be charged as ordinary text, estimated=" + estimate.totalTokens());
    }

    @Test
    void budgetsDurableAttachmentReferencesEquallyWithHydratedImagesWithoutAnyBase64() {
        // durable 形态：压缩选材视图里 user 消息只携带 attachmentIds 引用。
        Map<String, Object> durableUserMessage = Map.of(
                "role", "user",
                "content", "inspect this screenshot",
                "attachmentIds", List.of("image-1", "image-2"));

        AgentRequestTokenEstimator.ValueEstimate durable = estimator.analyzeValue(durableUserMessage);
        assertEquals(2, durable.imageCount());
        assertEquals(2 * 8_192, durable.imageInputTokens());

        // 预算等价性：与注水后的 image_url 形态计权一致，选材不因未注水而低估图片占用。
        List<Map<String, Object>> hydratedUserMessage = OpenAiImageProtocolFixture.userMessagesOf(2);
        AgentRequestTokenEstimator.ValueEstimate hydrated = estimator.analyzeValue(hydratedUserMessage.get(0));
        assertEquals(durable.imageInputTokens(), hydrated.imageInputTokens());

        // estimate 全链路（system+tools+messages）同样覆盖引用预算，防止压缩触发线漏算图片。
        int withAttachments = estimator.estimate("sys", List.of(), List.of(durableUserMessage), 200_000, 512)
                .messageTokens();
        int withoutAttachments = estimator.estimate("sys", List.of(),
                List.of(Map.of("role", "user", "content", "inspect this screenshot")), 200_000, 512)
                .messageTokens();
        assertTrue(withAttachments >= withoutAttachments + 2 * 8_192,
                () -> "attachment references must carry the visual budget, with=" + withAttachments
                        + " without=" + withoutAttachments);

        // 无附件消息不得被误计视觉权重。
        assertEquals(0, estimator.analyzeValue(Map.of("role", "user", "content", "plain text")).imageCount());
    }


    @Test
    void rejectsUnknownOrUnsafeModelWindowInsteadOfUsingPermissiveDefault() {
        AgentContextOverflowException missing = assertThrows(AgentContextOverflowException.class,
                () -> estimator.estimate("system", List.of(), List.of(), null, 512));
        assertEquals("context_window_unconfigured", missing.reasonCode());

        AgentContextOverflowException invalidReserve = assertThrows(AgentContextOverflowException.class,
                () -> estimator.estimate("system", List.of(), List.of(), 1_024, 1_024));
        assertEquals("output_reserve_exhausts_context_window", invalidReserve.reasonCode());
    }
}