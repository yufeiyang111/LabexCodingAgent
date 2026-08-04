package com.labex.labexagent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.mapper.AgentCompactionRecordMapper;
import com.labex.mapper.AgentConversationMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentCompactionConversationScopeTest {
    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void persistsConversationScopeAndStableSourceTaskBoundary() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        when(conversationMapper.selectOwnedForUpdate(7, 3, "conversation")).thenReturn(conversation);
        AgentCompactionRecord previous = new AgentCompactionRecord();
        previous.setCompactionId(90L);
        previous.setScope("conversation");
        previous.setConversationId("conversation");
        previous.setStatus("completed");
        previous.setCompactionEpoch(4L);
        when(mapper.selectOne(any())).thenReturn(previous);
        doAnswer(invocation -> {
            AgentCompactionRecord record = invocation.getArgument(0);
            record.setCompactionId(91L);
            return 1;
        }).when(mapper).insert(any());
        AgentCompactionService service = new AgentCompactionService(mapper, conversationMapper);
        CompactionSelection selection = selection();

        AgentCompactionRecord record = service.startConversation(
                new AgentCompactionService.ConversationStartRequest(
                        44L, "conversation", 7, 3, 2L, "manual", 90L, "previous summary",
                        selection, 38L, 4_000, 32_768, 4_096));

        ArgumentCaptor<AgentCompactionRecord> inserted = ArgumentCaptor.forClass(AgentCompactionRecord.class);
        verify(mapper).insert(inserted.capture());
        InOrder order = inOrder(conversationMapper, mapper);
        order.verify(conversationMapper).selectOwnedForUpdate(7, 3, "conversation");
        order.verify(mapper).selectOne(any());
        order.verify(mapper).insert(any());
        assertThat(record).isSameAs(inserted.getValue());
        assertThat(record.getScope()).isEqualTo("conversation");
        assertThat(record.getCompactionEpoch()).isEqualTo(5L);
        assertThat(record.getSourceMaxTaskId()).isEqualTo(38L);
        assertThat(record.getSourceMaxSequence()).isEqualTo(-1L);
    }

    @Test
    void reconstructsConversationProjectionFromSummaryTailAndTasksAfterTheBoundary() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        AgentCompactionService service = new AgentCompactionService(mapper);
        AgentCompactionRecord record = new AgentCompactionRecord();
        record.setScope("conversation");
        record.setConversationId("conversation");
        record.setStatus("completed");
        record.setCompactionEpoch(3L);
        record.setSummary("<conversation-checkpoint>durable summary</conversation-checkpoint>");
        record.setRetainedTail("[{\"role\":\"user\",\"content\":\"retained request\"},"
                + "{\"role\":\"assistant\",\"content\":\"retained answer\"}]");
        record.setSourceMaxTaskId(31L);

        AgentCompactionService.ConversationProjection projection = service.projectConversation(record,
                List.of(Map.of("role", "user", "content", "new request")));

        assertThat(projection.sourceMaxTaskId()).isEqualTo(31L);
        assertThat(projection.compactionEpoch()).isEqualTo(3L);
        assertThat(projection.messages()).containsExactly(
                Map.of("role", "user", "content", record.getSummary()),
                Map.of("role", "user", "content", "retained request"),
                Map.of("role", "assistant", "content", "retained answer"),
                Map.of("role", "user", "content", "new request"));
    }

    @Test
    void taskScopedStartsRemainExplicitlyTaskScoped() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AgentCompactionRecord record = invocation.getArgument(0);
            record.setCompactionId(92L);
            return 1;
        }).when(mapper).insert(any());
        AgentCompactionService service = new AgentCompactionService(mapper);

        AgentCompactionRecord record = service.start(new AgentCompactionService.StartRequest(
                7L, "conversation", 7, 3, 1L, "provider_overflow", "", selection(),
                19L, 4_000, 32_768, 4_096));

        assertThat(record.getScope()).isEqualTo("task");
        assertThat(record.getSourceMaxTaskId()).isNull();
    }


    @Test
    void rejectsAConversationCompactionBuiltFromAStalePreviousEpoch() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        when(conversationMapper.selectOwnedForUpdate(7, 3, "conversation")).thenReturn(conversation);
        AgentCompactionRecord newer = new AgentCompactionRecord();
        newer.setCompactionId(95L);
        newer.setScope("conversation");
        newer.setConversationId("conversation");
        newer.setStatus("completed");
        newer.setCompactionEpoch(5L);
        when(mapper.selectOne(any())).thenReturn(newer);
        AgentCompactionService service = new AgentCompactionService(mapper, conversationMapper);

        assertThatThrownBy(() -> service.startConversation(
                new AgentCompactionService.ConversationStartRequest(
                        46L, "conversation", 7, 3, 2L, "manual", 90L, "previous summary",
                        selection(), 40L, 4_000, 32_768, 4_096)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        verify(mapper, never()).insert(any());
    }

    private CompactionSelection selection() {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "old request"),
                Map.of("role", "assistant", "content", "old answer"),
                Map.of("role", "user", "content", "recent request"),
                Map.of("role", "assistant", "content", "recent answer"));
        return CompactionSelection.select(messages, 1, 2_000, estimator);
    }
}
