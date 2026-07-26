package com.labex.labexagent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.labex.labexagent.runtime.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

class AcceptanceScriptedProviderTest {

    private final AcceptanceScriptedProvider provider = new AcceptanceScriptedProvider();

    @Test
    void isOnlyAvailableUnderExplicitAcceptanceProfile() {
        Profile profile = AcceptanceScriptedProvider.class.getAnnotation(Profile.class);

        assertTrue(profile != null);
        assertEquals(List.of("acceptance & !prod"), List.of(profile.value()));
        assertEquals("acceptance_scripted", provider.getProviderId());
    }

    @Test
    void emitsAStableNativeToolCallThenACompleteFinalReply() {
        List<LlmProvider.StreamChunk> first = stream("[acceptance:tool] 请检查项目根目录");

        LlmProvider.StreamChunk toolCall = first.stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("list_files", toolCall.toolName());
        assertEquals("acceptance-tool-list", toolCall.toolCallId());
        assertEquals(0, toolCall.toolCallIndex());

        List<LlmProvider.StreamChunk> second = stream(
                "[acceptance:tool] 请检查项目根目录",
                "[Tool list_files result]\nREADME.md");
        String text = text(second);
        assertTrue(text.contains("## Summary"));
        assertTrue(text.contains("list_files"));
        assertTrue(second.stream().anyMatch(chunk -> "done".equals(chunk.type())));
    }

    @Test
    void emitsQuestionAndApprovalScenariosWithoutExecutingAnythingItself() {
        LlmProvider.StreamChunk question = stream("[acceptance:question] 询问继续方式").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("question", question.toolName());
        assertTrue(question.toolArgs().contains("继续真实验收"));

        LlmProvider.StreamChunk approval = stream("[acceptance:approval] 验证一次性审批").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("shell", approval.toolName());
        assertTrue(approval.toolArgs().contains("git add ."));
    }

    @Test
    void keepsConversationMarkersIsolatedAndHonorsCancellation() {
        String first = text(stream("[acceptance:isolation:A-ONLY]"));
        String second = text(stream("[acceptance:isolation:B-ONLY]"));

        assertTrue(first.contains("A-ONLY"));
        assertFalse(first.contains("B-ONLY"));
        assertTrue(second.contains("B-ONLY"));
        assertFalse(second.contains("A-ONLY"));

        List<LlmProvider.StreamChunk> cancelled = new ArrayList<>();
        provider.chatStream("", List.of(Map.of("role", "user", "content", "[acceptance:tool]")),
                List.of(), config(), () -> true, cancelled::add);
        assertEquals(List.of("cancelled"), cancelled.stream().map(LlmProvider.StreamChunk::type).toList());
    }

    private List<LlmProvider.StreamChunk> stream(String... contents) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String content : contents) {
            messages.add(Map.of("role", "user", "content", content));
        }
        List<LlmProvider.StreamChunk> chunks = new ArrayList<>();
        provider.chatStream("", messages, List.of(), config(), CancellationToken.none(), chunks::add);
        return chunks;
    }

    private LlmProvider.LlmConfig config() {
        return new LlmProvider.LlmConfig("acceptance-only", "acceptance://scripted", "acceptance-scripted", 4096, 0.0);
    }

    private String text(List<LlmProvider.StreamChunk> chunks) {
        return chunks.stream()
                .filter(chunk -> "text_delta".equals(chunk.type()))
                .map(LlmProvider.StreamChunk::content)
                .reduce("", String::concat);
    }
}