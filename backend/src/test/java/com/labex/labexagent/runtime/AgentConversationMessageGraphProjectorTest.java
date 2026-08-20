package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConversationMessageGraphProjectorTest {

    @Test
    void projectsCrossTaskConversationHistoryFromOneToolPartWithoutFinalSummaryPrefix() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        when(messages.selectList(any(Wrapper.class))).thenReturn(List.of(
                message(101L, 1L, 1001L, "user", null, "先分析代码"),
                message(102L, 2L, 1001L, "assistant", 101L, "我先读取配置。"),
                message(103L, 3L, 1001L, "assistant", 101L, "结论：采用方案一。"),
                message(201L, 4L, 1002L, "user", null, "按刚刚的方案开始实现")));
        when(parts.selectList(any(Wrapper.class))).thenReturn(List.of(
                toolPart(901L, 102L, "call-read", "read_file", "completed", "{\"path\":\"pom.xml\"}",
                        "<project><name>labex</name></project>")));

        AgentConversationMessageGraphProjector projector = new AgentConversationMessageGraphProjector(
                messages, parts, new AgentProviderMessageProjector());

        List<Map<String, Object>> projected = projector.projectForProvider(
                new AgentConversationMessageGraphProjector.Request(17, 23, "conv-a"));

        assertThat(projected).hasSize(5);
        assertThat(projected.get(0)).containsEntry("role", "user").containsEntry("content", "先分析代码");
        assertThat(projected.get(1)).containsEntry("role", "assistant").containsEntry("content", "我先读取配置。");
        assertThat(projected.get(1).get("tool_calls")).isEqualTo(List.of(Map.of(
                "id", "call-read",
                "type", "function",
                "function", Map.of("name", "read_file", "arguments", "{\"path\":\"pom.xml\"}"))));
        assertThat(projected.get(2)).containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-read")
                .containsEntry("name", "read_file")
                .containsEntry("content", "<project><name>labex</name></project>");
        assertThat(projected.get(3)).containsEntry("role", "assistant").containsEntry("content", "结论：采用方案一。");
        assertThat(projected.get(4)).containsEntry("role", "user").containsEntry("content", "按刚刚的方案开始实现");
        assertThat(projected).noneMatch(message -> String.valueOf(message.get("content")).contains("历史任务摘要"));
    }

    @Test
    void projectsFailureFromTheSameToolPartWithoutConvertingItToSuccess() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        when(messages.selectList(any(Wrapper.class))).thenReturn(List.of(
                message(101L, 1L, 1001L, "user", null, "运行测试"),
                message(102L, 2L, 1001L, "assistant", 101L, "")));
        when(parts.selectList(any(Wrapper.class))).thenReturn(List.of(
                toolPart(901L, 102L, "call-test", "shell", "failed", "{\"command\":\"mvn test\"}",
                        "exit=1：测试失败")));

        AgentConversationMessageGraphProjector projector = new AgentConversationMessageGraphProjector(
                messages, parts, new AgentProviderMessageProjector());

        List<Map<String, Object>> projected = projector.projectForProvider(
                new AgentConversationMessageGraphProjector.Request(17, 23, "conv-a"));

        assertThat(projected.get(2)).containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-test")
                .containsEntry("content", "exit=1：测试失败");
    }

    @Test
    void rejectsAnUnfinishedToolPartRatherThanInventingAToolResult() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        when(messages.selectList(any(Wrapper.class))).thenReturn(List.of(
                message(101L, 1L, 1001L, "user", null, "读取文件"),
                message(102L, 2L, 1001L, "assistant", 101L, "")));
        when(parts.selectList(any(Wrapper.class))).thenReturn(List.of(
                toolPart(901L, 102L, "call-read", "read_file", "running", "{\"path\":\"pom.xml\"}", "")));

        AgentConversationMessageGraphProjector projector = new AgentConversationMessageGraphProjector(
                messages, parts, new AgentProviderMessageProjector());

        assertThatThrownBy(() -> projector.projectForProvider(
                new AgentConversationMessageGraphProjector.Request(17, 23, "conv-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unfinished tool part")
                .hasMessageContaining("call-read");
    }

    @Test
    void failsClosedWhenMapperReturnsAMessageFromAnotherConversation() {
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunMessage foreign = message(101L, 1L, 1001L, "user", null, "不应泄漏");
        foreign.setConversationId("conv-b");
        when(messages.selectList(any(Wrapper.class))).thenReturn(List.of(foreign));
        when(parts.selectList(any(Wrapper.class))).thenReturn(List.of());

        AgentConversationMessageGraphProjector projector = new AgentConversationMessageGraphProjector(
                messages, parts, new AgentProviderMessageProjector());

        assertThatThrownBy(() -> projector.projectForProvider(
                new AgentConversationMessageGraphProjector.Request(17, 23, "conv-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match conversation ownership");
    }

    private AgentRunMessage message(Long id, Long sequence, Long taskId, String role, Long parentMessageId,
                                    String content) {
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(id);
        message.setConversationSequence(sequence);
        message.setTaskId(taskId);
        message.setConversationId("conv-a");
        message.setStudentId(17);
        message.setProjectId(23);
        message.setRole(role);
        message.setParentMessageId(parentMessageId);
        message.setContent(content);
        message.setStatus("completed");
        return message;
    }

    private AgentRunPart toolPart(Long id, Long messageId, String toolCallId, String toolName, String status,
                                  String inputJson, String outputText) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(id);
        part.setTaskId(1001L);
        part.setConversationId("conv-a");
        part.setMessageId(messageId);
        part.setStudentId(17);
        part.setProjectId(23);
        part.setPartType("tool");
        part.setStatus(status);
        part.setSequenceNumber(1L);
        part.setToolCallId(toolCallId);
        part.setToolName(toolName);
        part.setInputJson(inputJson);
        part.setOutputText(outputText);
        return part;
    }
}
