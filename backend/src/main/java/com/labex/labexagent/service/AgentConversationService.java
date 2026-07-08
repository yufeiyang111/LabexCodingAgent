package com.labex.labexagent.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.rag.config.RagConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private static final Set<String> MEMORY_SKIP_TYPES = Set.of("SESSION", "THINK", "TOOL_CALL");
    private static final Set<String> MEMORY_IMPORTANT_TYPES = Set.of("USER", "FINAL", "OBSERVE", "ERROR", "INTERRUPTED", "COMMAND_APPROVAL_REQUIRED", "USER_QUESTION");
    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final RagConfig ragConfig;

    public AgentConversationService(AgentConversationMapper conversationMapper, AgentMessageMapper messageMapper, RagConfig ragConfig) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.ragConfig = ragConfig;
    }

    public AgentConversation ensureConversation(Integer studentId, StudentProject project, String conversationId, String mode, String firstMessage) {
        AgentConversation existing;
        if (conversationId != null && !conversationId.isBlank() && (existing = this.getOwnedConversation(studentId, project.getProjectId(), conversationId)) != null) {
            return existing;
        }
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setStudentId(studentId);
        conversation.setProjectId(project.getProjectId());
        conversation.setTitle(this.buildTitle(firstMessage));
        conversation.setMode(mode);
        conversation.setProvider(this.ragConfig.getLlmProvider());
        conversation.setModel("ollama".equalsIgnoreCase(this.ragConfig.getLlmProvider()) ? this.ragConfig.getOllamaModel() : this.ragConfig.getMiniMaxModel());
        conversation.setSummary("");
        conversation.setStatus(Integer.valueOf(1));
        conversation.setCreateTime(LocalDateTime.now());
        conversation.setUpdateTime(LocalDateTime.now());
        this.conversationMapper.insert(conversation);
        return conversation;
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

    public String buildMemoryContext(Integer studentId, Integer projectId, String conversationId) {
        String summary;
        if (conversationId == null || conversationId.isBlank()) {
            return "";
        }
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            return "";
        }
        List<AgentMessage> recent = this.messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getConversationId, conversationId).eq(AgentMessage::getStudentId, studentId).eq(AgentMessage::getProjectId, projectId).orderByDesc(AgentMessage::getMessageId).last("LIMIT 24"));
        StringBuilder builder = new StringBuilder();
        String string = summary = conversation.getSummary() == null ? "" : conversation.getSummary();
        if (!summary.isBlank()) {
            builder.append("\u5386\u53f2\u6458\u8981:\n").append(conversation.getSummary()).append("\n\n");
        }
        builder.append("\u6700\u8fd1\u5173\u952e\u4e8b\u4ef6:\n");
        for (int i = recent.size() - 1; i >= 0; --i) {
            AgentMessage message = (AgentMessage)recent.get(i);
            if (MEMORY_SKIP_TYPES.contains(message.getEventType())) continue;
            builder.append('[').append(message.getEventType()).append("] ").append(this.limit(message.getContent(), this.memoryItemLimit(message.getEventType()))).append('\n');
        }
        return this.limit(builder.toString(), 12000);
    }

    public String compactConversation(Integer studentId, Integer projectId, String conversationId) {
        AgentConversation conversation = this.getOwnedConversation(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        List<AgentMessage> recent = this.messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>().eq(AgentMessage::getConversationId, conversationId).eq(AgentMessage::getStudentId, studentId).eq(AgentMessage::getProjectId, projectId).orderByDesc(AgentMessage::getMessageId).last("LIMIT 80"));
        String summary = this.rebuildCompactedSummary(conversation, recent, "Manual compacted context");
        this.persistSummary(conversation, summary);
        conversation.setCompactedAt(LocalDateTime.now());
        this.conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getConversationId, conversation.getConversationId())
                .set(AgentConversation::getCompactedAt, conversation.getCompactedAt())
                .set(AgentConversation::getUpdateTime, LocalDateTime.now()));
        return summary;
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
        return summary != null && (summary.startsWith("Auto compacted context:") || summary.startsWith("Manual compacted context:"));
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
