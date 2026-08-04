package com.labex.labexagent.service;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentTask;
import com.labex.labexagent.context.AgentCompactionRecord;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.context.CompactionSelection;
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.runtime.CompactionAgent;
import com.labex.service.AgentModelConfigService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 手动会话压缩的唯一编排入口：权威记录先完成，旧 AgentMessage 只做兼容投影。
 */
@Service
public class AgentConversationCompactionService {
    private static final Logger log = LoggerFactory.getLogger(AgentConversationCompactionService.class);
    private static final int DEFAULT_TAIL_TURNS = 1;
    private static final int DEFAULT_TAIL_TOKEN_BUDGET = 4_000;
    private static final int SUMMARY_ITEM_LIMIT = 320;
    private static final int SUMMARY_ITEM_COUNT = 8;

    private final AgentConversationService conversations;
    private final AgentConversationMemoryProjectionService memoryProjection;
    private final AgentCompactionService compactions;
    private final CompactionAgent compactionAgent;
    private final AgentModelConfigService modelConfigs;
    private final AgentRequestTokenEstimator tokenEstimator = new AgentRequestTokenEstimator();

    public AgentConversationCompactionService(AgentConversationService conversations,
                                              AgentConversationMemoryProjectionService memoryProjection,
                                              AgentCompactionService compactions,
                                              CompactionAgent compactionAgent,
                                              AgentModelConfigService modelConfigs) {
        this.conversations = conversations;
        this.memoryProjection = memoryProjection;
        this.compactions = compactions;
        this.compactionAgent = compactionAgent;
        this.modelConfigs = modelConfigs;
    }

    public Result compact(Integer studentId, Integer projectId, String conversationId, Integer modelConfigId,
                          AgentTask compactionTask, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        requireTaskOwnership(studentId, projectId, conversationId, compactionTask);
        if (token.isCancellationRequested()) {
            throw new CancellationException("Manual compaction cancelled");
        }
        AgentConversation conversation = conversations.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        AgentModelConfig activeConfig = modelConfigs.resolveForStudent(studentId, modelConfigId);
        if (modelConfigId != null && modelConfigId > 0 && (activeConfig == null
                || !modelConfigId.equals(activeConfig.getConfigId())
                || !Integer.valueOf(1).equals(activeConfig.getStatus()))) {
            throw new IllegalArgumentException("Compaction model config not found or disabled");
        }

        Optional<AgentCompactionRecord> previous = compactions.latestCompletedConversation(
                studentId, projectId, conversationId);
        AgentConversationMemoryProjectionService.Projection durable = memoryProjection.project(
                studentId, projectId, conversationId, compactionTask.getTaskId());
        List<Map<String, Object>> source = durable.messages();
        long sourceMaxTaskId = durable.sourceMaxTaskId();
        CompactionSelection selection = CompactionSelection.select(source,
                tailTurns(activeConfig), tailTokenBudget(activeConfig), tokenEstimator);
        if (!selection.changed()) {
            throw new IllegalStateException("Not enough durable conversation turns to compact");
        }

        int tokensBefore = tokenEstimator.estimateMessages(source);
        String previousSummary = previous.map(AgentCompactionRecord::getSummary).orElse("");
        AgentCompactionRecord record = null;
        boolean terminalized = false;
        try {
            record = compactions.startConversation(new AgentCompactionService.ConversationStartRequest(
                    compactionTask.getTaskId(), conversationId, studentId, projectId,
                    compactionTask.getExecutionEpoch() == null ? 0L : compactionTask.getExecutionEpoch(),
                    "manual", previous.map(AgentCompactionRecord::getCompactionId).orElse(null),
                    previousSummary, selection, sourceMaxTaskId, tokensBefore,
                    contextWindow(activeConfig), reservedOutput(activeConfig)));
            if (token.isCancellationRequested()) {
                throw new CancellationException("Manual compaction cancelled");
            }

            String latestRequest = latestUserRequest(source);
            CompactionAgent.Result modelResult = compactionAgent.compact(studentId, activeConfig,
                    selection.compactedHead(), latestRequest, null, token);
            if (token.isCancellationRequested()
                    || modelResult != null && "Compaction cancelled".equalsIgnoreCase(modelResult.reason())) {
                throw new CancellationException("Manual compaction cancelled");
            }

            boolean fallback = modelResult == null || !modelResult.success()
                    || modelResult.checkpoint() == null || modelResult.checkpoint().isBlank();
            String summary = fallback ? "" : modelResult.checkpoint();
            int tokensAfter = fallback ? tokensBefore
                    : tokenEstimator.estimateMessages(selection.projectedWithSummary(summary));
            if (!fallback && tokensAfter >= tokensBefore) {
                fallback = true;
            }
            if (fallback) {
                summary = deterministicCheckpoint(selection.compactedHead());
                tokensAfter = tokenEstimator.estimateMessages(selection.projectedWithSummary(summary));
            }
            if (tokensAfter >= tokensBefore) {
                throw new IllegalStateException("Conversation compaction did not reduce durable context");
            }
            String strategy = fallback ? "manual_deterministic_fallback" : "manual_model";
            compactions.complete(record, summary, tokensAfter);
            terminalized = true;

            boolean legacyProjectionWritten = writeLegacyProjection(conversation, record, summary, strategy,
                    fallback, modelResult, sourceMaxTaskId);
            return new Result(summary, strategy, fallback, record.getCompactionId(), sourceMaxTaskId,
                    legacyProjectionWritten);
        } catch (RuntimeException failure) {
            if (record != null && !terminalized) {
                try {
                    compactions.fail(record, failureReason(failure));
                } catch (RuntimeException finalizationFailure) {
                    failure.addSuppressed(finalizationFailure);
                }
            }
            throw failure;
        }
    }

    private boolean writeLegacyProjection(AgentConversation conversation, AgentCompactionRecord record,
                                          String summary, String strategy, boolean fallback,
                                          CompactionAgent.Result modelResult, long sourceMaxTaskId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("authority", "agent_compaction_record");
        metadata.put("projectionOnly", true);
        metadata.put("compactionId", record.getCompactionId());
        metadata.put("taskId", record.getTaskId());
        metadata.put("compactionEpoch", record.getCompactionEpoch());
        metadata.put("sourceMaxTaskId", sourceMaxTaskId);
        metadata.put("strategy", strategy);
        metadata.put("deterministicFallback", fallback);
        if (modelResult != null && modelResult.modelConfigId() != null) {
            metadata.put("modelConfigId", modelResult.modelConfigId());
        }
        if (modelResult != null) {
            metadata.put("dedicatedModel", modelResult.dedicatedModelSelected());
        }
        try {
            conversations.saveCompactionSummary(conversation, summary, metadata);
            conversations.saveEvent(conversation, "COMPACTION_COMPLETED", metadata);
            return true;
        } catch (RuntimeException projectionFailure) {
            log.warn("Durable conversation compaction {} completed but legacy projection failed: {}",
                    record.getCompactionId(), projectionFailure.getMessage());
            throw new IllegalStateException(
                    "Durable conversation compaction completed but legacy projection failed", projectionFailure);
        }
    }

    private void requireTaskOwnership(Integer studentId, Integer projectId, String conversationId,
                                      AgentTask task) {
        if (task == null || task.getTaskId() == null || task.getTaskId() <= 0
                || !equals(studentId, task.getStudentId())
                || !equals(projectId, task.getProjectId())
                || conversationId == null || !conversationId.equals(task.getConversationId())
                || !"compact".equalsIgnoreCase(task.getMode())) {
            throw new IllegalArgumentException("Owned manual compaction task is required");
        }
    }

    private boolean equals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private int tailTurns(AgentModelConfig config) {
        return config == null || config.getCompactionTailTurns() == null
                ? DEFAULT_TAIL_TURNS : Math.max(1, config.getCompactionTailTurns());
    }

    private int tailTokenBudget(AgentModelConfig config) {
        return config == null || config.getCompactionPreserveRecentTokens() == null
                ? DEFAULT_TAIL_TOKEN_BUDGET : Math.max(1, config.getCompactionPreserveRecentTokens());
    }

    private int contextWindow(AgentModelConfig config) {
        return config == null || config.getContextWindowTokens() == null
                ? 0 : Math.max(0, config.getContextWindowTokens());
    }

    private int reservedOutput(AgentModelConfig config) {
        return config == null || config.getMaxTokens() == null ? 0 : Math.max(0, config.getMaxTokens());
    }

    private String latestUserRequest(List<Map<String, Object>> source) {
        for (int index = source.size() - 1; index >= 0; index--) {
            Map<String, Object> message = source.get(index);
            if ("user".equalsIgnoreCase(String.valueOf(message.get("role")))) {
                String content = String.valueOf(message.getOrDefault("content", ""));
                if (!content.isBlank()) {
                    return content;
                }
            }
        }
        return "Manually compact this conversation while retaining durable facts and pending work.";
    }

    /** 模型不可用时，只从已选定的 durable head 生成有界摘要。 */
    private String deterministicCheckpoint(List<Map<String, Object>> head) {
        List<String> items = new ArrayList<>();
        for (Map<String, Object> message : head == null ? List.<Map<String, Object>>of() : head) {
            if (items.size() >= SUMMARY_ITEM_COUNT) {
                break;
            }
            String role = String.valueOf(message.getOrDefault("role", "message"));
            String content = String.valueOf(message.getOrDefault("content", "")).trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.length() > SUMMARY_ITEM_LIMIT) {
                content = content.substring(0, SUMMARY_ITEM_LIMIT) + "...";
            }
            items.add("- [" + role + "] " + content);
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("Durable conversation head is empty");
        }
        return "<conversation-checkpoint version=\"3\" source=\"manual-deterministic\">\n"
                + String.join("\n", items)
                + "\n</conversation-checkpoint>";
    }

    private String failureReason(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    public record Result(String summary,
                         String strategy,
                         boolean deterministicFallback,
                         Long compactionId,
                         long sourceMaxTaskId,
                         boolean legacyProjectionWritten) {
    }
}
