package com.labex.monitor.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AgentRuntimeProjectionServiceTest {

    private final AgentRuntimeProjectionService projection = new AgentRuntimeProjectionService();

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(42L);
        task.setConversationId("conv-1");
        task.setSessionId("session-1");
        task.setMode("build");
        task.setStatus("running");
        task.setCurrentStep("run_command");
        task.setExecutionEpoch(3L);
        task.setExecutionOwner("worker-1");
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 8, 21, 12, 0));
        task.setExecutionHeartbeatAt(LocalDateTime.of(2026, 8, 21, 11, 59));
        task.setRetryAttempts(2);
        task.setRecoveryAttempts(1);
        task.setSubmittedAt(LocalDateTime.of(2026, 8, 21, 11, 0));
        task.setStartedAt(LocalDateTime.of(2026, 8, 21, 11, 1));
        task.setUpdateTime(LocalDateTime.of(2026, 8, 21, 11, 59));
        task.setSummary("fix login bug");
        task.setModelConfigId(7);
        task.setRunVersion(2L);
        task.setLastEventSequence(99L);
        return task;
    }

    @Test
    void toSummaryMapsAllCoreFields() {
        AgentRuntimeTaskSummary summary = projection.toSummary(
                task(), "deepseek", "deepseek-chat", "tool_executed", true);

        assertThat(summary.taskId()).isEqualTo(42L);
        assertThat(summary.conversationId()).isEqualTo("conv-1");
        assertThat(summary.currentStatus()).isEqualTo("running");
        assertThat(summary.executionEpoch()).isEqualTo(3L);
        assertThat(summary.leaseOwner()).isEqualTo("worker-1");
        assertThat(summary.leaseExpireAt()).isNotNull();
        assertThat(summary.lastHeartbeatAt()).isNotNull();
        assertThat(summary.retryCount()).isEqualTo(2);
        assertThat(summary.recoveryAttempts()).isEqualTo(1);
        assertThat(summary.provider()).isEqualTo("deepseek");
        assertThat(summary.modelName()).isEqualTo("deepseek-chat");
        assertThat(summary.latestEvent()).isEqualTo("tool_executed");
        assertThat(summary.overdue()).isTrue();
    }

    @Test
    void toSummaryToleratesMissingProviderAndLatestEvent() {
        AgentRuntimeTaskSummary summary = projection.toSummary(task(), null, null, null, false);

        assertThat(summary.provider()).isNull();
        assertThat(summary.modelName()).isNull();
        assertThat(summary.latestEvent()).isNull();
        assertThat(summary.overdue()).isFalse();
    }

    @Test
    void toDetailIncludesWaitingInteractionAndLatestEvent() {
        AgentRunEvent latest = new AgentRunEvent();
        latest.setEventType("tool_executed");
        latest.setSequenceNumber(99L);

        AgentRunInteraction waiting = new AgentRunInteraction();
        waiting.setInteractionId("inter-1");
        waiting.setInteractionType("permission");
        waiting.setStatus("waiting");

        AgentRuntimeTaskDetail detail = projection.toDetail(
                task(), "deepseek", "deepseek-chat", latest, waiting, 2000, 200);

        assertThat(detail.taskId()).isEqualTo(42L);
        assertThat(detail.provider()).isEqualTo("deepseek");
        assertThat(detail.latestEvent()).isEqualTo("tool_executed");
        assertThat(detail.lastEventSequence()).isEqualTo(99L);
        assertThat(detail.runVersion()).isEqualTo(2L);
        assertThat(detail.waitingInteractionId()).isEqualTo("inter-1");
        assertThat(detail.waitingInteractionType()).isEqualTo("permission");
        assertThat(detail.waitingReason()).isEqualTo("等待权限审批");
    }

    @Test
    void toDetailWithoutWaitingOrLatestEvent() {
        AgentRuntimeTaskDetail detail = projection.toDetail(task(), null, null, null, null, 2000, 200);

        assertThat(detail.latestEvent()).isNull();
        assertThat(detail.waitingReason()).isNull();
        assertThat(detail.waitingInteractionId()).isNull();
        assertThat(detail.failureReason()).isNull();
    }

    @Test
    void failedTaskExposesFailureReasonFromLatestEvent() {
        AgentTask failedTask = task();
        failedTask.setStatus("failed");
        AgentRunEvent latest = new AgentRunEvent();
        latest.setEventType("provider_error");

        AgentRuntimeTaskDetail detail = projection.toDetail(failedTask, null, null, latest, null, 2000, 200);

        assertThat(detail.currentStatus()).isEqualTo("failed");
        assertThat(detail.failureReason()).isEqualTo("provider_error");
    }

    @Test
    void waitingReasonMapsInteractionTypes() {
        assertThat(projection.waitingReason(interaction("question"))).isEqualTo("等待用户回答");
        assertThat(projection.waitingReason(interaction("permission"))).isEqualTo("等待权限审批");
        assertThat(projection.waitingReason(interaction("network"))).isEqualTo("等待网络授权");
        assertThat(projection.waitingReason(interaction("config_proposal"))).isEqualTo("等待配置提案确认");
        assertThat(projection.waitingReason(null)).isNull();
    }

    @Test
    void taskStatusDerivesWaitingReasonWithoutInteraction() {
        AgentTask workspaceWait = task();
        workspaceWait.setStatus("waiting_workspace");
        assertThat(projection.waitingReasonOfStatus(workspaceWait.getStatus())).isEqualTo("等待工作区就绪");

        AgentTask retry = task();
        retry.setStatus("retrying");
        assertThat(projection.waitingReasonOfStatus(retry.getStatus())).isEqualTo("等待重试调度");

        assertThat(projection.waitingReasonOfStatus("running")).isNull();
        assertThat(projection.waitingReasonOfStatus(null)).isNull();
    }

    @Test
    void toEventItemTruncatesLongPayload() {
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(1L);
        event.setSequenceNumber(5L);
        event.setEventType("tool_executed");
        event.setState("running");
        event.setCreateTime(LocalDateTime.of(2026, 8, 21, 12, 0));
        event.setPayload("x".repeat(5000));

        AgentRuntimeEventItem item = projection.toEventItem(event, 100);

        assertThat(item.sequenceNumber()).isEqualTo(5L);
        assertThat(item.eventType()).isEqualTo("tool_executed");
        assertThat(item.payload()).hasSize(100);
        assertThat(item.payload()).endsWith("…");
    }

    @Test
    void toEventItemToleratesNullPayload() {
        AgentRunEvent event = new AgentRunEvent();
        event.setSequenceNumber(1L);
        event.setPayload(null);

        AgentRuntimeEventItem item = projection.toEventItem(event, 100);

        assertThat(item.payload()).isNull();
    }

    private static AgentRunInteraction interaction(String type) {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionType(type);
        interaction.setStatus("waiting");
        return interaction;
    }
}
