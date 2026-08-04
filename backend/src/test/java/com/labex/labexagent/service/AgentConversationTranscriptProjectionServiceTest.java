package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.Gson;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.List;
import java.util.stream.LongStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConversationTranscriptProjectionServiceTest {
    private static final Gson GSON = new Gson();

    @Test
    void projectsOnlyTheStableDurableTaskPrefixAndRemovesReasoningAndSecrets() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messageMapper = mock(AgentRunMessageMapper.class);
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(11L, "build", "completed", "api_key=supersecretvalue old request"),
                task(12L, "compact", "completed", "Manual context compaction"),
                task(13L, "build", "completed", "recent request"),
                task(14L, "build", "running", "in-flight request"),
                task(15L, "build", "completed", "must stay after the unstable gap")));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                finalMessage(11L, 101L, "<think>private reasoning</think>old answer"),
                finalMessage(13L, 103L, "recent answer"),
                finalMessage(15L, 105L, "must not be projected")));
        AgentConversationTranscriptProjectionService service =
                new AgentConversationTranscriptProjectionService(taskMapper, messageMapper);

        AgentConversationTranscriptProjectionService.Snapshot snapshot =
                service.snapshot(7, 3, "conversation", 0L, 20L);

        assertThat(snapshot.sourceMaxTaskId()).isEqualTo(13L);
        assertThat(snapshot.userTurns()).isEqualTo(2);
        assertThat(snapshot.messages()).hasSize(4);
        assertThat(snapshot.messages().toString())
                .doesNotContain("supersecretvalue", "private reasoning", "in-flight", "unstable gap")
                .contains("[REDACTED]", "old request", "old answer", "recent request", "recent answer");
    }

    @Test
    void keepsTheProviderExpandedPromptInTheModelTranscript() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messageMapper = mock(AgentRunMessageMapper.class);
        AgentTask slashTask = task(16L, "build", "completed", "Expanded provider review prompt");
        slashTask.setRequestPayload(GSON.toJson(Map.of(
                "message", "Expanded provider review prompt",
                "displayMessage", "/review src/App.vue")));
        when(taskMapper.selectList(any())).thenReturn(List.of(slashTask));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        AgentConversationTranscriptProjectionService service =
                new AgentConversationTranscriptProjectionService(taskMapper, messageMapper);

        AgentConversationTranscriptProjectionService.Snapshot snapshot =
                service.snapshot(7, 3, "conversation", 0L, null);

        assertThat(snapshot.messages()).containsExactly(
                Map.of("role", "user", "content", "Expanded provider review prompt"));
        assertThat(snapshot.messages().toString()).doesNotContain("/review src/App.vue");
    }

    @Test
    void exposesAContinuationBoundaryInsteadOfSilentlyDroppingTasksAfterTheBatchLimit() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messageMapper = mock(AgentRunMessageMapper.class);
        List<AgentTask> tasks = LongStream.rangeClosed(1, 501)
                .mapToObj(id -> task(id, "build", "completed", "request-" + id))
                .toList();
        when(taskMapper.selectList(any())).thenReturn(tasks);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        AgentConversationTranscriptProjectionService service =
                new AgentConversationTranscriptProjectionService(taskMapper, messageMapper);

        AgentConversationTranscriptProjectionService.Snapshot snapshot =
                service.snapshot(7, 3, "conversation", 0L, null);

        assertThat(snapshot.sourceMaxTaskId()).isEqualTo(500L);
        assertThat(snapshot.userTurns()).isEqualTo(500);
        assertThat(snapshot.messages()).hasSize(500);
        assertThat(snapshot.hasMore()).isTrue();
    }

    @Test
    void resumesStrictlyAfterThePreviousConversationCompactionBoundary() {
        AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
        AgentRunMessageMapper messageMapper = mock(AgentRunMessageMapper.class);
        when(taskMapper.selectList(any())).thenReturn(List.of(
                task(21L, "build", "completed", "already compacted"),
                task(22L, "build", "completed", "new durable request")));
        when(messageMapper.selectList(any())).thenReturn(List.of(
                finalMessage(21L, 201L, "old answer"),
                finalMessage(22L, 202L, "new durable answer")));
        AgentConversationTranscriptProjectionService service =
                new AgentConversationTranscriptProjectionService(taskMapper, messageMapper);

        AgentConversationTranscriptProjectionService.Snapshot snapshot =
                service.snapshot(7, 3, "conversation", 21L, 30L);

        assertThat(snapshot.sourceMaxTaskId()).isEqualTo(22L);
        assertThat(snapshot.userTurns()).isEqualTo(1);
        assertThat(snapshot.messages()).containsExactly(
                Map.of("role", "user", "content", "new durable request"),
                Map.of("role", "assistant", "content", "new durable answer"));
    }

    private AgentTask task(long taskId, String mode, String status, String message) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setConversationId("conversation");
        task.setStudentId(7);
        task.setProjectId(3);
        task.setMode(mode);
        task.setStatus(status);
        task.setTitle(message);
        task.setRequestPayload(GSON.toJson(Map.of("message", message)));
        return task;
    }

    private AgentRunMessage finalMessage(long taskId, long runMessageId, String content) {
        AgentRunMessage message = new AgentRunMessage();
        message.setRunMessageId(runMessageId);
        message.setTaskId(taskId);
        message.setConversationId("conversation");
        message.setStudentId(7);
        message.setProjectId(3);
        message.setMessageKey("assistant:final");
        message.setRole("assistant");
        message.setStatus("completed");
        message.setContent(content);
        return message;
    }
}
