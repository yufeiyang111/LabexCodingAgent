package com.labex.labexagent.runtime;

import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 从 durable transcript 和 compaction 记录构造唯一 Provider 消息投影。 */
@Service
public class AgentTranscriptProjectionService {
    private final AgentRunTranscriptService transcriptService;
    private final AgentProviderMessageProjector providerProjector;
    private final AgentCompactionService compactionService;
    private final AgentInputAttachmentService attachmentService;

    @Autowired
    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                             AgentProviderMessageProjector providerProjector,
                                             AgentCompactionService compactionService,
                                             AgentInputAttachmentService attachmentService) {
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
        this.attachmentService = attachmentService;
    }

    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                             AgentProviderMessageProjector providerProjector,
                                             AgentCompactionService compactionService) {
        this(transcriptService, providerProjector, compactionService, null);
    }

    /** Provider 的唯一读取入口；缺少持久化事实时失败，禁止回退到内存消息。 */
    public List<Map<String, Object>> loadProviderMessages(Long taskId) {
        Projection projection = loadDurableProjection(taskId);
        if (projection.messages().isEmpty()) {
            throw new IllegalStateException("Durable Provider transcript is empty for taskId=" + taskId);
        }
        return projection.messages();
    }

    /** JVM 重启时直接从 transcript 与最新 compaction epoch 重建。 */
    public Projection loadDurableProjection(Long taskId) {
        DurableProjection durable = durableProjection(taskId, false);
        return new Projection(durable.messages(), durable.detail());
    }

    /** 审批或提问恢复时保留可恢复的等待 Tool Part。 */
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
            List<Map<String, Object>> hydrated = hydrateAttachments(taskId, value.messages());
            return new DurableProjection(interactionResume
                            ? providerProjector.copyMessages(hydrated)
                            : providerProjector.project(hydrated),
                    "compaction_epoch=" + value.compactionEpoch()
                            + ",source_max_sequence=" + value.sourceMaxSequence());
        }
        List<Map<String, Object>> durableMessages = interactionResume
                ? transcriptService.loadProjectableTranscriptForInteractionResume(taskId)
                : transcriptService.loadProjectableTranscript(taskId);
        List<Map<String, Object>> hydrated = hydrateAttachments(taskId, durableMessages);
        return new DurableProjection(interactionResume
                        ? providerProjector.copyMessages(hydrated)
                        : providerProjector.project(hydrated),
                interactionResume ? "interaction_resume" : "durable_transcript");
    }

    private List<Map<String, Object>> hydrateAttachments(Long taskId, List<Map<String, Object>> messages) {
        if (attachmentService == null || messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        return messages.stream()
                .map(message -> attachmentService.hydrateProviderMessage(taskId, message))
                .toList();
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
