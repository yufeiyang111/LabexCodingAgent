package com.labex.monitor.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsOperation;
import com.labex.mapper.OpsOperationMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.event.OpsEventRecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationOrchestratorTest {

    private final OpsOperationMapper operationMapper = mock(OpsOperationMapper.class);
    private final AuditRecordingService auditService = mock(AuditRecordingService.class);
    private final OpsEventRecordingService eventService = mock(OpsEventRecordingService.class);
    private final OperationIdempotencyService idempotencyService = new OperationIdempotencyService(operationMapper);
    private OperationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new OperationOrchestrator(operationMapper, idempotencyService, auditService, eventService);
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingWithoutExecuting() {
        OpsOperation existing = new OpsOperation();
        existing.setOperationId(1L);
        existing.setStatus("SUCCEEDED");
        when(idempotencyService.findExisting("key-1")).thenReturn(existing);

        OpsOperation result = orchestrator.execute("CANCEL_TASK", "task", "42", "key-1",
                "OPS_OPERATOR", "OPS_OPERATOR", "127.0.0.1", null,
                context -> OperationExecutor.OperationResult.ok("done"));

        assertThat(result).isSameAs(existing);
        verify(operationMapper, never()).insert(any());
    }

    @Test
    void successfulExecutionPersistsTerminalStateAndAudits() {
        when(idempotencyService.findExisting("key-1")).thenReturn(null);

        OpsOperation result = orchestrator.execute("CANCEL_TASK", "task", "42", "key-1",
                "OPS_OPERATOR", "OPS_OPERATOR", "127.0.0.1", "cleanup",
                context -> OperationExecutor.OperationResult.ok("done"));

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getResult()).isEqualTo("done");
        assertThat(result.getCompletedAt()).isNotNull();
        verify(operationMapper).insert(any());
        verify(operationMapper).updateById(any());
        verify(auditService).record(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void executorFailureMarksFailedWithSafeMessage() {
        when(idempotencyService.findExisting("key-2")).thenReturn(null);

        OpsOperation result = orchestrator.execute("RETRY_TASK", "task", "42", "key-2",
                "OPS_OPERATOR", "OPS_OPERATOR", "10.0.0.1", null,
                context -> {
                    throw new IllegalStateException("db connection refused on 10.0.0.1:3306");
                });

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getFailureReason()).doesNotContain("10.0.0.1:3306");
    }

    @Test
    void explicitFailureResultIsPersisted() {
        when(idempotencyService.findExisting("key-3")).thenReturn(null);

        OpsOperation result = orchestrator.execute("CANCEL_TASK", "task", "99", "key-3",
                "OPS_OPERATOR", "OPS_OPERATOR", "127.0.0.1", null,
                context -> OperationExecutor.OperationResult.failed("task not found"));

        assertThat(result.getStatus()).isEqualTo("FAILED");
        assertThat(result.getFailureReason()).isEqualTo("task not found");
    }

    @Test
    void idempotencyKeyValidationRejectsBlank() {
        assertThat(idempotencyService.isValidKey(null)).isFalse();
        assertThat(idempotencyService.isValidKey("")).isFalse();
        assertThat(idempotencyService.isValidKey("  ")).isFalse();
        assertThat(idempotencyService.isValidKey("valid-key")).isTrue();
    }
}