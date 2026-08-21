package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.alert.AlertQueryService;
import com.labex.monitor.alert.AlertStateService;
import com.labex.monitor.auth.MonitorRoleService;
import com.labex.monitor.dto.AlertDto;
import com.labex.monitor.operation.OperationAuthorizationService;
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

/** 告警查询与处理（确认/静默/恢复），变更操作要求操作者角色。 */
@RestController
@RequestMapping("/ops/alerts")
public class MonitorAlertController {

    private final AlertQueryService queryService;
    private final AlertStateService stateService;
    private final OperationAuthorizationService authorizationService;
    private final MonitorRoleService roleService;

    public MonitorAlertController(AlertQueryService queryService, AlertStateService stateService,
                                  OperationAuthorizationService authorizationService, MonitorRoleService roleService) {
        this.queryService = queryService;
        this.stateService = stateService;
        this.authorizationService = authorizationService;
        this.roleService = roleService;
    }

    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String severity) {
        try {
            List<AlertDto> records = queryService.query(page, pageSize, status, severity);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("records", records);
            data.put("total", queryService.count(status, severity));
            return ResponseEntity.ok(Result.success(data));
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Result.error(-1, failure.getMessage()));
        }
    }

    @GetMapping("/{alertId}")
    public Result<AlertDto> detail(@PathVariable Long alertId) {
        AlertDto detail = queryService.detail(alertId);
        return detail == null ? Result.error(404, "Alert not found") : Result.success(detail);
    }

    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<Result<Void>> acknowledge(@PathVariable Long alertId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest request) {
        String role = authorizationService.requireOperator(request, operatorCode(body));
        if (!stateService.acknowledge(alertId, role)) {
            return ResponseEntity.ok(Result.error(404, "Alert not found or already resolved"));
        }
        return ResponseEntity.ok(Result.success());
    }

    @PostMapping("/{alertId}/silence")
    public ResponseEntity<Result<Void>> silence(@PathVariable Long alertId,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        String role = authorizationService.requireOperator(request, operatorCode(body));
        int durationMinutes = body == null || body.get("durationMinutes") == null ? 60
                : ((Number) body.get("durationMinutes")).intValue();
        try {
            if (!stateService.silence(alertId, durationMinutes, role)) {
                return ResponseEntity.ok(Result.error(404, "Alert not found or already resolved"));
            }
            return ResponseEntity.ok(Result.success());
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Result.error(-1, failure.getMessage()));
        }
    }

    @PostMapping("/{alertId}/resolve")
    public ResponseEntity<Result<Void>> resolve(@PathVariable Long alertId,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        String role = authorizationService.requireOperator(request, operatorCode(body));
        String reason = body == null ? null : (String) body.get("reason");
        if (!stateService.resolve(alertId, role, reason)) {
            return ResponseEntity.ok(Result.error(404, "Alert not found"));
        }
        return ResponseEntity.ok(Result.success());
    }

    private String operatorCode(Map<String, Object> body) {
        return body == null ? null : (String) body.get("operatorCode");
    }
}