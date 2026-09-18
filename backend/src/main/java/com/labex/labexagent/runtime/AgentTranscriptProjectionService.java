package com.labex.labexagent.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentTask;
import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.run.AgentConversationMessageGraphVersion;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.service.AgentConversationMemoryProjectionService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 从 durable transcript 和 compaction 记录构造唯一 Provider 消息投影。 */
@Service
public class AgentTranscriptProjectionService {
    private static final Logger log = LoggerFactory.getLogger(AgentTranscriptProjectionService.class);

    private final AgentRunTranscriptService transcriptService;
    private final AgentProviderMessageProjector providerProjector;
    private final AgentCompactionService compactionService;
    private final AgentInputAttachmentService attachmentService;
    private final AgentTaskMapper taskMapper;
    private final AgentConversationMemoryProjectionService conversationMemoryProjection;
    private final AgentConversationMapper conversationMapper;
    private final AgentConversationMessageGraphProjector graphProjector;
    private final AgentLoopProperties loopProperties;
    private final AgentRequestTokenEstimator tokenEstimator;
    /** 模型级 prune 开关的读取入口；生产装配为必需依赖。 */
    private final AgentModelConfigService modelConfigService;

    @Autowired
    public AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                             AgentProviderMessageProjector providerProjector,
                                             AgentCompactionService compactionService,
                                             AgentInputAttachmentService attachmentService,
                                             AgentTaskMapper taskMapper,
                                             AgentConversationMemoryProjectionService conversationMemoryProjection,
                                             AgentConversationMapper conversationMapper,
                                             AgentConversationMessageGraphProjector graphProjector,
                                             AgentLoopProperties loopProperties,
                                             AgentRequestTokenEstimator tokenEstimator,
                                             AgentModelConfigService modelConfigService) {
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
        if (loopProperties == null) {
            throw new IllegalArgumentException("Loop properties are required for Provider projection");
        }
        if (tokenEstimator == null) {
            throw new IllegalArgumentException("Token estimator is required for Provider projection");
        }
        this.loopProperties = loopProperties;
        this.tokenEstimator = tokenEstimator;
        this.modelConfigService = modelConfigService;
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
        this.loopProperties = new AgentLoopProperties();
        this.tokenEstimator = new AgentRequestTokenEstimator();
        this.modelConfigService = null;
    }

    /**
     * 保持既有隔离单测与兼容调用可用。占位化开关来自模型配置，这里不装配模型配置读取入口，
     * 因此该路径下历史工具输出占位化保持关闭——属保守方向（宁可不裁剪上下文）。
     */
    AgentTranscriptProjectionService(AgentRunTranscriptService transcriptService,
                                     AgentProviderMessageProjector providerProjector,
                                     AgentCompactionService compactionService,
                                     AgentInputAttachmentService attachmentService,
                                     AgentTaskMapper taskMapper,
                                     AgentConversationMemoryProjectionService conversationMemoryProjection,
                                     AgentConversationMapper conversationMapper,
                                     AgentConversationMessageGraphProjector graphProjector) {
        this(transcriptService, providerProjector, compactionService, attachmentService, taskMapper,
                conversationMemoryProjection, conversationMapper, graphProjector,
                new AgentLoopProperties(), new AgentRequestTokenEstimator(), null);
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
        this.loopProperties = new AgentLoopProperties();
        this.tokenEstimator = new AgentRequestTokenEstimator();
        this.modelConfigService = null;
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
        return compactHistoricalToolOutputs(loadUncompactedProviderMessages(taskId, task), task);
    }

    /**
     * 历史工具输出的语义占位化，<b>只作用于 Provider 请求视图</b>。
     *
     * <p>对齐 OpenCode {@code session/compaction.ts} 的 {@code prune}：从最新往前扫描已完成的历史工具结果，
     * 最近 {@code pruneProtectTokens} 一律保护，超出保护额度的候选只有在净可回收 token 超过
     * {@code pruneMinimumTokens} 时才真正占位化——prune 是有损操作，为微弱收益破坏上下文不划算。
     * 判据完全复用 {@link TurnAwareContextPruner}，不在此处另写阈值。</p>
     *
     * <p><b>开关的唯一权威</b>是模型级 {@code AgentModelConfig.compactionPrune} 经
     * {@link ContextWindowPolicy} 解析出的 {@code pruningEnabled}（对齐上游 {@code compaction.prune}，
     * 默认关闭）。这里刻意不引入第二个全局开关：同一件事只允许一个所有者。判定顺序为
     * 「先本地算候选、有候选才查模型配置」，使绝大多数没有超量历史工具输出的请求零额外查库。</p>
     *
     * <p>与上游的差异及原因：上游把 {@code part.state.time.compacted} 写回持久化 Part，本项目的 Provider
     * 视图由持久化 Message/Part 每次重新投影，因此占位化放在<b>投影边界的后处理</b>——收益相同
     * （Provider 请求体积下降、压缩触发频率降低），但不产生任何持久化写、不需要幂等与并发处理，
     * 也无不可逆副作用。原始工具输出始终完整保留在 {@code t_agent_run_part.output_text}，
     * {@code read_tool_output} 的分页回读能力不受影响。</p>
     *
     * <p>压缩选材视图（{@link #loadDurableCompactionView}）与恢复视图<b>不</b>经过这里：
     * 摘要需要读到完整工具输出，且压缩记录会整体序列化选材结果，不能把占位文本写进摘要输入。</p>
     */
    private List<Map<String, Object>> compactHistoricalToolOutputs(List<Map<String, Object>> providerMessages,
                                                                   AgentTask task) {
        if (providerMessages == null || providerMessages.isEmpty()) {
            return providerMessages == null ? List.of() : providerMessages;
        }
        AgentLoopProperties properties = loopProperties == null ? new AgentLoopProperties() : loopProperties;
        // 占位化需要可变的顶层列表；pruner 只替换命中的条目，未命中的条目保持原引用。
        List<Map<String, Object>> mutable = new ArrayList<>(providerMessages);
        TurnAwareContextPruner pruner = new TurnAwareContextPruner(this.tokenEstimator::estimateValue,
                properties.getPruneProtectTokens(), properties.getPruneMinimumTokens());
        // 只按轮数保护尾部（对齐上游 prune 的 turns < 2）：token 级保护由 pruneProtectTokens 承担，
        // 因此这里不额外施加尾部 token 预算。
        List<Integer> candidates = pruner.selectPrunableIndexes(mutable, properties.getTailTurns(),
                Integer.MAX_VALUE);
        if (candidates.isEmpty() || !isToolOutputCompactionEnabled(task)) {
            return providerMessages;
        }
        int compacted = 0;
        int reclaimedTokens = 0;
        for (int index : candidates) {
            Map<String, Object> message = mutable.get(index);
            Object rawName = message.get("name");
            String toolName = rawName == null ? "" : String.valueOf(rawName).trim().toLowerCase(Locale.ROOT);
            String placeholder = TurnAwareContextPruner.placeholderFor(toolName);
            Object rawContent = message.get("content");
            String content = rawContent instanceof String value ? value : "";
            if (placeholder.length() >= content.length()) {
                continue;
            }
            LinkedHashMap<String, Object> rewritten = new LinkedHashMap<>(message);
            rewritten.put("content", placeholder);
            mutable.set(index, rewritten);
            compacted++;
            reclaimedTokens += Math.max(0, tokenEstimator.estimateValue(content)
                    - tokenEstimator.estimateValue(placeholder));
        }
        if (compacted == 0) {
            return providerMessages;
        }
        // 验收与排障的唯一直接证据：占位化只发生在投影边界，不产生 SSE 事件，
        // 因此这里留一条可 grep 的记录（taskId + 条数 + 省下的 token）。
        log.info("PROVIDER_TOOL_OUTPUT_COMPACTED taskId={} projectId={} compactedToolResults={} "
                        + "reclaimedTokens={} projectedMessages={}",
                task == null ? null : task.getTaskId(),
                task == null ? null : task.getProjectId(),
                compacted, reclaimedTokens, mutable.size());
        return mutable;
    }

    /**
     * 模型级 prune 开关。复用 {@link ContextWindowPolicy} 的既有解析，保证与
     * {@code AgentLoopEngine} 的 {@code policy.pruningEnabled()} 判定完全同源，不复制阈值逻辑。
     * 任务缺少模型配置或配置不可读时一律按关闭处理（保守：宁可多留上下文）。
     */
    private boolean isToolOutputCompactionEnabled(AgentTask task) {
        if (task == null || modelConfigService == null) {
            return false;
        }
        Integer configId = task.getModelConfigId();
        if (configId == null || configId <= 0 || task.getStudentId() == null) {
            return false;
        }
        AgentModelConfig config = modelConfigService.getOwned(task.getStudentId(), configId);
        return config != null
                && ContextWindowPolicy.from(config).map(ContextWindowPolicy::pruningEnabled).orElse(false);
    }

    private List<Map<String, Object>> loadUncompactedProviderMessages(Long taskId, AgentTask task) {
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

    /** JVM 重启时直接从 transcript 与最新 compaction epoch 重建（附件在 Provider 边界注水）。 */
    public Projection loadDurableProjection(Long taskId) {
        DurableProjection durable = durableView(taskId, false, true);
        return new Projection(durable.messages(), durable.detail());
    }

    /** 审批或提问恢复时保留可恢复的等待 Tool Part。 */
    public Projection loadDurableProjectionForInteractionResume(Long taskId) {
        DurableProjection durable = durableView(taskId, true, true);
        return new Projection(durable.messages(), durable.detail());
    }

    /**
     * 压缩选材专用视图：应用已完成压缩，但绝不把 attachmentIds 注水成 Base64 data URL。
     *
     * <p>选出的 compactedHead/retainedTail 会整体序列化进 t_agent_compaction_record；
     * 若在此放行注水，图片负载将随 selection 永久写入压缩记录，附件过期后变成无法回收的死重。
     * 预算准确性由 AgentRequestTokenEstimator 对 durable 形态 attachmentIds 的视觉权重补偿。</p>
     */
    public Projection loadDurableCompactionView(Long taskId) {
        DurableProjection durable = durableView(taskId, false, false);
        return new Projection(durable.messages(), durable.detail());
    }

    private DurableProjection durableView(Long taskId, boolean interactionResume, boolean hydrateAttachments) {
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
            return new DurableProjection(
                    finalizeDurableView(taskId, value.messages(), interactionResume, hydrateAttachments),
                    "compaction_epoch=" + value.compactionEpoch()
                            + ",source_max_sequence=" + value.sourceMaxSequence());
        }
        List<Map<String, Object>> durableMessages = interactionResume
                ? transcriptService.loadProjectableTranscriptForInteractionResume(taskId)
                : transcriptService.loadProjectableTranscript(taskId);
        return new DurableProjection(
                finalizeDurableView(taskId, durableMessages, interactionResume, hydrateAttachments),
                interactionResume ? "interaction_resume" : "durable_transcript");
    }

    private List<Map<String, Object>> finalizeDurableView(Long taskId, List<Map<String, Object>> messages,
                                                          boolean interactionResume, boolean hydrateAttachments) {
        if (!hydrateAttachments || attachmentService == null) {
            // 未注水视图仍需深拷贝，避免 selection 序列化与上游缓存共享可变结构。
            return providerProjector.copyMessages(messages);
        }
        List<Map<String, Object>> hydrated = hydrateAttachments(taskId, messages);
        return interactionResume ? providerProjector.copyMessages(hydrated) : providerProjector.project(hydrated);
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

    private List<Map<String, Object>> hydrateAttachments(Long taskId, List<Map<String, Object>> messages) {
        if (attachmentService == null || messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        // 图片一次性注入策略：带附件的 user 消息仅在"其后尚无 assistant 回复"（模型尚未消费）
        // 时注水 Base64；已被消费的历史消息降级为文本占位，避免同一图片随每轮请求重复
        // 读取磁盘并重复编码上行。durable transcript 与压缩选材视图不受影响。
        int lastAssistantIndex = lastAssistantIndex(messages);
        List<Map<String, Object>> hydrated = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            Map<String, Object> message = messages.get(index);
            boolean modelConsumed = index <= lastAssistantIndex
                    && message != null
                    && "user".equalsIgnoreCase(String.valueOf(message.get("role")))
                    && message.get("attachmentIds") != null;
            hydrated.add(attachmentService.hydrateProviderMessage(taskId, message, modelConsumed));
        }
        return hydrated;
    }

    private int lastAssistantIndex(List<Map<String, Object>> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> message = messages.get(index);
            if (message != null && "assistant".equalsIgnoreCase(String.valueOf(message.get("role")))) {
                return index;
            }
        }
        return -1;
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
