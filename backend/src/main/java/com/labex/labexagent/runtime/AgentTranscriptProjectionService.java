package com.labex.labexagent.runtime;

import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 比较内存缓存和 durable transcript，并只在协议等价时切换到数据库投影。 */
@Service
public final class AgentTranscriptProjectionService {
    private final AgentRunTranscriptService transcriptService;
    private final AgentProviderMessageProjector providerProjector;
    private AgentCompactionService compactionService;

    @Autowired
    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService) {
        this(transcriptService, new AgentProviderMessageProjector(), null);
    }

    @Autowired(required = false)
    void setCompactionService(AgentCompactionService compactionService) {
        this.compactionService = compactionService;
    }

    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector) {
        this(transcriptService, providerProjector, null);
    }

    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector,
                                     AgentCompactionService compactionService) {
        this.transcriptService = transcriptService;
        this.providerProjector = providerProjector;
        this.compactionService = compactionService;
    }

    public Projection project(Long taskId, List<Map<String, Object>> inMemoryMessages) {
        List<Map<String, Object>> memoryProjection = providerProjector.project(inMemoryMessages);
        if (taskId == null) {
            return new Projection(memoryProjection, Source.MEMORY, false, "task_id_missing");
        }
        DurableProjection durable = durableProjection(taskId);
        if (durable.messages().isEmpty()) {
            return new Projection(memoryProjection, Source.MEMORY, false, "durable_transcript_empty");
        }
        if (durable.messages().equals(memoryProjection)) {
            return new Projection(durable.messages(), Source.DURABLE, false, durable.detail());
        }
        return new Projection(memoryProjection, Source.MEMORY, true,
                "durable=" + durable.messages().size() + ",memory=" + memoryProjection.size()
                        + "," + durable.detail());
    }

    /** JVM 重启时不依赖旧内存，直接从 transcript + latest compaction epoch 重建。 */
    public Projection loadDurableProjection(Long taskId) {
        DurableProjection durable = durableProjection(taskId);
        return new Projection(durable.messages(), Source.DURABLE, false, durable.detail());
    }

    private DurableProjection durableProjection(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return new DurableProjection(List.of(), "task_id_missing");
        }
        if (compactionService != null) {
            java.util.Optional<AgentCompactionService.Projection> compacted = compactionService.projectLatest(
                    taskId, boundary -> transcriptService.loadProjectableTranscriptAfter(taskId, boundary));
            if (compacted.isPresent()) {
                AgentCompactionService.Projection value = compacted.orElseThrow();
                return new DurableProjection(providerProjector.project(value.messages()),
                        "compaction_epoch=" + value.compactionEpoch()
                                + ",source_max_sequence=" + value.sourceMaxSequence());
            }
        }
        return new DurableProjection(providerProjector.project(transcriptService.loadProjectableTranscript(taskId)),
                "shadow_match");
    }

    public enum Source {
        DURABLE,
        MEMORY
    }

    public record Projection(List<Map<String, Object>> messages, Source source,
                             boolean shadowMismatch, String detail) {
        public Projection {
            messages = messages == null ? List.of() : List.copyOf(messages);
            detail = detail == null ? "" : detail;
        }
    }

    private record DurableProjection(List<Map<String, Object>> messages, String detail) {
    }
}