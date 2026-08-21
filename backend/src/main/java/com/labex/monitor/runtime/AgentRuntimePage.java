package com.labex.monitor.runtime;

import java.time.LocalDateTime;
import java.util.List;

/** Agent 运行态任务列表分页响应。 */
public record AgentRuntimePage<T>(List<T> items, long page, long pageSize, long total, boolean hasMore) {
}
