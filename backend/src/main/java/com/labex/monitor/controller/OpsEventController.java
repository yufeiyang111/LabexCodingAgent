package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.dto.EventDto;
import com.labex.monitor.event.OpsEventQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运维事件流查询。 */
@RestController
@RequestMapping("/ops/events")
public class OpsEventController {

    private final OpsEventQueryService queryService;

    public OpsEventController(OpsEventQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public ResponseEntity<Result<Map<String, Object>>> list(@RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize,
                                                            @RequestParam(required = false) String eventType,
                                                            @RequestParam(required = false) String severity,
                                                            @RequestParam(required = false) String targetType,
                                                            @RequestParam(required = false) String targetId) {
        List<EventDto> records = queryService.query(page, pageSize, eventType, severity, targetType, targetId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", records);
        data.put("total", queryService.count(eventType, severity, targetType, targetId));
        return ResponseEntity.ok(Result.success(data));
    }
}