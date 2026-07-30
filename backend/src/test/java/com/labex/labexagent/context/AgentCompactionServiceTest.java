package com.labex.labexagent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.mapper.AgentCompactionRecordMapper;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentCompactionServiceTest {

    private final AgentRequestTokenEstimator estimator = new AgentRequestTokenEstimator();

    @Test
    void remainsProxyableForTransactionalPersistence() {
        assertThat(Modifier.isFinal(AgentCompactionService.class.getModifiers())).isFalse();
    }

    @Test
    void persistsEpochHeadTailBudgetAndCompletedSummary() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AgentCompactionRecord record = invocation.getArgument(0);
            record.setCompactionId(41L);
            return 1;
        }).when(mapper).insert(any());
        AgentCompactionService service = new AgentCompactionService(mapper);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "old request"),
                Map.of("role", "assistant", "content", "old answer"),
                Map.of("role", "user", "content", "recent request"),
                Map.of("role", "assistant", "content", "recent answer"));
        CompactionSelection selection = CompactionSelection.select(messages, 1, 2_000, estimator);

        AgentCompactionRecord record = service.start(new AgentCompactionService.StartRequest(
                7L, "conversation-7", 11, 22, 3L, "provider_overflow", "previous summary",
                selection, 15L, 3_200, 4_096, 512));

        ArgumentCaptor<AgentCompactionRecord> inserted = ArgumentCaptor.forClass(AgentCompactionRecord.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getCompactionEpoch()).isEqualTo(1L);
        assertThat(inserted.getValue().getStatus()).isEqualTo("running");
        assertThat(inserted.getValue().getCompactedHead()).contains("old request");
        assertThat(inserted.getValue().getRetainedTail()).contains("recent request");
        assertThat(inserted.getValue().getPreviousSummary()).isEqualTo("previous summary");
        assertThat(inserted.getValue().getSourceMaxSequence()).isEqualTo(15L);

        service.complete(record, "new durable summary", 1_200);

        assertThat(record.getStatus()).isEqualTo("completed");
        assertThat(record.getSummary()).isEqualTo("new durable summary");
        assertThat(record.getEstimatedTokensAfter()).isEqualTo(1_200);
        verify(mapper).updateById(record);
    }

    @Test
    void preservesOpenToolBatchForInteractionResumeUntilRuntimeCompletes() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        AgentCompactionRecord completed = new AgentCompactionRecord();
        completed.setTaskId(7L);
        completed.setStatus("completed");
        completed.setCompactionEpoch(2L);
        completed.setSummary("durable summary");
        completed.setRetainedTail("[{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"question\",\"arguments\":\"{}\"}}]}]");
        completed.setSourceMaxSequence(9L);
        when(mapper.selectOne(any())).thenReturn(completed);
        AgentCompactionService service = new AgentCompactionService(mapper);

        AgentCompactionService.Projection projection = service.projectLatestForInteractionResume(7L, boundary -> List.of()).orElseThrow();

        assertThat(projection.messages()).hasSize(2);
        Map<String, Object> resumedAssistant = projection.messages().get(1);
        assertThat(resumedAssistant).containsEntry("role", "assistant");
        assertThat((List<?>) resumedAssistant.get("tool_calls")).hasSize(1);
    }

    @Test
    void exposesSafeOrderedCompactionHistoryWithoutTranscriptPayloads() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        AgentCompactionRecord completed = new AgentCompactionRecord();
        completed.setCompactionId(44L);
        completed.setTaskId(7L);
        completed.setExecutionEpoch(3L);
        completed.setCompactionEpoch(2L);
        completed.setTriggerReason("token_budget");
        completed.setStatus("completed");
        completed.setRetainedTurns(1);
        completed.setSourceMaxSequence(19L);
        completed.setEstimatedTokensBefore(29_000);
        completed.setEstimatedTokensAfter(8_000);
        completed.setModelWindowTokens(32_768);
        completed.setReservedOutputTokens(4_096);
        completed.setSummary("private summary");
        completed.setCompactedHead("private head");
        completed.setRetainedTail("private tail");
        when(mapper.selectList(any())).thenReturn(List.of(completed));
        AgentCompactionService service = new AgentCompactionService(mapper);

        List<Map<String, Object>> history = service.publicHistory(7L);

        assertThat(history).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("compactionId", 44L)
                    .containsEntry("compactionEpoch", 2L)
                    .containsEntry("status", "completed")
                    .containsEntry("estimatedTokensBefore", 29_000)
                    .containsEntry("estimatedTokensAfter", 8_000);
            assertThat(item).doesNotContainKeys("summary", "previousSummary", "compactedHead", "retainedTail");
        });
    }

    @Test
    void reconstructsRestartProjectionFromSummaryTailAndOnlyLaterTranscriptFacts() {
        AgentCompactionRecordMapper mapper = mock(AgentCompactionRecordMapper.class);
        AgentCompactionRecord completed = new AgentCompactionRecord();
        completed.setTaskId(7L);
        completed.setStatus("completed");
        completed.setCompactionEpoch(2L);
        completed.setSummary("<conversation-checkpoint>durable summary</conversation-checkpoint>");
        completed.setRetainedTail("[{\"role\":\"user\",\"content\":\"retained\"},{\"role\":\"assistant\",\"content\":\"retained answer\"}]");
        completed.setSourceMaxSequence(9L);
        when(mapper.selectOne(any())).thenReturn(completed);
        AgentCompactionService service = new AgentCompactionService(mapper);

        AgentCompactionService.Projection projection = service.projectLatest(7L, boundary -> {
            assertThat(boundary).isEqualTo(9L);
            return List.of(Map.of("role", "user", "content", "after restart"));
        }).orElseThrow();

        assertThat(projection.messages()).containsExactly(
                Map.of("role", "user", "content", completed.getSummary()),
                Map.of("role", "user", "content", "retained"),
                Map.of("role", "assistant", "content", "retained answer"),
                Map.of("role", "user", "content", "after restart"));
        assertThat(projection.compactionEpoch()).isEqualTo(2L);
    }
}