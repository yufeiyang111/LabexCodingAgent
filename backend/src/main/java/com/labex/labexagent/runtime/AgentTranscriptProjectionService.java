package com.labex.labexagent.runtime;

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

    @Autowired
    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService) {
        this(transcriptService, new AgentProviderMessageProjector());
    }

    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector) {
        this.transcriptService = transcriptService;
        this.providerProjector = providerProjector;
    }

    public Projection project(Long taskId, List<Map<String, Object>> inMemoryMessages) {
        List<Map<String, Object>> memoryProjection = providerProjector.project(inMemoryMessages);
        if (taskId == null) {
            return new Projection(memoryProjection, Source.MEMORY, false, "task_id_missing");
        }
        List<Map<String, Object>> durableMessages = transcriptService.loadProjectableTranscript(taskId);
        if (durableMessages.isEmpty()) {
            return new Projection(memoryProjection, Source.MEMORY, false, "durable_transcript_empty");
        }
        List<Map<String, Object>> durableProjection = providerProjector.project(durableMessages);
        if (durableProjection.equals(memoryProjection)) {
            return new Projection(durableProjection, Source.DURABLE, false, "shadow_match");
        }
        return new Projection(memoryProjection, Source.MEMORY, true,
                "durable=" + durableProjection.size() + ",memory=" + memoryProjection.size());
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
}
