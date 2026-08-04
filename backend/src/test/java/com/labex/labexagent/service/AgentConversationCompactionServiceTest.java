package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentTask;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.runtime.CompactionAgent;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentConversationCompactionServiceTest {

    @Test
    void completesTheDurableConversationRecordBeforeWritingTheLegacyProjection() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentModelConfigService modelConfigs = mock(AgentModelConfigService.class);
        AgentConversation conversation = conversation();
        AgentTask task = compactionTask(44L);
        AgentCompactionRecord running = runningRecord(91L, 44L);
        when(conversations.getOwnedConversation(7, 3, "conversation")).thenReturn(conversation);
        when(compactions.latestCompletedConversation(7, 3, "conversation")).thenReturn(Optional.empty());
        when(memory.project(7, 3, "conversation", 44L)).thenReturn(projection());
        when(compactions.startConversation(any())).thenReturn(running);
        when(compactionAgent.compact(eq(7), any(), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.success(
                        "<conversation-checkpoint version=\"3\">durable model summary</conversation-checkpoint>",
                        17, "summary-model", false));
        AgentConversationCompactionService service = new AgentConversationCompactionService(
                conversations, memory, compactions, compactionAgent, modelConfigs);

        AgentConversationCompactionService.Result result = service.compact(
                7, 3, "conversation", null, task, CancellationToken.none());

        InOrder durabilityOrder = inOrder(compactions, conversations);
        durabilityOrder.verify(compactions).complete(eq(running), eq(result.summary()), any(Integer.class));
        durabilityOrder.verify(conversations).markCompacted(conversation);
        assertThat(result.strategy()).isEqualTo("manual_model");
        assertThat(result.compactionId()).isEqualTo(91L);
        assertThat(result.sourceMaxTaskId()).isEqualTo(13L);
        assertThat(result.conversationMetadataUpdated()).isTrue();
        verify(compactions, never()).fail(any(), any());
    }

    @Test
    void modelFailureCompletesTheSameDurableRecordWithADeterministicFallback() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentModelConfigService modelConfigs = mock(AgentModelConfigService.class);
        AgentCompactionRecord running = runningRecord(92L, 45L);
        when(conversations.getOwnedConversation(7, 3, "conversation")).thenReturn(conversation());
        when(compactions.latestCompletedConversation(7, 3, "conversation")).thenReturn(Optional.empty());
        when(memory.project(7, 3, "conversation", 45L)).thenReturn(projection());
        when(compactions.startConversation(any())).thenReturn(running);
        when(compactionAgent.compact(eq(7), any(), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.failure("scripted provider failure"));
        AgentConversationCompactionService service = new AgentConversationCompactionService(
                conversations, memory, compactions, compactionAgent, modelConfigs);

        AgentConversationCompactionService.Result result = service.compact(
                7, 3, "conversation", null, compactionTask(45L), CancellationToken.none());

        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.strategy()).isEqualTo("manual_deterministic_fallback");
        assertThat(result.summary()).contains("manual-deterministic", "old request", "old answer")
                .doesNotContain("scripted provider failure");
        verify(compactions).complete(eq(running), eq(result.summary()), any(Integer.class));
        verify(compactions, never()).fail(any(), any());
    }


    @Test
    void exposesLegacyProjectionFailureWithoutRollingBackTheCompletedAuthorityRecord() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentModelConfigService modelConfigs = mock(AgentModelConfigService.class);
        AgentConversation conversation = conversation();
        AgentCompactionRecord running = runningRecord(93L, 46L);
        when(conversations.getOwnedConversation(7, 3, "conversation")).thenReturn(conversation);
        when(compactions.latestCompletedConversation(7, 3, "conversation")).thenReturn(Optional.empty());
        when(memory.project(7, 3, "conversation", 46L)).thenReturn(projection());
        when(compactions.startConversation(any())).thenReturn(running);
        when(compactionAgent.compact(eq(7), any(), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.success(
                        "<conversation-checkpoint version=\"3\">durable summary</conversation-checkpoint>",
                        17, "summary-model", false));
        doThrow(new IllegalStateException("conversation metadata unavailable"))
                .when(conversations).markCompacted(eq(conversation));
        AgentConversationCompactionService service = new AgentConversationCompactionService(
                conversations, memory, compactions, compactionAgent, modelConfigs);

        assertThatThrownBy(() -> service.compact(
                7, 3, "conversation", null, compactionTask(46L), CancellationToken.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conversation metadata unavailable");
        verify(compactions).complete(eq(running), any(), any(Integer.class));
        verify(compactions, never()).fail(any(), any());
    }


    @Test
    void replacesANonReducingModelCheckpointWithTheDeterministicFallback() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentModelConfigService modelConfigs = mock(AgentModelConfigService.class);
        AgentCompactionRecord running = runningRecord(94L, 47L);
        when(conversations.getOwnedConversation(7, 3, "conversation")).thenReturn(conversation());
        when(compactions.latestCompletedConversation(7, 3, "conversation")).thenReturn(Optional.empty());
        when(memory.project(7, 3, "conversation", 47L)).thenReturn(projection());
        when(compactions.startConversation(any())).thenReturn(running);
        when(compactionAgent.compact(eq(7), any(), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.success(
                        "<conversation-checkpoint>" + "z".repeat(5_000) + "</conversation-checkpoint>",
                        17, "summary-model", false));
        AgentConversationCompactionService service = new AgentConversationCompactionService(
                conversations, memory, compactions, compactionAgent, modelConfigs);

        AgentConversationCompactionService.Result result = service.compact(
                7, 3, "conversation", null, compactionTask(47L), CancellationToken.none());

        assertThat(result.deterministicFallback()).isTrue();
        assertThat(result.strategy()).isEqualTo("manual_deterministic_fallback");
        assertThat(result.summary()).contains("manual-deterministic").doesNotContain("z".repeat(1_000));
        verify(compactions).complete(eq(running), eq(result.summary()), any(Integer.class));
    }

    @Test
    void failsTheRunningAuthorityWhenNeitherModelNorFallbackCanReduceTheSelectedHistory() {
        AgentConversationService conversations = mock(AgentConversationService.class);
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        CompactionAgent compactionAgent = mock(CompactionAgent.class);
        AgentModelConfigService modelConfigs = mock(AgentModelConfigService.class);
        AgentCompactionRecord running = runningRecord(95L, 48L);
        AgentModelConfig config = new AgentModelConfig();
        config.setConfigId(17);
        config.setStatus(1);
        config.setCompactionTailTurns(2);
        config.setCompactionPreserveRecentTokens(4_000);
        config.setContextWindowTokens(32_768);
        config.setMaxTokens(4_096);
        when(conversations.getOwnedConversation(7, 3, "conversation")).thenReturn(conversation());
        when(modelConfigs.resolveForStudent(7, 17)).thenReturn(config);
        when(compactions.latestCompletedConversation(7, 3, "conversation")).thenReturn(Optional.empty());
        when(memory.project(7, 3, "conversation", 48L)).thenReturn(
                new AgentConversationMemoryProjectionService.Projection(List.of(
                        Map.of("role", "user", "content", "seed one"),
                        Map.of("role", "assistant", "content", "a".repeat(300)),
                        Map.of("role", "user", "content", "seed two"),
                        Map.of("role", "assistant", "content", "b".repeat(300)),
                        Map.of("role", "user", "content", "seed three"),
                        Map.of("role", "assistant", "content", "c".repeat(300))), 13L, 3));
        when(compactions.startConversation(any())).thenReturn(running);
        when(compactionAgent.compact(eq(7), eq(config), any(), any(), any(), any()))
                .thenReturn(CompactionAgent.Result.success(
                        "<conversation-checkpoint>" + "z".repeat(5_000) + "</conversation-checkpoint>",
                        17, "summary-model", false));
        AgentConversationCompactionService service = new AgentConversationCompactionService(
                conversations, memory, compactions, compactionAgent, modelConfigs);

        assertThatThrownBy(() -> service.compact(
                7, 3, "conversation", 17, compactionTask(48L), CancellationToken.none()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not reduce");
        verify(compactions).fail(eq(running), eq("Conversation compaction did not reduce durable context"));
        verify(compactions, never()).complete(any(), any(), any(Integer.class));
        verify(conversations, never()).markCompacted(any());
    }

    private AgentConversationMemoryProjectionService.Projection projection() {
        return new AgentConversationMemoryProjectionService.Projection(List.of(
                Map.of("role", "user", "content", "old request " + "x".repeat(600)),
                Map.of("role", "assistant", "content", "old answer " + "y".repeat(600)),
                Map.of("role", "user", "content", "recent request"),
                Map.of("role", "assistant", "content", "recent answer")), 13L, 2);
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        return conversation;
    }

    private AgentTask compactionTask(long taskId) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setConversationId("conversation");
        task.setStudentId(7);
        task.setProjectId(3);
        task.setMode("compact");
        task.setExecutionEpoch(2L);
        return task;
    }

    private AgentCompactionRecord runningRecord(long compactionId, long taskId) {
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setCompactionId(compactionId);
        record.setTaskId(taskId);
        record.setConversationId("conversation");
        record.setScope("conversation");
        record.setCompactionEpoch(1L);
        record.setStatus("running");
        record.setSourceMaxTaskId(13L);
        return record;
    }
}
