package com.labex.labexagent.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 从 Conversation 范围的 durable Message/Part 图生成 Provider 协议消息。 */
@Service
public class AgentConversationMessageGraphProjector {
    private static final Gson GSON = new Gson();
    private static final Set<String> TOOL_PART_TYPES = Set.of("tool", "tool_call");
    private static final Set<String> TERMINAL_TOOL_STATUSES = Set.of(
            "completed", "failed", "skipped", "interrupted");

    private final AgentRunMessageMapper messageMapper;
    private final AgentRunPartMapper partMapper;
    private final AgentProviderMessageProjector providerProjector;

    public AgentConversationMessageGraphProjector(AgentRunMessageMapper messageMapper,
                                                  AgentRunPartMapper partMapper,
                                                  AgentProviderMessageProjector providerProjector) {
        this.messageMapper = Objects.requireNonNull(messageMapper, "Run message mapper is required");
        this.partMapper = Objects.requireNonNull(partMapper, "Run part mapper is required");
        this.providerProjector = Objects.requireNonNull(providerProjector, "Provider projector is required");
    }

    /**
     * 读取未压缩的 native Conversation 图。
     *
     * <p>图内 compaction filter 会在后续切片接入；当前实现故意不调用 Task summary、memory prefix
     * 或 task-local transcript reader。</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> projectForProvider(Request request) {
        validateRequest(request);
        List<AgentRunMessage> messages = loadMessages(request);
        List<AgentRunPart> parts = loadParts(request);
        validateMessages(messages, request);
        Map<Long, List<AgentRunPart>> partsByMessage = groupParts(parts, request, messages);

        List<Map<String, Object>> providerMessages = new ArrayList<>();
        for (AgentRunMessage message : messages) {
            String role = normalized(message.getRole());
            if ("user".equals(role)) {
                providerMessages.add(simpleMessage("user", message.getContent()));
                continue;
            }
            if (!"assistant".equals(role)) {
                throw new IllegalStateException("Native conversation graph contains unsupported message role: " + role);
            }
            appendAssistantProjection(providerMessages, message, partsByMessage.getOrDefault(
                    message.getRunMessageId(), List.of()));
        }
        return providerProjector.project(providerMessages);
    }

    private List<AgentRunMessage> loadMessages(Request request) {
        List<AgentRunMessage> loaded = messageMapper.selectList(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getStudentId, request.studentId())
                .eq(AgentRunMessage::getProjectId, request.projectId())
                .eq(AgentRunMessage::getConversationId, request.conversationId())
                .isNotNull(AgentRunMessage::getConversationSequence)
                .orderByAsc(AgentRunMessage::getConversationSequence)
                .orderByAsc(AgentRunMessage::getRunMessageId));
        List<AgentRunMessage> sorted = new ArrayList<>(loaded == null ? List.of() : loaded);
        sorted.sort(Comparator.comparing(AgentRunMessage::getConversationSequence,
                Comparator.nullsLast(Long::compareTo)).thenComparing(AgentRunMessage::getRunMessageId,
                Comparator.nullsLast(Long::compareTo)));
        return List.copyOf(sorted);
    }

    private List<AgentRunPart> loadParts(Request request) {
        List<AgentRunPart> loaded = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getStudentId, request.studentId())
                .eq(AgentRunPart::getProjectId, request.projectId())
                .eq(AgentRunPart::getConversationId, request.conversationId())
                .orderByAsc(AgentRunPart::getMessageId)
                .orderByAsc(AgentRunPart::getSequenceNumber)
                .orderByAsc(AgentRunPart::getPartId));
        List<AgentRunPart> sorted = new ArrayList<>(loaded == null ? List.of() : loaded);
        sorted.sort(Comparator.comparing(AgentRunPart::getMessageId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(AgentRunPart::getSequenceNumber, Comparator.nullsLast(Long::compareTo))
                .thenComparing(AgentRunPart::getPartId, Comparator.nullsLast(Long::compareTo)));
        return List.copyOf(sorted);
    }

    private void validateMessages(List<AgentRunMessage> messages, Request request) {
        Set<Long> sequences = new LinkedHashSet<>();
        Map<Long, AgentRunMessage> byId = new LinkedHashMap<>();
        for (AgentRunMessage message : messages) {
            requireMessageOwnership(message, request);
            if (message.getRunMessageId() == null || message.getRunMessageId() <= 0
                    || message.getConversationSequence() == null || message.getConversationSequence() <= 0) {
                throw new IllegalStateException("Native conversation graph message requires durable id and sequence");
            }
            if (!sequences.add(message.getConversationSequence())) {
                throw new IllegalStateException("Native conversation graph contains duplicate message sequence: "
                        + message.getConversationSequence());
            }
            byId.put(message.getRunMessageId(), message);
        }
        for (AgentRunMessage message : messages) {
            String role = normalized(message.getRole());
            if ("assistant".equals(role)) {
                AgentRunMessage parent = byId.get(message.getParentMessageId());
                if (parent == null || !"user".equals(normalized(parent.getRole()))) {
                    throw new IllegalStateException("Assistant message requires a durable user parent: messageId="
                            + message.getRunMessageId());
                }
            } else if (!"user".equals(role)) {
                throw new IllegalStateException("Native conversation graph contains unsupported message role: " + role);
            }
        }
    }

    private Map<Long, List<AgentRunPart>> groupParts(List<AgentRunPart> parts, Request request,
                                                       List<AgentRunMessage> messages) {
        Map<Long, AgentRunMessage> messagesById = new LinkedHashMap<>();
        for (AgentRunMessage message : messages) {
            messagesById.put(message.getRunMessageId(), message);
        }
        Map<Long, List<AgentRunPart>> grouped = new LinkedHashMap<>();
        for (AgentRunPart part : parts) {
            requirePartOwnership(part, request);
            AgentRunMessage parent = messagesById.get(part.getMessageId());
            if (parent == null || !"assistant".equals(normalized(parent.getRole()))) {
                throw new IllegalStateException("Native graph part does not belong to an assistant message: partId="
                        + part.getPartId());
            }
            grouped.computeIfAbsent(part.getMessageId(), ignored -> new ArrayList<>()).add(part);
        }
        return grouped;
    }

    private void appendAssistantProjection(List<Map<String, Object>> providerMessages, AgentRunMessage message,
                                           List<AgentRunPart> allParts) {
        List<AgentRunPart> toolParts = allParts.stream()
                .filter(this::isToolPart)
                .toList();
        LinkedHashMap<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", safeContent(message.getContent()));
        if (!toolParts.isEmpty()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (AgentRunPart part : toolParts) {
                toolCalls.add(toToolCall(part));
            }
            assistant.put("tool_calls", List.copyOf(toolCalls));
        }
        providerMessages.add(assistant);

        for (AgentRunPart part : toolParts) {
            String status = normalized(part.getStatus());
            if (!TERMINAL_TOOL_STATUSES.contains(status)) {
                throw new IllegalStateException("unfinished tool part cannot be projected for normal invocation: "
                        + part.getToolCallId());
            }
            providerMessages.add(toolResult(part));
        }
    }

    private Map<String, Object> toToolCall(AgentRunPart part) {
        String callId = required(part.getToolCallId(), "Tool part requires tool_call_id");
        ToolFunction function = toolFunction(part);
        LinkedHashMap<String, Object> call = new LinkedHashMap<>();
        call.put("id", callId);
        call.put("type", "function");
        call.put("function", Map.of("name", function.name(), "arguments", function.arguments()));
        return call;
    }

    private Map<String, Object> toolResult(AgentRunPart part) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", "tool");
        result.put("tool_call_id", required(part.getToolCallId(), "Tool part requires tool_call_id"));
        result.put("name", toolFunction(part).name());
        result.put("content", safeContent(part.getOutputText()));
        return result;
    }

    private ToolFunction toolFunction(AgentRunPart part) {
        String name = trimmed(part.getToolName());
        String arguments = trimmed(part.getInputJson());
        if (!arguments.isBlank()) {
            try {
                JsonElement root = JsonParser.parseString(arguments);
                if (root.isJsonObject()) {
                    JsonObject object = root.getAsJsonObject();
                    JsonObject function = object.has("function") && object.get("function").isJsonObject()
                            ? object.getAsJsonObject("function") : null;
                    if (function != null) {
                        if (name.isBlank() && function.has("name")) {
                            name = trimmed(function.get("name").getAsString());
                        }
                        if (function.has("arguments")) {
                            JsonElement rawArguments = function.get("arguments");
                            arguments = rawArguments.isJsonPrimitive() && rawArguments.getAsJsonPrimitive().isString()
                                    ? rawArguments.getAsString() : GSON.toJson(rawArguments);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // 工具参数保持原始文本，让 Provider/工具协议校验给出可审计错误。
            }
        }
        return new ToolFunction(required(name, "Tool part requires a tool name"),
                required(arguments, "Tool part requires input_json arguments"));
    }

    private boolean isToolPart(AgentRunPart part) {
        return part != null && TOOL_PART_TYPES.contains(normalized(part.getPartType()));
    }

    private void requireMessageOwnership(AgentRunMessage message, Request request) {
        if (message == null || !Objects.equals(message.getStudentId(), request.studentId())
                || !Objects.equals(message.getProjectId(), request.projectId())
                || !Objects.equals(message.getConversationId(), request.conversationId())) {
            throw new IllegalStateException("Durable graph message does not match conversation ownership");
        }
    }

    private void requirePartOwnership(AgentRunPart part, Request request) {
        if (part == null || !Objects.equals(part.getStudentId(), request.studentId())
                || !Objects.equals(part.getProjectId(), request.projectId())
                || !Objects.equals(part.getConversationId(), request.conversationId())) {
            throw new IllegalStateException("Durable graph part does not match conversation ownership");
        }
    }

    private void validateRequest(Request request) {
        if (request == null || request.studentId() == null || request.studentId() <= 0
                || request.projectId() == null || request.projectId() <= 0
                || request.conversationId() == null || request.conversationId().isBlank()) {
            throw new IllegalArgumentException("Conversation graph projection requires owned conversation identity");
        }
    }

    private Map<String, Object> simpleMessage(String role, String content) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("content", safeContent(content));
        return result;
    }

    private String required(String value, String message) {
        String safe = trimmed(value);
        if (safe.isBlank()) {
            throw new IllegalStateException(message);
        }
        return safe;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeContent(String value) {
        return value == null ? "" : value;
    }

    public record Request(Integer studentId, Integer projectId, String conversationId) {
    }

    private record ToolFunction(String name, String arguments) {
    }
}

