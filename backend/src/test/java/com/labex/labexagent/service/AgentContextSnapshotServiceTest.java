package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentSsePublisher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentContextSnapshotServiceTest {

    private final AgentContextSnapshotService service = new AgentContextSnapshotService();
    private final Gson gson = new Gson();

    @Test
    void recordsFullAssembledContextToJsonAndMarkdownFiles(@TempDir Path tempDir) throws Exception {
        StudentProject project = new StudentProject();
        project.setWorkspacePath(tempDir.toString());

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "实现一个斐波那契函数"),
                Map.of("role", "assistant", "content", "正在分析...", "tool_calls", List.of(
                        Map.of("id", "call-1", "type", "function", "function", Map.of("name", "write_file", "arguments", "{\"path\":\"fib.js\"}"))
                )),
                Map.of("role", "tool", "tool_call_id", "call-1", "name", "write_file", "content", "{\"success\":true}")
        );

        List<Map<String, Object>> tools = List.of(
                Map.of("name", "write_file", "description", "写入文件")
        );

        String relativePath = service.recordSnapshot(
                project, 101L, 1, "conv-test-1",
                "openai_compatible", "deepseek-chat",
                "You are LabexAgent, a programming assistant.",
                messages, tools, 1280);

        assertEquals(".labex/context-history/task-101-iter-1.json", relativePath);

        Path jsonFile = tempDir.resolve(relativePath);
        assertTrue(Files.isRegularFile(jsonFile), "JSON snapshot file must be created on disk");

        String jsonContent = Files.readString(jsonFile, StandardCharsets.UTF_8);
        JsonObject obj = gson.fromJson(jsonContent, JsonObject.class);
        assertEquals(101L, obj.get("taskId").getAsLong());
        assertEquals(1, obj.get("iteration").getAsInt());
        assertEquals("deepseek-chat", obj.get("model").getAsString());
        assertEquals("openai_compatible", obj.get("provider").getAsString());
        assertEquals(3, obj.get("messageCount").getAsInt());
        assertEquals(1, obj.get("toolCount").getAsInt());
        assertEquals("You are LabexAgent, a programming assistant.", obj.get("systemPrompt").getAsString());

        // 验证配套的 Markdown 文件也同步生成
        Path mdFile = tempDir.resolve(".labex/context-history/task-101-iter-1.md");
        assertTrue(Files.isRegularFile(mdFile), "Markdown summary file must be created on disk");
        String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);
        assertTrue(mdContent.contains("LLM Assembled Context Snapshot"));
        assertTrue(mdContent.contains("deepseek-chat"));
        assertTrue(mdContent.contains("实现一个斐波那契函数"));
        assertTrue(mdContent.contains("write_file"));
    }

    @Test
    void publishesTransientSseEventWithFullPayload() throws Exception {
        AgentSsePublisher sse = mock(AgentSsePublisher.class);

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "ping")
        );

        service.publishSnapshotToSse(
                sse, 202L, 2, "conv-2", "openai_compatible", "gpt-4o",
                "System instructions", messages, List.of(), 500, ".labex/context-history/task-202-iter-2.json");

        verify(sse).sendTransient(eq("MODEL_CONTEXT_SNAPSHOT"), any(Map.class));
    }

    @Test
    void safelyHandlesNullProjectOrPath() {
        StudentProject emptyProject = new StudentProject();
        String result = service.recordSnapshot(
                emptyProject, 1L, 1, "c", "p", "m", "sys", List.of(), List.of(), 100);
        assertNull(result);

        String nullResult = service.recordSnapshot(
                null, 1L, 1, "c", "p", "m", "sys", List.of(), List.of(), 100);
        assertNull(nullResult);
    }
}
