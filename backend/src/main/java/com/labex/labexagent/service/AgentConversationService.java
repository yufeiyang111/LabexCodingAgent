package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.labexagent.llm.InternalReasoningBoundary;
import com.labex.rag.config.RagConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentConversationService {
    private static final Logger log = LoggerFactory.getLogger(AgentConversationService.class);
    private static final Gson GSON = new Gson();
    private static final int MEMORY_CONTEXT_LIMIT = 12000;
    private static final int RECENT_EVENT_LIMIT = 24;
    private static final Set<String> MEMORY_SKIP_TYPES = Set.of("SESSION", "THINK", "TOOL_CALL",
            "COMPACTION_STARTED", "COMPACTION_COMPLETED", "COMPACTION_FAILED", "CONTEXT_PRUNED");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{10,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~-]{10,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|authorization|token|password|secret)\\s*[:=]\\s*([^\\s,;\\]}]+)");
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final RagConfig ragConfig;
    private final AgentConversationForkBoundaryService forkBoundaries;
    private final AgentConversationMemoryProjectionService durableMemoryProjection;
    private final String memoryProjectionMode;

    public AgentConversationService(AgentConversationMapper conversationMapper,
                                    AgentMessageMapper messageMapper,
                                    RagConfig ragConfig) {
        this(conversationMapper, messageMapper, ragConfig,
                (AgentConversationForkBoundaryService) null, null, "legacy");
    }

    /** 测试与迁移验证使用；生产由 Spring 注入唯一的 boundary service。 */
    public AgentConversationService(AgentConversationMapper conversationMapper,
                                    AgentMessageMapper messageMapper,
                                    RagConfig ragConfig,
                                    AgentTaskMapper taskMapper,
                                    AgentConversationMemoryProjectionService durableMemoryProjection,
                                    String memoryProjectionMode) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.ragConfig = ragConfig;
        this.forkBoundaries = taskMapper == null ? null
                : new AgentConversationForkBoundaryService(conversationMapper, messageMapper, taskMapper);
        this.durableMemoryProjection = durableMemoryProjection;
        this.memoryProjectionMode = normalizeMemoryProjectionMode(memoryProjectionMode);
    }

    @Autowired
    public AgentConversationService(AgentConversationMapper conversationMapper,
                                    AgentMessageMapper messageMapper,
                                    RagConfig ragConfig,
                                    AgentConversationForkBoundaryService forkBoundaries,
                                    AgentConversationMemoryProjectionService durableMemoryProjection,
                                    @Value("${labex-agent.memory.projection-mode:durable}") String memoryProjectionMode) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.ragConfig = ragConfig;
        this.forkBoundaries = forkBoundaries;
        this.durableMemoryProjection = durableMemoryProjection;
        this.memoryProjectionMode = normalizeMemoryProjectionMode(memoryProjectionMode);
    }

    public AgentConversation ensureConversation(Integer studentId, StudentProject project, String conversationId, String mode, String firstMessage) {
        return ensureConversation(studentId, project, conversationId, mode, firstMessage, null);
    }

    public AgentConversation ensureConversation(Integer studentId, StudentProject project, String conversationId, String mode,
                                                String firstMessage, AgentModelConfig modelConfig) {
        AgentConversation existing;
        if (conversationId != null && !conversationId.isBlank() && (existing = this.getOwnedConversation(studentId, project.getProjectId(), conversationId)) != null) {
            applyModelMetadata(existing, modelConfig);
            return existing;
        }
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setStudentId(studentId);
        conversation.setProjectId(project.getProjectId());
        conversation.setTitle(this.buildTitle(firstMessage));
        conversation.setMode(mode);
        conversation.setProvider(modelConfig == null
                ? this.ragConfig.getLlmProvider()
                : normalizeProvider(modelConfig.getProvider()));
        conversation.setModel(modelConfig == null
                ? ("ollama".equalsIgnoreCase(this.ragConfig.getLlmProvider()) ? this.ragConfig.getOllamaModel() : this.ragConfig.getMiniMaxModel())
                : normalizeModel(modelConfig.getModelName()));
        conversation.setStatus(Integer.valueOf(1));
        conversation.setCreateTime(LocalDateTime.now());
        conversation.setUpdateTime(LocalDateTime.now());
        this.conversationMapper.insert(conversation);
        return conversation;
    }

    private void applyModelMetadata(AgentConversation conversation, AgentModelConfig modelConfig) {
        if (conversation == null || modelConfig == null) {
            return;
        }
        String provider = normalizeProvider(modelConfig.getProvider());
        String model = normalizeModel(modelConfig.getModelName());
        if (provider.equals(conversation.getProvider()) && model.equals(conversation.getModel())) {
            return;
        }
        conversation.setProvider(provider);
        conversation.setModel(model);
        conversation.setUpdateTime(LocalDateTime.now());
        this.conversationMapper.updateById(conversation);
    }

    private String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "openai_compatible" : provider;
    }

    private String normalizeModel(String model) {
        return model == null ? "" : model;
    }

    public AgentConversation getOwnedConversation(Integer studentId, Integer projectId, String conversationId) {
        return this.conversationMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.labex.entity.AgentConversation>().eq(com.labex.entity.AgentConversation::getConversationId, conversationId).eq(com.labex.entity.AgentConversation::getStudentId, studentId).eq(com.labex.entity.AgentConversation::getProjectId, projectId).eq(com.labex.entity.AgentConversation::getStatus, 1));
    }

    public List<AgentConversation> list(Integer studentId, Integer projectId) {
        return this.conversationMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.labex.entity.AgentConversation>().eq(com.labex.entity.AgentConversation::getStudentId, studentId).eq(com.labex.entity.AgentConversation::getProjectId, projectId).eq(com.labex.entity.AgentConversation::getStatus, 1)
                .orderByDesc(com.labex.entity.AgentConversation::getUpdateTime)
                .orderByDesc(com.labex.entity.AgentConversation::getCreateTime)
                .orderByDesc(com.labex.entity.AgentConversation::getConversationId));
    }

    public List<AgentMessage> messages(Integer studentId, Integer projectId, String conversationId) {
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        return this.messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getConversationId, conversationId).eq(AgentMessage::getStudentId, studentId).eq(AgentMessage::getProjectId, projectId).orderByAsc(AgentMessage::getMessageId));
    }

    public MessagePage messagePage(Integer studentId, Integer projectId, String conversationId,
                                   Long beforeMessageId, int turnLimit) {
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        int safeTurnLimit = Math.min(50, Math.max(1, turnLimit));
        LambdaQueryWrapper<AgentMessage> turnsQuery = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId)
                .eq(AgentMessage::getEventType, "USER");
        if (beforeMessageId != null && beforeMessageId > 0) {
            turnsQuery.lt(AgentMessage::getMessageId, beforeMessageId);
        }
        List<AgentMessage> newestFirstTurns = this.messageMapper.selectList(turnsQuery
                .orderByDesc(AgentMessage::getMessageId)
                .last("LIMIT " + (safeTurnLimit + 1)));
        if (newestFirstTurns.isEmpty()) {
            return new MessagePage(List.of(), false, null);
        }
        boolean hasMore = newestFirstTurns.size() > safeTurnLimit;
        List<AgentMessage> selectedTurns = newestFirstTurns.subList(0, Math.min(safeTurnLimit, newestFirstTurns.size()));
        long firstMessageId = selectedTurns.get(selectedTurns.size() - 1).getMessageId();
        LambdaQueryWrapper<AgentMessage> eventsQuery = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId)
                .ge(AgentMessage::getMessageId, firstMessageId);
        if (beforeMessageId != null && beforeMessageId > 0) {
            eventsQuery.lt(AgentMessage::getMessageId, beforeMessageId);
        }
        List<AgentMessage> events = this.messageMapper.selectList(eventsQuery.orderByAsc(AgentMessage::getMessageId));
        return new MessagePage(events, hasMore, hasMore ? firstMessageId : null);
    }

    public void delete(Integer studentId, Integer projectId, String conversationId) {
        this.conversationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.labex.entity.AgentConversation>().eq(com.labex.entity.AgentConversation::getConversationId, conversationId).eq(com.labex.entity.AgentConversation::getStudentId, studentId).eq(com.labex.entity.AgentConversation::getProjectId, projectId).set(com.labex.entity.AgentConversation::getStatus, 0).set(com.labex.entity.AgentConversation::getUpdateTime, java.time.LocalDateTime.now()));
    }

    public void saveUserMessage(AgentConversation conversation, String content) {
        this.saveMessage(conversation, "USER", "user", content, Map.of("content", content));
    }

    public void saveEvent(AgentConversation conversation, String type, Object data) {
        Object safeData = this.sanitizeReasoningProjection(type, data);
        String content = this.extractContent(safeData);
        String role = "FINAL".equals(type) || "FINAL_DELTA".equals(type) ? "assistant" : "event";
        this.saveMessage(conversation, type, role, content, safeData);
    }

    public void saveCompactionSummary(AgentConversation conversation, String checkpoint, Map<String, Object> metadata) {
        if (conversation == null || checkpoint == null || checkpoint.isBlank()) {
            return;
        }
        String safeCheckpoint = redactSecrets(checkpoint);
        LinkedHashMap<String, Object> safeMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        safeMetadata.put("content", safeCheckpoint);
        this.saveMessage(conversation, "COMPACTION_SUMMARY", "event", safeCheckpoint, safeMetadata);
        conversation.setCompactedAt(LocalDateTime.now());
        this.conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversation.getConversationId())
                .set(AgentConversation::getCompactedAt, conversation.getCompactedAt())
                .set(AgentConversation::getUpdateTime, LocalDateTime.now()));
    }

    public String buildMemoryContext(Integer studentId, Integer projectId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "";
        }
        if ("durable".equals(memoryProjectionMode)) {
            return buildDurableMemoryContext(studentId, projectId, conversationId);
        }
        String legacy = buildLegacyMemoryContext(studentId, projectId, conversationId);
        if (!"shadow".equals(memoryProjectionMode) || durableMemoryProjection == null) {
            return legacy;
        }
        try {
            String durable = buildDurableMemoryContext(studentId, projectId, conversationId);
            logMemoryShadowComparison(conversationId, legacy, durable);
        } catch (RuntimeException failure) {
            log.warn("Conversation memory shadow projection failed for {}: {}",
                    conversationId, failure.getClass().getSimpleName());
        }
        return legacy;
    }

    private String buildDurableMemoryContext(Integer studentId, Integer projectId, String conversationId) {
        if (durableMemoryProjection == null) {
            throw new IllegalStateException("Durable conversation memory projection is unavailable");
        }
        return durableMemoryProjection.buildContext(studentId, projectId, conversationId);
    }

    private String buildLegacyMemoryContext(Integer studentId, Integer projectId, String conversationId) {
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            return "";
        }
        AgentMessage latestCompaction = latestCompactionSummary(studentId, projectId, conversationId);
        List<AgentMessage> recent = recentMemoryEvents(studentId, projectId, conversationId,
                latestCompaction == null ? null : latestCompaction.getMessageId());
        StringBuilder builder = new StringBuilder();
        if (latestCompaction != null && latestCompaction.getContent() != null && !latestCompaction.getContent().isBlank()) {
            builder.append("\u5386\u53f2\u538b\u7f29\u6458\u8981:\n").append(latestCompaction.getContent()).append("\n\n");
        }
        builder.append("\u6700\u8fd1\u5173\u952e\u4e8b\u4ef6:\n");
        for (int i = recent.size() - 1; i >= 0; --i) {
            AgentMessage message = recent.get(i);
            if (MEMORY_SKIP_TYPES.contains(message.getEventType())) {
                continue;
            }
            builder.append('[').append(message.getEventType()).append("] ")
                    .append(this.limit(message.getContent(), this.memoryItemLimit(message.getEventType())))
                    .append('\n');
        }
        return this.limit(builder.toString(), MEMORY_CONTEXT_LIMIT);
    }

    private void logMemoryShadowComparison(String conversationId, String legacy, String durable) {
        String legacyNormalized = normalizeMemoryText(legacy);
        String durableNormalized = normalizeMemoryText(durable);
        boolean exact = legacyNormalized.equals(durableNormalized);
        log.info("Conversation memory shadow comparison conversation={} exact={} legacyChars={} durableChars={} legacyHash={} durableHash={}",
                conversationId, exact, legacyNormalized.length(), durableNormalized.length(),
                fingerprint(legacyNormalized), fingerprint(durableNormalized));
    }

    private static String normalizeMemoryProjectionMode(String mode) {
        String normalized = mode == null ? "durable" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("legacy", "shadow", "durable").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported conversation memory projection mode: " + mode);
        }
        return normalized;
    }

    private String normalizeMemoryText(String value) {
        return (value == null ? "" : value).replaceAll("\\s+", " ").trim();
    }

    private String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private AgentMessage latestCompactionSummary(Integer studentId, Integer projectId, String conversationId) {
        List<AgentMessage> summaries = this.messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId)
                .eq(AgentMessage::getEventType, "COMPACTION_SUMMARY")
                .orderByDesc(AgentMessage::getMessageId)
                .last("LIMIT 1"));
        return summaries == null || summaries.isEmpty() ? null : summaries.get(0);
    }

    private List<AgentMessage> recentMemoryEvents(Integer studentId, Integer projectId, String conversationId,
                                                    Long afterMessageId) {
        LambdaQueryWrapper<AgentMessage> query = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId);
        if (afterMessageId != null) {
            query.gt(AgentMessage::getMessageId, afterMessageId);
        }
        return this.messageMapper.selectList(query.orderByDesc(AgentMessage::getMessageId)
                .last("LIMIT " + RECENT_EVENT_LIMIT));
    }

    public record MessagePage(List<AgentMessage> events, boolean hasMore, Long nextBeforeMessageId) {
    }


    public AgentConversation forkConversation(Integer studentId, Integer projectId,
                                                String conversationId, Long messageId) {
        return forkConversation(studentId, projectId, conversationId, messageId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentConversation forkConversation(Integer studentId, Integer projectId,
                                                String conversationId, Long messageId,
                                                Long requestedTaskId) {
        AgentConversation source = this.conversationMapper.selectOwnedForUpdate(
                studentId, projectId, conversationId);
        if (source == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        Long forkedFromTaskId = forkBoundaries == null ? null
                : forkBoundaries.resolveNewFork(
                        studentId, projectId, conversationId, messageId, requestedTaskId);
        LocalDateTime copyCutoff = forkBoundaries == null ? null
                : forkBoundaries.copyCutoff(studentId, projectId, conversationId, forkedFromTaskId);

        AgentConversation child = new AgentConversation();
        child.setConversationId(UUID.randomUUID().toString());
        child.setStudentId(studentId);
        child.setProjectId(projectId);
        child.setTitle(this.buildForkTitle(source.getTitle()));
        child.setMode(source.getMode());
        child.setProvider(source.getProvider());
        child.setModel(source.getModel());
        child.setParentConversationId(source.getConversationId());
        child.setForkedFromMessageId(messageId);
        child.setForkedFromTaskId(forkedFromTaskId);
        child.setStatus(1);
        child.setCreateTime(LocalDateTime.now());
        child.setUpdateTime(LocalDateTime.now());
        if (this.conversationMapper.insert(child) != 1) {
            throw new IllegalStateException("Unable to persist forked conversation");
        }

        List<AgentMessage> sourceMessages = List.of();
        if (forkBoundaries == null || forkedFromTaskId != null) {
            LambdaQueryWrapper<AgentMessage> query = new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversationId)
                    .eq(AgentMessage::getStudentId, studentId)
                    .eq(AgentMessage::getProjectId, projectId);
            if (messageId != null && messageId > 0) {
                query.le(AgentMessage::getMessageId, messageId);
            }
            if (copyCutoff != null) {
                query.le(AgentMessage::getCreateTime, copyCutoff);
            }
            query.orderByAsc(AgentMessage::getMessageId);
            List<AgentMessage> selected = this.messageMapper.selectList(query);
            sourceMessages = selected == null ? List.of() : selected;
        }
        for (AgentMessage sourceMessage : sourceMessages) {
            AgentMessage copy = new AgentMessage();
            copy.setConversationId(child.getConversationId());
            copy.setStudentId(studentId);
            copy.setProjectId(projectId);
            copy.setEventType(sourceMessage.getEventType());
            copy.setRole(sourceMessage.getRole());
            copy.setContent(sourceMessage.getContent());
            copy.setEventData(sourceMessage.getEventData());
            copy.setCreateTime(sourceMessage.getCreateTime() == null
                    ? LocalDateTime.now() : sourceMessage.getCreateTime());
            this.messageMapper.insert(copy);
        }
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceConversationId", source.getConversationId());
        metadata.put("forkedFromMessageId", messageId == null ? "" : messageId);
        metadata.put("forkedFromTaskId", forkedFromTaskId == null ? "" : forkedFromTaskId);
        metadata.put("projectionOnly", true);
        this.saveMessage(child, "SYSTEM", "event",
                "Conversation forked from " + source.getConversationId(), metadata);
        return child;
    }

    public MemoryStats getMemoryStats(Integer studentId, Integer projectId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return new MemoryStats(0, 0, false, 0);
        }
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            return new MemoryStats(0, 0, false, 0);
        }
        Long count = this.messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getConversationId, conversationId).eq(AgentMessage::getStudentId, studentId).eq(AgentMessage::getProjectId, projectId));
        AgentMessage latestCompaction = this.latestCompactionSummary(studentId, projectId, conversationId);
        String summary = latestCompaction == null || latestCompaction.getContent() == null
                ? "" : latestCompaction.getContent();
        return new MemoryStats(summary.length(), count == null ? 0 : count.intValue(), this.isAutoCompacted(summary), 12000);
    }

    private Object sanitizeReasoningProjection(String type, Object data) {
        boolean finalProjection = "FINAL".equals(type) || "FINAL_DELTA".equals(type);
        boolean reasoningProjection = type != null && type.startsWith("THINK");
        if (!finalProjection && !reasoningProjection) return data;
        if (data instanceof String text) {
            return finalProjection
                    ? InternalReasoningBoundary.stripVisible(text)
                    : InternalReasoningBoundary.stripTags(text);
        }
        if (!(data instanceof Map<?, ?> source)) return data;

        LinkedHashMap<Object, Object> safe = new LinkedHashMap<>(source);
        for (String field : List.of("content", "delta")) {
            Object value = safe.get(field);
            if (value instanceof String text) {
                safe.put(field, finalProjection
                        ? InternalReasoningBoundary.stripVisible(text)
                        : InternalReasoningBoundary.stripTags(text));
            }
        }
        return safe;
    }

    private void saveMessage(AgentConversation conversation, String type, String role, String content, Object data) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversation.getConversationId());
        message.setStudentId(conversation.getStudentId());
        message.setProjectId(conversation.getProjectId());
        message.setEventType(type);
        message.setRole(role);
        message.setContent(this.limit(content, 30000));
        message.setEventData(this.limit(GSON.toJson(data), 30000));
        message.setCreateTime(LocalDateTime.now());
        this.messageMapper.insert(message);
        this.conversationMapper.update(null, (new LambdaUpdateWrapper<AgentConversation>().eq(AgentConversation::getConversationId, conversation.getConversationId())).set(AgentConversation::getUpdateTime, LocalDateTime.now()));
    }

    private boolean isAutoCompacted(String summary) {
        return summary != null && (summary.startsWith("Auto compacted context:")
                || summary.startsWith("Manual compacted context:")
                || summary.contains("<conversation-checkpoint version=\"3\""));
    }

    private String extractContent(Object data) {
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            Object content = map.get("content");
            if (content == null) {
                content = map.get("message");
            }
            if (content == null) {
                content = map.get("delta");
            }
            return content == null ? GSON.toJson(data) : String.valueOf(content);
        }
        return data == null ? "" : String.valueOf(data);
    }

    private String buildTitle(String firstMessage) {
        String text = firstMessage == null || firstMessage.isBlank() ? "\u65b0\u5bf9\u8bdd" : firstMessage.trim().replaceAll("\\s+", " ");
        return text.length() > 28 ? text.substring(0, 28) + "..." : text;
    }

    private String buildForkTitle(String sourceTitle) {
        String base = sourceTitle == null || sourceTitle.isBlank() ? "\u65b0\u5bf9\u8bdd" : sourceTitle.trim();
        String title = base + " 分支";
        return title.length() > 36 ? title.substring(0, 36) + "..." : title;
    }

    private int memoryItemLimit(String type) {
        return switch (type == null ? "" : type) {
            case "USER" -> 500;
            case "FINAL" -> 700;
            case "OBSERVE" -> 420;
            case "ERROR", "INTERRUPTED", "COMMAND_APPROVAL_REQUIRED", "USER_QUESTION" -> 600;
            case "COMPACTION_SUMMARY" -> 6_500;
            default -> 300;
        };
    }

    private static String redactSecrets(String text) {
        String safe = text == null ? "" : text;
        safe = API_KEY.matcher(safe).replaceAll("[REDACTED]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        return NAMED_SECRET.matcher(safe).replaceAll("$1=[REDACTED]");
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return "...\u5df2\u538b\u7f29\u65e9\u671f\u5185\u5bb9...\n" + text.substring(text.length() - max);
    }

    public static class MemoryStats {
        private final int estimatedTokens;
        private final int messageCount;
        private final boolean needsCompact;
        private final int maxTokens;
        public MemoryStats(int estimatedTokens, int messageCount, boolean needsCompact, int maxTokens) {
            this.estimatedTokens = estimatedTokens; this.messageCount = messageCount; this.needsCompact = needsCompact; this.maxTokens = maxTokens;
        }
        public int getEstimatedTokens() { return estimatedTokens; }
        public int getMessageCount() { return messageCount; }
        public boolean isNeedsCompact() { return needsCompact; }
        public int getMaxTokens() { return maxTokens; }
    }
}
