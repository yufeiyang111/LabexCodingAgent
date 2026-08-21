package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.audit.AuditQueryService;
import com.labex.monitor.dto.AuditRecordDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志查询。 */
@RestController
@RequestMapping("/ops/audit")
public class AuditController {

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            @RequestParam(required = false) String operatorId,
                                                            @RequestParam(required = false) String actionType,
                                                            @RequestParam(required = false) String targetType,
                                                            @RequestParam(required = false) String targetId,
                                                            @RequestParam(required = false) String result) {
        List<AuditRecordDto> records = queryService.query(page, pageSize, operatorId, actionType,
                targetType, targetId, result);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", queryService.count(operatorId, actionType, targetType, targetId, result));
        return ResponseEntity.ok(Result.success(data));
    }
}