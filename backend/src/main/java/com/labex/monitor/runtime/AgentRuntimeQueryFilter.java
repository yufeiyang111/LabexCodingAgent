package com.labex.monitor.runtime;

/**
 * Agent 运行态任务查询条件（Query Object，不可变）。
 * page/pageSize 在 Controller 边界已归一化（page>=1，pageSize 在 [1,max] 内）。
 */
public record AgentRuntimeQueryFilter(
        String status,
        Integer studentId,
        String provider,
        boolean overdueOnly,
        int page,
        int pageSize) {
}
