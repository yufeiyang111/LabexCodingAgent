package com.labex.labexagent.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentTask;
import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentConversationMessageGraphVersion;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.service.AgentConversationMemoryProjectionService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentTaskMapper;
import java.util.ArrayList;
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
    private final AgentTaskMapper taskMapper;
    private final AgentConversationMemoryProjectionService conversationMemoryProjection;
    private final AgentConversationMapper conversationMapper;
    private final AgentConversationMessageGraphProjector graphProjector;

    @Autowired
    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                             AgentProviderMessageProjector providerProjector,
                                             AgentCompactionService compactionService,
                                             AgentInputAttachmentService attachmentService,
                                             AgentTaskMapper taskMapper,
                                             AgentConversationMemoryProjectionService conversationMemoryProjection,
                                             AgentConversationMapper conversationMapper,
                                             AgentConversationMessageGraphProjector graphProjector) {
        if (transcriptService == null) {
            throw new IllegalArgumentException("Durable Provider transcript service is required");
        }
        if (providerProjector == null) {
            throw new IllegalArgumentException("Provider transcript projector is required");
        }
        if (compactionService == null) {
            throw new IllegalArgumentException("Compaction service is required for Provider projection");
        }
        if (taskMapper == null) {
            throw new IllegalArgumentException("Durable task mapper is required for Provider projection");
        }
        if (conversationMemoryProjection == null) {
            throw new IllegalArgumentException("Conversation memory projection is required for Provider projection");
        }
        if (conversationMapper == null) {
            throw new IllegalArgumentException("Conversation mapper is required for Provider projection");
        }
        if (graphProjector == null) {
            throw new IllegalArgumentException("Conversation graph projector is required for Provider projection");
        }
        this.transcriptService = transcriptService;
        this.providerProjector = providerProjector;
        this.compactionService = compactionService;
        this.attachmentService = attachmentService;
        this.taskMapper = taskMapper;
        this.conversationMemoryProjection = conversationMemoryProjection;
        this.conversationMapper = conversationMapper;
        this.graphProjector = graphProjector;
    }

    /** 仅供不装配会话投影依赖的隔离单测使用。 */
    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector,
                                     AgentCompactionService compactionService,
                                     AgentInputAttachmentService attachmentService) {
        if (transcriptService == null) {
            throw new IllegalArgumentException("Durable Provider transcript service is required");
        }
        if (providerProjector == null) {
            throw new IllegalArgumentException("Provider transcript projector is required");
        }
        if (compactionService == null) {
            throw new IllegalArgumentException("Compaction service is required for Provider projection");
        }
        this.transcriptService = transcriptService;
        this.providerProjector = providerProjector;
        this.compactionService = compactionService;
        this.attachmentService = attachmentService;
        this.taskMapper = null;
        this.conversationMemoryProjection = null;
        this.conversationMapper = null;
        this.graphProjector = null;
    }

    /** 仅供 legacy 会话投影隔离单测使用。 */
    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector,
                                     AgentCompactionService compactionService,
                                     AgentInputAttachmentService attachmentService,
                                     AgentTaskMapper taskMapper,
                                     AgentConversationMemoryProjectionService conversationMemoryProjection) {
        if (transcriptService == null || providerProjector == null || compactionService == null
                || taskMapper == null || conversationMemoryProjection == null) {
            throw new IllegalArgumentException("Legacy Provider projection dependencies are required");
        }
        this.transcriptService = transcriptService;
        this.providerProjector = providerProjector;
        this.compactionService = compactionService;
        this.attachmentService = attachmentService;
        this.taskMapper = taskMapper;
        this.conversationMemoryProjection = conversationMemoryProjection;
        this.conversationMapper = null;
        this.graphProjector = null;
    }

    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector,
                                     AgentCompactionService compactionService) {
        this(transcriptService, providerProjector, compactionService, null);
    }

    /**
     * Provider 的唯一读取入口。
     *
     * <p>只有固定为 {@code labex-native + conversation_graph_v1} 的会话读取 Conversation 图；
     * 其余会话保留兼容期的 task transcript + 历史投影。图版本的读取不会回退到历史摘要前缀，
     * 避免将两种不同语义的事实源拼在同一次 Provider 请求里。</p>
     */
    public List<Map<String, Object>> loadProviderMessages(Long taskId) {
        AgentTask task = taskMapper == null ? null : taskMapper.selectById(taskId);
        if (usesConversationGraph(task)) {
            return graphProjector.projectForProvider(new AgentConversationMessageGraphProjector.Request(
                    task.getStudentId(), task.getProjectId(), task.getConversationId()));
        }

        Projection currentTask = loadDurableProjection(taskId);
        if (currentTask.messages().isEmpty()) {
            throw new IllegalStateException("Durable Provider transcript is empty for taskId=" + taskId);
        }
        if (taskMapper == null || conversationMemoryProjection == null) {
            // 兼容旧的隔离单测构造器；生产 Bean 必须走带会话投影依赖的构造器。
            return currentTask.messages();
        }
        if (task == null) {
            throw new IllegalStateException(
                    "Durable task is unavailable for Provider conversation projection: taskId=" + taskId);
        }
        if (task.getStudentId() == null || task.getProjectId() == null
                || task.getConversationId() == null || task.getConversationId().isBlank()) {
            return currentTask.messages();
        }
        AgentConversationMemoryProjectionService.Projection conversation =
                conversationMemoryProjection.project(task.getStudentId(), task.getProjectId(),
                        task.getConversationId(), taskId);
        if (conversation.messages().isEmpty()) {
            return currentTask.messages();
        }
        List<Map<String, Object>> combined = new ArrayList<>(
                conversation.messages().size() + currentTask.messages().size());
        combined.addAll(conversation.messages());
        combined.addAll(currentTask.messages());
        return providerProjector.project(combined);
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

    private boolean usesConversationGraph(AgentTask task) {
        if (task == null || task.getStudentId() == null || task.getProjectId() == null
                || task.getConversationId() == null || task.getConversationId().isBlank()) {
            return false;
        }
        if (AgentRuntimeProfile.fromPersisted(task.getRuntimeProfile()) != AgentRuntimeProfile.LABEX_NATIVE) {
            return false;
        }
        if (conversationMapper == null || graphProjector == null) {
            throw new IllegalStateException("Conversation graph dependencies are unavailable for native taskId="
                    + task.getTaskId());
        }
        AgentConversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getStudentId, task.getStudentId())
                .eq(AgentConversation::getProjectId, task.getProjectId())
                .eq(AgentConversation::getConversationId, task.getConversationId())
                .eq(AgentConversation::getStatus, 1));
        return conversation != null && AgentConversationMessageGraphVersion.matches(
                conversation.getHistoryProjectionVersion());
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
