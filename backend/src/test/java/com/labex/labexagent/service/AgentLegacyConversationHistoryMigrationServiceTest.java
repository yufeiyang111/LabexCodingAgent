package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.migration.AgentLegacyMigrationGateService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentLegacyConversationHistoryMigrationServiceTest {
    private static final Gson GSON = new Gson();

    @BeforeAll
    static void initializeMetadata() {
        for (Class<?> type : List.of(AgentConversation.class, AgentMessage.class, AgentTask.class, AgentRunEvent.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
            }
        }
    }

    @Test
    void migratesLegacyTurnsIntoTerminalTasksAndIdempotentRunEventsOnlyOnce() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunMessageMapper runMessages = mock(AgentRunMessageMapper.class);
        AgentRunPartMapper parts = mock(AgentRunPartMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentLegacyMigrationGateService migrationGates = mock(AgentLegacyMigrationGateService.class);
        AgentConversation conversation = conversation();
        when(conversations.selectOne(any())).thenReturn(conversation);
        when(conversations.updateById(any())).thenReturn(1);
        when(messages.selectList(any())).thenReturn(List.of(
                legacy(1, "USER", "first", Map.of("content", "first")),
                legacy(2, "FINAL", "answer-1", Map.of("content", "answer-1")),
                legacy(3, "USER", "second", Map.of("content", "second")),
                legacy(4, "ERROR", "failed-2", Map.of("message", "failed-2"))));
        when(tasks.selectList(any())).thenReturn(List.of());
        when(events.selectCount(any())).thenReturn(0L);
        when(runMessages.selectCount(any())).thenReturn(0L);
        when(parts.selectCount(any())).thenReturn(0L);
        AtomicLong ids = new AtomicLong(100);
        doAnswer(invocation -> {
            AgentTask task = invocation.getArgument(0);
            task.setTaskId(ids.incrementAndGet());
            return 1;
        }).when(tasks).insert(any(AgentTask.class));
        when(lifecycle.appendEvent(any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentRunEvent event = new AgentRunEvent();
            event.setTaskId(invocation.getArgument(0));
            event.setEventType(invocation.getArgument(1));
            return event;
        });

        AgentLegacyConversationHistoryMigrationService service = new AgentLegacyConversationHistoryMigrationService(
                conversations, messages, tasks, events, runMessages, parts, lifecycle, migrationGates);

        boolean migrated = service.ensureMigrated(7, 3, "legacy");
        boolean repeated = service.ensureMigrated(7, 3, "legacy");

        assertThat(migrated).isTrue();
        assertThat(repeated).isFalse();
        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(tasks, times(2)).insert(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues()).extracting(AgentTask::getStatus)
                .containsExactly("completed", "failed");
        assertThat(taskCaptor.getAllValues()).extracting(task ->
                        GSON.fromJson(task.getRequestPayload(), Map.class).get("message"))
                .containsExactly("first", "second");
        verify(lifecycle).appendEvent(eq(101L), eq("FINAL"), any(), eq("legacy-message:2"));
        verify(lifecycle).appendEvent(eq(102L), eq("ERROR"), any(), eq("legacy-message:4"));
        assertThat(conversation.getHistoryProjectionVersion()).isEqualTo("durable-v1");
        assertThat(conversation.getHistoryMigratedAt()).isNotNull();
        verify(conversations).updateById(conversation);
        verify(messages, times(1)).selectList(any());
        verify(migrationGates).recordReaderHit(
                AgentLegacyMigrationGateService.LEGACY_HISTORY_READER, 4L);
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("legacy");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setStatus(1);
        conversation.setCreateTime(LocalDateTime.of(2026, 7, 1, 10, 0));
        return conversation;
    }

    private AgentMessage legacy(long id, String type, String content, Object data) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(id);
        message.setConversationId("legacy");
        message.setStudentId(7);
        message.setProjectId(3);
        message.setEventType(type);
        message.setRole("USER".equals(type) ? "user" : "event");
        message.setContent(content);
        message.setEventData(GSON.toJson(data));
        message.setCreateTime(LocalDateTime.of(2026, 7, 1, 10, 0).plusSeconds(id));
        return message;
    }
}
