package com.labex.monitor.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.entity.OpsOperation;
import com.labex.monitor.access.ClientIpResolver;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.auth.MonitorRoleService;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.config.OpsAlertProperties;
import com.labex.monitor.operation.CancelTaskOperation;
import com.labex.monitor.operation.OperationAuthorizationService;
import com.labex.monitor.operation.OperationIdempotencyService;
import com.labex.monitor.operation.OperationOrchestrator;
import com.labex.monitor.operation.OperationQueryService;
import com.labex.monitor.operation.RecoverLeaseOperation;
import com.labex.monitor.operation.RetryTaskOperation;
import com.labex.monitor.operation.WorkerOperation;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunRecoveryService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.mapper.OpsOperationMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.event.OpsEventRecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonitorOperationControllerTest {

    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final OpsOperationMapper operationMapper = mock(OpsOperationMapper.class);
    private final AuditRecordingService auditService = mock(AuditRecordingService.class);
    private final OpsEventRecordingService eventService = mock(OpsEventRecordingService.class);
    private final AgentTaskService taskService = mock(AgentTaskService.class);
    private final AgentRunLifecycleService lifecycleService = mock(AgentRunLifecycleService.class);
    private final AgentRunRecoveryService recoveryService = mock(AgentRunRecoveryService.class);
    private final MonitorRoleService roleService = new MonitorRoleService();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MonitorProperties properties = new MonitorProperties();
        properties.setEnabled(true);
        properties.setAccessCode("s3cret");
        MonitorAuthInterceptor interceptor =
                new MonitorAuthInterceptor(accessService, properties, new ObjectMapper());
        OperationIdempotencyService idempotencyService = new OperationIdempotencyService(operationMapper);
        OperationOrchestrator orchestrator = new OperationOrchestrator(operationMapper, idempotencyService,
                auditService, eventService);
        ClientIpResolver ipResolver = new ClientIpResolver();
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitorOperationController(orchestrator, idempotencyService,
                        new OperationAuthorizationService(roleService), new OperationQueryService(operationMapper),
                        new CancelTaskOperation(taskService),
                        new RetryTaskOperation(lifecycleService, taskService, new OpsAlertProperties()),
                        new RecoverLeaseOperation(recoveryService), new WorkerOperation(), ipResolver))
                .addInterceptors(interceptor)
                .build();
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken("viewer")).thenReturn(true);
        when(accessService.isValidToken("operator")).thenReturn(true);
        when(accessService.sessionRole("viewer")).thenReturn(MonitorRoleService.VIEWER);
        when(accessService.sessionRole("operator")).thenReturn(MonitorRoleService.OPERATOR);
    }

    @Test
    void unauthenticatedOperationIsRejected() throws Exception {
        when(accessService.isValidToken(null)).thenReturn(false);

        mockMvc.perform(post("/ops/operations/tasks/42/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerIsForbiddenForOperations() throws Exception {
        mockMvc.perform(post("/ops/operations/tasks/42/cancel").header("X-Monitor-Token", "viewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mockMvc.perform(post("/ops/operations/tasks/42/cancel").header("X-Monitor-Token", "operator"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelTaskSucceedsWithIdempotencyKey() throws Exception {
        when(taskService.requestCancellation(42L, "Cancelled by ops operator", "Ops cancellation")).thenReturn(true);
        when(taskService.finalizeCancellation(42L, "Cancelled by ops operator", "Ops cancellation")).thenReturn(true);

        mockMvc.perform(post("/ops/operations/tasks/42/cancel").header("X-Monitor-Token", "operator")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\": \"cancel-42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void retryTaskPersistsFailedState() throws Exception {
        when(lifecycleService.cancelScheduledRetry(42L, java.util.Map.of("source", "ops", "reason", "manual retry"),
                "ops-retry-cancel-42")).thenReturn(false);
        when(taskService.scheduleModelRetry(42L, 1, 0L, "Ops manual retry: manual retry")).thenReturn(null);

        mockMvc.perform(post("/ops/operations/tasks/42/retry").header("X-Monitor-Token", "operator")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\": \"retry-42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @Test
    void duplicateSubmissionReturnsFirstResult() throws Exception {
        OpsOperation existing = new OpsOperation();
        existing.setOperationId(7L);
        existing.setStatus("SUCCEEDED");
        when(operationMapper.selectByIdempotencyKey("cancel-42")).thenReturn(existing);

        mockMvc.perform(post("/ops/operations/tasks/42/cancel").header("X-Monitor-Token", "operator")
                        .contentType("application/json")
                        .content("{\"idempotencyKey\": \"cancel-42\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value(7));
    }

    @Test
    void workerPauseRequiresOperator() throws Exception {
        mockMvc.perform(post("/ops/operations/workers/wsl-1/pause").header("X-Monitor-Token", "viewer"))
                .andExpect(status().isForbidden());
    }
}