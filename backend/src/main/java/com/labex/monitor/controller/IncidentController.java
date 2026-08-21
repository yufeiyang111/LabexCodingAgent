package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.entity.OpsIncident;
import com.labex.monitor.auth.MonitorRoleService;
import com.labex.monitor.dto.EventDto;
import com.labex.monitor.dto.IncidentDto;
import com.labex.monitor.incident.IncidentService;
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

/** 故障生命周期接口：创建/查询/状态迁移/时间线。 */
@RestController
@RequestMapping("/ops/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final OperationAuthorizationService authorizationService;
    private final MonitorRoleService roleService;

    public IncidentController(IncidentService incidentService,
                              OperationAuthorizationService authorizationService, MonitorRoleService roleService) {
        this.incidentService = incidentService;
        this.authorizationService = authorizationService;
        this.roleService = roleService;
    }

    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String severity) {
        List<IncidentDto> records = incidentService.query(page, pageSize, status, severity);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", incidentService.count(status, severity));
        return ResponseEntity.ok(Result.success(data));
    }

    @GetMapping("/{incidentId}")
    public Result<OpsIncident> detail(@PathVariable Long incidentId) {
        OpsIncident incident = incidentService.detail(incidentId);
        return incident == null ? Result.error(404, "Incident not found") : Result.success(incident);
    }

    @GetMapping("/{incidentId}/timeline")
    public Result<List<EventDto>> timeline(@PathVariable Long incidentId) {
        if (incidentService.detail(incidentId) == null) {
            return Result.error(404, "Incident not found");
        }
        return Result.success(incidentService.timeline(incidentId));
    }

    @PostMapping
    public ResponseEntity<Result<OpsIncident>> create(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        authorizationService.requireOperator(request);
        String title = body == null ? null : (String) body.get("title");
        String severity = body == null || body.get("severity") == null ? "warning" : (String) body.get("severity");
        Long sourceAlertId = body == null || body.get("sourceAlertId") == null ? null
                : ((Number) body.get("sourceAlertId")).longValue();
        String summary = body == null ? null : (String) body.get("summary");
        try {
            OpsIncident incident = incidentService.create(title, severity, sourceAlertId, summary, operatorId(request));
            return ResponseEntity.ok(Result.success(incident));
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Result.error(-1, failure.getMessage()));
        }
    }

    @PostMapping("/{incidentId}/acknowledge")
    public ResponseEntity<Result<Void>> acknowledge(@PathVariable Long incidentId, HttpServletRequest request) {
        return transition(incidentId, "ACKNOWLEDGED", request);
    }

    @PostMapping("/{incidentId}/mitigate")
    public ResponseEntity<Result<Void>> mitigate(@PathVariable Long incidentId, HttpServletRequest request) {
        return transition(incidentId, "MITIGATING", request);
    }

    @PostMapping("/{incidentId}/resolve")
    public ResponseEntity<Result<Void>> resolve(@PathVariable Long incidentId, HttpServletRequest request) {
        return transition(incidentId, "RESOLVED", request);
    }

    @PostMapping("/{incidentId}/close")
    public ResponseEntity<Result<Void>> close(@PathVariable Long incidentId, HttpServletRequest request) {
        return transition(incidentId, "CLOSED", request);
    }

    private ResponseEntity<Result<Void>> transition(Long incidentId, String targetStatus, HttpServletRequest request) {
        authorizationService.requireOperator(request);
        try {
            if (!incidentService.transition(incidentId, targetStatus, operatorId(request), null)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND.value()).body(Result.error(404, "Incident not found"));
            }
            return ResponseEntity.ok(Result.success());
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Result.error(-1, failure.getMessage()));
        }
    }

    private String operatorId(HttpServletRequest request) {
        return roleService.normalize(authorizationService.currentRole(request));
    }
}