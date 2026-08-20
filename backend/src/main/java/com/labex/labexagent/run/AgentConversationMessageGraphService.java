package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation 范围的 durable Message 图写入边界。
 *
 * <p>Task 仍然拥有执行生命周期；Message 的顺序、parent 和上下文归属则由 Conversation 持有。
 * 所有新图节点都在持有 Conversation 行锁的事务中分配 sequence，避免跨 Task 的写入交错。</p>
 */
@Service
public class AgentConversationMessageGraphService {
    private static final String USER_ROLE = "user";
    private static final String ASSISTANT_ROLE = "assistant";
    private static final String USER_COMPLETED_STATUS = "completed";
    private static final String ASSISTANT_STREAMING_STATUS = "streaming";
    private static final String ASSISTANT_COMPLETED_STATUS = "completed";
    private static final String TOOL_PART_TYPE = "tool";
    private static final Set<String> TERMINAL_TOOL_PART_STATUSES =
            Set.of("completed", "failed", "skipped", "interrupted");

    private final AgentConversationMapper conversationMapper;
    private final AgentTaskMapper taskMapper;
    private final AgentRunMessageMapper messageMapper;
    private final AgentRunPartMapper partMapper;

    public AgentConversationMessageGraphService(AgentConversationMapper conversationMapper,
                                                AgentTaskMapper taskMapper,
                                                AgentRunMessageMapper messageMapper,
                                                AgentRunPartMapper partMapper) {
        this.conversationMapper = Objects.requireNonNull(conversationMapper, "Conversation mapper is required");
        this.taskMapper = Objects.requireNonNull(taskMapper, "Task mapper is required");
        this.messageMapper = Objects.requireNonNull(messageMapper, "Run message mapper is required");
        this.partMapper = Objects.requireNonNull(partMapper, "Run part mapper is required");
    }

    /** 追加真实用户请求，并把当前 Task 锚定到该 Message。 */
    @Transactional(rollbackFor = Exception.class)
    public MessageRef appendUserRequest(UserRequest request) {
        validateRequest(request.taskId(), request.studentId(), request.projectId(), request.conversationId(),
                request.messageKey());
        AgentTask task = requireOwnedTask(request.taskId(), request.studentId(), request.projectId(),
                request.conversationId());
        AgentConversation conversation = requireOwnedConversation(request.studentId(), request.projectId(),
                request.conversationId());
        AgentRunMessage existing = findByTaskAndKey(request.taskId(), request.messageKey());
        if (existing != null) {
            requireMessageContext(existing, request.taskId(), request.studentId(), request.projectId(),
                    request.conversationId(), USER_ROLE);
            return MessageRef.from(existing);
        }

        long conversationSequence = advanceConversationSequence(conversation);
        AgentRunMessage message = newMessage(request.taskId(), request.studentId(), request.projectId(),
                request.conversationId(), request.messageKey(), USER_ROLE, USER_COMPLETED_STATUS,
                null, conversationSequence, request.content(), request.metadata());
        insert(message);
        updateConversation(conversation);

        Long originMessageId = task.getOriginMessageId();
        if (originMessageId != null && !originMessageId.equals(message.getRunMessageId())) {
            throw new IllegalStateException("Task already belongs to another origin message: taskId="
                    + request.taskId());
        }
        task.setOriginMessageId(message.getRunMessageId());
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException("Failed to anchor task to durable user message: taskId=" + request.taskId());
        }
        return MessageRef.from(message);
    }

    /** 在指定真实用户节点下创建一条 streaming assistant Message。 */
    @Transactional(rollbackFor = Exception.class)
    public MessageRef beginAssistantMessage(AssistantRequest request) {
        validateRequest(request.taskId(), request.studentId(), request.projectId(), request.conversationId(),
                request.messageKey());
        if (request.parentMessageId() == null || request.parentMessageId() <= 0) {
            throw new IllegalArgumentException("Assistant message requires a positive user parent message id");
        }
        requireOwnedTask(request.taskId(), request.studentId(), request.projectId(), request.conversationId());
        AgentConversation conversation = requireOwnedConversation(request.studentId(), request.projectId(),
                request.conversationId());
        AgentRunMessage existing = findByTaskAndKey(request.taskId(), request.messageKey());
        if (existing != null) {
            requireMessageContext(existing, request.taskId(), request.studentId(), request.projectId(),
                    request.conversationId(), ASSISTANT_ROLE);
            if (!request.parentMessageId().equals(existing.getParentMessageId())) {
                throw new IllegalStateException("Assistant retry parent differs from durable graph node: messageKey="
                        + request.messageKey());
            }
            return MessageRef.from(existing);
        }

        AgentRunMessage parent = messageMapper.selectById(request.parentMessageId());
        if (parent == null) {
            throw new IllegalStateException("Assistant parent message is unavailable: messageId="
                    + request.parentMessageId());
        }
        requireMessageContext(parent, null, request.studentId(), request.projectId(), request.conversationId(), USER_ROLE);

        long conversationSequence = advanceConversationSequence(conversation);
        AgentRunMessage message = newMessage(request.taskId(), request.studentId(), request.projectId(),
                request.conversationId(), request.messageKey(), ASSISTANT_ROLE, ASSISTANT_STREAMING_STATUS,
                request.parentMessageId(), conversationSequence, null, request.metadata());
        insert(message);
        updateConversation(conversation);
        return MessageRef.from(message);
    }

    /**
     * 在同一条 assistant Message 下写入或推进 Tool Part。
     *
     * <p>Provider 所需的 {@code role=tool} 只在投影时生成；数据库中不会创建独立 tool Message。
     * Part 的 call identity 不可改变，终态重试只能重放相同状态与结果，避免恢复流程覆盖真实失败事实。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public PartRef upsertToolPart(ToolPartRequest request) {
        validateToolPartRequest(request);
        requireOwnedTask(request.taskId(), request.studentId(), request.projectId(), request.conversationId());
        requireOwnedConversation(request.studentId(), request.projectId(), request.conversationId());
        AgentRunMessage assistant = messageMapper.selectById(request.messageId());
        if (assistant == null) {
            throw new IllegalStateException("Tool part assistant message is unavailable: messageId=" + request.messageId());
        }
        requireMessageContext(assistant, request.taskId(), request.studentId(), request.projectId(),
                request.conversationId(), ASSISTANT_ROLE);

        AgentRunPart existing = findPartByTaskAndKey(request.taskId(), request.partKey());
        if (existing == null) {
            AgentRunPart part = newToolPart(request);
            insert(part);
            return PartRef.from(part);
        }

        requirePartContext(existing, request);
        if (isTerminalToolPart(existing.getStatus())) {
            if (!sameToolPartResolution(existing, request)) {
                throw new IllegalStateException("Terminal tool part retry conflicts with durable result: partKey="
                        + request.partKey());
            }
            return PartRef.from(existing);
        }
        existing.setStatus(normalizeStatus(request.status()));
        existing.setOutputText(request.outputText());
        existing.setMetadata(normalizeMetadata(request.metadata()));
        existing.setUpdateTime(LocalDateTime.now());
        if (partMapper.updateById(existing) != 1) {
            throw new IllegalStateException("Failed to update durable conversation tool part: partKey="
                    + request.partKey());
        }
        return PartRef.from(existing);
    }

    /** 完成当前 assistant Message；不会改变其 parent 或 conversation sequence。 */
    @Transactional(rollbackFor = Exception.class)
    public MessageRef completeAssistantMessage(AssistantCompletion request) {
        validateAssistantCompletion(request);
        requireOwnedTask(request.taskId(), request.studentId(), request.projectId(), request.conversationId());
        requireOwnedConversation(request.studentId(), request.projectId(), request.conversationId());
        AgentRunMessage assistant = messageMapper.selectById(request.messageId());
        if (assistant == null) {
            throw new IllegalStateException("Assistant message is unavailable for completion: messageId="
                    + request.messageId());
        }
        requireMessageContext(assistant, request.taskId(), request.studentId(), request.projectId(),
                request.conversationId(), ASSISTANT_ROLE);
        assistant.setContent(request.content() == null ? "" : request.content());
        assistant.setStatus(ASSISTANT_COMPLETED_STATUS);
        assistant.setMetadata(normalizeMetadata(request.metadata()));
        assistant.setUpdateTime(LocalDateTime.now());
        if (messageMapper.updateById(assistant) != 1) {
            throw new IllegalStateException("Failed to complete durable conversation assistant message: messageId="
                    + request.messageId());
        }
        return MessageRef.from(assistant);
    }

    private AgentTask requireOwnedTask(Long taskId, Integer studentId, Integer projectId, String conversationId) {
        AgentTask task = taskMapper.selectByTaskIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalStateException("Task is unavailable for conversation graph write: taskId=" + taskId);
        }
        if (!Objects.equals(task.getStudentId(), studentId)
                || !Objects.equals(task.getProjectId(), projectId)
                || !Objects.equals(task.getConversationId(), conversationId)) {
            throw new IllegalStateException("Task does not belong to the requested conversation graph: taskId=" + taskId);
        }
        return task;
    }

    private AgentConversation requireOwnedConversation(Integer studentId, Integer projectId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectOwnedForUpdate(studentId, projectId, conversationId);
        if (conversation == null) {
            throw new IllegalStateException("Conversation is unavailable for graph write: conversationId=" + conversationId);
        }
        return conversation;
    }

    private AgentRunMessage findByTaskAndKey(Long taskId, String messageKey) {
        return messageMapper.selectOne(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .eq(AgentRunMessage::getMessageKey, messageKey));
    }

    private AgentRunPart findPartByTaskAndKey(Long taskId, String partKey) {
        return partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getPartKey, partKey));
    }

    private long advanceConversationSequence(AgentConversation conversation) {
        long current = conversation.getNextMessageSequence() == null ? 0L : conversation.getNextMessageSequence();
        if (current < 0 || current == Long.MAX_VALUE) {
            throw new IllegalStateException("Conversation message sequence is invalid: conversationId="
                    + conversation.getConversationId());
        }
        long next = current + 1;
        conversation.setNextMessageSequence(next);
        return next;
    }

    private AgentRunMessage newMessage(Long taskId, Integer studentId, Integer projectId, String conversationId,
                                       String messageKey, String role, String status, Long parentMessageId,
                                       long conversationSequence, String content, String metadata) {
        AgentRunMessage message = new AgentRunMessage();
        message.setTaskId(taskId);
        message.setConversationId(conversationId);
        message.setStudentId(studentId);
        message.setProjectId(projectId);
        message.setMessageKey(messageKey);
        message.setParentMessageId(parentMessageId);
        message.setConversationSequence(conversationSequence);
        message.setRole(role);
        message.setStatus(status);
        message.setContent(content);
        message.setMetadata(normalizeMetadata(metadata));
        return message;
    }

    private AgentRunPart newToolPart(ToolPartRequest request) {
        LocalDateTime now = LocalDateTime.now();
        AgentRunPart part = new AgentRunPart();
        part.setTaskId(request.taskId());
        part.setConversationId(request.conversationId());
        part.setMessageId(request.messageId());
        part.setStudentId(request.studentId());
        part.setProjectId(request.projectId());
        part.setPartKey(request.partKey());
        part.setSequenceNumber(request.sequenceNumber());
        part.setPartType(TOOL_PART_TYPE);
        part.setStatus(normalizeStatus(request.status()));
        part.setToolCallId(request.toolCallId());
        part.setToolName(normalizeToolName(request.toolName()));
        part.setInputJson(normalizeInputJson(request.inputJson()));
        part.setOutputText(request.outputText());
        part.setMetadata(normalizeMetadata(request.metadata()));
        part.setCreateTime(now);
        part.setUpdateTime(now);
        return part;
    }

    private void insert(AgentRunMessage message) {
        if (messageMapper.insert(message) != 1 || message.getRunMessageId() == null || message.getRunMessageId() <= 0) {
            throw new IllegalStateException("Failed to persist durable conversation message: messageKey="
                    + message.getMessageKey());
        }
    }

    private void insert(AgentRunPart part) {
        if (partMapper.insert(part) != 1 || part.getPartId() == null || part.getPartId() <= 0) {
            throw new IllegalStateException("Failed to persist durable conversation tool part: partKey="
                    + part.getPartKey());
        }
    }

    private void updateConversation(AgentConversation conversation) {
        if (conversationMapper.updateById(conversation) != 1) {
            throw new IllegalStateException("Failed to advance conversation message sequence: conversationId="
                    + conversation.getConversationId());
        }
    }

    private void requireMessageContext(AgentRunMessage message, Long expectedTaskId, Integer studentId,
                                       Integer projectId, String conversationId, String role) {
        if (!Objects.equals(message.getStudentId(), studentId)
                || !Objects.equals(message.getProjectId(), projectId)
                || !Objects.equals(message.getConversationId(), conversationId)
                || !role.equalsIgnoreCase(message.getRole())
                || (expectedTaskId != null && !Objects.equals(message.getTaskId(), expectedTaskId))) {
            throw new IllegalStateException("Durable graph message does not match the requested context");
        }
    }

    private void requirePartContext(AgentRunPart part, ToolPartRequest request) {
        boolean matching = Objects.equals(part.getTaskId(), request.taskId())
                && Objects.equals(part.getConversationId(), request.conversationId())
                && Objects.equals(part.getMessageId(), request.messageId())
                && Objects.equals(part.getStudentId(), request.studentId())
                && Objects.equals(part.getProjectId(), request.projectId())
                && TOOL_PART_TYPE.equalsIgnoreCase(part.getPartType())
                && Objects.equals(part.getSequenceNumber(), request.sequenceNumber())
                && Objects.equals(part.getToolCallId(), request.toolCallId())
                && Objects.equals(normalizeToolName(part.getToolName()), normalizeToolName(request.toolName()))
                && Objects.equals(normalizeInputJson(part.getInputJson()), normalizeInputJson(request.inputJson()));
        if (!matching) {
            throw new IllegalStateException("Durable graph tool part does not match the requested context: partKey="
                    + request.partKey());
        }
    }

    private boolean isTerminalToolPart(String status) {
        return TERMINAL_TOOL_PART_STATUSES.contains(normalizeStatus(status));
    }

    private boolean sameToolPartResolution(AgentRunPart existing, ToolPartRequest request) {
        return normalizeStatus(existing.getStatus()).equals(normalizeStatus(request.status()))
                && Objects.equals(existing.getOutputText(), request.outputText());
    }

    private void validateToolPartRequest(ToolPartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Conversation graph tool part request is required");
        }
        validateRequest(request.taskId(), request.studentId(), request.projectId(), request.conversationId(),
                request.partKey());
        if (request.messageId() == null || request.messageId() <= 0
                || request.sequenceNumber() == null || request.sequenceNumber() < 0
                || request.toolCallId() == null || request.toolCallId().isBlank()
                || normalizeToolName(request.toolName()).isBlank()
                || normalizeStatus(request.status()).isBlank()) {
            throw new IllegalArgumentException("Conversation graph tool part requires message, sequence, call, tool and status");
        }
    }

    private void validateAssistantCompletion(AssistantCompletion request) {
        if (request == null) {
            throw new IllegalArgumentException("Conversation graph assistant completion is required");
        }
        if (request.taskId() == null || request.taskId() <= 0
                || request.studentId() == null || request.studentId() <= 0
                || request.projectId() == null || request.projectId() <= 0
                || request.conversationId() == null || request.conversationId().isBlank()
                || request.messageId() == null || request.messageId() <= 0) {
            throw new IllegalArgumentException("Conversation graph assistant completion requires task, owner, conversation and message");
        }
    }

    private void validateRequest(Long taskId, Integer studentId, Integer projectId, String conversationId,
                                 String messageKey) {
        if (taskId == null || taskId <= 0 || studentId == null || studentId <= 0
                || projectId == null || projectId <= 0 || conversationId == null || conversationId.isBlank()
                || messageKey == null || messageKey.isBlank()) {
            throw new IllegalArgumentException("Conversation graph write requires task, owner, conversation and message key");
        }
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    private String normalizeInputJson(String inputJson) {
        return inputJson == null || inputJson.isBlank() ? "{}" : inputJson;
    }

    private String normalizeMetadata(String metadata) {
        return metadata == null || metadata.isBlank() ? "{}" : metadata;
    }

    public record UserRequest(Long taskId, Integer studentId, Integer projectId, String conversationId,
                              String messageKey, String content, String metadata) {
    }

    public record AssistantRequest(Long taskId, Integer studentId, Integer projectId, String conversationId,
                                   String messageKey, Long parentMessageId, String metadata) {
    }

    public record ToolPartRequest(Long taskId, Integer studentId, Integer projectId, String conversationId,
                                  Long messageId, String partKey, Long sequenceNumber, String status,
                                  String toolCallId, String toolName, String inputJson, String outputText,
                                  String metadata) {
    }

    public record AssistantCompletion(Long taskId, Integer studentId, Integer projectId, String conversationId,
                                      Long messageId, String content, String metadata) {
    }

    public record MessageRef(Long messageId, Long conversationSequence, String role, Long parentMessageId) {
        private static MessageRef from(AgentRunMessage message) {
            return new MessageRef(message.getRunMessageId(), message.getConversationSequence(),
                    message.getRole(), message.getParentMessageId());
        }
    }

    public record PartRef(Long partId, Long messageId, Long sequenceNumber, String status, String toolCallId) {
        private static PartRef from(AgentRunPart part) {
            return new PartRef(part.getPartId(), part.getMessageId(), part.getSequenceNumber(),
                    part.getStatus(), part.getToolCallId());
        }
    }
}
