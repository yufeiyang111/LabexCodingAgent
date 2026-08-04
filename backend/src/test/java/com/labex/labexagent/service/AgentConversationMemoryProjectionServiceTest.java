package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.mapper.AgentConversationMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentConversationMemoryProjectionServiceTest {

    @Test
    void forkProjectsOnlyTheImmutableParentBoundaryThenItsOwnTasks() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationTranscriptProjectionService transcript = mock(AgentConversationTranscriptProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        AgentConversation child = conversation("child", "parent", 11L);
        AgentConversation parent = conversation("parent", null, null);
        when(conversations.selectOne(any())).thenReturn(child, parent);
        when(compactions.latestCompletedConversationAtOrBeforeTaskId(7, 3, "child", null))
                .thenReturn(Optional.empty());
        when(compactions.latestCompletedConversationAtOrBeforeTaskId(7, 3, "parent", 11L))
                .thenReturn(Optional.empty());
        when(transcript.snapshot(7, 3, "parent", 0L, 12L)).thenReturn(snapshot(
                11L, message("user", "parent-before-fork"), message("assistant", "parent-answer")));
        when(transcript.snapshot(7, 3, "child", 11L, null)).thenReturn(snapshot(
                21L, message("user", "child-request"), message("assistant", "child-answer")));
        AgentConversationMemoryProjectionService service =
                new AgentConversationMemoryProjectionService(conversations, transcript, compactions);

        AgentConversationMemoryProjectionService.Projection projected = service.project(7, 3, "child", null);

        assertThat(projected.sourceMaxTaskId()).isEqualTo(21L);
        assertThat(projected.messages()).containsExactly(
                message("user", "parent-before-fork"),
                message("assistant", "parent-answer"),
                message("user", "child-request"),
                message("assistant", "child-answer"));
        assertThat(projected.messages().toString()).doesNotContain("parent-after-fork");
    }

    @Test
    void forkReusesTheLatestParentCheckpointEligibleAtItsBoundary() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationTranscriptProjectionService transcript = mock(AgentConversationTranscriptProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        AgentConversation child = conversation("child", "parent", 11L);
        AgentConversation parent = conversation("parent", null, null);
        AgentCompactionRecord checkpoint = completedCompaction("parent", 10L);
        when(conversations.selectOne(any())).thenReturn(child, parent);
        when(compactions.latestCompletedConversationAtOrBeforeTaskId(7, 3, "child", null))
                .thenReturn(Optional.empty());
        when(compactions.latestCompletedConversationAtOrBeforeTaskId(7, 3, "parent", 11L))
                .thenReturn(Optional.of(checkpoint));
        when(transcript.snapshot(7, 3, "parent", 10L, 12L)).thenReturn(snapshot(
                11L, message("user", "parent-tail"), message("assistant", "tail-answer")));
        when(compactions.projectConversation(eq(checkpoint), any())).thenReturn(
                new AgentCompactionService.ConversationProjection(List.of(
                        message("user", "<conversation-checkpoint>parent-summary</conversation-checkpoint>"),
                        message("user", "parent-tail"),
                        message("assistant", "tail-answer")), 2L, 10L));
        when(transcript.snapshot(7, 3, "child", 11L, null)).thenReturn(snapshot(
                21L, message("user", "child-request"), message("assistant", "child-answer")));
        AgentConversationMemoryProjectionService service =
                new AgentConversationMemoryProjectionService(conversations, transcript, compactions);

        AgentConversationMemoryProjectionService.Projection projected = service.project(7, 3, "child", null);

        assertThat(projected.messages().toString())
                .contains("parent-summary", "parent-tail", "child-request");
        verify(compactions).projectConversation(eq(checkpoint), any());
    }

    @Test
    void ownCompletedCheckpointStopsWalkingTheParentGraph() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationTranscriptProjectionService transcript = mock(AgentConversationTranscriptProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        AgentConversation child = conversation("child", "parent", 11L);
        AgentCompactionRecord own = completedCompaction("child", 21L);
        when(conversations.selectOne(any())).thenReturn(child);
        when(compactions.latestCompletedConversationAtOrBeforeTaskId(7, 3, "child", null))
                .thenReturn(Optional.of(own));
        when(transcript.snapshot(7, 3, "child", 21L, null)).thenReturn(snapshot(
                22L, message("user", "after-own-compaction"), message("assistant", "new-answer")));
        when(compactions.projectConversation(eq(own), any())).thenReturn(
                new AgentCompactionService.ConversationProjection(List.of(
                        message("user", "<conversation-checkpoint>own-summary</conversation-checkpoint>"),
                        message("user", "after-own-compaction"),
                        message("assistant", "new-answer")), 3L, 21L));
        AgentConversationMemoryProjectionService service =
                new AgentConversationMemoryProjectionService(conversations, transcript, compactions);

        AgentConversationMemoryProjectionService.Projection projected = service.project(7, 3, "child", null);

        assertThat(projected.messages().toString()).contains("own-summary", "after-own-compaction");
        verify(conversations, never()).selectById("parent");
    }

    @Test
    void cyclicForkGraphFailsExplicitlyInsteadOfRecursingForever() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationTranscriptProjectionService transcript = mock(AgentConversationTranscriptProjectionService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        AgentConversation child = conversation("child", "parent", 11L);
        AgentConversation parent = conversation("parent", "child", 7L);
        when(conversations.selectOne(any())).thenReturn(child, parent, child);
        when(compactions.latestCompletedConversationAtOrBeforeTaskId(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        AgentConversationMemoryProjectionService service =
                new AgentConversationMemoryProjectionService(conversations, transcript, compactions);

        assertThatThrownBy(() -> service.project(7, 3, "child", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    private AgentConversation conversation(String id, String parentId, Long forkTaskId) {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(id);
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setParentConversationId(parentId);
        conversation.setForkedFromTaskId(forkTaskId);
        conversation.setStatus(1);
        return conversation;
    }

    private AgentCompactionRecord completedCompaction(String conversationId, long sourceMaxTaskId) {
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setCompactionId(sourceMaxTaskId);
        record.setTaskId(sourceMaxTaskId + 100);
        record.setScope(AgentCompactionService.SCOPE_CONVERSATION);
        record.setConversationId(conversationId);
        record.setStudentId(7);
        record.setProjectId(3);
        record.setStatus("completed");
        record.setSummary("summary");
        record.setSourceMaxTaskId(sourceMaxTaskId);
        return record;
    }

    @SafeVarargs
    private final AgentConversationTranscriptProjectionService.Snapshot snapshot(
            long sourceMaxTaskId, Map<String, Object>... messages) {
        return new AgentConversationTranscriptProjectionService.Snapshot(
                List.of(messages), sourceMaxTaskId,
                (int) List.of(messages).stream().filter(item -> "user".equals(item.get("role"))).count());
    }

    private Map<String, Object> message(String role, String content) {
        return Map.of("role", role, "content", content);
    }
}
