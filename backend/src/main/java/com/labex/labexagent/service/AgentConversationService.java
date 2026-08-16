package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfileProperties;
import com.labex.mapper.AgentConversationMapper;
import com.labex.rag.config.RagConfig;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话元数据服务；Provider memory 与 UI 历史都委托 durable projector。 */
@Service
public class AgentConversationService {
    private final AgentConversationMapper conversationMapper;
    private final RagConfig ragConfig;
    private final AgentConversationForkBoundaryService forkBoundaries;
    private final AgentConversationMemoryProjectionService durableMemoryProjection;
    private final AgentConversationHistoryProjectionService historyProjection;
    private AgentRuntimeProfileProperties runtimeProfileProperties = new AgentRuntimeProfileProperties();

    @Autowired
    public AgentConversationService(AgentConversationMapper conversationMapper,
                                    RagConfig ragConfig,
                                    AgentConversationForkBoundaryService forkBoundaries,
                                    AgentConversationMemoryProjectionService durableMemoryProjection,
                                    AgentConversationHistoryProjectionService historyProjection) {
        this.conversationMapper = conversationMapper;
        this.ragConfig = ragConfig;
        this.forkBoundaries = forkBoundaries;
        this.durableMemoryProjection = durableMemoryProjection;
        this.historyProjection = historyProjection;
    }

    @Autowired
    void setRuntimeProfileProperties(AgentRuntimeProfileProperties runtimeProfileProperties) {
        if (runtimeProfileProperties == null) {
            throw new IllegalArgumentException("runtimeProfileProperties is required");
        }
        this.runtimeProfileProperties = runtimeProfileProperties;
    }

    public AgentConversation ensureConversation(Integer studentId, StudentProject project,
                                                String conversationId, String mode, String firstMessage) {
        return ensureConversation(studentId, project, conversationId, mode, firstMessage, null);
    }

    public AgentConversation ensureConversation(Integer studentId, StudentProject project,
                                                String conversationId, String mode, String firstMessage,
                                                AgentModelConfig modelConfig) {
        return ensureConversation(studentId, project, conversationId, mode, firstMessage, modelConfig, null);
    }

    /**
     * 确保会话存在，并在新会话创建时固定其运行时 profile。
     * 已存在会话的 profile 不可修改；只有显式传入不一致 profile 时才拒绝请求。
     */
    public AgentConversation ensureConversation(Integer studentId, StudentProject project,
                                                String conversationId, String mode, String firstMessage,
                                                AgentModelConfig modelConfig,
                                                AgentRuntimeProfile requestedRuntimeProfile) {
        AgentConversation existing;
        if (conversationId != null && !conversationId.isBlank()
                && (existing = getOwnedConversation(studentId, project.getProjectId(), conversationId)) != null) {
            assertRequestedProfileMatches(existing, requestedRuntimeProfile);
            applyModelMetadata(existing, modelConfig);
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setStudentId(studentId);
        conversation.setProjectId(project.getProjectId());
        conversation.setTitle(buildTitle(firstMessage));
        conversation.setMode(mode);
        conversation.setProvider(modelConfig == null
                ? ragConfig.getLlmProvider() : normalizeProvider(modelConfig.getProvider()));
        conversation.setModel(modelConfig == null
                ? ("ollama".equalsIgnoreCase(ragConfig.getLlmProvider())
                    ? ragConfig.getOllamaModel() : ragConfig.getMiniMaxModel())
                : normalizeModel(modelConfig.getModelName()));
        conversation.setRuntimeProfile(resolveNewConversationProfile(requestedRuntimeProfile).persistedValue());
        conversation.setHistoryProjectionVersion(AgentLegacyConversationHistoryMigrationService.DURABLE_VERSION);
        conversation.setHistoryMigratedAt(now);
        conversation.setStatus(1);
        conversation.setCreateTime(now);
        conversation.setUpdateTime(now);
        if (conversationMapper.insert(conversation) != 1) {
            throw new IllegalStateException("Unable to persist Agent conversation");
        }
        return conversation;
    }

    private AgentRuntimeProfile resolveNewConversationProfile(AgentRuntimeProfile requestedRuntimeProfile) {
        return requestedRuntimeProfile == null ? runtimeProfileProperties.resolveDefaultProfile() : requestedRuntimeProfile;
    }

    private void assertRequestedProfileMatches(AgentConversation conversation,
                                               AgentRuntimeProfile requestedRuntimeProfile) {
        if (requestedRuntimeProfile == null) {
            return;
        }
        AgentRuntimeProfile existingProfile = AgentRuntimeProfile.fromPersisted(conversation.getRuntimeProfile());
        if (existingProfile != requestedRuntimeProfile) {
            throw new IllegalArgumentException("Conversation runtime profile is immutable: "
                    + existingProfile.persistedValue());
        }
    }

    private void applyModelMetadata(AgentConversation conversation, AgentModelConfig modelConfig) {
        if (conversation == null || modelConfig == null) return;
        String provider = normalizeProvider(modelConfig.getProvider());
        String model = normalizeModel(modelConfig.getModelName());
        if (provider.equals(conversation.getProvider()) && model.equals(conversation.getModel())) return;
        conversation.setProvider(provider);
        conversation.setModel(model);
        conversation.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(conversation);
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "openai_compatible" : provider;
    }

    private String normalizeModel(String model) {
        return model == null ? "" : model;
    }

    public AgentConversation getOwnedConversation(Integer studentId, Integer projectId, String conversationId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversationId)
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getStatus, 1));
    }

    public List<AgentConversation> list(Integer studentId, Integer projectId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<AgentConversation>()
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .eq(AgentConversation::getStatus, 1)
                .orderByDesc(AgentConversation::getUpdateTime)
                .orderByDesc(AgentConversation::getCreateTime)
                .orderByDesc(AgentConversation::getConversationId));
    }

    public AgentConversationHistoryProjectionService.HistoryPage messagePage(
            Integer studentId, Integer projectId, String conversationId,
            Long beforeTaskId, int taskLimit) {
        if (historyProjection == null) {
            throw new IllegalStateException("Durable conversation history projection is unavailable");
        }
        return historyProjection.page(studentId, projectId, conversationId, beforeTaskId, taskLimit);
    }

    public void delete(Integer studentId, Integer projectId, String conversationId) {
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversationId)
                .eq(AgentConversation::getStudentId, studentId)
                .eq(AgentConversation::getProjectId, projectId)
                .set(AgentConversation::getStatus, 0)
                .set(AgentConversation::getUpdateTime, LocalDateTime.now()));
    }

    /** 只更新由 durable task graph 派生的会话活跃时间，不写入第二份历史事实。 */
    public void touchActivity(AgentConversation conversation) {
        if (conversation == null || conversation.getConversationId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        conversation.setUpdateTime(now);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversation.getConversationId())
                .eq(conversation.getStudentId() != null, AgentConversation::getStudentId, conversation.getStudentId())
                .eq(conversation.getProjectId() != null, AgentConversation::getProjectId, conversation.getProjectId())
                .set(AgentConversation::getUpdateTime, now));
    }

    /** compacted_at 只表示最近一次压缩元数据；摘要正文保存在 completed AgentCompactionRecord。 */
    public void markCompacted(AgentConversation conversation) {
        if (conversation == null || conversation.getConversationId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        conversation.setCompactedAt(now);
        conversation.setUpdateTime(now);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversation.getConversationId())
                .eq(conversation.getStudentId() != null, AgentConversation::getStudentId, conversation.getStudentId())
                .eq(conversation.getProjectId() != null, AgentConversation::getProjectId, conversation.getProjectId())
                .set(AgentConversation::getCompactedAt, now)
                .set(AgentConversation::getUpdateTime, now));
    }

    public String buildMemoryContext(Integer studentId, Integer projectId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return "";
        if (historyProjection != null) {
            historyProjection.ensureMigrated(studentId, projectId, conversationId);
        }
        if (durableMemoryProjection == null) {
            throw new IllegalStateException("Durable conversation memory projection is unavailable");
        }
        return durableMemoryProjection.buildContext(studentId, projectId, conversationId);
    }

    public AgentConversation forkConversation(Integer studentId, Integer projectId,
                                              String conversationId, Long messageId) {
        return forkConversation(studentId, projectId, conversationId, messageId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentConversation forkConversation(Integer studentId, Integer projectId,
                                              String conversationId, Long messageId,
                                              Long requestedTaskId) {
        AgentConversation source = conversationMapper.selectOwnedForUpdate(studentId, projectId, conversationId);
        if (source == null) throw new IllegalArgumentException("Conversation not found");
        Long forkedFromTaskId = forkBoundaries == null ? null
                : forkBoundaries.resolveNewFork(studentId, projectId, conversationId, messageId, requestedTaskId);
        LocalDateTime now = LocalDateTime.now();
        AgentConversation child = new AgentConversation();
        child.setConversationId(UUID.randomUUID().toString());
        child.setStudentId(studentId);
        child.setProjectId(projectId);
        child.setTitle(buildForkTitle(source.getTitle()));
        child.setMode(source.getMode());
        child.setProvider(source.getProvider());
        child.setModel(source.getModel());
        // 分支是原对话的持久化延续，必须继承 source 的 profile snapshot；历史空值兼容为 legacy。
        child.setRuntimeProfile(AgentRuntimeProfile.fromPersisted(source.getRuntimeProfile()).persistedValue());
        child.setParentConversationId(source.getConversationId());
        child.setForkedFromMessageId(messageId);
        child.setForkedFromTaskId(forkedFromTaskId);
        child.setHistoryProjectionVersion(AgentLegacyConversationHistoryMigrationService.DURABLE_VERSION);
        child.setHistoryMigratedAt(now);
        child.setStatus(1);
        child.setCreateTime(now);
        child.setUpdateTime(now);
        if (conversationMapper.insert(child) != 1) {
            throw new IllegalStateException("Unable to persist forked conversation");
        }
        return child;
    }

    public MemoryStats getMemoryStats(Integer studentId, Integer projectId, String conversationId) {
        if (historyProjection == null || conversationId == null || conversationId.isBlank()) {
            return new MemoryStats(0, 0, false, 0);
        }
        AgentConversationHistoryProjectionService.MemoryStats stats =
                historyProjection.memoryStats(studentId, projectId, conversationId);
        return new MemoryStats(stats.estimatedTokens(), stats.messageCount(),
                stats.needsCompact(), stats.maxTokens());
    }

    private String buildTitle(String firstMessage) {
        String text = firstMessage == null || firstMessage.isBlank()
                ? "新对话" : firstMessage.trim().replaceAll("\\s+", " ");
        return text.length() > 28 ? text.substring(0, 28) + "..." : text;
    }

    private String buildForkTitle(String sourceTitle) {
        String base = sourceTitle == null || sourceTitle.isBlank() ? "新对话" : sourceTitle.trim();
        String title = base + " 分支";
        return title.length() > 36 ? title.substring(0, 36) + "..." : title;
    }

    public static class MemoryStats {
        private final int estimatedTokens;
        private final int messageCount;
        private final boolean needsCompact;
        private final int maxTokens;

        public MemoryStats(int estimatedTokens, int messageCount, boolean needsCompact, int maxTokens) {
            this.estimatedTokens = estimatedTokens;
            this.messageCount = messageCount;
            this.needsCompact = needsCompact;
            this.maxTokens = maxTokens;
        }

        public int getEstimatedTokens() { return estimatedTokens; }
        public int getMessageCount() { return messageCount; }
        public boolean isNeedsCompact() { return needsCompact; }
        public int getMaxTokens() { return maxTokens; }
    }
}
