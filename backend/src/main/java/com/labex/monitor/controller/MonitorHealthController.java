package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.health.DependencyHealthStatus;
import com.labex.monitor.health.HealthSnapshot;
import com.labex.monitor.health.MonitorHealthQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 运维健康检查接口（路径与现有 /ops 一致，受 MonitorAuthInterceptor 保护）。 */
@RestController
@RequestMapping("/ops/health")
public class MonitorHealthController {

    private final MonitorHealthQueryService queryService;

    public MonitorHealthController(MonitorHealthQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/summary")
    public Result<HealthSnapshot> summary() {
        return Result.success(queryService.summary());
    }

    @GetMapping("/dependencies")
    public Result<List<DependencyHealthStatus>> dependencies() {
        return Result.success(queryService.dependencies());
    }
}
