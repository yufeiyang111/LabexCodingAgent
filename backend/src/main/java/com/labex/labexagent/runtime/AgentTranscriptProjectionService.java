package com.labex.labexagent.runtime;

import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 从 durable transcript 和 compaction 记录构造唯一 Provider 消息投影? */
@Service
public final class AgentTranscriptProjectionService {
    private final AgentRunTranscriptService transcriptService;
    private final AgentProviderMessageProjector providerProjector;
    private final AgentCompactionService compactionService;

    @Autowired
    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                             AgentProviderMessageProjector providerProjector,
                                             AgentCompactionService compactionService) {
        if (transcriptService == null) {
            throw new IllegalArgumentException("Durable Provider transcript service is required");
        }
        this.transcriptService = transcriptService;
        if (providerProjector == null) {
            throw new IllegalArgumentException("Provider transcript projector is required");
        }
        if (compactionService == null) {
            throw new IllegalArgumentException("Compaction service is required for Provider projection");
        }
        this.providerProjector = providerProjector;
        this.compactionService = compactionService;
    }

    /** Provider 的唯一读取入口；缺少持久化事实时失败，禁止回退到内存消息? */
    public List<Map<String, Object>> loadProviderMessages(Long taskId) {
        Projection projection = loadDurableProjection(taskId);
        if (projection.messages().isEmpty()) {
            throw new IllegalStateException("Durable Provider transcript is empty for taskId=" + taskId);
        }
        return projection.messages();
    }

    /** JVM 重启时直接从 transcript 与最新 compaction epoch 重建? */
    public Projection loadDurableProjection(Long taskId) {
        DurableProjection durable = durableProjection(taskId, false);
        return new Projection(durable.messages(), durable.detail());
    }

    /** 审批或提问恢复时保留可恢复的等待 Tool Part? */
    public Projection loadDurableProjectionForInteractionResume(Long taskId) {
        DurableProjection durable = durableProjection(taskId, true);
        return new Projection(durable.messages(), durable.detail());
    }

    private DurableProjection durableProjection(Long taskId, boolean interactionResume) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("Durable Provider transcript requires a positive taskId");
        }
        java.util.Optional<AgentCompactionService.Projection> compacted = interactionResume
                    ? compactionService.projectLatestForInteractionResume(taskId,
                    boundary -> transcriptService.loadProjectableTranscriptForInteractionResumeAfter(taskId, boundary))
                    : compactionService.projectLatest(taskId,
                    boundary -> transcriptService.loadProjectableTranscriptAfter(taskId, boundary));
        if (compacted.isPresent()) {
            AgentCompactionService.Projection value = compacted.orElseThrow();
            return new DurableProjection(interactionResume
                            ? providerProjector.copyMessages(value.messages())
                            : providerProjector.project(value.messages()),
                    "compaction_epoch=" + value.compactionEpoch()
                            + ",source_max_sequence=" + value.sourceMaxSequence());
        }
        return new DurableProjection(interactionResume
                        ? providerProjector.copyMessages(
                                transcriptService.loadProjectableTranscriptForInteractionResume(taskId))
                        : providerProjector.project(transcriptService.loadProjectableTranscript(taskId)),
                interactionResume ? "interaction_resume" : "durable_transcript");
    }

    public record Projection(List<Map<String, Object>> messages, String detail) {
        public Projection {
            messages = messages == null ? List.of() : List.copyOf(messages);
            detail = detail == null ? "" : detail;
        }
    }

    private record DurableProjection(List<Map<String, Object>> messages, String detail) {
    }
}
