package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.labexagent.run.AgentRunState;
import com.labex.monitor.runtime.AgentRuntimeEventItem;
import com.labex.monitor.runtime.AgentRuntimePage;
import com.labex.monitor.runtime.AgentRuntimeQueryFilter;
import com.labex.monitor.runtime.AgentRuntimeQueryService;
import com.labex.monitor.runtime.AgentRuntimeTaskDetail;
import com.labex.monitor.runtime.AgentRuntimeTaskSummary;
import com.labex.monitor.runtime.MonitorRuntimeProperties;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运维 Agent 运行态只读查询接口（/ops/runtime/**，受 MonitorAuthInterceptor 保护）。 */
@RestController
@RequestMapping("/ops/runtime")
public class MonitorRuntimeController {

    private static final Set<String> VALID_STATUSES = Arrays.stream(AgentRunState.values())
            .map(AgentRunState::persistedStatus)
            .collect(Collectors.toSet());
    private static final int MAX_PROVIDER_LENGTH = 100;

    private final AgentRuntimeQueryService queryService;
    private final MonitorRuntimeProperties properties;

    public MonitorRuntimeController(AgentRuntimeQueryService queryService, MonitorRuntimeProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    @GetMapping("/tasks")
    public ResponseEntity<Result<AgentRuntimePage<AgentRuntimeTaskSummary>>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer studentId,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "0") int pageSize) {
        if (status != null && !status.isBlank() && !VALID_STATUSES.contains(status.trim().toLowerCase())) {
            return badRequest("无效的任务状态: " + status);
        }
        if (studentId != null && studentId <= 0) {
            return badRequest("studentId 必须为正整数");
        }
        if (provider != null && provider.trim().length() > MAX_PROVIDER_LENGTH) {
            return badRequest("provider 过长");
        }
        if (page <= 0) {
            return badRequest("page 必须为正整数");
        }
        if (pageSize < 0) {
            return badRequest("pageSize 不能为负数");
        }
        int normalizedPageSize = normalizePageSize(pageSize);
        AgentRuntimeQueryFilter filter = new AgentRuntimeQueryFilter(
                blankToNull(status), studentId, blankToNull(provider), overdueOnly, page, normalizedPageSize);
        return ResponseEntity.ok(Result.success(queryService.listTasks(filter)));
    }

    @GetMapping("/tasks/{taskId}")
    public Result<AgentRuntimeTaskDetail> task(@PathVariable Long taskId) {
        if (taskId == null || taskId <= 0) {
            return Result.error(-1, "非法 taskId");
        }
        AgentRuntimeTaskDetail detail = queryService.taskDetail(taskId);
        if (detail == null) {
            return Result.error(404, "Agent task not found");
        }
        return Result.success(detail);
    }

    @GetMapping("/tasks/{taskId}/timeline")
    public ResponseEntity<Result<AgentRuntimePage<AgentRuntimeEventItem>>> timeline(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long afterSequence,
            @RequestParam(defaultValue = "0") int limit) {
        if (taskId == null || taskId <= 0) {
            return badRequest("非法 taskId");
        }
        if (afterSequence != null && afterSequence < 0) {
            return badRequest("afterSequence 不能为负数");
        }
        if (limit < 0) {
            return badRequest("limit 不能为负数");
        }
        return ResponseEntity.ok(Result.success(queryService.taskTimeline(taskId, afterSequence, limit)));
    }

    private <T> ResponseEntity<Result<T>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.error(-1, message));
    }

    private int normalizePageSize(int requested) {
        if (requested == 0) {
            return properties.getDefaultPageSize();
        }
        return Math.min(requested, properties.getMaxPageSize());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
