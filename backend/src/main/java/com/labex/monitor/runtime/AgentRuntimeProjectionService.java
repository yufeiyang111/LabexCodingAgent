package com.labex.monitor.runtime;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 将 Agent 权威状态（AgentTask / AgentRunEvent / AgentRunInteraction）组装为运维只读 DTO。
 * 只做投影与脱敏，不写任何状态、不引入第二套状态源。
 */
@Component
public class AgentRuntimeProjectionService {

    public AgentRuntimeTaskSummary toSummary(AgentTask task, String provider, String modelName,
                                             String latestEvent, boolean overdue) {
        return new AgentRuntimeTaskSummary(
                task.getTaskId(),
                task.getConversationId(),
                task.getSessionId(),
                task.getMode(),
                task.getStatus(),
                task.getCurrentStep(),
                task.getExecutionEpoch(),
                task.getExecutionOwner(),
                task.getExecutionLeaseExpiresAt(),
                task.getExecutionHeartbeatAt(),
                task.getRetryAttempts(),
                task.getRecoveryAttempts(),
                task.getSubmittedAt(),
                task.getStartedAt(),
                task.getUpdateTime(),
                task.getSummary(),
                provider,
                modelName,
                latestEvent,
                overdue);
    }

    public AgentRuntimeTaskDetail toDetail(AgentTask task, String provider, String modelName,
                                           AgentRunEvent latest, AgentRunInteraction waiting,
                                           int eventPayloadMaxChars, int summaryMaxChars) {
        String latestEvent = latest == null ? null : latest.getEventType();
        String failureReason = "failed".equals(task.getStatus()) ? latestEvent : null;
        String waitingReason = waiting == null
                ? waitingReasonOfStatus(task.getStatus())
                : waitingReason(waiting);
        return new AgentRuntimeTaskDetail(
                task.getTaskId(),
                task.getConversationId(),
                task.getSessionId(),
                task.getMode(),
                task.getStatus(),
                task.getCurrentStep(),
                task.getExecutionEpoch(),
                task.getExecutionOwner(),
                task.getExecutionLeaseExpiresAt(),
                task.getExecutionHeartbeatAt(),
                task.getRetryAttempts(),
                task.getRecoveryAttempts(),
                task.getSubmittedAt(),
                task.getStartedAt(),
                task.getUpdateTime(),
                truncate(task.getSummary(), summaryMaxChars),
                provider,
                modelName,
                task.getRunVersion(),
                task.getLastEventSequence(),
                latestEvent,
                failureReason,
                waitingReason,
                waiting == null ? null : waiting.getInteractionType(),
                waiting == null ? null : waiting.getInteractionId());
    }

    public AgentRuntimeEventItem toEventItem(AgentRunEvent event, int payloadMaxChars) {
        return new AgentRuntimeEventItem(
                event.getEventId(),
                event.getSequenceNumber(),
                event.getEventType(),
                event.getState(),
                event.getCreateTime(),
                truncate(event.getPayload(), payloadMaxChars));
    }

    public String waitingReason(AgentRunInteraction waiting) {
        if (waiting == null || waiting.getInteractionType() == null) {
            return null;
        }
        return switch (waiting.getInteractionType()) {
            case "question" -> "等待用户回答";
            case "permission" -> "等待权限审批";
            case "network" -> "等待网络授权";
            case "config_proposal" -> "等待配置提案确认";
            default -> null;
        };
    }

    public String waitingReasonOfStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "waiting_workspace" -> "等待工作区就绪";
            case "waiting_environment" -> "等待环境恢复";
            case "waiting_recovery" -> "等待循环保护恢复";
            case "retrying" -> "等待重试调度";
            case "queued" -> "排队中";
            case "preparing" -> "准备中";
            default -> null;
        };
    }

    public boolean isOverdue(AgentTask task, LocalDateTime now) {
        if (task.getExecutionLeaseExpiresAt() == null) {
            return false;
        }
        if (isTerminal(task.getStatus())) {
            return false;
        }
        return task.getExecutionLeaseExpiresAt().isBefore(now);
    }

    public boolean isTerminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (maxChars <= 1 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 1) + "…";
    }
}
