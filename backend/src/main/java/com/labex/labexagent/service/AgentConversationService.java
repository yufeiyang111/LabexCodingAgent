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
import com.labex.labexagent.runtime.CancellationToken;
import com.labex.labexagent.runtime.CompactionAgent;
import com.labex.rag.config.RagConfig;
import com.labex.service.AgentModelConfigService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentConversationService {
    private static final Gson GSON = new Gson();
    private static final int SUMMARY_LIMIT = 9000;
    private static final int MEMORY_CONTEXT_LIMIT = 12000;
    private static final int RECENT_EVENT_LIMIT = 24;
    private static final int COMPACT_TRIGGER_CHARS = 16000;
    private static final int COMPACT_SOURCE_EVENTS = 120;
    private static final int COMPACT_TAIL_EVENTS = 18;
    private static final Set<String> MEMORY_SKIP_TYPES = Set.of("SESSION", "THINK", "TOOL_CALL",
            "COMPACTION_STARTED", "COMPACTION_COMPLETED", "COMPACTION_FAILED", "CONTEXT_PRUNED");
    private static final Set<String> MEMORY_IMPORTANT_TYPES = Set.of("USER", "FINAL", "OBSERVE", "ERROR", "INTERRUPTED",
            "COMMAND_APPROVAL_REQUIRED", "USER_QUESTION", "COMPACTION_SUMMARY");
    private static final Pattern API_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{10,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~-]{10,}");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)\\b(api[_ -]?key|authorization|token|password|secret)\\s*[:=]\\s*([^\\s,;\\]}]+)");
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final RagConfig ragConfig;
    private final CompactionAgent compactionAgent;
    private final AgentModelConfigService modelConfigService;

    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper, RagConfig ragConfig) {
        this(conversationMapper, messageMapper, ragConfig, null, null);
    }

    @Autowired
    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper,
                                    RagConfig ragConfig, CompactionAgent compactionAgent,
                                    AgentModelConfigService modelConfigService) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.ragConfig = ragConfig;
        this.compactionAgent = compactionAgent;
        this.modelConfigService = modelConfigService;
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
        conversation.setSummary("");
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
        return this.conversationMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.labex.entity.AgentConversation>().eq(com.labex.entity.AgentConversation::getStudentId, studentId).eq(com.labex.entity.AgentConversation::getProjectId, projectId).eq(com.labex.entity.AgentConversation::getStatus, 1).orderByDesc(com.labex.entity.AgentConversation::getUpdateTime));
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
        this.updateSummary(conversation, "USER", content);
        this.autoCompactIfNeeded(conversation);
    }

    public void saveEvent(AgentConversation conversation, String type, Object data) {
        String content = this.extractContent(data);
        String role = "FINAL".equals(type) || "FINAL_DELTA".equals(type) ? "assistant" : "event";
        this.saveMessage(conversation, type, role, content, data);
        if (MEMORY_IMPORTANT_TYPES.contains(type)) {
            this.updateSummary(conversation, type, content);
        }
        this.autoCompactIfNeeded(conversation);
    }

    public void saveCompactionSummary(AgentConversation conversation, String checkpoint, Map<String, Object> metadata) {
        if (conversation == null || checkpoint == null || checkpoint.isBlank()) {
            return;
        }
        String safeCheckpoint = redactSecrets(checkpoint);
        LinkedHashMap<String, Object> safeMetadata = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        safeMetadata.put("content", safeCheckpoint);
        this.saveMessage(conversation, "COMPACTION_SUMMARY", "event", safeCheckpoint, safeMetadata);
        this.persistSummary(conversation, safeCheckpoint);
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
        } else {
            String summary = conversation.getSummary() == null ? "" : conversation.getSummary();
            if (!summary.isBlank()) {
                builder.append("\u5386\u53f2\u6458\u8981:\n").append(summary).append("\n\n");
            }
        }
        builder.append("\u6700\u8fd1\u5173\u952e\u4e8b\u4ef6:\n");
        for (int i = recent.size() - 1; i >= 0; --i) {
            AgentMessage message = recent.get(i);
            if (MEMORY_SKIP_TYPES.contains(message.getEventType())) continue;
            builder.append('[').append(message.getEventType()).append("] ")
                    .append(this.limit(message.getContent(), this.memoryItemLimit(message.getEventType())))
                    .append('\n');
        }
        return this.limit(builder.toString(), MEMORY_CONTEXT_LIMIT);
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

    public String compactConversation(Integer studentId, Integer projectId, String conversationId) {
        return compactConversation(studentId, projectId, conversationId, null).summary();
    }

    public ManualCompactionResult compactConversation(Integer studentId, Integer projectId, String conversationId,
                                                       Integer modelConfigId) {
        return compactConversation(studentId, projectId, conversationId, modelConfigId, CancellationToken.none());
    }

    public ManualCompactionResult compactConversation(Integer studentId, Integer projectId, String conversationId,
                                                       Integer modelConfigId, CancellationToken cancellationToken) {
        CancellationToken token = cancellationToken == null ? CancellationToken.none() : cancellationToken;
        if (token.isCancellationRequested()) {
            throw new java.util.concurrent.CancellationException("Manual compaction cancelled");
        }
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        List<AgentMessage> recent = this.messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId)
                .orderByDesc(AgentMessage::getMessageId)
                .last("LIMIT " + COMPACT_SOURCE_EVENTS));
        AgentModelConfig activeConfig = modelConfigService == null ? null
                : modelConfigService.resolveForStudent(studentId, modelConfigId);
        if (modelConfigId != null && modelConfigId > 0 && (activeConfig == null
                || !modelConfigId.equals(activeConfig.getConfigId())
                || !Integer.valueOf(1).equals(activeConfig.getStatus()))) {
            throw new IllegalArgumentException("Compaction model config not found or disabled");
        }
        this.saveEvent(conversation, "COMPACTION_STARTED", Map.of("strategy", "manual", "modelConfigId",
                modelConfigId == null ? 0 : modelConfigId));
        CompactionAgent.Result modelResult = compactionAgent == null
                ? CompactionAgent.Result.failure("Compaction agent is unavailable")
                : compactionAgent.compact(studentId, activeConfig, runtimeMessagesForCompaction(recent),
                        latestUserRequest(recent), null, token);
        if (token.isCancellationRequested() || "Compaction cancelled".equals(modelResult.reason())) {
            throw new java.util.concurrent.CancellationException("Manual compaction cancelled");
        }
        if (modelResult.success()) {
            this.saveCompactionSummary(conversation, modelResult.checkpoint(), Map.of(
                    "strategy", "manual_model",
                    "modelConfigId", modelResult.modelConfigId() == null ? 0 : modelResult.modelConfigId(),
                    "dedicatedModel", modelResult.dedicatedModelSelected()));
            this.saveEvent(conversation, "COMPACTION_COMPLETED", Map.of("strategy", "manual_model"));
            return new ManualCompactionResult(modelResult.checkpoint(), "manual_model", false);
        }
        this.saveEvent(conversation, "COMPACTION_FAILED", Map.of("strategy", "manual_model",
                "reason", modelResult.reason()));
        String fallback = deterministicManualSummary(conversation, recent);
        String checkpoint = "<conversation-checkpoint version=\"3\" source=\"manual-deterministic\">\n"
                + fallback + "\n</conversation-checkpoint>";
        this.saveCompactionSummary(conversation, checkpoint, Map.of("strategy", "manual_deterministic_fallback",
                "reason", modelResult.reason()));
        this.saveEvent(conversation, "COMPACTION_COMPLETED", Map.of("strategy", "manual_deterministic_fallback"));
        return new ManualCompactionResult(checkpoint, "manual_deterministic_fallback", true);
    }

    private String deterministicManualSummary(AgentConversation conversation, List<AgentMessage> recent) {
        String summary = this.rebuildCompactedSummary(conversation, recent, "Manual compacted context");
        this.persistSummary(conversation, redactSecrets(summary));
        conversation.setCompactedAt(LocalDateTime.now());
        this.conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversation.getConversationId())
                .set(AgentConversation::getCompactedAt, conversation.getCompactedAt())
                .set(AgentConversation::getUpdateTime, LocalDateTime.now()));
        return summary;
    }

    private List<Map<String, Object>> runtimeMessagesForCompaction(List<AgentMessage> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (AgentMessage message : chronological) {
            if (message == null || MEMORY_SKIP_TYPES.contains(message.getEventType())) {
                continue;
            }
            String content = message.getContent() == null ? "" : message.getContent();
            if (content.isBlank()) {
                continue;
            }
            String role = "FINAL".equals(message.getEventType()) ? "assistant" : "user";
            String projected = "USER".equals(message.getEventType()) || "FINAL".equals(message.getEventType())
                    ? content : "[" + message.getEventType() + "] " + content;
            messages.add(Map.of("role", role, "content", redactSecrets(this.limit(projected, 6_000))));
        }
        return messages;
    }

    private String latestUserRequest(List<AgentMessage> newestFirst) {
        if (newestFirst != null) {
            for (AgentMessage message : newestFirst) {
                if ("USER".equals(message.getEventType()) && message.getContent() != null && !message.getContent().isBlank()) {
                    return redactSecrets(message.getContent());
                }
            }
        }
        return "Manually compact this conversation while retaining durable facts and pending work.";
    }

    public record MessagePage(List<AgentMessage> events, boolean hasMore, Long nextBeforeMessageId) {
    }

    public record ManualCompactionResult(String summary, String strategy, boolean deterministicFallback) {
    }

    public AgentConversation forkConversation(Integer studentId, Integer projectId, String conversationId, Long messageId) {
        AgentConversation source = this.getOwnedConversation(studentId, projectId, conversationId);
        if (source == null) {
            throw new IllegalArgumentException("Conversation not found");
        }

        AgentConversation child = new AgentConversation();
        child.setConversationId(UUID.randomUUID().toString());
        child.setStudentId(studentId);
        child.setProjectId(projectId);
        child.setTitle(this.buildForkTitle(source.getTitle()));
        child.setMode(source.getMode());
        child.setProvider(source.getProvider());
        child.setModel(source.getModel());
        child.setSummary(source.getSummary() == null ? "" : source.getSummary());
        child.setParentConversationId(source.getConversationId());
        child.setForkedFromMessageId(messageId);
        child.setStatus(1);
        child.setCreateTime(LocalDateTime.now());
        child.setUpdateTime(LocalDateTime.now());
        this.conversationMapper.insert(child);

        LambdaQueryWrapper<AgentMessage> query = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getStudentId, studentId)
                .eq(AgentMessage::getProjectId, projectId);
        if (messageId != null && messageId > 0) {
            query.le(AgentMessage::getMessageId, messageId);
        }
        query.orderByAsc(AgentMessage::getMessageId);

        List<AgentMessage> sourceMessages = this.messageMapper.selectList(query);
        for (AgentMessage sourceMessage : sourceMessages) {
            AgentMessage copy = new AgentMessage();
            copy.setConversationId(child.getConversationId());
            copy.setStudentId(studentId);
            copy.setProjectId(projectId);
            copy.setEventType(sourceMessage.getEventType());
            copy.setRole(sourceMessage.getRole());
            copy.setContent(sourceMessage.getContent());
            copy.setEventData(sourceMessage.getEventData());
            copy.setCreateTime(sourceMessage.getCreateTime() == null ? LocalDateTime.now() : sourceMessage.getCreateTime());
            this.messageMapper.insert(copy);
        }
        this.saveMessage(child, "SYSTEM", "event", "Conversation forked from " + source.getConversationId(),
                Map.of("sourceConversationId", source.getConversationId(), "forkedFromMessageId", messageId == null ? "" : messageId));
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
        String summary = conversation.getSummary() == null ? "" : conversation.getSummary();
        return new MemoryStats(summary.length(), count == null ? 0 : count.intValue(), this.isAutoCompacted(summary), 12000);
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

    private void updateSummary(AgentConversation conversation, String type, String content) {
        if (!MEMORY_IMPORTANT_TYPES.contains(type)) {
            return;
        }
        String item = "[" + type + "] " + this.limit(content, this.memoryItemLimit(type));
        String summary = conversation.getSummary() == null ? "" : conversation.getSummary();
        summary = this.limit(summary + "\n" + item, 9000);
        this.persistSummary(conversation, summary);
    }

    private void autoCompactIfNeeded(AgentConversation conversation) {
        String summary;
        String string = summary = conversation.getSummary() == null ? "" : conversation.getSummary();
        if (summary.length() < 16000 && !this.isNearSummaryLimit(summary)) {
            return;
        }
        List<AgentMessage> recent = this.messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getConversationId, conversation.getConversationId()).eq(AgentMessage::getStudentId, conversation.getStudentId()).eq(AgentMessage::getProjectId, conversation.getProjectId()).orderByDesc(AgentMessage::getMessageId).last("LIMIT 120"));
        String compacted = this.rebuildCompactedSummary(conversation, recent, "Auto compacted context");
        this.persistSummary(conversation, compacted);
    }

    private String rebuildCompactedSummary(AgentConversation conversation, List<AgentMessage> newestFirst, String title) {
        ArrayList<AgentMessage> chronological = newestFirst == null ? new ArrayList<AgentMessage>() : new ArrayList<AgentMessage>(newestFirst);
        Collections.reverse(chronological);
        List<AgentMessage> important = chronological.stream().filter(message -> !MEMORY_SKIP_TYPES.contains(message.getEventType())).filter(message -> MEMORY_IMPORTANT_TYPES.contains(message.getEventType())).toList();
        int tailStart = Math.max(0, important.size() - 18);
        StringBuilder compact = new StringBuilder(title).append(":\n");
        compact.append("- strategy: keep durable decisions, changed files, failures, final outcomes, and the latest tail events.\n");
        compact.append("- conversation: ").append(conversation.getConversationId()).append("\n\n");
        if (tailStart > 0) {
            compact.append("Earlier durable facts:\n");
            for (AgentMessage message2 : important.subList(0, tailStart)) {
                this.appendCompactLine(compact, message2, this.compactItemLimit(message2.getEventType()));
            }
            compact.append("\nRecent tail events kept verbatim-like:\n");
        } else {
            compact.append("Recent tail events:\n");
        }
        for (AgentMessage message2 : important.subList(tailStart, important.size())) {
            this.appendCompactLine(compact, message2, this.memoryItemLimit(message2.getEventType()));
        }
        return this.limit(compact.toString(), 9000);
    }

    private void appendCompactLine(StringBuilder builder, AgentMessage message, int maxChars) {
        builder.append('[').append(message.getEventType()).append("] ").append(this.limit(this.normalizeForMemory(message.getContent()), maxChars)).append('\n');
    }

    private void persistSummary(AgentConversation conversation, String summary) {
        conversation.setSummary(summary);
        this.conversationMapper.update(null, ((new LambdaUpdateWrapper<AgentConversation>().eq(AgentConversation::getConversationId, conversation.getConversationId())).set(AgentConversation::getSummary, summary)).set(AgentConversation::getUpdateTime, LocalDateTime.now()));
    }

    private boolean isNearSummaryLimit(String summary) {
        return summary != null && (double)summary.length() > 7650.0;
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

    private int compactItemLimit(String type) {
        return switch (type == null ? "" : type) {
            case "USER" -> 220;
            case "FINAL" -> 340;
            case "OBSERVE" -> 260;
            case "ERROR", "INTERRUPTED", "COMMAND_APPROVAL_REQUIRED" -> 420;
            default -> 180;
        };
    }

    private static String redactSecrets(String text) {
        String safe = text == null ? "" : text;
        safe = API_KEY.matcher(safe).replaceAll("[REDACTED]");
        safe = BEARER.matcher(safe).replaceAll("Bearer [REDACTED]");
        return NAMED_SECRET.matcher(safe).replaceAll("$1=[REDACTED]");
    }

    private String normalizeForMemory(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)```.*?```", "[code/output block omitted]").replaceAll("\\s+", " ").trim();
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
