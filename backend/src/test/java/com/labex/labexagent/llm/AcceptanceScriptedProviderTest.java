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
    void recognizesTheDurableQuestionContinuationAsResolved() {
        List<LlmProvider.StreamChunk> resumed = stream(
                "[acceptance:question] question continuation",
                "Durable continuation context: Resolution status: answered");

        assertTrue(resumed.stream().noneMatch(chunk -> "tool_call".equals(chunk.type())));
        assertTrue(text(resumed).contains("durable user-question interaction resumed"));
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

    @Test
    void emitsPermissionAndCompletionEvidenceScenariosDeterministically() {
        LlmProvider.StreamChunk permission = stream("[acceptance:permission] permission scenario").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("read_file", permission.toolName());
        assertTrue(permission.toolArgs().contains(".env"));

        LlmProvider.StreamChunk edit = stream("[acceptance:evidence] evidence scenario").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("write_file", edit.toolName());

        LlmProvider.StreamChunk verification = stream(
                "[acceptance:evidence] evidence scenario",
                "[Tool write_file result]\ncreated acceptance-evidence.txt").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("read_file", verification.toolName());

        LlmProvider.StreamChunk test = stream(
                "[acceptance:evidence] evidence scenario",
                "[Tool write_file result]\ncreated acceptance-evidence.txt",
                "[Tool read_file result]\n[read_file path=package.json sha256=abc]\n{}").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("run_tests", test.toolName());

        String completed = text(stream(
                "[acceptance:evidence] evidence scenario",
                "[Tool write_file result]\ncreated acceptance-evidence.txt",
                "[Tool read_file result]\n[read_file path=package.json sha256=abc]\n{}",
                "[Tool run_tests result]\npassed"));
        assertTrue(completed.contains("completion evidence"));
    }

    @Test
    void emitsAnUnverifiedWriteWithoutInventingVerification() {
        LlmProvider.StreamChunk edit = stream("[acceptance:unverified] reject false completion").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst().orElseThrow();
        assertEquals("write_file", edit.toolName());

        String finalText = text(stream("[acceptance:unverified] reject false completion",
                "[Tool write_file result]\ncreated"));
        assertTrue(finalText.contains("## Summary"));
    }

    @Test
    void distinguishesApprovedAndRejectedCommandContinuations() {
        String approved = text(stream(
                "[acceptance:approval] evidence scenario?",
                "command approval decision: approved"));
        String rejected = text(stream(
                "[acceptance:approval] evidence scenario?",
                "command approval decision: rejected"));

        assertTrue(approved.contains("approved"));
        assertTrue(rejected.contains("rejected"));
    }

    @Test
    void supportsABoundedCheckoutHoldScenario() {
        String previous = System.getProperty("labex.acceptance.hold.ms");
        System.setProperty("labex.acceptance.hold.ms", "1");
        try {
            String reply = text(stream("[acceptance:checkout-hold]"));
            assertTrue(reply.contains("checkout lease"));
        } finally {
            if (previous == null) {
                System.clearProperty("labex.acceptance.hold.ms");
            } else {
                System.setProperty("labex.acceptance.hold.ms", previous);
            }
        }
    }

    @Test
    void drivesAQuestionThenLargeNativeToolCallForDurableCompactionAcceptance() {
        LlmProvider.StreamChunk question = stream("[acceptance:compaction] long context scenario").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst().orElseThrow();
        assertEquals("question", question.toolName());

        LlmProvider.StreamChunk largeCall = stream(
                "[acceptance:compaction] long context scenario",
                "Durable continuation context: Resolution status: answered").stream()
                .filter(chunk -> "tool_call".equals(chunk.type()))
                .findFirst().orElseThrow();
        assertEquals("list_files", largeCall.toolName());
        assertTrue(largeCall.toolArgs().length() > 20_000);

        String completed = text(stream(
                "<conversation-checkpoint>[acceptance:compaction] durable summary</conversation-checkpoint>",
                "Durable continuation context: Resolution status: answered",
                "[Tool list_files result]\nREADME.md"));
        assertTrue(completed.contains("durable compaction epoch"));
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