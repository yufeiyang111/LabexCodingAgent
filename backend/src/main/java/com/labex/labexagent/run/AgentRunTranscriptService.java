package com.labex.labexagent.run;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent Provider transcript 的持久化边界。
 *
 * <p>该服务只使用 Run Message/Part 作为事实源：Message 保存 role/content，
 * assistant 的 tool_calls 和 tool 的执行结果分别保存为可重建 Part/Message。
 * Provider 请求可以从这里重新构造，不依赖某个 JVM 中仍然存活的 msgs 列表。</p>
 */
@Service
public class AgentRunTranscriptService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunTranscriptService.class);
    private static final Gson GSON = new Gson();
    private static final String PROVIDER_KEY_PREFIX = "provider:";
    private static final String DEFERRED_RESOLUTION_METADATA = "deferredResolution";

    private final AgentRunMessageMapper messageMapper;
    private final AgentRunPartMapper partMapper;
    private final AgentTaskMapper taskMapper;

    public AgentRunTranscriptService(AgentRunMessageMapper messageMapper,
                                     AgentRunPartMapper partMapper,
                                     AgentTaskMapper taskMapper) {
        this.messageMapper = messageMapper;
        this.partMapper = partMapper;
        this.taskMapper = taskMapper;
    }

    /** 以稳定序号追加一条 Provider message；重复恢复只更新同一个 key。 */
    @Transactional(rollbackFor = Exception.class)
    public void appendMessage(Long taskId, long executionEpoch, long sequence, Map<String, Object> providerMessage) {
        if (taskId == null || taskId <= 0 || providerMessage == null) {
            return;
        }
        String role = stringValue(providerMessage.get("role"));
        if (role.isBlank()) {
            throw new IllegalArgumentException("Provider transcript message role is required");
        }
        String key = providerKey(executionEpoch, sequence);
        AgentRunMessage message = upsertMessage(taskId, key, sequence, role,
                stringValue(providerMessage.get("content")), messageMetadata(executionEpoch, providerMessage));
        if ("assistant".equalsIgnoreCase(role)) {
            appendAssistantToolParts(taskId, message, executionEpoch, sequence, providerMessage);
        } else if ("tool".equalsIgnoreCase(role)) {
            appendToolResultPart(taskId, message, executionEpoch, sequence, providerMessage);
        }
    }

    /** 将当前持久化 transcript 按 Provider 协议重建。 */
    public List<Map<String, Object>> loadProjectableTranscript(Long taskId) {
        return loadProjectableTranscriptAfter(taskId, -1L);
    }

    /** 持久化运行时边界说明。 */
    public List<Map<String, Object>> loadProjectableTranscriptAfter(Long taskId, long sequenceExclusive) {
        return loadProjectableTranscriptAfter(taskId, sequenceExclusive, false);
    }

    /**
      * 持久化运行时边界说明。
      * 持久化运行时边界说明。
     */
    public List<Map<String, Object>> loadProjectableTranscriptForInteractionResume(Long taskId) {
        return loadProjectableTranscriptAfter(taskId, -1L, true);
    }

    /** 持久化运行时边界说明。 */
    public List<Map<String, Object>> loadProjectableTranscriptForInteractionResumeAfter(
            Long taskId, long sequenceExclusive) {
        return loadProjectableTranscriptAfter(taskId, sequenceExclusive, true);
    }

    private List<Map<String, Object>> loadProjectableTranscriptAfter(
            Long taskId, long sequenceExclusive, boolean preserveOpenBatch) {
        if (taskId == null || taskId <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<AgentRunMessage> query = new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .likeRight(AgentRunMessage::getMessageKey, PROVIDER_KEY_PREFIX);
        if (sequenceExclusive >= 0) {
            query.gt(AgentRunMessage::getSequenceNumber, sequenceExclusive);
        }
        List<AgentRunMessage> messages = messageMapper.selectList(query
                .orderByAsc(AgentRunMessage::getSequenceNumber)
                .orderByAsc(AgentRunMessage::getRunMessageId));
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentRunMessage message : messages) {
            long sequence = message.getSequenceNumber() == null ? -1L : message.getSequenceNumber();
            // 持久化运行时边界说明。
            if (sequence <= sequenceExclusive) {
                continue;
            }
            String role = stringValue(message.getRole());
            if ("assistant".equalsIgnoreCase(role)) {
                result.add(rebuildAssistant(message, taskId, preserveOpenBatch));
            } else if ("tool".equalsIgnoreCase(role)) {
                result.add(rebuildTool(message));
            } else {
                result.add(simpleMessage(role, message.getContent()));
            }
        }
        return preserveOpenBatch ? resumeProjection(result) : protocolSafeProjection(result);
    }

    /**
      * 持久化运行时边界说明。
      * 持久化运行时边界说明。
     */
    public Map<String, Object> resolvedInteractionToolResult(
            AgentRunInteraction interaction, List<Map<String, Object>> transcript) {
        if (interaction == null || transcript == null || transcript.isEmpty()) {
            return null;
        }
        String expectedToolCallId = expectedToolCallId(interaction);
        String expectedToolName = expectedToolName(interaction);
        UnresolvedToolCall selected = null;
        for (Map<String, Object> message : transcript) {
            if (!"assistant".equalsIgnoreCase(stringValue(message.get("role")))) {
                continue;
            }
            Object rawCalls = message.get("tool_calls");
            if (!(rawCalls instanceof List<?> calls)) {
                continue;
            }
            for (Object rawCall : calls) {
                if (!(rawCall instanceof Map<?, ?> call)) {
                    continue;
                }
                String id = stringValue(call.get("id"));
                String name = functionName(call);
                if (id.isBlank() || name.isBlank() || hasToolResult(transcript, id)) {
                    continue;
                }
                if (!expectedToolCallId.isBlank() && expectedToolCallId.equals(id)) {
                    selected = new UnresolvedToolCall(id, name);
                    break;
                }
                if (expectedToolCallId.isBlank() && (selected == null || name.equals(expectedToolName))) {
                    selected = new UnresolvedToolCall(id, name);
                }
                if (expectedToolCallId.isBlank() && !expectedToolName.isBlank() && name.equals(expectedToolName)) {
                    break;
                }
            }
            if (selected != null && ((!expectedToolCallId.isBlank() && selected.id().equals(expectedToolCallId))
                    || (expectedToolCallId.isBlank() && !expectedToolName.isBlank()
                    && selected.name().equals(expectedToolName)))) {
                break;
            }
        }
        if (selected == null) {
            if (!expectedToolCallId.isBlank()) {
                throw new IllegalStateException("Interaction tool call is missing from durable transcript: "
                        + expectedToolCallId);
            }
            return null;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", "tool");
        result.put("tool_call_id", selected.id());
        result.put("name", selected.name());
        result.put("content", interactionResultContent(interaction));
        return result;
    }

    /** 为交互暂停的整个 tool batch 生成协议完整、顺序稳定的 tool result? */
    public List<Map<String, Object>> resolvedInteractionToolResults(
            AgentRunInteraction interaction, List<Map<String, Object>> transcript) {
        if (interaction == null || transcript == null || transcript.isEmpty()) return List.of();
        List<UnresolvedToolCall> unresolved = new ArrayList<>();
        for (Map<String, Object> message : transcript) {
            if (!"assistant".equalsIgnoreCase(stringValue(message.get("role")))) continue;
            if (!(message.get("tool_calls") instanceof List<?> calls)) continue;
            for (Object rawCall : calls) {
                if (!(rawCall instanceof Map<?, ?> call)) continue;
                String id = stringValue(call.get("id"));
                String name = functionName(call);
                if (!id.isBlank() && !name.isBlank() && !hasToolResult(transcript, id)) {
                    unresolved.add(new UnresolvedToolCall(id, name));
                }
            }
        }
        if (unresolved.isEmpty()) return List.of();
        String expectedId = expectedToolCallId(interaction);
        String expectedName = expectedToolName(interaction);
        UnresolvedToolCall selected;
        if (!expectedId.isBlank()) {
            selected = unresolved.stream()
                    .filter(call -> expectedId.equals(call.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Interaction tool call is missing from durable transcript: " + expectedId));
        } else {
            selected = unresolved.stream()
                    .filter(call -> expectedName.isBlank() || expectedName.equals(call.name()))
                    .findFirst()
                    .orElse(unresolved.get(0));
        }
        Map<String, AgentRunPart> partsByCall = new LinkedHashMap<>();
        List<AgentRunPart> durableParts = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, interaction.getTaskId())
                .eq(AgentRunPart::getPartType, "tool_call"));
        if (durableParts != null) {
            for (AgentRunPart part : durableParts) {
                if (part.getToolCallId() != null) partsByCall.put(part.getToolCallId(), part);
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (UnresolvedToolCall call : unresolved) {
            String content;
            if (call.id().equals(selected.id())) {
                content = interactionResultContent(interaction);
            } else {
                AgentRunPart part = partsByCall.get(call.id());
                String status = part == null ? "" : stringValue(part.getStatus()).toLowerCase();
                if (!Set.of("skipped", "interrupted", "error", "completed").contains(status)) {
                    throw new IllegalStateException("Companion tool call is not terminal for interaction resume: " + call.id());
                }
                Map<String, Object> synthetic = new LinkedHashMap<>();
                synthetic.put("status", status);
                synthetic.put("reason", "not_executed_after_interaction_pause");
                synthetic.put("detail", part.getOutputText() == null ? "" : part.getOutputText());
                content = limit(GSON.toJson(synthetic), 8_000);
            }
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("role", "tool");
            result.put("tool_call_id", call.id());
            result.put("name", call.name());
            result.put("content", content);
            results.add(result);
        }
        return List.copyOf(results);
    }

    private List<Map<String, Object>> protocolSafeProjection(List<Map<String, Object>> messages) {
        List<Map<String, Object>> projected = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            String role = stringValue(message.get("role"));
            if (!"assistant".equalsIgnoreCase(role)
                    || !(message.get("tool_calls") instanceof List<?> calls)
                    || calls.isEmpty()) {
                if ("tool".equalsIgnoreCase(role)) {
                    throw new IllegalStateException("Orphan durable tool result at message index " + index);
                }
                projected.add(message);
                index++;
                continue;
            }

            Set<String> pendingToolCalls = new LinkedHashSet<>();
            for (Object rawCall : calls) {
                if (rawCall instanceof Map<?, ?> call) {
                    String id = stringValue(call.get("id"));
                    if (!id.isBlank()) pendingToolCalls.add(id);
                }
            }
            List<Map<String, Object>> batch = new ArrayList<>();
            batch.add(message);
            int cursor = index + 1;
            while (cursor < messages.size() && !pendingToolCalls.isEmpty()) {
                Map<String, Object> candidate = messages.get(cursor);
                if (!"tool".equalsIgnoreCase(stringValue(candidate.get("role")))) {
                    break;
                }
                pendingToolCalls.remove(stringValue(candidate.get("tool_call_id")));
                batch.add(candidate);
                cursor++;
            }
            if (pendingToolCalls.isEmpty()) {
                projected.addAll(batch);
            }
            // 未完成批次整体跳过；cursor 指向的后续 user/assistant turn 仍继续参与恢复投影。
            index = cursor;
        }
        return List.copyOf(projected);
    }

    /** 当前 transcript 的下一个追加序号，用于恢复进程后的幂等续写。 */
    private List<Map<String, Object>> resumeProjection(List<Map<String, Object>> messages) {
        List<Map<String, Object>> projected = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            Map<String, Object> message = messages.get(index);
            String role = stringValue(message.get("role"));
            if (!"assistant".equalsIgnoreCase(role)
                    || !(message.get("tool_calls") instanceof List<?> calls)
                    || calls.isEmpty()) {
                if ("tool".equalsIgnoreCase(role)) {
                    throw new IllegalStateException("Orphan durable tool result at message index " + index);
                }
                projected.add(message);
                index++;
                continue;
            }
            Set<String> pendingToolCalls = new LinkedHashSet<>();
            for (Object rawCall : calls) {
                if (rawCall instanceof Map<?, ?> call) {
                    String id = stringValue(call.get("id"));
                    if (!id.isBlank()) pendingToolCalls.add(id);
                }
            }
            projected.add(message);
            int cursor = index + 1;
            while (cursor < messages.size() && !pendingToolCalls.isEmpty()) {
                Map<String, Object> candidate = messages.get(cursor);
                if (!"tool".equalsIgnoreCase(stringValue(candidate.get("role")))) {
                    break;
                }
                pendingToolCalls.remove(stringValue(candidate.get("tool_call_id")));
                projected.add(candidate);
                cursor++;
            }
            if (!pendingToolCalls.isEmpty()) {
                // 持久化运行时边界说明。
                return List.copyOf(projected);
            }
            index = cursor;
        }
        return List.copyOf(projected);
    }

    private boolean isOpenPartStatus(String status) {
        return "waiting_user".equalsIgnoreCase(status)
                || "waiting_approval".equalsIgnoreCase(status)
                || "running".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "skipped".equalsIgnoreCase(status)
                || "interrupted".equalsIgnoreCase(status);
    }

    private boolean hasToolResult(List<Map<String, Object>> transcript, String toolCallId) {
        return transcript.stream().anyMatch(message ->
                "tool".equalsIgnoreCase(stringValue(message.get("role")))
                        && toolCallId.equals(stringValue(message.get("tool_call_id"))));
    }

    private String expectedToolCallId(AgentRunInteraction interaction) {
        Map<String, Object> payload = parseObject(interaction.getRequestPayload());
        return stringValue(payload.get("toolCallId"));
    }

    private String expectedToolName(AgentRunInteraction interaction) {
        if ("question".equalsIgnoreCase(interaction.getInteractionType())) {
            return "question";
        }
        Map<String, Object> payload = parseObject(interaction.getRequestPayload());
        return stringValue(payload.get("toolName"));
    }

    private String functionName(Map<?, ?> call) {
        Object function = call.get("function");
        if (function instanceof Map<?, ?> functionMap) {
            return stringValue(functionMap.get("name"));
        }
        return stringValue(call.get("name"));
    }

    private String interactionResultContent(AgentRunInteraction interaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interactionType", stringValue(interaction.getInteractionType()));
        result.put("status", stringValue(interaction.getStatus()));
        result.put("request", parseObject(interaction.getRequestPayload()));
        result.put("response", parseObject(interaction.getResponsePayload()));
        return limit(GSON.toJson(result), 8_000);
    }

    private record UnresolvedToolCall(String id, String name) {
    }

    public long nextSequence(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return 0L;
        }
        List<AgentRunMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .likeRight(AgentRunMessage::getMessageKey, PROVIDER_KEY_PREFIX)
                .orderByDesc(AgentRunMessage::getSequenceNumber)
                .last("LIMIT 1"));
        if (messages == null || messages.isEmpty() || messages.get(0).getSequenceNumber() == null) {
            return 0L;
        }
        return messages.get(0).getSequenceNumber() + 1L;
    }

    /** 将 Agent 主循环之外完成的工具结果追加回唯一的 Provider transcript。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean appendDeferredToolResult(Long taskId, String toolCallId, String toolName, String content) {
        if (taskId == null || taskId <= 0 || toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("taskId and toolCallId are required for a deferred tool result");
        }
        AgentRunPart existingResult = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool_result")
                .last("LIMIT 1"));
        if (existingResult != null && isDeferredResolution(existingResult)) {
            log.debug("DEFERRED_TOOL_RESULT_PERSIST_SKIPPED taskId={} toolCallId={} partId={} reason=already_resolved",
                    taskId, toolCallId, existingResult.getPartId());
            return false;
        }
        AgentRunPart toolCall = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool_call")
                .last("LIMIT 1"));
        if (toolCall == null) {
            throw new IllegalStateException("Deferred tool result has no matching tool call: " + toolCallId);
        }
        AgentTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalStateException("Deferred tool result has no owning task: " + taskId);
        }
        String resolvedToolName = toolName == null || toolName.isBlank() ? toolCall.getToolName() : toolName;
        if (resolvedToolName == null || resolvedToolName.isBlank()) {
            throw new IllegalStateException("Deferred tool result has no tool name: " + toolCallId);
        }
        String resolvedContent = content == null ? "" : content;
        if (existingResult != null) {
            replaceDeferredToolResult(existingResult, resolvedToolName, resolvedContent);
            completeMatchingToolCallPart(taskId, toolCallId, resolvedContent);
            log.debug("DEFERRED_TOOL_RESULT_PLACEHOLDER_REPLACED taskId={} toolCallId={} partId={}",
                    taskId, toolCallId, existingResult.getPartId());
            return true;
        }
        long epoch = task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
        LinkedHashMap<String, Object> providerResult = new LinkedHashMap<>();
        providerResult.put("role", "tool");
        providerResult.put("tool_call_id", toolCallId);
        providerResult.put("name", resolvedToolName);
        providerResult.put("content", resolvedContent);
        providerResult.put(DEFERRED_RESOLUTION_METADATA, true);
        appendMessage(taskId, epoch, nextSequence(taskId), providerResult);
        log.debug("DEFERRED_TOOL_RESULT_APPENDED taskId={} toolCallId={}", taskId, toolCallId);
        return true;
    }

    /** 只有已覆盖等待占位内容的最终 tool result 才能触发 Provider 续跑。 */
    @Transactional(readOnly = true)
    public boolean hasPersistedToolResult(Long taskId, String toolCallId) {
        if (taskId == null || taskId <= 0 || toolCallId == null || toolCallId.isBlank()) {
            return false;
        }
        AgentRunPart result = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool_result")
                .last("LIMIT 1"));
        boolean ready = isDeferredResolution(result);
        log.debug("DEFERRED_TOOL_RESULT_GATE taskId={} toolCallId={} partId={} partStatus={} ready={}",
                taskId, toolCallId, result == null ? null : result.getPartId(),
                result == null ? null : result.getStatus(), ready);
        return ready;
    }

    private void replaceDeferredToolResult(AgentRunPart result, String toolName, String content) {
        if (result.getMessageId() == null) {
            throw new IllegalStateException("Deferred tool result placeholder has no owning message");
        }
        AgentRunMessage message = messageMapper.selectById(result.getMessageId());
        if (message == null) {
            throw new IllegalStateException("Deferred tool result placeholder message is missing");
        }
        message.setStatus("completed");
        message.setContent(content);
        message.setUpdateTime(LocalDateTime.now());
        messageMapper.updateById(message);

        LinkedHashMap<String, Object> providerResult = new LinkedHashMap<>();
        providerResult.put("role", "tool");
        providerResult.put("tool_call_id", result.getToolCallId());
        providerResult.put("name", toolName);
        providerResult.put("content", content);
        result.setStatus("completed");
        result.setToolName(toolName);
        result.setInputJson(GSON.toJson(providerResult));
        result.setOutputText(limit(content, 8_000));
        markDeferredResolution(result);
        result.setUpdateTime(LocalDateTime.now());
        partMapper.updateById(result);
    }

    private boolean isDeferredResolution(AgentRunPart result) {
        if (result == null || !"tool_result".equals(result.getPartType())) return false;
        Object marker = parseObject(result.getMetadata()).get(DEFERRED_RESOLUTION_METADATA);
        return Boolean.TRUE.equals(marker) || "true".equalsIgnoreCase(String.valueOf(marker));
    }

    private void markDeferredResolution(AgentRunPart result) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(parseObject(result.getMetadata()));
        metadata.put("provider", true);
        metadata.put("partType", "tool_result");
        metadata.put(DEFERRED_RESOLUTION_METADATA, true);
        result.setMetadata(GSON.toJson(metadata));
    }

    public boolean hasProjectableTranscript(Long taskId) {
        try {
            return !loadProjectableTranscript(taskId).isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private AgentRunMessage upsertMessage(Long taskId, String key, long sequence, String role,
                                          String content, Map<String, Object> metadata) {
        AgentRunMessage message = messageMapper.selectOne(new LambdaQueryWrapper<AgentRunMessage>()
                .eq(AgentRunMessage::getTaskId, taskId)
                .eq(AgentRunMessage::getMessageKey, key)
                .last("LIMIT 1"));
        AgentTask task = taskMapper.selectById(taskId);
        if (message == null) {
            message = new AgentRunMessage();
            message.setTaskId(taskId);
            message.setMessageKey(key);
            message.setCreateTime(LocalDateTime.now());
            if (task != null) {
                message.setConversationId(task.getConversationId());
                message.setStudentId(task.getStudentId());
                message.setProjectId(task.getProjectId());
            }
        }
        message.setSequenceNumber(sequence);
        message.setRole(role);
        message.setStatus("tool".equalsIgnoreCase(role) ? "completed" : "durable");
        message.setContent(limit(content, 12_000));
        message.setMetadata(GSON.toJson(metadata));
        message.setUpdateTime(LocalDateTime.now());
        if (message.getRunMessageId() == null) {
            messageMapper.insert(message);
        } else {
            messageMapper.updateById(message);
        }
        return message;
    }

    private void appendAssistantToolParts(Long taskId, AgentRunMessage message, long epoch,
                                          long sequence, Map<String, Object> providerMessage) {
        Object rawCalls = providerMessage.get("tool_calls");
        if (!(rawCalls instanceof List<?> calls)) {
            return;
        }
        for (int index = 0; index < calls.size(); index++) {
            if (!(calls.get(index) instanceof Map<?, ?> rawCall)) {
                throw new IllegalArgumentException("assistant tool_calls must contain objects");
            }
            String id = stringValue(rawCall.get("id"));
            String type = stringValue(rawCall.get("type"));
            Object rawFunction = rawCall.get("function");
            if (id.isBlank() || !(rawFunction instanceof Map<?, ?> function)) {
                throw new IllegalArgumentException("assistant tool_call id and function are required");
            }
            String name = stringValue(function.get("name"));
            String arguments = stringValue(function.get("arguments"));
            if (name.isBlank()) {
                throw new IllegalArgumentException("assistant tool_call function.name is required");
            }
            LinkedHashMap<String, Object> call = new LinkedHashMap<>();
            call.put("id", id);
            call.put("type", type.isBlank() ? "function" : type);
            call.put("function", Map.of("name", name, "arguments", arguments));
            upsertPart(taskId, message, toolCallKey(epoch, sequence, index, id),
                    sequence * 1000L + index, "tool_call", "pending", id, name, call, "");
        }
    }

    private void appendToolResultPart(Long taskId, AgentRunMessage message, long epoch,
                                      long sequence, Map<String, Object> providerMessage) {
        String toolCallId = stringValue(providerMessage.get("tool_call_id"));
        String toolName = stringValue(providerMessage.get("name"));
        if (toolCallId.isBlank() || toolName.isBlank()) {
            throw new IllegalArgumentException("tool message tool_call_id and name are required");
        }
        String content = stringValue(providerMessage.get("content"));
        AgentRunPart result = upsertPart(taskId, message, toolResultKey(epoch, sequence, toolCallId), sequence,
                "tool_result", "completed", toolCallId, toolName, providerMessage, content);
        if (Boolean.TRUE.equals(providerMessage.get(DEFERRED_RESOLUTION_METADATA))) {
            markDeferredResolution(result);
            result.setUpdateTime(LocalDateTime.now());
            partMapper.updateById(result);
        }
        completeMatchingToolCallPart(taskId, toolCallId, content);
    }

    private void completeMatchingToolCallPart(Long taskId, String toolCallId, String output) {
        List<AgentRunPart> calls = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getToolCallId, toolCallId)
                .eq(AgentRunPart::getPartType, "tool_call"));
        if (calls == null) return;
        for (AgentRunPart call : calls) {
            call.setStatus("completed");
            call.setOutputText(limit(output, 8_000));
            call.setUpdateTime(LocalDateTime.now());
            partMapper.updateById(call);
        }
    }

    private AgentRunPart upsertPart(Long taskId, AgentRunMessage message, String key, long sequence,
                                    String partType, String status, String toolCallId, String toolName,
                                    Object input, String output) {
        AgentRunPart part = partMapper.selectOne(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getPartKey, key)
                .last("LIMIT 1"));
        AgentTask task = taskMapper.selectById(taskId);
        if (part == null) {
            part = new AgentRunPart();
            part.setTaskId(taskId);
            part.setPartKey(key);
            part.setCreateTime(LocalDateTime.now());
            if (task != null) {
                part.setConversationId(task.getConversationId());
                part.setStudentId(task.getStudentId());
                part.setProjectId(task.getProjectId());
            }
        }
        part.setMessageId(message.getRunMessageId());
        part.setSequenceNumber(sequence);
        part.setPartType(partType);
        part.setStatus(status);
        part.setToolCallId(toolCallId);
        part.setToolName(toolName);
        part.setInputJson(GSON.toJson(input == null ? Map.of() : input));
        part.setOutputText(limit(output, 8_000));
        part.setMetadata(GSON.toJson(Map.of("provider", true, "partType", partType)));
        part.setUpdateTime(LocalDateTime.now());
        if (part.getPartId() == null) {
            partMapper.insert(part);
        } else {
            partMapper.updateById(part);
        }
        return part;
    }

    private Map<String, Object> rebuildAssistant(AgentRunMessage message, Long taskId) {
        return rebuildAssistant(message, taskId, false);
    }

    private Map<String, Object> rebuildAssistant(AgentRunMessage message, Long taskId, boolean allowOpenBatch) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", message.getContent() == null ? "" : message.getContent());
        List<AgentRunPart> calls = partMapper.selectList(new LambdaQueryWrapper<AgentRunPart>()
                .eq(AgentRunPart::getTaskId, taskId)
                .eq(AgentRunPart::getMessageId, message.getRunMessageId())
                .eq(AgentRunPart::getPartType, "tool_call")
                .orderByAsc(AgentRunPart::getSequenceNumber)
                .orderByAsc(AgentRunPart::getPartId));
        if (calls != null && !calls.isEmpty()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (AgentRunPart part : calls) {
                if (!"completed".equalsIgnoreCase(part.getStatus())
                        && !"pending".equalsIgnoreCase(part.getStatus())
                        && !(allowOpenBatch && isOpenPartStatus(part.getStatus()))) {
                    throw new IllegalStateException("Provider tool call part is not recoverable: " + part.getPartKey());
                }
                Map<String, Object> parsed = parseObject(part.getInputJson());
                if (parsed.isEmpty()) {
                    throw new IllegalStateException("Provider tool call arguments are missing: " + part.getPartKey());
                }
                toolCalls.add(parsed);
            }
            result.put("tool_calls", toolCalls);
        }
        return result;
    }

    private Map<String, Object> rebuildTool(AgentRunMessage message) {
        Map<String, Object> metadata = parseObject(message.getMetadata());
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("role", "tool");
        result.put("tool_call_id", stringValue(metadata.get("tool_call_id")));
        result.put("name", stringValue(metadata.get("name")));
        result.put("content", message.getContent() == null ? "" : message.getContent());
        return result;
    }

    private Map<String, Object> messageMetadata(long epoch, Map<String, Object> providerMessage) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", true);
        metadata.put("executionEpoch", epoch);
        if (providerMessage.containsKey("tool_call_id")) {
            metadata.put("tool_call_id", providerMessage.get("tool_call_id"));
        }
        if (providerMessage.containsKey("name")) {
            metadata.put("name", providerMessage.get("name"));
        }
        return metadata;
    }

    private Map<String, Object> simpleMessage(String role, String content) {
        return Map.of("role", role, "content", content == null ? "" : content);
    }

    private Map<String, Object> parseObject(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonElement element = JsonParser.parseString(raw);
            if (!element.isJsonObject()) return Map.of();
            return GSON.fromJson(element, Map.class);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private String providerKey(long epoch, long sequence) {
        return PROVIDER_KEY_PREFIX + epoch + ":message:" + sequence;
    }

    private String toolCallKey(long epoch, long sequence, int index, String id) {
        return PROVIDER_KEY_PREFIX + epoch + ":tool-call:" + sequence + ":" + index + ":" + safeKey(id);
    }

    private String toolResultKey(long epoch, long sequence, String id) {
        return PROVIDER_KEY_PREFIX + epoch + ":tool-result:" + sequence + ":" + safeKey(id);
    }

    private String safeKey(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.length() <= 72 ? normalized : normalized.substring(0, 72);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "\n...truncated...";
    }
}
