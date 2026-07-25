package com.labex.labexagent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationCheckpointCompactorTest {

    @Test
    void preservesActionableStateAndKeepsRecentTurnsIntact() {
        List<Map<String, Object>> messages = historicalMessages();
        List<Map<String, Object>> protectedTail = List.copyOf(messages.subList(messages.size() - 6, messages.size()));
        int charsBefore = messageChars(messages);
        AgentContext context = context();

        boolean compacted = new ConversationCheckpointCompactor().compact(
                messages, "修复认证失败并完成后端验证", context);

        assertTrue(compacted);
        assertEquals(7, messages.size());
        assertEquals(protectedTail, messages.subList(1, messages.size()));
        assertTrue(messageChars(messages) < charsBefore);

        String checkpoint = content(messages.get(0));
        assertTrue(checkpoint.contains("<conversation-checkpoint version=\"2\">"));
        assertTrue(checkpoint.contains("修复认证失败并完成后端验证"));
        assertTrue(checkpoint.contains("定位根因"));
        assertTrue(checkpoint.contains("补充回归测试"));
        assertTrue(checkpoint.contains("backend/src/main/java/com/labex/security/JwtUtil.java"));
        assertTrue(checkpoint.contains("backend/src/test/java/com/labex/security/JwtUtilTest.java"));
        assertTrue(checkpoint.contains("BUILD SUCCESS"));
        assertTrue(checkpoint.contains("NullPointerException"));
        assertTrue(checkpoint.contains("旧 checkpoint 中必须继续保留的决定"));
    }

    @Test
    void acceptsValidatedModelCheckpointOnlyWhenItShrinksHistoricalContext() {
        List<Map<String, Object>> messages = historicalMessages();
        List<Map<String, Object>> protectedTail = List.copyOf(messages.subList(messages.size() - 4, messages.size()));
        String checkpoint = "<conversation-checkpoint version=\"3\" source=\"model\">\n- durable state\n</conversation-checkpoint>";

        ConversationCheckpointCompactor.Result result = new ConversationCheckpointCompactor()
                .compactWithCheckpoint(messages, checkpoint, 2);

        assertTrue(result.changed());
        assertEquals(checkpoint, result.checkpoint());
        assertEquals(protectedTail, messages.subList(1, messages.size()));
    }

    @Test
    void redactsSensitiveValuesBeforeReturningDeterministicCheckpoint() {
        List<Map<String, Object>> messages = historicalMessages();
        messages.set(2, message("user", "[Tool read_file result]\napi_key=sk-abcdefghijklmnopqrstuvwxyz" + "x".repeat(1_500)));

        ConversationCheckpointCompactor.Result result = new ConversationCheckpointCompactor()
                .compactWithResult(messages, "authorization: Bearer abcdefghijklmnopqrstuvwxyz", context());

        assertTrue(result.changed());
        assertFalse(result.checkpoint().contains("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(result.checkpoint().contains("[REDACTED]"));
    }

    @Test
    void skipsCompactionWhenOnlyProtectedRecentTurnsExist() {
        List<Map<String, Object>> messages = recentOnlyMessages();
        List<Map<String, Object>> original = List.copyOf(messages);

        boolean compacted = new ConversationCheckpointCompactor().compact(
                messages, "保持不变", context());

        assertTrue(!compacted);
        assertEquals(original, messages);
    }

    private AgentContext context() {
        return new AgentContext(
                "session", 1, null, "conversation", 1L, Path.of("workspace"),
                new ArrayList<>(),
                List.of(
                        new AgentContext.PlanItem("定位根因", "检查认证链路", true),
                        new AgentContext.PlanItem("补充回归测试", "覆盖空 token", false)),
                1);
    }

    private List<Map<String, Object>> historicalMessages() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "<conversation-checkpoint version=\"2\">\n## Decisions and findings\n- 旧 checkpoint 中必须继续保留的决定：JWT 解析必须拒绝空 token。\n</conversation-checkpoint>"));
        messages.add(message("assistant", ""));
        messages.add(message("user", "[Tool edit_file result]\nModified backend/src/main/java/com/labex/security/JwtUtil.java to reject blank bearer tokens.\nCurrent engineering stage: implementation\n" + "x".repeat(1_500)));
        messages.add(message("assistant", ""));
        messages.add(message("user", "[Tool run_tests result]\nBUILD SUCCESS\nTests run: 18, Failures: 0, Errors: 0\nVerified backend/src/test/java/com/labex/security/JwtUtilTest.java\n" + "y".repeat(1_500)));
        messages.add(message("assistant", ""));
        messages.add(message("user", "[Tool run_tests result]\nBUILD FAILURE\njava.lang.NullPointerException: token must not be null\nUnresolved: add a null-token regression test.\n" + "z".repeat(1_500)));
        messages.add(message("assistant", "older assistant response"));
        messages.addAll(recentOnlyMessages());
        return messages;
    }

    private List<Map<String, Object>> recentOnlyMessages() {
        return List.of(
                message("user", "recent user result 1"),
                message("assistant", "recent assistant response 1"),
                message("user", "recent user result 2"),
                message("assistant", "recent assistant response 2"),
                message("user", "recent user result 3"),
                message("assistant", "recent assistant response 3"));
    }

    private Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }

    private String content(Map<String, Object> message) {
        return (String) message.get("content");
    }

    private int messageChars(List<Map<String, Object>> messages) {
        return messages.stream().map(this::content).mapToInt(String::length).sum();
    }
}