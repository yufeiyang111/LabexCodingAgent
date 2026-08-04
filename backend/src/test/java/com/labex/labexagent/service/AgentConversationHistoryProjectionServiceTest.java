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
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunMessageService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AgentConversationHistoryProjectionServiceTest {
    private static final Gson GSON = new Gson();

    @BeforeAll
    static void initializeMetadata() {
        for (Class<?> type : List.of(AgentConversation.class, AgentTask.class, AgentRunEvent.class)) {
            if (TableInfoHelper.getTableInfo(type) == null) {
                TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), type);
            }
        }
    }

    @Test
    void pagesStableTaskTurnsAndHydratesEventsMessagesAndPartsWithoutLegacyRows() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentLegacyConversationHistoryMigrationService migration = mock(AgentLegacyConversationHistoryMigrationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        when(conversations.selectOne(any())).thenReturn(conversation("conversation", null, null));
        when(tasks.selectList(any())).thenReturn(List.of(task(30, "conversation"), task(20, "conversation"), task(10, "conversation")));
        when(events.selectList(any())).thenReturn(List.of(event(20, 1, "THINK"), event(20, 2, "FINAL"), event(30, 1, "FINAL")));
        when(runMessages.publicHistoryByTaskIds(List.of(20L, 30L))).thenReturn(Map.of(
                20L, List.of(Map.of("messageKey", "assistant:final", "content", "answer-20")),
                30L, List.of(Map.of("messageKey", "assistant:final", "content", "answer-30"))));
        when(parts.publicHistoryByTaskIds(List.of(20L, 30L))).thenReturn(Map.of(
                20L, List.of(Map.of("partKey", "final", "partType", "text", "output", "answer-20")),
                30L, List.of(Map.of("partKey", "final", "partType", "text", "output", "answer-30"))));

        AgentConversationHistoryProjectionService service = new AgentConversationHistoryProjectionService(
                conversations, tasks, events, runMessages, parts, null, migration, memory);

        AgentConversationHistoryProjectionService.HistoryPage page = service.page(7, 3, "conversation", null, 2);

        assertThat(page.projectionVersion()).isEqualTo("durable-task-history-v1");
        assertThat(page.turns()).extracting(AgentConversationHistoryProjectionService.HistoryTurn::taskId)
                .containsExactly(20L, 30L);
        assertThat(page.turns()).extracting(AgentConversationHistoryProjectionService.HistoryTurn::userContent)
                .containsExactly("request-20", "request-30");
        assertThat(page.turns().get(0).events()).extracting(AgentConversationHistoryProjectionService.HistoryEvent::sequence)
                .containsExactly(1L, 2L);
        assertThat(page.turns().get(0).runMessages()).hasSize(1);
        assertThat(page.turns().get(0).parts()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextBeforeTaskId()).isEqualTo(20L);
        verify(migration).ensureMigrated(7, 3, "conversation");
    }

    @Test
    void recursivelyIncludesOnlyTheImmutableParentForkBoundary() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentLegacyConversationHistoryMigrationService migration = mock(AgentLegacyConversationHistoryMigrationService.class);
        AgentConversationForkBoundaryService boundaries = mock(AgentConversationForkBoundaryService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentConversation child = conversation("child", "parent", 20L);
        AgentConversation parent = conversation("parent", null, null);
        when(conversations.selectOne(any())).thenReturn(child, parent);
        when(tasks.selectList(any())).thenReturn(
                List.of(task(21, "parent"), task(20, "parent"), task(10, "parent")),
                List.of(task(30, "child")));
        when(events.selectList(any())).thenReturn(List.of());
        when(runMessages.publicHistoryByTaskIds(List.of(10L, 20L, 30L))).thenReturn(Map.of());
        when(parts.publicHistoryByTaskIds(List.of(10L, 20L, 30L))).thenReturn(Map.of());

        AgentConversationHistoryProjectionService service = new AgentConversationHistoryProjectionService(
                conversations, tasks, events, runMessages, parts, boundaries, migration, memory);

        AgentConversationHistoryProjectionService.HistoryPage page = service.page(7, 3, "child", null, 20);

        assertThat(page.turns()).extracting(AgentConversationHistoryProjectionService.HistoryTurn::taskId)
                .containsExactly(10L, 20L, 30L);
        assertThat(page.turns()).extracting(AgentConversationHistoryProjectionService.HistoryTurn::sourceConversationId)
                .containsExactly("parent", "parent", "child");
        assertThat(page.turns()).extracting(AgentConversationHistoryProjectionService.HistoryTurn::inherited)
                .containsExactly(true, true, false);
    }

    @Test
    void prefersTheUserVisibleRequestOverTheProviderExpandedPrompt() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunMessageService runMessages = mock(AgentRunMessageService.class);
        AgentRunPartService parts = mock(AgentRunPartService.class);
        AgentLegacyConversationHistoryMigrationService migration =
                mock(AgentLegacyConversationHistoryMigrationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentTask slashTask = task(40, "conversation");
        slashTask.setRequestPayload(GSON.toJson(Map.of(
                "message", "Expanded provider review prompt",
                "displayMessage", "/review src/App.vue")));
        when(conversations.selectOne(any())).thenReturn(conversation("conversation", null, null));
        when(tasks.selectList(any())).thenReturn(List.of(slashTask));
        when(events.selectList(any())).thenReturn(List.of());
        when(runMessages.publicHistoryByTaskIds(List.of(40L))).thenReturn(Map.of());
        when(parts.publicHistoryByTaskIds(List.of(40L))).thenReturn(Map.of());

        AgentConversationHistoryProjectionService service = new AgentConversationHistoryProjectionService(
                conversations, tasks, events, runMessages, parts, null, migration, memory);

        AgentConversationHistoryProjectionService.HistoryPage page =
                service.page(7, 3, "conversation", null, 20);

        assertThat(page.turns()).extracting(AgentConversationHistoryProjectionService.HistoryTurn::userContent)
                .containsExactly("/review src/App.vue");
    }

    @Test
    void rejectsConversationCyclesInsteadOfSilentlyTruncatingHistory() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversation first = conversation("first", "second", 10L);
        AgentConversation second = conversation("second", "first", 9L);
        when(conversations.selectOne(any())).thenReturn(first, second, first);
        AgentConversationHistoryProjectionService service = new AgentConversationHistoryProjectionService(
                conversations, mock(AgentTaskMapper.class), mock(AgentRunEventMapper.class),
                mock(AgentRunMessageService.class), mock(AgentRunPartService.class),
                mock(AgentConversationForkBoundaryService.class),
                mock(AgentLegacyConversationHistoryMigrationService.class),
                mock(AgentConversationMemoryProjectionService.class));

        assertThatThrownBy(() -> service.page(7, 3, "first", null, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void durableStatsUseTheSameConversationGraphAndNeverLegacyMessageCounts() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        when(conversations.selectOne(any())).thenReturn(conversation("conversation", null, null));
        when(memory.project(7, 3, "conversation", null)).thenReturn(
                new AgentConversationMemoryProjectionService.Projection(List.of(
                        Map.of("role", "user", "content", "question"),
                        Map.of("role", "assistant", "content", "answer")), 12L, 1));
        AgentLegacyConversationHistoryMigrationService migration = mock(AgentLegacyConversationHistoryMigrationService.class);
        AgentConversationHistoryProjectionService service = new AgentConversationHistoryProjectionService(
                conversations, mock(AgentTaskMapper.class), mock(AgentRunEventMapper.class),
                mock(AgentRunMessageService.class), mock(AgentRunPartService.class), null, migration, memory);

        AgentConversationHistoryProjectionService.MemoryStats stats = service.memoryStats(7, 3, "conversation");

        assertThat(stats.messageCount()).isEqualTo(2);
        assertThat(stats.estimatedTokens()).isPositive();
        assertThat(stats.maxTokens()).isEqualTo(12_000);
        verify(migration).ensureMigrated(7, 3, "conversation");
    }

    private AgentConversation conversation(String id, String parent, Long boundary) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(id);
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setParentConversationId(parent);
        conversation.setForkedFromTaskId(boundary);
        conversation.setHistoryProjectionVersion("durable-v1");
        conversation.setStatus(1);
        return conversation;
    }

    private AgentTask task(long id, String conversationId) {
        AgentTask task = new AgentTask();
        task.setTaskId(id);
        task.setConversationId(conversationId);
        task.setStudentId(7);
        task.setProjectId(3);
        task.setSessionId("session-" + id);
        task.setMode("build");
        task.setStatus("completed");
        task.setTitle("request-" + id);
        task.setRequestPayload(GSON.toJson(Map.of("message", "request-" + id)));
        task.setLastEventSequence(2L);
        task.setCreateTime(LocalDateTime.of(2026, 8, 4, 10, 0).plusSeconds(id));
        task.setUpdateTime(task.getCreateTime());
        return task;
    }

    private AgentRunEvent event(long taskId, long sequence, String type) {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(taskId * 100 + sequence);
        event.setTaskId(taskId);
        event.setStudentId(7);
        event.setProjectId(3);
        event.setSequenceNumber(sequence);
        event.setState("completed");
        event.setEventType(type);
        event.setPayload(GSON.toJson("FINAL".equals(type)
                ? Map.of("content", "answer-" + taskId)
                : Map.of("content", "thinking-" + taskId)));
        event.setIdempotencyKey("event-" + taskId + "-" + sequence);
        event.setCreateTime(LocalDateTime.of(2026, 8, 4, 10, 0).plusSeconds(sequence));
        return event;
    }
}
