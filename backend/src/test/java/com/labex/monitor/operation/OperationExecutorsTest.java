package com.labex.monitor.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunRecoveryService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.monitor.config.OpsAlertProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationExecutorsTest {

    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
    private final AgentRunRecoveryService recoveryService = mock(AgentRunRecoveryService.class);
    private final OpsAlertProperties properties = new OpsAlertProperties();

    private CancelTaskOperation cancelOperation;
    private RetryTaskOperation retryOperation;
    private RecoverLeaseOperation recoverOperation;
    private WorkerOperation workerOperation;

    @BeforeEach
    void setUp() {
        cancelOperation = new CancelTaskOperation(taskService);
        retryOperation = new RetryTaskOperation(lifecycleService, taskService, properties);
        recoverOperation = new RecoverLeaseOperation(recoveryService);
        workerOperation = new WorkerOperation();
    }

    @Test
    void cancelSucceedsWhenRequestAndFinalizeBothWork() {
        when(taskService.requestCancellation(42L, "Cancelled by ops operator", "Ops cancellation")).thenReturn(true);
        when(taskService.finalizeCancellation(42L, "Cancelled by ops operator", "Ops cancellation")).thenReturn(true);

        var result = cancelOperation.execute(ctx("task", "42"));

        assertThat(result.succeeded()).isTrue();
        verify(taskService).requestCancellation(42L, "Cancelled by ops operator", "Ops cancellation");
    }

    @Test
    void cancelFailsForTerminalTask() {
        when(taskService.requestCancellation(42L, "Cancelled by ops operator", "Ops cancellation")).thenReturn(false);

        var result = cancelOperation.execute(ctx("task", "42"));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.message()).contains("终态");
    }

    @Test
    void cancelRejectsNonNumericId() {
        assertThat(cancelOperation.execute(ctx("task", "abc")).succeeded()).isFalse();
        assertThat(cancelOperation.execute(ctx("task", "-1")).succeeded()).isFalse();
    }

    @Test
    void retrySucceedsForRetryableTask() {
        when(lifecycleService.cancelScheduledRetry(42L, java.util.Map.of("source", "ops", "reason", "manual retry"),
                "ops-retry-cancel-42")).thenReturn(true);
        when(taskService.scheduleModelRetry(42L, properties.getTaskRetryMaxAttempts(), 0L, "Ops manual retry: manual retry"))
                .thenReturn(new AgentTaskService.ModelRetrySchedule(1, java.time.LocalDateTime.now().plusSeconds(1),
                        0L, new com.labex.entity.AgentRunEvent()));

        var result = retryOperation.execute(ctx("task", "42"));

        assertThat(result.succeeded()).isTrue();
    }

    @Test
    void retryFailsWhenTaskNotRetryable() {
        when(lifecycleService.cancelScheduledRetry(42L, java.util.Map.of("source", "ops", "reason", "manual retry"),
                "ops-retry-cancel-42")).thenReturn(false);
        when(taskService.scheduleModelRetry(42L, properties.getTaskRetryMaxAttempts(), 0L,
                "Ops manual retry: manual retry")).thenReturn(null);

        var result = retryOperation.execute(ctx("task", "42"));

        assertThat(result.succeeded()).isFalse();
        assertThat(result.message()).contains("可重试");
    }

    @Test
    void recoverLeaseDelegatesToRecoveryService() {
        var result = recoverOperation.execute(ctx("lease", "all"));

        assertThat(result.succeeded()).isTrue();
        verify(recoveryService).recoverAfterStartup();
    }

    @Test
    void workerOperationRequiresWorkerId() {
        assertThat(workerOperation.execute(ctx("worker", "  ")).succeeded()).isFalse();
        assertThat(workerOperation.execute(ctx("worker", "wsl-1")).succeeded()).isTrue();
    }

    private OperationExecutor.OperationContext ctx(String targetType, String targetId) {
        return new OperationExecutor.OperationContext("TEST", targetType, targetId, null);
    }
}