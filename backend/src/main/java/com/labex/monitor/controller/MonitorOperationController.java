package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.entity.OpsOperation;
import com.labex.monitor.access.ClientIpResolver;
import com.labex.monitor.config.OpsAlertProperties;
import com.labex.monitor.dto.OperationDto;
import com.labex.monitor.dto.OperationRequest;
import com.labex.monitor.operation.CancelTaskOperation;
import com.labex.monitor.operation.OperationAuthorizationService;
import com.labex.monitor.operation.OperationIdempotencyService;
import com.labex.monitor.operation.OperationOrchestrator;
import com.labex.monitor.operation.OperationQueryService;
import com.labex.monitor.operation.RecoverLeaseOperation;
import com.labex.monitor.operation.RetryTaskOperation;
import com.labex.monitor.operation.WorkerOperation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 受控运维操作：全部要求操作者角色 + 幂等键 + 审计。 */
@RestController
@RequestMapping("/ops/operations")
public class MonitorOperationController {

    private final OperationOrchestrator orchestrator;
    private final OperationIdempotencyService idempotencyService;
    private final OperationAuthorizationService authorizationService;
    private final OperationQueryService queryService;
    private final CancelTaskOperation cancelTaskOperation;
    private final RetryTaskOperation retryTaskOperation;
    private final RecoverLeaseOperation recoverLeaseOperation;
    private final WorkerOperation workerOperation;
    private final ClientIpResolver ipResolver;

    public MonitorOperationController(OperationOrchestrator orchestrator,
                                      OperationIdempotencyService idempotencyService,
                                      OperationAuthorizationService authorizationService,
                                      OperationQueryService queryService,
                                      CancelTaskOperation cancelTaskOperation,
                                      RetryTaskOperation retryTaskOperation,
                                      RecoverLeaseOperation recoverLeaseOperation,
                                      WorkerOperation workerOperation,
                                      ClientIpResolver ipResolver) {
        this.orchestrator = orchestrator;
        this.idempotencyService = idempotencyService;
        this.authorizationService = authorizationService;
        this.queryService = queryService;
        this.cancelTaskOperation = cancelTaskOperation;
        this.retryTaskOperation = retryTaskOperation;
        this.recoverLeaseOperation = recoverLeaseOperation;
        this.workerOperation = workerOperation;
        this.ipResolver = ipResolver;
    }

    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            @RequestParam(required = false) String actionType,
                                                            @RequestParam(required = false) String targetType,
                                                            @RequestParam(required = false) String status) {
        List<OperationDto> records = queryService.query(page, pageSize, actionType, targetType, status);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", (long) records.size());
        return ResponseEntity.ok(Result.success(data));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<Result<OpsOperation>> cancelTask(@PathVariable Long taskId,
                                                           @RequestBody(required = false) OperationRequest body,
                                                           HttpServletRequest request) {
        return execute("CANCEL_TASK", "task", String.valueOf(taskId), body, cancelTaskOperation, request);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public ResponseEntity<Result<OpsOperation>> retryTask(@PathVariable Long taskId,
                                                          @RequestBody(required = false) OperationRequest body,
                                                          HttpServletRequest request) {
        return execute("RETRY_TASK", "task", String.valueOf(taskId), body, retryTaskOperation, request);
    }

    @PostMapping("/leases/recover")
    public ResponseEntity<Result<OpsOperation>> recoverLeases(@RequestBody(required = false) OperationRequest body,
                                                              HttpServletRequest request) {
        return execute("RECOVER_LEASES", "lease", "all", body, recoverLeaseOperation, request);
    }

    @PostMapping("/workers/{workerId}/pause")
    public ResponseEntity<Result<OpsOperation>> pauseWorker(@PathVariable String workerId,
                                                            @RequestBody(required = false) OperationRequest body,
                                                            HttpServletRequest request) {
        return execute("PAUSE_WORKER", "worker", workerId, body, workerOperation, request);
    }

    @PostMapping("/workers/{workerId}/resume")
    public ResponseEntity<Result<OpsOperation>> resumeWorker(@PathVariable String workerId,
                                                             @RequestBody(required = false) OperationRequest body,
                                                             HttpServletRequest request) {
        return execute("RESUME_WORKER", "worker", workerId, body, workerOperation, request);
    }

    private ResponseEntity<Result<OpsOperation>> execute(String actionType, String targetType, String targetId,
                                                         OperationRequest body, com.labex.monitor.operation.OperationExecutor executor,
                                                         HttpServletRequest request) {
        authorizationService.requireOperator(request);
        String idempotencyKey = body == null ? null : body.getIdempotencyKey();
        if (!idempotencyService.isValidKey(idempotencyKey)) {
            return ResponseEntity.badRequest().body(Result.error(-1, "缺少幂等键"));
        }
        String reason = body == null ? null : body.getReason();
        OpsOperation operation = orchestrator.execute(actionType, targetType, targetId, idempotencyKey,
                authorizationService.currentRole(request), authorizationService.currentRole(request),
                ipResolver.resolve(request), reason, executor);
        return ResponseEntity.ok(Result.success(operation));
    }
}