package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationMessageGraphServiceTest {

    @Test
    void appendsRealUserMessageWithConversationSequenceAndTaskAnchor() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentConversation conversation = conversation("conv-a", 7L);
        AgentTask task = task(1001L, "conv-a");
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(tasks.selectByTaskIdForUpdate(1001L)).thenReturn(task);
        when(messages.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            AgentRunMessage inserted = invocation.getArgument(0);
            inserted.setRunMessageId(501L);
            return 1;
        }).when(messages).insert(any(AgentRunMessage.class));
        when(conversations.updateById(any(AgentConversation.class))).thenReturn(1);
        when(tasks.updateById(any(AgentTask.class))).thenReturn(1);

        AgentConversationMessageGraphService service = new AgentConversationMessageGraphService(
                conversations, tasks, messages, parts);

        AgentConversationMessageGraphService.MessageRef appended = service.appendUserRequest(
                new AgentConversationMessageGraphService.UserRequest(
                        1001L, 17, 23, "conv-a", "request:1001", "删除 Skill", "{}"));

        assertThat(appended.messageId()).isEqualTo(501L);
        assertThat(appended.conversationSequence()).isEqualTo(8L);
        assertThat(appended.role()).isEqualTo("user");
        assertThat(appended.parentMessageId()).isNull();

        ArgumentCaptor<AgentRunMessage> messageCaptor = ArgumentCaptor.forClass(AgentRunMessage.class);
        verify(messages).insert(messageCaptor.capture());
        AgentRunMessage inserted = messageCaptor.getValue();
        assertThat(inserted.getTaskId()).isEqualTo(1001L);
        assertThat(inserted.getConversationId()).isEqualTo("conv-a");
        assertThat(inserted.getConversationSequence()).isEqualTo(8L);
        assertThat(inserted.getRole()).isEqualTo("user");
        assertThat(inserted.getContent()).isEqualTo("删除 Skill");

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(tasks).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getOriginMessageId()).isEqualTo(501L);

        ArgumentCaptor<AgentConversation> conversationCaptor = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversations).updateById(conversationCaptor.capture());
        assertThat(conversationCaptor.getValue().getNextMessageSequence()).isEqualTo(8L);
    }

    @Test
    void retriesSameUserRequestWithoutCreatingSecondGraphNode() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentConversation conversation = conversation("conv-a", 8L);
        AgentTask task = task(1001L, "conv-a");
        AgentRunMessage existing = message(501L, 8L, 1001L, "conv-a", "request:1001", "user", null, "删除 Skill");
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(tasks.selectByTaskIdForUpdate(1001L)).thenReturn(task);
        when(messages.selectOne(any(Wrapper.class))).thenReturn(existing);

        AgentConversationMessageGraphService service = new AgentConversationMessageGraphService(
                conversations, tasks, messages, parts);

        AgentConversationMessageGraphService.MessageRef appended = service.appendUserRequest(
                new AgentConversationMessageGraphService.UserRequest(
                        1001L, 17, 23, "conv-a", "request:1001", "删除 Skill", "{}"));

        assertThat(appended.messageId()).isEqualTo(501L);
        assertThat(appended.conversationSequence()).isEqualTo(8L);
        verify(messages, never()).insert(any(AgentRunMessage.class));
        verify(conversations, never()).updateById(any(AgentConversation.class));
        verify(tasks, never()).updateById(any(AgentTask.class));
    }

    @Test
    void appendsAssistantMessageUnderTheSpecifiedRealUserNode() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentConversation conversation = conversation("conv-a", 8L);
        AgentTask task = task(1001L, "conv-a");
        AgentRunMessage user = message(501L, 8L, 1001L, "conv-a", "request:1001", "user", null, "删除 Skill");
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(tasks.selectByTaskIdForUpdate(1001L)).thenReturn(task);
        when(messages.selectOne(any(Wrapper.class))).thenReturn(null);
        when(messages.selectById(501L)).thenReturn(user);
        doAnswer(invocation -> {
            AgentRunMessage inserted = invocation.getArgument(0);
            inserted.setRunMessageId(502L);
            return 1;
        }).when(messages).insert(any(AgentRunMessage.class));
        when(conversations.updateById(any(AgentConversation.class))).thenReturn(1);

        AgentConversationMessageGraphService service = new AgentConversationMessageGraphService(
                conversations, tasks, messages, parts);

        AgentConversationMessageGraphService.MessageRef appended = service.beginAssistantMessage(
                new AgentConversationMessageGraphService.AssistantRequest(
                        1001L, 17, 23, "conv-a", "assistant:1001:1", 501L, "{}"));

        assertThat(appended.messageId()).isEqualTo(502L);
        assertThat(appended.conversationSequence()).isEqualTo(9L);
        assertThat(appended.role()).isEqualTo("assistant");
        assertThat(appended.parentMessageId()).isEqualTo(501L);

        ArgumentCaptor<AgentRunMessage> messageCaptor = ArgumentCaptor.forClass(AgentRunMessage.class);
        verify(messages).insert(messageCaptor.capture());
        AgentRunMessage inserted = messageCaptor.getValue();
        assertThat(inserted.getRole()).isEqualTo("assistant");
        assertThat(inserted.getParentMessageId()).isEqualTo(501L);
        assertThat(inserted.getConversationSequence()).isEqualTo(9L);
    }

    @Test
    void updatesOneToolPartFromRunningToFailedWithoutCreatingRoleToolMessage() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentConversation conversation = conversation("conv-a", 9L);
        AgentTask task = task(1001L, "conv-a");
        AgentRunMessage assistant = message(502L, 9L, 1001L, "conv-a",
                "assistant:1001:1", "assistant", 501L, "我先读取配置。");
        AgentRunPart persisted = toolPart(701L, 1001L, 502L, "conv-a", "tool:call-read",
                "running", "call-read", "read_file", "{\"path\":\"pom.xml\"}", null);
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(tasks.selectByTaskIdForUpdate(1001L)).thenReturn(task);
        when(messages.selectById(502L)).thenReturn(assistant);
        when(parts.selectOne(any(Wrapper.class))).thenReturn(null, persisted);
        doAnswer(invocation -> {
            AgentRunPart inserted = invocation.getArgument(0);
            inserted.setPartId(701L);
            return 1;
        }).when(parts).insert(any(AgentRunPart.class));
        when(parts.updateById(any(AgentRunPart.class))).thenReturn(1);

        AgentConversationMessageGraphService service = new AgentConversationMessageGraphService(
                conversations, tasks, messages, parts);

        service.upsertToolPart(new AgentConversationMessageGraphService.ToolPartRequest(
                1001L, 17, 23, "conv-a", 502L, "tool:call-read", 1L, "running",
                "call-read", "read_file", "{\"path\":\"pom.xml\"}", null, "{}"));
        service.upsertToolPart(new AgentConversationMessageGraphService.ToolPartRequest(
                1001L, 17, 23, "conv-a", 502L, "tool:call-read", 1L, "failed",
                "call-read", "read_file", "{\"path\":\"pom.xml\"}", "文件不存在", "{}"));

        ArgumentCaptor<AgentRunPart> insertedCaptor = ArgumentCaptor.forClass(AgentRunPart.class);
        verify(parts).insert(insertedCaptor.capture());
        assertThat(insertedCaptor.getValue())
                .extracting(AgentRunPart::getMessageId, AgentRunPart::getPartType, AgentRunPart::getStatus,
                        AgentRunPart::getToolCallId, AgentRunPart::getToolName)
                .containsExactly(502L, "tool", "running", "call-read", "read_file");

        ArgumentCaptor<AgentRunPart> updatedCaptor = ArgumentCaptor.forClass(AgentRunPart.class);
        verify(parts).updateById(updatedCaptor.capture());
        assertThat(updatedCaptor.getValue())
                .extracting(AgentRunPart::getStatus, AgentRunPart::getOutputText)
                .containsExactly("failed", "文件不存在");
        verify(messages, never()).insert(any(AgentRunMessage.class));
    }

    @Test
    void completesAssistantMessageWithoutChangingItsGraphIdentity() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentConversation conversation = conversation("conv-a", 9L);
        AgentTask task = task(1001L, "conv-a");
        AgentRunMessage assistant = message(502L, 9L, 1001L, "conv-a",
                "assistant:1001:1", "assistant", 501L, "");
        assistant.setStatus("streaming");
        when(conversations.selectOwnedForUpdate(17, 23, "conv-a")).thenReturn(conversation);
        when(tasks.selectByTaskIdForUpdate(1001L)).thenReturn(task);
        when(messages.selectById(502L)).thenReturn(assistant);
        when(messages.updateById(any(AgentRunMessage.class))).thenReturn(1);

        AgentConversationMessageGraphService service = new AgentConversationMessageGraphService(
                conversations, tasks, messages, parts);

        AgentConversationMessageGraphService.MessageRef completed = service.completeAssistantMessage(
                new AgentConversationMessageGraphService.AssistantCompletion(
                        1001L, 17, 23, "conv-a", 502L, "已经完成方案。", "{}"));

        assertThat(completed)
                .extracting(AgentConversationMessageGraphService.MessageRef::messageId,
                        AgentConversationMessageGraphService.MessageRef::conversationSequence,
                        AgentConversationMessageGraphService.MessageRef::parentMessageId)
                .containsExactly(502L, 9L, 501L);
        ArgumentCaptor<AgentRunMessage> updatedCaptor = ArgumentCaptor.forClass(AgentRunMessage.class);
        verify(messages).updateById(updatedCaptor.capture());
        assertThat(updatedCaptor.getValue())
                .extracting(AgentRunMessage::getStatus, AgentRunMessage::getContent,
                        AgentRunMessage::getParentMessageId, AgentRunMessage::getConversationSequence)
                .containsExactly("completed", "已经完成方案。", 501L, 9L);
    }

    private AgentConversation conversation(String conversationId, long nextMessageSequence) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(conversationId);
        conversation.setStudentId(17);
        conversation.setProjectId(23);
        conversation.setStatus(1);
        conversation.setNextMessageSequence(nextMessageSequence);
        return conversation;
    }

    private AgentTask task(long taskId, String conversationId) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setStudentId(17);
        task.setProjectId(23);
        task.setConversationId(conversationId);
        return task;
    }

    private AgentRunPart toolPart(Long partId, Long taskId, Long messageId, String conversationId,
                                  String partKey, String status, String toolCallId, String toolName,
                                  String inputJson, String outputText) {
        AgentRunPart part = new AgentRunPart();
        part.setPartId(partId);
        part.setTaskId(taskId);
        part.setMessageId(messageId);
        part.setConversationId(conversationId);
        part.setStudentId(17);
        part.setProjectId(23);
        part.setPartKey(partKey);
        part.setSequenceNumber(1L);
        part.setPartType("tool");
        part.setStatus(status);
        part.setToolCallId(toolCallId);
        part.setToolName(toolName);
        part.setInputJson(inputJson);
        part.setOutputText(outputText);
        return part;
    }

    private AgentRunMessage message(Long id, Long sequence, Long taskId, String conversationId,
                                    String key, String role, Long parentMessageId, String content) {
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(id);
        message.setConversationSequence(sequence);
        message.setTaskId(taskId);
        message.setConversationId(conversationId);
        message.setStudentId(17);
        message.setProjectId(23);
        message.setMessageKey(key);
        message.setRole(role);
        message.setParentMessageId(parentMessageId);
        message.setContent(content);
        return message;
    }
}

