package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.rag.config.RagConfig;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationForkDurableBoundaryTest {
    private static final Gson GSON = new Gson();

    @BeforeAll
    static void initializeEntityMetadata() {
        initializeEntityMetadata(AgentConversation.class);
        initializeEntityMetadata(AgentMessage.class);
        initializeEntityMetadata(AgentTask.class);
    }

    private static void initializeEntityMetadata(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @Test
    void forkPersistsTheLastTaskInTheStableTerminalPrefix() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(conversations.selectOwnedForUpdate(7, 3, "source")).thenReturn(conversation("source"));
        AgentTask stable = task(11L, "completed");
        when(tasks.selectList(any())).thenReturn(List.of(
                stable,
                task(12L, "running"),
                task(13L, "completed")));
        when(tasks.selectById(11L)).thenReturn(stable);
        when(conversations.insert(any())).thenReturn(1);
        AgentConversationService service = service(conversations, messages, tasks);

        AgentConversation child = service.forkConversation(7, 3, "source", null, null);

        assertThat(child.getForkedFromTaskId()).isEqualTo(11L);
        ArgumentCaptor<AgentConversation> inserted = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversations).insert(inserted.capture());
        assertThat(inserted.getValue().getForkedFromTaskId()).isEqualTo(11L);
        assertThat(inserted.getValue().getHistoryProjectionVersion()).isEqualTo("durable-v1");
        assertThat(inserted.getValue().getHistoryMigratedAt()).isNotNull();
        verify(messages, never()).selectList(any());
    }

    @Test
    void aConversationWithoutAStableTaskDoesNotCopyInFlightLegacyEvents() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(conversations.selectOwnedForUpdate(7, 3, "source")).thenReturn(conversation("source"));
        when(tasks.selectList(any())).thenReturn(List.of(task(12L, "running")));
        when(conversations.insert(any())).thenReturn(1);
        AgentConversationService service = service(conversations, messages, tasks);

        AgentConversation child = service.forkConversation(7, 3, "source", null, null);

        assertThat(child.getForkedFromTaskId()).isNull();
        verify(messages, never()).selectList(any());
    }

    @Test
    void explicitTaskBoundaryCannotCrossTheStablePrefix() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(conversations.selectOwnedForUpdate(7, 3, "source")).thenReturn(conversation("source"));
        when(tasks.selectList(any())).thenReturn(List.of(
                task(11L, "completed"),
                task(12L, "waiting_approval"),
                task(13L, "completed")));
        AgentConversationService service = service(conversations, messages, tasks);

        assertThatThrownBy(() -> service.forkConversation(7, 3, "source", null, 13L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable");
    }

    @Test
    void legacyMessageBoundaryMapsToItsDurableTaskIdentity() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(conversations.selectOwnedForUpdate(7, 3, "source")).thenReturn(conversation("source"));
        AgentTask first = task(11L, "completed");
        when(tasks.selectList(any())).thenReturn(List.of(first, task(12L, "completed")));
        when(tasks.selectById(11L)).thenReturn(first);
        when(conversations.insert(any())).thenReturn(1);
        AgentMessage selected = new AgentMessage();
        selected.setMessageId(88L);
        selected.setConversationId("source");
        selected.setStudentId(7);
        selected.setProjectId(3);
        selected.setEventData(GSON.toJson(Map.of("taskId", 11L)));
        when(messages.selectById(88L)).thenReturn(selected);
        AgentConversationService service = service(conversations, messages, tasks);

        AgentConversation child = service.forkConversation(7, 3, "source", 88L, null);

        assertThat(child.getForkedFromTaskId()).isEqualTo(11L);
    }

    private AgentConversationService service(AgentConversationMapper conversations,
                                             AgentMessageMapper messages,
                                             AgentTaskMapper tasks) {
        return new AgentConversationService(conversations, messages, mock(RagConfig.class),
                tasks, null, "legacy");
    }

    private AgentConversation conversation(String id) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(id);
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setStatus(1);
        return conversation;
    }

    private AgentTask task(long id, String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(id);
        task.setConversationId("source");
        task.setStudentId(7);
        task.setProjectId(3);
        task.setMode("build");
        task.setStatus(status);
        LocalDateTime time = LocalDateTime.of(2026, 8, 4, 10, 0).plusSeconds(id);
        task.setCreateTime(time.minusSeconds(2));
        task.setUpdateTime(time.minusSeconds(1));
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            task.setFinishedAt(time);
        }
        task.setRequestPayload(GSON.toJson(Map.of("message", "request-" + id)));
        return task;
    }
}
