package com.labex.labexagent.controller;

import com.labex.common.Result;
import com.labex.labexagent.migration.AgentLegacyMigrationReadinessService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员只读的 Agent Runtime 运维接口。 */
@RestController
@RequestMapping("/admin/agent/runtime")
@PreAuthorize("hasRole('ADMIN')")
public class AgentRuntimeMaintenanceController {
    private final AgentLegacyMigrationReadinessService readinessService;

    public AgentRuntimeMaintenanceController(AgentLegacyMigrationReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/legacy-migration-readiness")
    public Result<AgentLegacyMigrationReadinessService.ReadinessReport> legacyMigrationReadiness() {
        return Result.success(readinessService.refresh());
    }
}
