package com.labex.labexagent.tool.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.labex.entity.AgentRunInteraction;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.CreateProposalRequest;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalException;
import com.labex.labexagent.projectconfig.AgentProjectConfigProposalService.ProposalResult;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunInteractionService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.labexagent.runtime.AgentToolTurnExecutor;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.labexagent.tool.ToolSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 侧唯一的项目配置变更入口：把完整候选配置提交为一个 proposal，并创建
 * {@code config_proposal} 持久化等待投影，然后阻塞本轮直到 owner 决策。
 *
 * <p>Provenance（taskId/executionEpoch/toolCallId）只从 {@link AgentContext} 与执行器
 * 的 toolCallId 绑定读取，绝不接受用户输入提供的权威字段。该工具没有 apply、
 * secret rotation 或 host maintenance 能力；批准与 apply 只发生在 owner 决策路径
 * （{@link AgentProjectConfigProposalService}）。
 *
 * <p>proposal 表是唯一权威事实；interaction 只是等待投影，payload 只含 proposal ID、
 * digest、changed paths 和 expiry，不含候选内容或秘密。工具重放（JVM 重启后恢复的
 * tool call）通过确定性 proposal key 与 interaction idempotency key 返回第一次的
 * durable 结果。
 */
@Component
public class ProposeProjectConfigTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(ProposeProjectConfigTool.class);
    private static final String TOOL_NAME = "propose_project_config";
    private static final String INTERACTION_TYPE = AgentRunInteraction.TYPE_CONFIG_PROPOSAL;

    private final AgentProjectConfigProposalService proposalService;
    private final AgentRunInteractionService interactionService;
    private final AgentRunLifecycleService lifecycleService;

    public ProposeProjectConfigTool(AgentProjectConfigProposalService proposalService,
                                    AgentRunInteractionService interactionService,
                                    AgentRunLifecycleService lifecycleService) {
        this.proposalService = proposalService;
        this.interactionService = interactionService;
        this.lifecycleService = lifecycleService;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("type", "object");
        candidate.put("description", "Complete candidate document: protected config path -> content");
        candidate.put("additionalProperties", true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("candidate", candidate);
        properties.put("expectedRevision", Map.of("type", "integer",
                "description", "Current accepted revision the candidate is based on"));
        properties.put("reason", Map.of("type", "string",
                "description", "Human-readable reason for the change"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("candidate"));
        return new ToolDefinition(TOOL_NAME,
                "为项目提交配置变更 proposal 并等待项目所有者批准或拒绝；不直接修改任何受保护配置。",
                schema);
    }

    @Override
    public ToolResult execute(AgentContext context, JsonObject args) {
        requireActiveFence(context);
        Long taskId = context.getTaskId();
        Integer studentId = context.getStudentId();
        Integer projectId = context.getProject() == null ? null : context.getProject().getProjectId();
        if (taskId == null || studentId == null || projectId == null) {
            return ToolResult.failed("propose_project_config requires an active task context");
        }
        Map<String, String> candidate = candidateMap(args.get("candidate"));
        if (candidate.isEmpty()) {
            return ToolResult.failed("candidate is required and must be a complete document");
        }
        Long expectedRevision = args.has("expectedRevision") && args.get("expectedRevision").isJsonPrimitive()
                ? args.get("expectedRevision").getAsLong() : null;
        String reason = ToolSupport.stringArg(args, "reason", "Agent requested configuration change").trim();

        long executionEpoch = context.getExecutionEpoch();
        String toolCallId = AgentToolTurnExecutor.currentToolCallId();
        try {
            ProposalResult proposal = proposalService.createProposal(studentId, projectId,
                    new CreateProposalRequest(expectedRevision, candidate, null,
                            reason.length() > 2048 ? reason.substring(0, 2048) : reason,
                            proposalKey(taskId, candidate), AgentProjectConfigProposalService.SOURCE_AGENT,
                            null, taskId, executionEpoch, toolCallId));
            LocalDateTime expiresTime = proposal.expiresTime() == null
                    ? LocalDateTime.now().plusSeconds(AgentProjectConfigProposalService.EXPIRY_SECONDS)
                    : proposal.expiresTime();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("proposalId", proposal.proposalId());
            payload.put("candidateConfigDigest", proposal.candidateConfigDigest());
            payload.put("changedPathSummary", proposal.changedPathSummary());
            payload.put("expiresTime", expiresTime.toString());
            payload.put("toolCallId", toolCallId == null ? "" : toolCallId);
            AgentRunInteraction interaction = interactionService.createWaiting(
                    new AgentRunInteractionService.WaitingInteraction(
                            UUID.randomUUID().toString(), taskId, context.getConversationId(),
                            context.getSessionId(), studentId, projectId, INTERACTION_TYPE,
                            payload, interactionKey(taskId, proposal.proposalId()), expiresTime));
            emitCreatedEvent(context, taskId, executionEpoch, toolCallId, proposal, expiresTime);
            Map<String, Object> projection = new LinkedHashMap<>(payload);
            projection.put("taskId", taskId);
            projection.put("sessionId", context.getSessionId());
            projection.put("conversationId", context.getConversationId());
            projection.put("toolCallId", toolCallId == null ? "" : toolCallId);
            projection.put("createdAt", LocalDateTime.now().toString());
            return ToolResult.interactionRequired(
                    "Configuration change proposal " + proposal.proposalId()
                            + " is pending owner approval. The proposal expires at " + expiresTime
                            + ". Waiting for the owner to approve or reject it.",
                    interaction.getInteractionId(), INTERACTION_TYPE).withInteractionPayload(projection);
        } catch (ProposalException proposalFailure) {
            return ToolResult.failed("propose_project_config rejected: " + proposalFailure.reasonCode()
                    + " (" + proposalFailure.getMessage() + ")");
        }
    }

    /** CREATED 事件是任务投影；proposal + interaction 才是事实，投影失败不能阻断工具。 */
    private void emitCreatedEvent(AgentContext context, Long taskId, long executionEpoch, String toolCallId,
                                  ProposalResult proposal, LocalDateTime expiresTime) {
        if (lifecycleService == null) {
            return;
        }
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("proposalId", proposal.proposalId());
        eventPayload.put("candidateConfigDigest", proposal.candidateConfigDigest());
        eventPayload.put("changedPathSummary", proposal.changedPathSummary());
        eventPayload.put("expiresTime", expiresTime.toString());
        eventPayload.put("taskId", taskId);
        eventPayload.put("executionEpoch", executionEpoch);
        eventPayload.put("toolCallId", toolCallId == null ? "" : toolCallId);
        try {
            lifecycleService.appendEvent(context.getExecutionFence(), taskId, "CONFIG_PROPOSAL_CREATED",
                    eventPayload, "config-proposal-created:v1:" + taskId + ":" + proposal.proposalId());
        } catch (RuntimeException projectionFailure) {
            log.warn("CONFIG_PROPOSAL_CREATED projection failed taskId={} proposalId={}",
                    taskId, proposal.proposalId(), projectionFailure);
        }
    }

    private Map<String, String> candidateMap(JsonElement raw) {
        Map<String, String> candidate = new LinkedHashMap<>();
        if (raw == null || !raw.isJsonObject()) {
            return candidate;
        }
        JsonObject object = raw.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String value = entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
                    ? entry.getValue().getAsString() : entry.getValue().toString();
            candidate.put(entry.getKey(), value);
        }
        return candidate;
    }

    /** 确定性 proposal key：同一任务对同一候选内容重放时返回第一次的 durable proposal。 */
    private String proposalKey(Long taskId, Map<String, String> candidate) {
        return "propose-project-config:v1:" + taskId + ":" + digest(candidate);
    }

    /** 确定性 interaction idempotency key：绑定 proposal，重放时返回同一个等待投影。 */
    private String interactionKey(Long taskId, Long proposalId) {
        return "config-proposal:v1:" + taskId + ":" + proposalId;
    }

    private static String digest(Map<String, String> candidate) {
        StringBuilder canonical = new StringBuilder();
        new TreeMap<>(candidate).forEach((path, content) ->
                canonical.append(path).append('=').append(content).append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                hex.append(String.format("%02x", hash[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(candidate.hashCode());
        }
    }

    private void requireActiveFence(AgentContext context) {
        ExecutionFence fence = context == null ? null : context.getExecutionFence();
        if (fence == null) {
            throw new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                    AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.INVALID_FENCE);
        }
    }
}
